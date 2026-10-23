package com.fxxkmoondrop.secret

/**
 * 水月雨 SPP 均衡器载荷（39 字节）编解码。
 *
 * 布局不是猜的：用官方 App 真实下发帧反推，并做到「解码 -> 编码」**逐字节复现**两条官方帧
 * （18:05:48 与 18:06:28 那两条 47 字节写帧，含末尾那个看着像校验和的字节）：
 *
 *   [0..1]   00 04                       固定头
 *   [2..3]   preamp   s16be /60  dB      段 -1 的增益，即全局预增益
 *   五段，每段 7 字节（段 i 起点 = 2 + 7i）：
 *     [0..1] 上一段增益 s16be /60        段 0 这一格放的就是 preamp
 *     [2..3] 频点      u16be  Hz
 *     [4..5] Q         u16be  /4096
 *     [6]    00                           每段固定 0
 *   [37..38] 第 5 段增益 s16be /60        <- 末尾两字节是数据，不是校验和
 *
 * 单位（/60、/4096）与水月雨同类 SPP 设备的公开实现一致
 * （devicePEQ / eqcaps `moondrop-edge-serial`，0BSD），并与本机两条真帧数值自洽
 * （增益落在 -12..+3 dB、Q 落在 0..12，preamp 约等于 -最大增益）。
 */
object MoondropEq {

    const val BANDS = 5
    const val SIZE = 2 + BANDS * 7 + 2          // = 39
    private const val GAIN_SCALE = 60f
    private const val Q_SCALE = 4096f

    data class Band(var freq: Int, var gainDb: Float, var q: Float)

    data class Profile(var preampDb: Float, val bands: MutableList<Band>) {
        fun copy(): Profile = Profile(preampDb, bands.map { Band(it.freq, it.gainDb, it.q) }.toMutableList())
    }

    /** 每段末尾那个固定 0 的下标：8, 15, 22, 29, 36 */
    private fun padAt(i: Int) = 2 + i * 7 + 6

    /**
     * 解析载荷。长度不对、或每段固定 0 不成立就返回 null ——
     * 说明不是我们解出来的那种布局，宁可报「不认识」，也不要拿错误解释去画曲线/写回设备。
     */
    fun decode(p: ByteArray): Profile? {
        if (p.size != SIZE) return null
        for (i in 0 until BANDS) if (p[padAt(i)].toInt() != 0) return null
        fun s16(o: Int): Int {
            val v = (((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF))
            return if (v >= 0x8000) v - 0x10000 else v
        }
        fun u16(o: Int): Int = ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)
        val pre = s16(2) / GAIN_SCALE
        val bands = ArrayList<Band>(BANDS)
        for (i in 0 until BANDS) {
            val o = 2 + i * 7
            val g = if (i < BANDS - 1) s16(o + 7) else s16(2 + BANDS * 7)
            bands.add(Band(u16(o + 2), g / GAIN_SCALE, u16(o + 4) / Q_SCALE))
        }
        return Profile(pre, bands)
    }

    fun encode(pr: Profile): ByteArray {
        require(pr.bands.size == BANDS) { "need $BANDS bands" }
        val out = ByteArray(SIZE)
        out[0] = 0x00; out[1] = 0x04
        fun s16(o: Int, v: Float) {
            val r = Math.round(v * GAIN_SCALE).coerceIn(-32768, 32767) and 0xFFFF
            out[o] = ((r shr 8) and 0xFF).toByte(); out[o + 1] = (r and 0xFF).toByte()
        }
        fun u16(o: Int, v: Int) {
            val r = v.coerceIn(0, 0xFFFF)
            out[o] = ((r shr 8) and 0xFF).toByte(); out[o + 1] = (r and 0xFF).toByte()
        }
        s16(2, pr.preampDb)
        for (i in 0 until BANDS) {
            val o = 2 + i * 7
            s16(o, if (i == 0) pr.preampDb else pr.bands[i - 1].gainDb)
            u16(o + 2, pr.bands[i].freq)
            u16(o + 4, Math.round(pr.bands[i].q * Q_SCALE))
            out[o + 6] = 0
        }
        s16(2 + BANDS * 7, pr.bands[BANDS - 1].gainDb)
        return out
    }

    /** 按 preamp 约等于 -最大正增益 补预增益，避免叠加削波（官方帧就是这个关系，误差 <=0.1 dB） */
    fun withAutoPreamp(pr: Profile): Profile {
        val p = pr.copy()
        val max = p.bands.maxOf { it.gainDb }
        p.preampDb = if (max > 0f) -max else 0f
        return p
    }

    /** 频响预览用的幅度响应（dB），peaking 滤波器，RBJ cookbook 形式 */
    fun magnitudeDb(pr: Profile, hz: Double, fs: Double = 48000.0): Double {
        var total = pr.preampDb.toDouble()
        for (b in pr.bands) {
            val a = Math.pow(10.0, b.gainDb / 40.0)
            val w0 = 2 * Math.PI * b.freq / fs
            val alpha = Math.sin(w0) / (2 * b.q.coerceAtLeast(0.05f))
            val cosw = Math.cos(w0)
            val b0 = 1 + alpha * a; val b1 = -2 * cosw; val b2 = 1 - alpha * a
            val a0 = 1 + alpha / a; val a1 = -2 * cosw; val a2 = 1 - alpha / a
            val w = 2 * Math.PI * hz / fs
            val cw = Math.cos(w); val cw2 = Math.cos(2 * w); val sw = Math.sin(w); val sw2 = Math.sin(2 * w)
            val numRe = b0 + b1 * cw + b2 * cw2
            val numIm = -(b1 * sw + b2 * sw2)
            val denRe = a0 + a1 * cw + a2 * cw2
            val denIm = -(a1 * sw + a2 * sw2)
            val mag = Math.sqrt(numRe * numRe + numIm * numIm) / Math.sqrt(denRe * denRe + denIm * denIm)
            total += 20 * Math.log10(mag)
        }
        return total
    }
}
