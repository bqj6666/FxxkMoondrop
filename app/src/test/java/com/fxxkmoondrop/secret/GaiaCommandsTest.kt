package com.fxxkmoondrop.secret

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GaiaCommands 帧构造 / 能力位图解析 / ANC 映射 的 JVM 单元测试。
 *
 * 纯字节逻辑，不依赖 Android 框架，`./gradlew test` 可直接运行。
 * 期望值来源：ADAPTATION.md 铁证 + 官方 App 逆向（真机抓包 / 官方源码逐条核对）。
 */
class GaiaCommandsTest {

    // ==================== GAIA V3 帧构造 ====================

    /** GA2 铁证：查询 ANC V2 当前模式 = `00 1D 40 03` */
    @Test
    fun anc2GetMode_matchesVerifiedBytes() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x1D, 0x40, 0x03),
            GaiaCommands.anc2GetMode()
        )
    }

    /** 布丁 / 太空漫游2 铁证：设置 ANC V2 = `00 1D 40 04 <mode>` */
    @Test
    fun anc2SetMode_appendsSingleBytePayload() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x1D, 0x40, 0x04, 0x01),
            GaiaCommands.anc2SetMode(1)
        )
    }

    /** BASIC GET_SUPPORTED_FEATURES：feature=0, cmd=1 */
    @Test
    fun basicGetSupportedFeatures_encodesFeatureAndCommand() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x1D, 0x00, 0x01),
            GaiaCommands.basicGetSupportedFeatures()
        )
    }

    /** TYPE_RESPONSE(2) 落在 cmdValue 的 bit7 */
    @Test
    fun v3PacketVendor_responseType_setsBit7() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x1D, 0x01, 0x03),
            GaiaCommands.v3PacketVendor(0x1D, 0, GaiaCommands.TYPE_RESPONSE, 3, null)
        )
    }

    /** vendor 大端两字节；feature 跨字节时高字节落 0x40 */
    @Test
    fun v3Packet_vendorAndFeatureEncoding() {
        val f = GaiaCommands.v3Packet(GaiaCommands.F_ANC_V2, 42, null)
        assertEquals(4, f.size)
        assertEquals(0x00, f[0].toInt())
        assertEquals(GaiaCommands.GAIA_VENDOR, f[1].toInt())
        assertEquals(0x40, f[2].toInt())
        assertEquals(42, f[3].toInt())
    }

    // ==================== 9ECA BleSourceSwitch 帧构造 ====================

    /** 帧头固定：A5 01 01 + cmd + seq + len */
    @Test
    fun srcFrame_headerAndLength() {
        assertArrayEquals(
            byteArrayOf(0xA5.toByte(), 0x01, 0x01, 0x01, 0x00, 0x00),
            GaiaCommands.srcGetAudioSource(0)
        )
    }

    @Test
    fun srcPing_usesCommand127() {
        assertArrayEquals(
            byteArrayOf(0xA5.toByte(), 0x01, 0x01, 0x7F, 0x00, 0x00),
            GaiaCommands.srcPing(0)
        )
    }

    /** payload 上限 14 字节契约 */
    @Test
    fun srcCommand_rejectsPayloadOver14Bytes() {
        assertThrows(IllegalArgumentException::class.java) {
            GaiaCommands.srcCommand(1, 0, ByteArray(15))
        }
    }

    @Test
    fun srcCommand_acceptsExactly14Bytes() {
        val f = GaiaCommands.srcCommand(1, 0, ByteArray(14))
        assertEquals(6 + 14, f.size)
        assertEquals(14, f[5].toInt())
    }

    // ==================== 能力位图解析 ====================

    /** 大端 32 位 word，bit 序号 = feature id */
    @Test
    fun parseSupportedFeatures_readsBitsBigEndian() {
        // word0 = 0x00000104 -> bit2(F_ANC) + bit8(F_AUDIO_CURATION)
        val s = GaiaCommands.parseSupportedFeatures(byteArrayOf(0x00, 0x00, 0x01, 0x04))
        assertEquals(setOf(GaiaCommands.F_ANC, GaiaCommands.F_AUDIO_CURATION), s)
    }

    /** 第 2 个 word（wordIdx=1）承载 feature 32 = ANC V2 */
    @Test
    fun parseSupportedFeatures_secondWordCoversAncV2() {
        val s = GaiaCommands.parseSupportedFeatures(
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01)
        )
        assertEquals(setOf(GaiaCommands.F_ANC_V2), s)
    }

    @Test
    fun parseSupportedFeatures_nullOrEmptyIsEmptySet() {
        assertTrue(GaiaCommands.parseSupportedFeatures(null).isEmpty())
        assertTrue(GaiaCommands.parseSupportedFeatures(ByteArray(0)).isEmpty())
    }

    /** 长度非 4 倍数 = 位图被截断（alpha2.22 健壮性判定） */
    @Test
    fun isFeaturePayloadTruncated_onlyWhenNotMultipleOfFour() {
        assertFalse(GaiaCommands.isFeaturePayloadTruncated(null))
        assertFalse(GaiaCommands.isFeaturePayloadTruncated(ByteArray(4)))
        assertFalse(GaiaCommands.isFeaturePayloadTruncated(ByteArray(8)))
        assertTrue(GaiaCommands.isFeaturePayloadTruncated(ByteArray(5)))
    }

    // ==================== ANC 路径选择 ====================

    @Test
    fun ancPathFrom_priorityOrder() {
        assertEquals(GaiaCommands.ANC_PATH_UNKNOWN, GaiaCommands.ancPathFrom(emptySet()))
        assertEquals(
            GaiaCommands.ANC_PATH_ANC_V1,
            GaiaCommands.ancPathFrom(setOf(GaiaCommands.F_ANC))
        )
        assertEquals(
            GaiaCommands.ANC_PATH_ANC_V2,
            GaiaCommands.ancPathFrom(setOf(GaiaCommands.F_ANC_V2))
        )
        // AudioCuration 优先级最高
        assertEquals(
            GaiaCommands.ANC_PATH_AUDIO_CURATION,
            GaiaCommands.ancPathFrom(
                setOf(
                    GaiaCommands.F_ANC,
                    GaiaCommands.F_ANC_V2,
                    GaiaCommands.F_AUDIO_CURATION
                )
            )
        )
    }

    // ==================== 设备码 <-> UI 模式映射 ====================

    /** ANC V2 恒等映射（0..5）；越界返回 -1，禁止发送 */
    @Test
    fun ancV2_isIdentityMapping() {
        for (m in 0..5) {
            assertEquals(m, GaiaCommands.ancDevFromUi(GaiaCommands.ANC_PATH_ANC_V2, m))
            assertEquals(m, GaiaCommands.ancUiFromDev(GaiaCommands.ANC_PATH_ANC_V2, m))
        }
        assertEquals(-1, GaiaCommands.ancDevFromUi(GaiaCommands.ANC_PATH_ANC_V2, 6))
        assertEquals(-1, GaiaCommands.ancUiFromDev(GaiaCommands.ANC_PATH_ANC_V2, 6))
        assertEquals(-1, GaiaCommands.ancUiFromDev(GaiaCommands.ANC_PATH_ANC_V2, -1))
    }

    /** ANC V1：仅 0/1 两态 */
    @Test
    fun ancV1_isBinaryMapping() {
        assertEquals(0, GaiaCommands.ancDevFromUi(GaiaCommands.ANC_PATH_ANC_V1, 0))
        assertEquals(1, GaiaCommands.ancDevFromUi(GaiaCommands.ANC_PATH_ANC_V1, 2))
        assertEquals(0, GaiaCommands.ancUiFromDev(GaiaCommands.ANC_PATH_ANC_V1, 0))
        assertEquals(1, GaiaCommands.ancUiFromDev(GaiaCommands.ANC_PATH_ANC_V1, 1))
    }

    /** GA2 实测 SET 映射 [1,2,4,3]：UI 透传(2)->dev 4、UI 抗风(3)->dev 3；第 5 档不支持 */
    @Test
    fun audioCuration_ga2SetMap() {
        val ga2 = intArrayOf(1, 2, 4, 3)
        assertEquals(1, GaiaCommands.ancDevFromUi(GaiaCommands.ANC_PATH_AUDIO_CURATION, 0, ga2))
        assertEquals(2, GaiaCommands.ancDevFromUi(GaiaCommands.ANC_PATH_AUDIO_CURATION, 1, ga2))
        assertEquals(4, GaiaCommands.ancDevFromUi(GaiaCommands.ANC_PATH_AUDIO_CURATION, 2, ga2))
        assertEquals(3, GaiaCommands.ancDevFromUi(GaiaCommands.ANC_PATH_AUDIO_CURATION, 3, ga2))
        assertEquals(-1, GaiaCommands.ancDevFromUi(GaiaCommands.ANC_PATH_AUDIO_CURATION, 4, ga2))
    }

    /** GA2 实测 GET 读回 0-based 直传 [0,1,2,3] */
    @Test
    fun audioCuration_ga2GetMapIsZeroBased() {
        val ga2Get = intArrayOf(0, 1, 2, 3)
        assertEquals(0, GaiaCommands.ancUiFromDev(GaiaCommands.ANC_PATH_AUDIO_CURATION, 0, null, ga2Get))
        assertEquals(2, GaiaCommands.ancUiFromDev(GaiaCommands.ANC_PATH_AUDIO_CURATION, 2, null, ga2Get))
        assertEquals(-1, GaiaCommands.ancUiFromDev(GaiaCommands.ANC_PATH_AUDIO_CURATION, 7, null, ga2Get))
    }

    /** 无档案时回退官方默认 1-based [1,2,3,4] */
    @Test
    fun audioCuration_defaultMapFallback() {
        assertEquals(4, GaiaCommands.ancDevFromUi(GaiaCommands.ANC_PATH_AUDIO_CURATION, 3))
        assertEquals(2, GaiaCommands.ancUiFromDev(GaiaCommands.ANC_PATH_AUDIO_CURATION, 3))
    }

    /** 未知路径一律 -1：绝不把「未知/未就绪」当特定能力处理（alpha2.26.7） */
    @Test
    fun unknownPath_neverSendsCommand() {
        assertEquals(-1, GaiaCommands.ancDevFromUi(GaiaCommands.ANC_PATH_UNKNOWN, 1))
        assertEquals(-1, GaiaCommands.ancUiFromDev(GaiaCommands.ANC_PATH_UNKNOWN, 1))
    }
}
