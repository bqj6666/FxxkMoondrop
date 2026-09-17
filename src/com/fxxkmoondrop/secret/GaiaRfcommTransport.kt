package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.util.Log
import java.io.IOException
import java.util.UUID

/**
 * alpha2.38.9: GAIA over RFCOMM/SPP 传输层。
 *
 * 布丁 (MD-TWS-056) 等经典蓝牙设备使用 RFCOMM/SPP 而非 BLE GATT，
 * 但 GAIA V3 帧格式完全相同（vendor 0x001D + commandValue + payload）。
 *
 * 本类封装 BluetoothSocket 连接、写入和读取线程，
 * 收到的完整帧通过 onPacket 回调交给 GaiaPacketHandler 解析，
 * 与 BLE GATT 路径共用同一套上层逻辑。
 *
 * alpha2.41.4: 帧边界处理重构。
 * 旧逻辑（50ms 等待 + 找到 0x001D 头即"剩余全部当一个帧"）无法处理：
 * a) 设备对每个响应双发（裸 PDU + FF 传输帧各一遍）导致粘包错切；
 * b) FF 传输帧（SOF=0xFF，官方 RFCOMM 封装）被当垃圾跳过。
 * 现由 GaiaRfcommFramer 流式状态机按官方 TransportProtocol 格式精确切分，
 * FF 帧按 Len 字段切、裸 PDU 按下一帧起始/burst 结束切，半截帧保留续读。
 *
 * 协议来源: https://github.com/lingbai-rong/PuddingPods
 *           + 官方 Moondrop App 反编译 TransportProtocol.java（FF 帧格式）
 */
