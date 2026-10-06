package com.fxxkmoondrop.secret

import com.fxxkmoondrop.secret.GattProtocolVerdict.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATT 服务发现裁定逻辑的单元测试。
 *
 * 回归来源：issue #12（水月雨 知更鸟 Robin）。空服务列表被判为
 * 「协议证伪」并永久拉黑，导致除电量外全部功能不可用。
 */
class GattProtocolVerdictTest {

    @Test
    fun `找到 GAIA 服务即支持`() {
        assertEquals(Verdict.SUPPORTED, GattProtocolVerdict.decide(3, hasGaia = true, hasSrc9 = false))
    }

    @Test
    fun `找到 9ECA 服务即支持`() {
        assertEquals(Verdict.SUPPORTED, GattProtocolVerdict.decide(5, hasGaia = false, hasSrc9 = true))
    }

    @Test
    fun `两个都在也算支持`() {
        assertEquals(Verdict.SUPPORTED, GattProtocolVerdict.decide(9, hasGaia = true, hasSrc9 = true))
    }

    /** 核心回归：空服务列表 **不能** 判为证伪。 */
    @Test
    fun `服务列表为空判为不确定 不得拉黑`() {
        assertEquals(
            "issue #12 根因：空列表是传输层异常，不是协议指纹证伪",
            Verdict.INCONCLUSIVE,
            GattProtocolVerdict.decide(serviceCount = 0, hasGaia = false, hasSrc9 = false),
        )
    }

    /** 有服务但缺两个协议 -> 这才是真正的证伪依据。 */
    @Test
    fun `有服务但缺协议才算证伪`() {
        assertEquals(
            Verdict.REFUTED,
            GattProtocolVerdict.decide(serviceCount = 4, hasGaia = false, hasSrc9 = false),
        )
    }

    @Test
    fun `服务数量为 1 且无协议也算证伪`() {
        assertEquals(Verdict.REFUTED, GattProtocolVerdict.decide(1, false, false))
    }

    // ── 空服务列表重试策略 ──

    @Test
    fun `空列表首次应重试`() {
        assertTrue(GattProtocolVerdict.shouldRetryEmptyServices(0))
    }

    @Test
    fun `未达上限仍应重试`() {
        assertTrue(GattProtocolVerdict.shouldRetryEmptyServices(1))
    }

    @Test
    fun `达到上限后不再重试 退回 RFCOMM`() {
        assertFalse(GattProtocolVerdict.shouldRetryEmptyServices(2))
        assertFalse(GattProtocolVerdict.shouldRetryEmptyServices(3))
    }

    @Test
    fun `重试上限是两次且间隔大于零`() {
        assertEquals(2, GattProtocolVerdict.MAX_EMPTY_SVC_RETRIES)
        assertTrue(
            "间隔必须大于 0，否则变成忙等重发，可能反而加剧链路问题",
            GattProtocolVerdict.EMPTY_SVC_RETRY_DELAY_MS > 0,
        )
    }
}
