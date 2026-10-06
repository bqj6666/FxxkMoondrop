package com.fxxkmoondrop.secret

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测预算测试。
 *
 * 回归来源：对未收录型号的探测失败后**无限重试**（每 5 秒一轮），
 * 反复的 GATT / RFCOMM 连接会打断其他品牌耳机跑在同一条 BLE 上的头部追踪。
 */
class ProbeBudgetTest {

    @After
    fun tearDown() = ProbeBudget.reset()

    @Test
    fun `初始允许探测`() {
        ProbeBudget.reset()
        assertTrue(ProbeBudget.allow("ROBIN'S EARPHONES"))
    }

    @Test
    fun `用满预算后不再允许`() {
        ProbeBudget.reset()
        val n = "ROBIN'S EARPHONES"
        repeat(ProbeBudget.MAX_ATTEMPTS) { ProbeBudget.note(n) }
        assertFalse(
            "用满预算必须停止探测 —— 否则就是每 5 秒一轮的无限重试",
            ProbeBudget.allow(n),
        )
        assertEquals(ProbeBudget.MAX_ATTEMPTS, ProbeBudget.used(n))
    }

    @Test
    fun `中途仍允许`() {
        ProbeBudget.reset()
        ProbeBudget.note("dev")
        if (ProbeBudget.MAX_ATTEMPTS > 1) {
            assertTrue("一次失败（可能是抖动）后应还能再试", ProbeBudget.allow("dev"))
        }
    }

    @Test
    fun `预算是按设备独立的`() {
        ProbeBudget.reset()
        repeat(ProbeBudget.MAX_ATTEMPTS) { ProbeBudget.note("device-a") }
        assertFalse(ProbeBudget.allow("device-a"))
        assertTrue("别的设备不该被连坐", ProbeBudget.allow("device-b"))
    }

    @Test
    fun `设备名大小写与空白归一化`() {
        ProbeBudget.reset()
        repeat(ProbeBudget.MAX_ATTEMPTS) { ProbeBudget.note("  Robin's Earphones  ") }
        assertFalse(ProbeBudget.allow("ROBIN'S EARPHONES"))
    }

    @Test
    fun `reset 后恢复额度`() {
        ProbeBudget.reset()
        repeat(ProbeBudget.MAX_ATTEMPTS) { ProbeBudget.note("d") }
        assertFalse(ProbeBudget.allow("d"))
        ProbeBudget.reset()
        assertTrue("用户显式刷新后应可重来", ProbeBudget.allow("d"))
    }

    @Test
    fun `空名不消耗也不允许`() {
        ProbeBudget.reset()
        assertFalse(ProbeBudget.allow(null))
        assertFalse(ProbeBudget.allow("   "))
        ProbeBudget.note(null)
        assertEquals(0, ProbeBudget.used(null))
    }

    @Test
    fun `预算上限是有限值`() {
        assertTrue(
            "上限必须是有限小数，否则又回到无限重试",
            ProbeBudget.MAX_ATTEMPTS in 1..5,
        )
    }
}
