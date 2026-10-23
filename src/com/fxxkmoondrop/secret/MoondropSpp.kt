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

    fun isConnected(): Boolean = try { socket?.isConnected == true } catch (_: Throwable) { false }

    /**
     * 建 SPP 链路。
     *
     * 实测个别水月雨设备只接受其中一种建链方式（安全 RFCOMM 会被拒、通道 1 才行），
     * 所以按「安全 -> 不安全 -> 反射通道 1」依次试，日志里写明哪条成功，别只报一句失败。
     */
    fun connect(address: String): Boolean {
        disconnect()
        val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (_: Throwable) { null } ?: return false
        val dev = try { adapter.getRemoteDevice(address) } catch (_: Throwable) { return false }
        val attempts = listOf<Pair<String, () -> BluetoothSocket>>(
                "secure" to { dev.createRfcommSocketToServiceRecord(SPP) },
                "insecure" to { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
                "channel1" to {
                    val m = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    m.invoke(dev, 1) as BluetoothSocket
                }
        )
        for ((name, make) in attempts) {
            try {
                val s = make()
                s.connect()
                socket = s
                ins = s.inputStream
                outs = s.outputStream
                Log.i(TAG, "spp connected ($name) -> $address")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "spp connect fail ($name): $t")
            }
        }
        disconnect()
        return false
    }

    fun disconnect() {
        try { ins?.close() } catch (_: Throwable) { }
        try { outs?.close() } catch (_: Throwable) { }
        try { socket?.close() } catch (_: Throwable) { }
        socket = null; ins = null; outs = null
    }

    /** 组帧：FF 04 len(u16be) + payload（payload = 00 1D cmd data…，len 只数 cmd 之后的 data） */
    private fun frame(payload: ByteArray): ByteArray {
        val n = payload.size - 4
        val head = byteArrayOf(0xFF.toByte(), 0x04, ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte())
        return head + payload
    }

    /**
     * 收包缓冲。设备会在同一次 read 里塞进多帧（实测：
     * `ff040001001d0b0301` 后面直接跟着 `ff040001001d0a8101`），
     * 也会把一帧拆成两次到（实测读到 37/39 字节的半包），
     * 所以必须自己按 FF 04 <len> 分帧，不能「读一次就当一帧」。
     */
    private val rx = java.io.ByteArrayOutputStream()

    private fun hex(b: ByteArray): String = b.joinToString("") { String.format("%02x", it) }

    /** 从残留 + 新数据里切出一整帧；不足一帧返回 null（继续等） */
    private fun nextFrame(): ByteArray? {
        val b = rx.toByteArray()
        var i = 0
        while (i + 8 <= b.size) {
            if ((b[i].toInt() and 0xFF) == 0xFF && (b[i + 1].toInt() and 0xFF) == 0x04) {
                val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                val total = 8 + len            // 4 头 + 4 (00 1D cmd…) + len
                if (b.size - i >= total) {
                    val f = b.copyOfRange(i, i + total)
                    rx.reset(); rx.write(b, i + total, b.size - (i + total))
                    return f
                }
                return null                    // 头齐了但数据没齐，等下一块
            }
            i++
        }
        if (b.size > 512) rx.reset()           // 全是垃圾就丢掉，防止无限增长
        return null
    }

    /**
     * 发一条命令并等一整帧回包（超时返回 null）。
     *
     * 设备会主动推事件帧（实测 `ff040001001d0a8101` 会紧跟在应答后面），
     * 所以按命令字过滤：应答命令字 = 请求命令字 | 0x01（实测 0A05 → 0B05）。
     */
    fun request(payload: ByteArray, timeoutMs: Int = 1500, expectCmd: Int = -1): ByteArray? {
        val o = outs ?: return null
        val i = ins ?: return null
        val want = if (expectCmd >= 0) expectCmd else (payload[2].toInt() and 0xFF)
        return try {
            synchronized(this) {
                val tx = frame(payload)
                rx.reset()
                Log.i(TAG, "tx " + hex(tx))
                o.write(tx); o.flush()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(512)
                while (System.currentTimeMillis() < deadline) {
                    while (true) {
                        val f = nextFrame() ?: break
                        val cmd = f[6].toInt() and 0xFF
                        if ((cmd and 0xFE) == (want and 0xFE)) {
                            Log.i(TAG, "rx " + hex(f))
                            return f
                        }
                        Log.i(TAG, "event(ignored) " + hex(f))
                    }
                    if (i.available() > 0) {
                        val n = i.read(buf)
                        if (n > 0) rx.write(buf, 0, n)
                    } else {
                        Thread.sleep(20)
                    }
                }
                Log.w(TAG, "timeout, rx partial=" + hex(rx.toByteArray()))
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

    /**
     * 读当前 EQ 曲线 —— 实测命令是 **0A 05**（探测得出，不是猜的）。
     * 回包：FF 04 <len> 00 1D 0B 05 00 04 <len 字节曲线数据>
     */
    fun readCurve(): ByteArray? {
        val r = request(byteArrayOf(0x00, 0x1D, 0x0A, 0x05)) ?: return null
        val d = extractData(r) ?: return null
        lastCurveLen = d.size
        return d
    }

    /**
     * 从回包里取业务数据：跳过 FF 04 <len:2> 00 1D <cmd> 以及可选的 2 字节子参数。
     * 规则按实测回包的通用形态推导（长度字段即数据长度），不做型号假设。
     */
    private fun extractData(raw: ByteArray): ByteArray? {
        if (raw.size < 6) return null
        // 找 00 1D 同步头
        var i = 0
        while (i + 4 < raw.size && !(raw[i] == 0.toByte() && raw[i + 1] == 0x1D.toByte())) i++
        if (i + 4 >= raw.size) return null
        val lenField = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        // 实测形态：FF 04 <len> 00 1D <cmd> <data…>，data 直接从 cmd 之后开始、长度为 len
        // （读曲线 0A05 的 data 就是 "00 04 + 37B"，写回时原样带回，不做二次拆分）
        val start = i + 4
        if (start >= raw.size) return null
        val n = if (lenField in 1..(raw.size - start)) lenField else raw.size - start
        return raw.copyOfRange(start, start + n)
    }

    /** 预设列表：0A 01 回包 → 数据区首字节是档位数，其后是各档 id */
    fun readPresetList(): IntArray? {
        val r = request(byteArrayOf(0x00, 0x1D, 0x0A, 0x01)) ?: return null
        val d = extractData(r) ?: return null
        if (d.size < 2) return null
        val count = d[0].toInt() and 0xFF
        val ids = d.copyOfRange(1, Math.min(d.size, 1 + count))
        return IntArray(ids.size) { ids[it].toInt() and 0xFF }
    }

    /** 当前预设：0A 02 回包数据区 */
    fun readPresetRaw(): Int {
        val d = readPresetData() ?: return -1
        if (d.isEmpty()) return -1
        return d[0].toInt() and 0xFF
    }

    fun readPresetData(): ByteArray? {
        val r = request(byteArrayOf(0x00, 0x1D, 0x0A, 0x02)) ?: return null
        return extractData(r)
    }

    /**
     * 写 EQ 曲线：把参数原样下发。
     *
     * ⚠️ 不写死 39 字节：不同型号的频点数量/参数长度可能不同，
     * 一律以「读回来多少就写回多少」为准，保证同一套协议的各型号都能用。
     */
    /**
     * 写 EQ 曲线：把参数原样下发。
     *
     * ⚠️ 设备对 0A 06 **不回 ACK**（实测：写入后 2.5s 内无 0B 06 回包，
     * 用 0A 05 回读才看到内容已经变了），所以这里只判「帧是否成功写出」，
     * 不判回包；要确认效果用 [writeCurveVerified]。
     *
     * ⚠️ 不写死 39 字节：不同型号的频点数量/参数长度可能不同，
     * 一律以「读回来多少就写回多少」为准，保证同一套协议的各型号都能用。
     */
    fun writeCurve(params: ByteArray): Boolean {
        if (params.isEmpty()) return false
        val o = outs ?: return false
        if (params.size != lastCurveLen && lastCurveLen > 0) {
            Log.w(TAG, "curve size ${params.size} != device $lastCurveLen")
            return false
        }
        return try {
            val tx = frame(byteArrayOf(0x00, 0x1D, 0x0A, 0x06) + params)
            o.write(tx); o.flush()
            Log.i(TAG, "tx " + hex(tx))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "writeCurve fail: $t")
            false
        }
    }

    /** 最近一次读到的曲线长度（设备自己告诉我们它是多长，写回时必须一致） */
    @Volatile private var lastCurveLen = 0

    /**
     * 写曲线并回读校验。设备实测原样存储（官方写入字节 = 0A05 读回字节），
     * 且写完要等一会儿才回应答，所以回读带重试。
     */
    fun writeCurveVerified(params: ByteArray): Boolean {
        if (!writeCurve(params)) return false
        repeat(4) { k ->
            Thread.sleep(if (k == 0) 500L else 800L)
            val back = readCurve() ?: return@repeat
            if (back.size == params.size && back.contentEquals(params)) return true
            Log.w(TAG, "readback mismatch: " + hex(back))
        }
        return false
    }

    /** 任意 00 1D 命令（供上层做能力探测，不写死具体型号） */
    fun raw(cmd: Int, vararg data: Int): ByteArray? =
        request(byteArrayOf(0x00, 0x1D, cmd.toByte()) + data.map { it.toByte() }.toByteArray())
}
