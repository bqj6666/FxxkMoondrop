package com.fxxkmoondrop.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [XiaomiCarrierId] 的形态 → 载体 ID 映射测试。
 *
 * 设计要点（借鉴 HyperEars 的 MiLinkCarrierIdentity，AGPL-3.0，未复制其代码）：
 * 映射键是**形态**（TWS / 头戴），不是型号 —— 这些 ID 是「能力载体」，
 * 让 MiLink 走通原生准入与类型重建，**不代表任何小米型号的真实身份**。
 * 因此不存在型号硬编码表，新增形态只需加一行。
 */
class XiaomiCarrierIdTest {

    @Test
    fun `TWS 形态映射到 01010607`() {
        assertEquals("01010607", XiaomiCarrierId.forFormFactor(XiaomiCarrierId.FormFactor.TWS))
    }

    @Test
    fun `头戴形态映射到 01013A04`() {
        assertEquals("01013A04", XiaomiCarrierId.forFormFactor(XiaomiCarrierId.FormFactor.HEADPHONES))
    }

    // ── 未登记形态必须返回 null，绝不返回随便一个 ID ──
    // 硬编码的代价就在这里：宁可「拿不到载体 ID 而降级」，
    // 也不能让 TWS 冒用头戴 ID 去走 MiLink 的准入流程。
    @Test
    fun `未登记形态返回 null 而非兜底 ID`() {
        assertNull(XiaomiCarrierId.forFormFactor(XiaomiCarrierId.FormFactor.UNKNOWN))
    }

    @Test
    fun `形态名解析大小写与空白不敏感`() {
        assertNotNull(XiaomiCarrierId.parse("tws"))
        assertNotNull(XiaomiCarrierId.parse("  TWS  "))
        assertNotNull(XiaomiCarrierId.parse("headphones"))
    }

    @Test
    fun `无法识别的形态名返回 null`() {
        assertNull(XiaomiCarrierId.parse(""))
        assertNull(XiaomiCarrierId.parse("   "))
        assertNull(XiaomiCarrierId.parse("speaker"))
        assertNull(XiaomiCarrierId.parse("tws_2"))
    }

    // ── ID 格式自检：8 位十六进制。防止手滑写错一位 ──
    @Test
    fun `所有已登记载体 ID 都是 8 位十六进制`() {
        XiaomiCarrierId.registered().forEach { (formFactor, id) ->
            assertEquals("$formFactor 的载体 ID 应为 8 位", 8, id.length)
            assertTrue("$formFactor 的载体 ID 含非十六进制字符: $id",
                    id.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
        }
    }

    // ── 映射表的不可变性 ──
    @Test
    fun `不同形态不会映射到同一个 ID`() {
        val ids = XiaomiCarrierId.registered().values
        assertEquals("两个形态共用同一载体 ID 会串台", ids.size, ids.toSet().size)
    }

    @Test
    fun `形态枚举与映射表一一对应`() {
        // 除了 UNKNOWN，每个形态都必须有载体 ID —— 防止加了枚举却忘了填映射
        XiaomiCarrierId.FormFactor.entries.forEach { ff ->
            if (ff == XiaomiCarrierId.FormFactor.UNKNOWN) return@forEach
            assertNotNull("$ff 已登记为形态却查不到载体 ID", XiaomiCarrierId.forFormFactor(ff))
        }
    }
}
