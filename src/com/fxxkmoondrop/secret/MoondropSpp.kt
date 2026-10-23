package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Moondrop 私有协议（经典蓝牙 SPP）。
 *
 * 帧格式（实测，官方 App 抓包）：
 *   FF 04 <len:u16be> | 00 1D <cmd> <sub…>
 * 其中 len = payload.size - 4。
 *
 * 已确证命令：
 *   00 1D 0A 02           查询当前预设
 *   00 1D 0A 03 00/01/02  切换预设（流行/标准/监听）
 *   00 1D 0A 06 + 39B     写入 EQ 曲线（39 字节参数原样）
 */
object MoondropSpp {

    private const val TAG = "MoondropSpp"
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var ins: InputStream? = null
    private var outs: OutputStream? = null

    @Volatile private var lastReply: ByteArray? = null

    fun isConnected(): Boolean = try { socket?.isConnected == true } catch (_: Throwable) { false }

    fun connect(address: String): Boolean {
        disconnect()
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            val dev = adapter.getRemoteDevice(address)
            val s = dev.createRfcommSocketToServiceRecord(SPP)
            s.connect()
            socket = s
            ins = s.inputStream
            outs = s.outputStream
            Log.i(TAG, "spp connected -> $address")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "spp connect fail: $t")
            disconnect()
            false
        }
    }

    fun disconnect() {
        try { ins?.close() } catch (_: Throwable) { }
        try { outs?.close() } catch (_: Throwable) { }
        try { socket?.close() } catch (_: Throwable) { }
        socket = null; ins = null; outs = null
    }

    /** 组帧：FF 04 len(u16be) + payload */
    private fun frame(payload: ByteArray): ByteArray {
        val n = (payload.size - 4).coerceAtLeast(0)
        val head = byteArrayOf(0xFF.toByte(), 0x04, ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte())
        return head + payload
    }

    /** 发一条命令并等待回包（返回原始字节，超时返回 null） */
    fun request(payload: ByteArray, timeoutMs: Int = 1500): ByteArray? {
        val o = outs ?: return null
        val i = ins ?: return null
        return try {
            synchronized(this) {
                lastReply = null
                o.write(frame(payload)); o.flush()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < deadline) {
                    if (i.available() > 0) {
                        val n = i.read(buf)
                        if (n > 0) {
                            val got = buf.copyOf(n)
                            lastReply = got
                            Log.i(TAG, "reply " + got.joinToString("") { String.format("%02x", it) })
                            return got
                        }
                    } else {
                        Thread.sleep(20)
                    }
                }
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "request fail: $t")
            null
        }
    }

    /** 读当前预设：00 1D 0A 02 */
    fun readPreset(): ByteArray? = request(byteArrayOf(0x00, 0x1D, 0x0A, 0x02))

    /** 切预设：0=流行 1=标准 2=监听 */
    fun setPreset(index: Int): ByteArray? =
        request(byteArrayOf(0x00, 0x1D, 0x0A, 0x03, index.toByte()))

    /** 读当前 EQ 曲线（39 字节参数或其查询回包） */
    fun readCurve(): ByteArray? = request(byteArrayOf(0x00, 0x1D, 0x0A, 0x0A.toByte()))

    /**
     * 写 EQ 曲线：把参数原样下发。
     *
     * ⚠️ 不写死 39 字节：不同型号的频点数量/参数长度可能不同，
     * 一律以「读回来多少就写回多少」为准，保证同一套协议的各型号都能用。
     */
    fun writeCurve(params: ByteArray): ByteArray? {
        if (params.size < 8) return null          // 太短肯定不是有效参数
        val payload = byteArrayOf(0x00, 0x1D, 0x0A, 0x06) + params
        return request(payload, 2500)
    }

    /** 任意 00 1D 命令（供上层做能力探测，不写死具体型号） */
    fun raw(cmd: Int, vararg data: Int): ByteArray? =
        request(byteArrayOf(0x00, 0x1D, cmd.toByte()) + data.map { it.toByte() }.toByteArray())
}