class GaiaRfcommTransport(
    private val handler: Handler,
    private val onPacket: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "GaiaRfcomm"
        /** 标准 SPP UUID */
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        /** burst 静默期（ms）：线路连续安静这么久就认定一个 burst 结束 */
        private const val QUIET_MS = 45L

        /** 传输帧（官方 RFCOMM 封装）：FF | Ver | Flags | Len | PDU */
        private const val MODE_FRAMED_V4 = 0
        private const val MODE_FRAMED_V3 = 1
        /** 裸 PDU：00 1D | cmdValue | payload（无封装，旧行为） */
        private const val MODE_BARE = 2
        private val MODE_NAMES = arrayOf("FRAMED_V4", "FRAMED_V3", "BARE")
        /**
         * 每种发送封装的尝试窗口（ms）。收到任何有效回包前按窗口轮换，
         * 收到后锁定 —— 设备固件差异（是否要 FF 传输帧、版本 3 还是 4）
         * 无法预先得知，靠回包自适应，不硬编码。
         */
        private const val MODE_TRY_MS = 3500L
    }

    private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    @Volatile private var connected = false
    private var readThread: Thread? = null

    /** 本次连接是否已收到过至少一个有效 PDU（用于锁定发送封装） */
    @Volatile private var receivedAny = false
    /** 连接建立时刻（封装轮换计时基准） */
    @Volatile private var connectedAt = 0L
    /** 已锁定的发送封装；-1 = 尚未锁定，仍在轮换 */
    @Volatile private var lockedMode = -1
    /** 最近一次实际使用的发送封装（收到回包时据此锁定） */
    @Volatile private var lastSentMode = MODE_FRAMED_V4

    /** alpha2.41.4: 流式帧切分器（跨 burst 保留半截帧） */
    private val framer = GaiaRfcommFramer()

    /**
     * 建立 RFCOMM 连接（阻塞调用，应在非主线程执行）。
     * @return true 连接成功并已启动读取线程
     */
    fun connect(device: BluetoothDevice): Boolean {
        return try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            connected = true
            running = true
            framer.reset()
            receivedAny = false
            lockedMode = -1
            lastSentMode = MODE_FRAMED_V4
            connectedAt = System.currentTimeMillis()
            readThread = Thread({ readLoop() }, "GaiaRfcomm-Reader").apply { isDaemon = true }
            readThread?.start()
            Log.i(TAG, "RFCOMM connected to " + device.address + " name=" + device.name)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "RFCOMM connect denied", e)
            onError("缺少蓝牙权限")
            false
        } catch (e: Exception) {
            Log.e(TAG, "RFCOMM connect failed", e)
            onError("RFCOMM 连接失败: " + e.message)
            false
        }
    }

    /** 当前应使用的发送封装：已锁定则用锁定的，否则按窗口轮换。 */
    private fun currentMode(): Int {
        val locked = lockedMode
        if (locked >= 0) return locked
        val elapsed = System.currentTimeMillis() - connectedAt
        val idx = ((elapsed / MODE_TRY_MS) % MODE_NAMES.size).toInt()
        return when (idx) {
            MODE_FRAMED_V3 -> MODE_FRAMED_V3
            MODE_BARE -> MODE_BARE
            else -> MODE_FRAMED_V4
        }
    }

    /** 收到有效回包：锁定当前发送封装（此后不再轮换）。 */
    private fun markReceived() {
        if (receivedAny) return
        receivedAny = true
        val m = lastSentMode
        lockedMode = m
        Log.i(TAG, "framing locked to " + MODE_NAMES[m])
        AppLog.i(TAG, "protocol: RFCOMM framing locked to " + MODE_NAMES[m])
    }

    /**
     * 按官方 GAIA v4 RFCOMM 封装打包（反编译本机官方 App 的
     * `com.qualcomm.qti.gaiaclient.core.gaia.core.transport.TransportProtocol.Rfcomm.Frame.format` 得到）：
     *
     *   byte0 = 0xFF (SOF)
     *   byte1 = version
     *   byte2 = flags（bit0=校验和, bit1=双字节长度；官方 GaiaFormatter.Rfcomm 默认无校验和）
     *   byte3.. = 长度 = PDU 长度 - 4（双字节长度仅 version>=4 且长度 > 255 时）
     *   之后 = PDU 原样
     *
     * 旧实现直接发裸 PDU，设备侧解析器读到的 SOF 是 0x00（非法），整帧错位 → 无有效回包。
     */
    private fun wrap(pdu: ByteArray, version: Int): ByteArray {
        val payloadLen = (pdu.size - 4).coerceAtLeast(0)
        val ext = version >= 4 && payloadLen > 255
        val headerLen = if (ext) 5 else 4
        val out = ByteArray(headerLen + pdu.size)
        out[0] = 0xFF.toByte()
        out[1] = version.toByte()
        out[2] = if (ext) 0x02 else 0x00
        if (ext) {
            out[3] = ((payloadLen shr 8) and 0xFF).toByte()
            out[4] = (payloadLen and 0xFF).toByte()
        } else {
            out[3] = (payloadLen and 0xFF).toByte()
        }
        System.arraycopy(pdu, 0, out, headerLen, pdu.size)
        return out
    }

    /** 发送 GAIA 帧（线程安全写入）。 */
    fun send(packet: ByteArray) {
        val s = socket
        if (s == null || !connected) {
            onError("RFCOMM 未连接")
            return
        }
        try {
            val mode = currentMode()
            lastSentMode = mode
            val out = when (mode) {
                MODE_BARE -> packet
                MODE_FRAMED_V3 -> wrap(packet, 3)
                else -> wrap(packet, 4)
            }
            val os = s.outputStream
            os.write(out)
            os.flush()
            Log.d(TAG, "TX " + MODE_NAMES[mode] + " " + out.contentToString())
        } catch (e: IOException) {
            Log.e(TAG, "RFCOMM write failed", e)
            onError("RFCOMM 写入失败: " + e.message)
        }
    }

    /** 断开连接并释放资源。 */
    fun disconnect() {
        running = false
        connected = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        try { readThread?.interrupt() } catch (_: Exception) { }
        readThread = null
        framer.reset()
        receivedAny = false
        lockedMode = -1
        connectedAt = 0L
        Log.d(TAG, "RFCOMM disconnected")
    }

    /** 传输是否就绪。 */
    fun isReady(): Boolean = connected && running

    /**
     * 读取线程主循环。
     *
     * SPP 的 InputStream 是流式的，没有消息边界。
     * 策略：阻塞读到首批字节 → 短暂等待 50ms → 读走已到达的后续字节拼成 burst →
     * 交给 GaiaRfcommFramer 切分（FF 帧按 Len 精确切、裸 PDU 按帧起始/burst 结束切），
     * 切出的每个 PDU 逐个回调 onPacket。
     */
    private fun readLoop() {
        val input = try {
            socket?.inputStream
        } catch (e: Exception) {
            Log.e(TAG, "getInputStream failed", e)
            handler.post { onDisconnected() }
            return
        } ?: return

        val buf = ByteArray(512)
        while (running) {
            try {
                val n = input.read(buf)
                if (n <= 0) continue
                if (!running) break

                // 3.0.2: 累积到静默期结束（旧实现只 sleep 固定 50ms 再查一次
                // available()，且 read(full, n, avail) 的返回值不检查 —— 少读时
                // 尾部会被 ByteArray 的 0x00 填充，凭空造出假字节污染帧缓冲）。
                val acc = ArrayList<Byte>(n)
                for (i in 0 until n) acc.add(buf[i])
                var quietSince = System.currentTimeMillis()
                while (running) {
                    if (System.currentTimeMillis() - quietSince >= QUIET_MS) break
                    val avail = try { input.available() } catch (_: Exception) { 0 }
                    if (avail <= 0) {
                        Thread.sleep(5)
                        continue
                    }
                    val tmp = ByteArray(if (avail > 512) 512 else avail)
                    val r = try { input.read(tmp, 0, tmp.size) } catch (_: Exception) { -1 }
                    if (r <= 0) break
                    for (i in 0 until r) acc.add(tmp[i])
                    quietSince = System.currentTimeMillis()
                }
                val burst = ByteArray(acc.size)
                for (i in acc.indices) burst[i] = acc[i]
                Log.d(TAG, "RX burst(" + burst.size + ") " + toHex(burst))

                // 静默期结束 -> 该 burst 已完整，按 burstEnd=true 切分
                val pdus = framer.feed(burst, true)
                for (pdu in pdus) {
                    markReceived()
                    Log.d(TAG, "RX pdu " + pdu.contentToString())
                    val copyForCallback = pdu.copyOf()
                    handler.post { onPacket(copyForCallback) }
                }
                if (pdus.isEmpty() && framer.pendingSize() > 0) {
                    Log.d(TAG, "framer pending " + framer.pendingSize() + " bytes (partial frame)")
                }
            } catch (e: IOException) {
                if (running) {
                    Log.w(TAG, "RFCOMM read EOF, disconnected")
                    connected = false
                    handler.post { onDisconnected() }
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "readLoop unexpected error", e)
            }
        }
    }

    /** 十六进制诊断串（定位设备实际回包格式用；过长的只打前 64 字节）。 */
    private fun toHex(b: ByteArray): String {
        val n = if (b.size > 64) 64 else b.size
        val sb = StringBuilder(n * 3 + 8)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02X", b[i].toInt() and 0xFF))
        }
        if (b.size > n) sb.append(" ...(").append(b.size).append("B)")
        return sb.toString()
    }
}
