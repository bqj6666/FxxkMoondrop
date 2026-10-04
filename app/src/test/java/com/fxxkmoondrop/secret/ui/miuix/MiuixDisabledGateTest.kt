package com.fxxkmoondrop.secret.ui.miuix

import com.fxxkmoondrop.secret.MiuixSurface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Miuix 轨的**停用闸门**测试。
 *
 * ## 背景
 *
 * 3.2.13 用户决定：Miuix 面板三件套（概览 / 设置 / 导航栏）功能与交互
 * 都跑通了，但整体观感与参考项目仍有差距（深色下卡片与背景对比偏弱等），
 * 于是**暂时停用**——代码全部保留，只是不再进入。
 *
 * ## 为什么要有这个测试
 *
 * 停用是**一行布尔值**（`MiuixSurface.DISABLED`）。这种改动最容易被
 * 下一个人「顺手清理」时误删，或者反过来在没做完视觉调优时被提前打开。
 * 用测试钉死当前意图：
 *
 *   - 停用期间 `enabled()` 必须恒 false（哪怕 SP 里存着 MIUIX）
 *   - `disabled` 必须为 true（设置页据此隐藏入口）
 *
 * 将来决定恢复 Miuix 时，**改这两个测试 + 改 DISABLED**，三处一起动。
 */
class MiuixDisabledGateTest {

    /**
     * 停用期间即使 SP 存着 `MIUIX`，`enabled()` 也必须返回 false。
     *
     * 真机上装过的那版 SP 里确实存着 `ui_style=MIUIX`，
     * 若闸门失效，用户升级后会直接进 Miuix 界面 —— 正是要避免的情况。
     */
    @Test
    fun `停用期间 enabled 恒为 false`() {
        assertFalse(
            "Miuix 轨已停用，MiuixSurface.enabled() 必须恒 false；" +
                    "恢复请改 MiuixSurface.DISABLED 并同步改本测试",
            MiuixSurface.enabled(null),
        )
    }

    /** 设置页用它隐藏「界面风格」下拉，必须与 enabled 保持一致。 */
    @Test
    fun `disabled 标志为 true`() {
        assertTrue(
            "停用期间 MiuixSurface.disabled 必须为 true，否则设置页仍会显示无效入口",
            MiuixSurface.disabled,
        )
    }

    /** 两个标志必须一致，避免出现「入口显示了但进不去」的半吊子状态。 */
    @Test
    fun `disabled 与 enabled 互斥`() {
        assertTrue(
            "disabled=true 时 enabled() 必须为 false",
            MiuixSurface.disabled && !MiuixSurface.enabled(null),
        )
    }
}
