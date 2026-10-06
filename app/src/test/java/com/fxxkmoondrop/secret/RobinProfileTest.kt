package com.fxxkmoondrop.secret

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知更鸟 / Robin 型号档案（issue #12）。
 *
 * ## 回归来源
 *
 * 用户反馈「透传按钮是个摆设，按了也没用」。日志实证：
 *
 * ```
 * 23:30~23:32  dev=2   （稳定，用户在用降噪）
 * 23:32:31     dev=1   （用耳机切档，耳机上报）
 * ```
 *
 * 整段使用期只在 dev=1 / dev=2 之间切换，且用户明确说
 * 「耳机本身也就只有降噪和透传模式」。而默认映射 [1,2,3,4] 把
 * dev=1 解成「关闭」、dev=2 解成「降噪」，点 UI 的「透传」(ui=2)
 * 会发 dev=3 —— 该耳机只认 1/2，命令被忽略，按钮自然毫无反应。
 *
 * 修法：建档案，令 dev=1=降噪、dev=2=透传，并声明只有这两档。
 */
class RobinProfileTest {

    private val name = "ROBIN'S EARPHONES"

    /** SET：降噪 -> 1，透传 -> 2。 */
    @Test
    fun `SET 映射 降噪=1 透传=2`() {
        val m = AncProfileLib.resolve(name, null)
        assertEquals("UI 1(降噪) 应发 dev=1", 1, m[1])
        assertEquals("UI 2(透传) 应发 dev=2", 2, m[2])
    }

    /** 关闭(0) 与抗风噪(3) 不存在，用 -1 标记「不支持」，避免长出死按钮。 */
    @Test
    fun `关闭与抗风噪档位不存在`() {
        val m = AncProfileLib.resolve(name, null)
        assertEquals("没关闭档", -1, m[0])
        assertEquals("没抗风噪档", -1, m[3])
    }

    /** GET：dev -> UI。设备上报 1 应解成降噪、2 应解成透传。 */
    @Test
    fun `GET 反解 dev1=降噪 dev2=透传`() {
        val m = AncProfileLib.resolve(name, null)
        assertEquals("dev=1 是降噪", 1, m.indexOf(1))
        assertEquals("dev=2 是透传", 2, m.indexOf(2))
    }

    /** 这是本次修复的核心：点 UI「透传」必须发 dev=2，而不是默认映射的 3。 */
    @Test
    fun `点透传不再发未支持的三号值`() {
        val m = AncProfileLib.resolve(name, null)
        val devForTransparency = m[2]
        assertEquals(2, devForTransparency)
        assertFalse(
            "发 dev=3 正是 issue #12 里命令被忽略的原因",
            devForTransparency == 3,
        )
    }

    /** 只声明降噪与透传两档 —— UI 不应出现关闭/抗风噪两个死按钮。 */
    @Test
    fun `只宣告降噪与透传两档`() {
        val modes = AncProfileLib.supportedUiModes(name)
        assertNotNull("命中档案后应限制档位", modes)
        assertArrayEquals(intArrayOf(1, 2), modes)
    }

    /** 未收录型号不得被限制（返回 null = 走 BASIC_UI_MODES）。 */
    @Test
    fun `未收录型号不受限`() {
        assertEquals(null, AncProfileLib.supportedUiModes("SOME UNKNOWN HEADSET"))
        assertEquals(null, AncProfileLib.supportedUiModes(null))
    }

    /** 不能误伤既有档案。 */
    @Test
    fun `既有型号档案不受影响`() {
        val ga2 = AncProfileLib.resolve("GOLDEN AGES 2", null)
        assertEquals("GA2 关闭仍为 1", 1, ga2[0])
        assertEquals("GA2 降噪仍为 2", 2, ga2[1])
        assertEquals("GA2 透传仍为 4", 4, ga2[2])
        assertArrayEquals(
            "GA2 四档齐全，不该被限制",
            intArrayOf(0, 1, 2, 3),
            AncProfileLib.supportedUiModes("GOLDEN AGES 2"),
        )
    }

    /** 档案名可被识别（设置页展示用）。 */
    @Test
    fun `档案名可识别`() {
        assertEquals("ROBIN", AncProfileLib.matchedProfileName(name))
        assertTrue(AncProfileLib.matchedProfileName(name) != "默认")
    }
}
