package com.fxxkmoondrop.secret

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [XiaomiProbe.evaluate] 的判定规则测试。
 *
 * 直接调用真实实现的纯逻辑静态方法（不碰任何 Android API），因此不存在
 * 「测试里复制一份逻辑」导致实现改了测试还绿的问题。
 */
class XiaomiProbeTest {

    /** 「一个包都不存在」的查询函数。显式声明类型，避免尾随 lambda 解析歧义。 */
    private val noPkg: (String) -> Boolean = { false }

    /** 「一个属性都没有」的属性表。 */
    private fun noProps(): Map<String, String> = emptyMap()

    // ── L1：品牌（精确匹配，防子串误判）──
    @Test
    fun `小米系品牌直接命中`() {
        assertTrue(XiaomiProbe.evaluate("xiaomi", noProps(), noPkg))
        assertTrue(XiaomiProbe.evaluate("redmi", noProps(), noPkg))
        assertTrue(XiaomiProbe.evaluate("poco", noProps(), noPkg))
        assertTrue(XiaomiProbe.evaluate("black sesame", noProps(), noPkg))
    }

    @Test
    fun `非小米品牌不命中`() {
        assertFalse(XiaomiProbe.evaluate("oneplus", noProps(), noPkg))
        assertFalse(XiaomiProbe.evaluate("oppo", noProps(), noPkg))
        assertFalse(XiaomiProbe.evaluate("samsung", noProps(), noPkg))
    }

    @Test
    fun `品牌为 null 时交给后两级判据`() {
        assertFalse(XiaomiProbe.evaluate(null, noProps(), noPkg))
        assertTrue(XiaomiProbe.evaluate(null, mapOf("ro.miui.ui.version.name" to "V14"), noPkg))
    }

    @Test
    fun `品牌是子串不算命中`() {
        // 「XiaomiFake」「notxiaomi」不能被当成小米
        assertFalse(XiaomiProbe.evaluate("xiaomifake", noProps(), noPkg))
        assertFalse(XiaomiProbe.evaluate("notxiaomi", noProps(), noPkg))
    }

    @Test
    fun `品牌首尾空白影响判定说明调用方需先规范化`() {
        // 刻意不测：规范化在 norm() 里，evaluate 契约要求传入值已小写去空白。
        // 这里只确认不抛异常。
        XiaomiProbe.evaluate("  xiaomi  ", noProps(), noPkg)
    }

    // ── L2：系统属性（MIUI 与 HyperOS 两代都认）──
    @Test
    fun `MIUI 属性命中`() {
        assertTrue(XiaomiProbe.evaluate("oneplus", mapOf("ro.miui.ui.version.name" to "V12"), noPkg))
    }

    @Test
    fun `HyperOS 属性命中`() {
        assertTrue(XiaomiProbe.evaluate("oneplus", mapOf("ro.mi.os.version.name" to "14"), noPkg))
    }

    @Test
    fun `属性值为空或纯空白不算命中`() {
        assertFalse(XiaomiProbe.evaluate("oneplus", mapOf("ro.miui.ui.version.name" to ""), noPkg))
        assertFalse(XiaomiProbe.evaluate("oneplus", mapOf("ro.miui.ui.version.name" to "   "), noPkg))
        assertFalse(XiaomiProbe.evaluate("oneplus", mapOf("ro.miui.ui.version.name" to ""), noPkg))
    }

    // ── L3：包存在性 ──
    @Test
    fun `存在小米独有包即命中`() {
        assertTrue(XiaomiProbe.evaluate("oneplus", noProps()) { it == "com.miui.securitycenter" })
        assertTrue(XiaomiProbe.evaluate("oneplus", noProps()) { it == "com.miui.home" })
        assertTrue(XiaomiProbe.evaluate("oneplus", noProps()) { it == "com.miui.securitycenter" || it == "com.mi.android.globalFileexplorer" })
    }

    @Test
    fun `包全部不存在则不命中`() {
        assertFalse(XiaomiProbe.evaluate("oneplus", noProps()) { false })
    }

    @Test
    fun `不相关包不算命中`() {
        assertFalse(XiaomiProbe.evaluate("oneplus", noProps()) { it == "com.android.settings" })
        assertFalse(XiaomiProbe.evaluate("oneplus", noProps()) { it == "com.xiaomi.bluetooth" })
    }

    // ── 三级是「或」关系，任一命中即可 ──
    @Test
    fun `三级判据独立生效`() {
        assertTrue(XiaomiProbe.evaluate("xiaomi", noProps(), noPkg))
        assertTrue(XiaomiProbe.evaluate("oneplus", mapOf("ro.mi.os.version.name" to "14"), noPkg))
        assertTrue(XiaomiProbe.evaluate("oneplus", noProps()) { it == "com.miui.home" })
    }

    @Test
    fun `包查询抛异常时降级为不命中而不崩溃`() {
        assertFalse(XiaomiProbe.evaluate("oneplus", noProps()) { throw RuntimeException("PM 挂了") })
    }
}
