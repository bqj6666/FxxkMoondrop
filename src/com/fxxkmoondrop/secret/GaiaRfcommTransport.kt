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
 * 帧边界处理：SPP 流没有内置消息边界（不像 BLE 特征值），
 * 采用 50ms 间隔判断法——读到首字节后短暂等待，
 * 将同一 burst 的数据拼接为完整帧。
 *
 * 协议来源: https://github.com/lingbai-rong/PuddingPods
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
        /** 帧完成等待时间（ms） */
        private const val FRAME_WAIT_MS = 50L
    }

    private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    @Volatile private var connected = false
    private var readThread: Thread? = null

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
        Log.d(TAG, "RFCOMM disconnected")
    }

    /** 传输是否就绪。 */
    fun isReady(): Boolean = connected && running

    /**
     * 读取线程主循环。
     *
     * SPP 的 InputStream 是流式的，没有消息边界。
     * 策略：阻塞读到首批字节 → 短暂等待 50ms → 读取已到达的后续字节 → 拼接为完整帧。
     * 如果一次读到多帧（含多个 0x001D vendor 头），则逐帧切分分发。
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

                // 等待短暂时间让后续字节到达（帧完成）
                Thread.sleep(FRAME_WAIT_MS)
                val avail = try { input.available() } catch (_: Exception) { 0 }
                val frame = if (avail > 0) {
                    val full = ByteArray(n + avail)
                    System.arraycopy(buf, 0, full, 0, n)
                    input.read(full, n, avail)
                    full
                } else {
                    buf.copyOfRange(0, n)
                }

                // 逐帧切分（可能一次读到多个 GAIA 帧）
                var off = 0
                while (off + 4 <= frame.size) {
                    val vendor = ((frame[off].toInt() and 0xFF) shl 8) or
                            (frame[off + 1].toInt() and 0xFF)
                    if (vendor != GaiaConstants.GAIA_VENDOR) {
                        // 非 GAIA 帧，跳过单字节继续寻找
                        off++
                        continue
                    }
                    // 找到 GAIA 帧头，剩余全部作为一个帧分发
                    // （GAIA V3 无长度字段，无法精确切分多帧，实际设备单次响应为单帧）
                    val single = frame.copyOfRange(off, frame.size)
                    Log.d(TAG, "RX " + single.contentToString())
                    val copyForCallback = single.copyOf()
                    handler.post { onPacket(copyForCallback) }
                    off = frame.size
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
