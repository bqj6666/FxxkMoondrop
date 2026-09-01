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
        /** burst 完成等待时间（ms）：读到首字节后稍候，让同 burst 后续字节到达 */
        private const val FRAME_WAIT_MS = 50L
    }

    private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    @Volatile private var connected = false
    private var readThread: Thread? = null

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

    /** 发送 GAIA 帧（线程安全写入）。 */
    fun send(packet: ByteArray) {
        val s = socket
        if (s == null || !connected) {
            onError("RFCOMM 未连接")
            return
        }
        try {
            val os = s.outputStream
            os.write(packet)
            os.flush()
            Log.d(TAG, "TX " + packet.contentToString())
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

        val buf = ByteArray(256)
        while (running) {
            try {
                val n = input.read(buf)
                if (n <= 0) continue
                if (!running) break

                // 等待短暂时间让后续字节到达（同 burst）
                Thread.sleep(FRAME_WAIT_MS)
                val avail = try { input.available() } catch (_: Exception) { 0 }
                val burst = if (avail > 0) {
                    val full = ByteArray(n + avail)
                    System.arraycopy(buf, 0, full, 0, n)
                    input.read(full, n, avail)
                    full
                } else {
                    buf.copyOfRange(0, n)
                }
                val burstEnd = try { input.available() <= 0 } catch (_: Exception) { true }

                // alpha2.41.4: 流式切分，逐 PDU 分发
                val pdus = framer.feed(burst, burstEnd)
                for (pdu in pdus) {
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
}
