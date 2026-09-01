package com.fxxkmoondrop.secret

/**
 * alpha2.41.4: RFCOMM/SPP 流式 GAIA 帧切分器。
 *
 * 背景：GAIA 设备在 RFCOMM 上发送两种封装（真机日志 + 官方 App 反编译 TransportProtocol.java 确认）：
 *
 * 1. 传输帧（官方 RFCOMM 封装，SOF=0xFF 开头）：
 *    FF | Version(1B) | Flags(1B) | Length(1B 或 2B) | PDU(Length+4) | [Checksum(1B)]
 *    - Flags bit0 = CHECKSUM：PDU 后附 1 字节校验和
 *    - Flags bit1 = LENGTH_EXTENSION：Length 为 2 字节（仅 Version>=4 时有效）
 *    - Length = PDU 中 payload 字节数，PDU 总长 = Length + 4（vendor 2B + cmdValue 2B）
 *    - 帧总长 = 头(4/5B) + PDU + (校验和 0/1B)
 *
 * 2. 裸 PDU（部分设备直发，vendor 头 00 1D 开头）：
 *    00 1D | cmdValue(2B) | payload...
 *    - 无长度字段，无法精确定界；帧边界只能靠流中下一个帧起始（FF 或 00 1D）
 *      或 burst 结束（SPP 读流 available()==0）判断。
 *
 * 本切分器为流式状态机：feed() 每次喂入一个 burst 的字节，
 * 返回切分出的完整 PDU 列表（调用方逐个交给 GaiaPacketHandler）。
 * 半截帧保留在内部缓冲等待后续字节，绝不丢弃。
 *
 * 校验和：官方算法为 native（utils-lib.so）无源码，此处不校验、仅按长度切分，
 * 宽容处理以保证兼容性（错切一帧比放过一帧更糟）。
 *
 * 局限性（裸 PDU 固有缺陷）：若裸 PDU 的 payload 内部恰好出现 FF 或 00 1D 字节序列，
 * 会被误认为下一帧起始而提前切分。实测设备（布丁 MD-TWS-056 等）单 burst 单帧
 * 且 payload 为短模式字节，不受影响；误切也只影响那一帧，不会污染后续帧。
 */
class GaiaRfcommFramer {

    companion object {
        /** 传输帧 SOF 字节 */
        private const val SOF = 0xFF
        /** GAIA vendor 头高字节（0x001D） */
        private const val VENDOR_HI = 0x00
        /** GAIA vendor 头低字节（0x001D） */
        private const val VENDOR_LO = 0x1D
        /** Flags bit0: 校验和存在 */
        private const val FLAG_CHECKSUM = 0x01
        /** Flags bit1: 双字节长度 */
        private const val FLAG_LENGTH_EXTENSION = 0x02
        /** 长度扩展仅对 Version>=4 生效（官方 TransportProtocol.Rfcomm.Packet.getHasLengthExtension） */
        private const val VERSION_WITH_LENGTH_EXTENSION = 4
        /** PDU 中 cmdValue 头占 4 字节（vendor 2B + cmdValue 2B） */
        private const val PDU_HEADER_SIZE = 4
    }

    /** 待切分缓冲（包含半截帧） */
    private val buf = ArrayList<Byte>(128)

    /**
     * 喂入一个 burst 的字节并切分。
     *
     * @param chunk 本次读到的字节
     * @param burstEnd true 表示本次 burst 已结束（读流 available()==0），
     *                 残留的完整裸 PDU 前缀将被交付；FF 半截帧仍保留等待后续字节。
     * @return 切分出的 PDU 列表（可能为空）
     */
    fun feed(chunk: ByteArray, burstEnd: Boolean): List<ByteArray> {
        for (b in chunk) buf.add(b)
        val out = ArrayList<ByteArray>(2)

        while (true) {
            if (buf.size < 2) break
            val b0 = buf[0].toInt() and 0xFF
            val b1 = buf[1].toInt() and 0xFF

            if (b0 == SOF) {
                // ---- 传输帧：FF Ver Flags Len... ----
                if (buf.size < 4) break // 还看不到 flags/长度，等待
                val version = b1
                val flags = buf[2].toInt() and 0xFF
                val hasLenExt = version >= VERSION_WITH_LENGTH_EXTENSION &&
                        (flags and FLAG_LENGTH_EXTENSION) != 0
                val headerLen = if (hasLenExt) 5 else 4
                if (buf.size < headerLen) break
                val length = if (hasLenExt) {
                    ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
                } else {
                    buf[3].toInt() and 0xFF
                }
                val hasChecksum = (flags and FLAG_CHECKSUM) != 0
                val pduLen = length + PDU_HEADER_SIZE
                val total = headerLen + pduLen + (if (hasChecksum) 1 else 0)
                if (buf.size < total) break // 半截帧，保留等待后续 burst
                // 帧完整：切出 PDU（跳过传输头，含校验和则跳过尾部）
                val pdu = ByteArray(pduLen)
                for (i in 0 until pduLen) pdu[i] = buf[headerLen + i]
                consume(total)
                out.add(pdu)
                continue
            }

            if (b0 == VENDOR_HI && b1 == VENDOR_LO) {
                // ---- 裸 PDU：00 1D ... ----
                // 从 offset 4 起找下一个帧起始（FF 或 00 1D）
                var cut = -1
                var i = 4
                while (i < buf.size) {
                    val v = buf[i].toInt() and 0xFF
                    if (v == SOF || (v == VENDOR_HI && i + 1 < buf.size &&
                                    (buf[i + 1].toInt() and 0xFF) == VENDOR_LO)) {
                        cut = i
                        break
                    }
                    i++
                }
                if (cut > 0) {
                    // 找到下一帧边界：交付本段裸 PDU
                    val pdu = ByteArray(cut)
                    for (j in 0 until cut) pdu[j] = buf[j]
                    consume(cut)
                    out.add(pdu)
                    continue
                }
                // 无边界：burst 已结束则整段交付（老设备单帧模式）
                if (burstEnd) {
                    val pdu = ByteArray(buf.size)
                    for (j in pdu.indices) pdu[j] = buf[j]
                    consume(buf.size)
                    out.add(pdu)
                }
                // burst 未结束：保留等待下一 burst 的边界字节
                break
            }

            // ---- 垃圾字节：跳过 1 字节继续扫描 ----
            consume(1)
        }
        return out
    }

    /** 消费缓冲前 n 个字节 */
    private fun consume(n: Int) {
        for (i in 0 until n) buf.removeAt(0)
    }

    /** 缓冲残留字节数（诊断用） */
    fun pendingSize(): Int = buf.size

    /** 清空内部缓冲（断开重连时调用） */
    fun reset() = buf.clear()
}
