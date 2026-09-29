package com.fxxkmoondrop.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UiStyle] 的存储与解析规则测试。
 *
 * 主题是**二维正交**的：
 *  - 亮暗  `ThemeUtil.themeMode`（Int 0/1/2，沿用既有实现，不动）
 *  - 风格  `UiStyle`（MATERIAL / MIUIX）
 *
 * 二者互不依赖，任意组合都合法。
 *
 * 设计借鉴 `silverpoetry/HyperEars` 的 `ui/theme/UiPreferences`（AGPL-3.0，
 * 仅参考其「切换风格不丢另一套的专属设置」与二维正交这两点设计）。
 */
class UiStyleTest {

    @Test
    fun `默认风格是 Material`() {
        // 默认必须是不引入任何新依赖的那一轨：
        // 存量用户升级后看到的界面必须与 3.2.12 完全一致。
        assertEquals(UiStyle.MATERIAL, UiStyle.fromStored(null))
        assertEquals(UiStyle.MATERIAL, UiStyle.fromStored(""))
    }

    @Test
    fun `合法值能正确解析`() {
        assertEquals(UiStyle.MATERIAL, UiStyle.fromStored("MATERIAL"))
        assertEquals(UiStyle.MIUIX, UiStyle.fromStored("MIUIX"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(UiStyle.MIUIX, UiStyle.fromStored("miuix"))
        assertEquals(UiStyle.MATERIAL, UiStyle.fromStored("Material"))
    }

    // 存储值被外部改坏时必须退回默认，绝不抛异常
    // —— 主题读不出来不该让整个设置页崩掉。
    @Test
    fun `非法值退回默认而非抛异常`() {
        assertEquals(UiStyle.MATERIAL, UiStyle.fromStored("cupertino"))
        assertEquals(UiStyle.MATERIAL, UiStyle.fromStored("MIUIX_V2"))
        assertEquals(UiStyle.MATERIAL, UiStyle.fromStored("  "))
        assertEquals(UiStyle.MATERIAL, UiStyle.fromStored("12345"))
    }

    // ── 关键设计：切换风格不丢另一套的设置 ──
    // 风格是「存哪个键」的选择，不应清空任何既有偏好。
    @Test
    fun `Miuix 专属设置各有独立键`() {
        // 亮暗读的是既有 ThemeUtil 的 theme_mode 键，与 UiStyle 完全无关：
        // 风格只是「换渲染器 + 换一组键」，不碰亮暗，所以切风格不会丢亮暗偏好。
        assertEquals("ui_miuix_blur", UiStyle.MIUIX.navigatBlurKey)
        assertEquals("ui_miuix_floating_nav", UiStyle.MIUIX.floatingNavKey)
        assertEquals("ui_miuix_scale", UiStyle.MIUIX.scaleKey)
    }

    @Test
    fun `两轨专属设置键不冲突`() {
        // Material 轨沿用既有的 cfg 键，不新增
        assertFalse(UiStyle.MATERIAL.prefKeys().any { it.startsWith("ui_miuix") })
        assertTrue(UiStyle.MIUIX.prefKeys().all { it.startsWith("ui_miuix") })
    }

    // ── 界面缩放：仅 Miuix 轨支持，做区间钳制 ──
    @Test
    fun `界面缩放钳制在 0x9 到 1x1 区间`() {
        // 低于下限钳到 MIN_SCALE，不是钳到默认值
        assertEquals(0.9f, UiStyle.clampScale(0.5f), 0.001f)
        assertEquals(0.9f, UiStyle.clampScale(0.9f), 0.001f)
        assertEquals(1.0f, UiStyle.clampScale(1.0f), 0.001f)
        assertEquals(1.1f, UiStyle.clampScale(1.1f), 0.001f)
        assertEquals(1.1f, UiStyle.clampScale(9.0f), 0.001f)
    }

    @Test
    fun `非有限缩放值退回默认`() {
        // 存储里出现 NaN / 无穷大时不能传给 View.setScaleX，会直接崩
        assertEquals(1.0f, UiStyle.clampScale(Float.NaN), 0.001f)
        assertEquals(1.0f, UiStyle.clampScale(Float.POSITIVE_INFINITY), 0.001f)
        assertEquals(1.0f, UiStyle.clampScale(Float.NEGATIVE_INFINITY), 0.001f)
    }
}
