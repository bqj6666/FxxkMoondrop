package com.fxxkmoondrop.secret

import org.junit.Assert.assertEquals
import org.junit.Test

/** DetailRows.visibility 的规则测试：耳机未连接 / 已连接未就绪 / 已连接就绪 三档。 */
class DetailRowsTest {

    @Test
    fun `耳机未连接时三行全隐藏`() {
        val v = DetailRows.visibility(hasSlice = true, connected = false, ready = false)
        assertEquals(false, v.slice)
        assertEquals(false, v.loading)
        assertEquals(false, v.panel)
    }

    @Test
    fun `没有切片地址的设备未连接时同样三行全隐藏`() {
        val v = DetailRows.visibility(hasSlice = false, connected = false, ready = false)
        assertEquals(false, v.slice)
        assertEquals(false, v.loading)
        assertEquals(false, v.panel)
    }

    @Test
    fun `已连接但GAIA未就绪时用官方加载行占位`() {
        val v = DetailRows.visibility(hasSlice = true, connected = true, ready = false)
        assertEquals(false, v.slice)
        assertEquals(true, v.loading)
        assertEquals(true, v.panel)
    }

    @Test
    fun `已连接且GAIA就绪时切片出来加载行收起`() {
        val v = DetailRows.visibility(hasSlice = true, connected = true, ready = true)
        assertEquals(true, v.slice)
        assertEquals(false, v.loading)
        assertEquals(true, v.panel)
    }

    @Test
    fun `设备没有切片地址时不给切片也不留占位但面板照旧`() {
        val v = DetailRows.visibility(hasSlice = false, connected = true, ready = false)
        assertEquals(false, v.slice)
        assertEquals(false, v.loading)
        assertEquals(true, v.panel)
    }

    @Test
    fun `上报顺序抖动（就绪但连接位还是0）不收起已出来的行`() {
        val v = DetailRows.visibility(hasSlice = true, connected = false, ready = true)
        assertEquals(true, v.slice)
        assertEquals(false, v.loading)
        assertEquals(true, v.panel)
    }

    @Test
    fun `断开连接后加载行与切片与面板一起消失`() {
        // 断开后模块上报 connected=0、gaia 也会回落成 0，等价于「未连接」那一档。
        val v = DetailRows.visibility(hasSlice = true, connected = false, ready = false)
        assertEquals(false, v.loading)
        assertEquals(false, v.slice)
        assertEquals(false, v.panel)
    }
}
