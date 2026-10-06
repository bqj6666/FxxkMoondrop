package com.fxxkmoondrop.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Material3 规范值约束测试。
 *
 * ## 为什么值得钉死
 *
 * 3.3.1 之前同一屏里卡片的圆角有 **20 / 24 / 28 三种**，
 * 用户反馈「我们的很奇怪」。根因之一就是圆角不在规范档上。
 *
 * 规范值来自 material-1.14.0.aar 实测（不是凭印象）：
 *
 * ```
 * Base.Widget.Material3.CardView
 *   → shapeAppearance = ?attr/shapeAppearanceCornerMedium
 *   → m3_sys_shape_corner_value_medium = 12dp
 *
 * m3_sys_shape_corner_value_extra_large = 28dp   （对话框用）
 * ```
 *
 * 这些数字以后很容易被「顺手调大一点」改掉，用测试锁住。
 */
class Material3SpecTest {

    /** 卡片圆角必须 = M3 官方 medium 档 12dp。 */
    @Test
    fun `卡片圆角必须是 M3 规范 12dp`() {
        assertEquals(
            "CardView 规范档 = shapeAppearanceCornerMedium = 12dp；" +
                    "改成别的值就会脱离 M3 规范（20/24/28 都不是 Card 档）",
            12, M3Ui.RADIUS_CARD,
        )
    }

    /** 对话框圆角必须 = M3 官方 extraLarge 档 28dp，与卡片是两个档位。 */
    @Test
    fun `对话框圆角必须是 M3 规范 28dp`() {
        assertEquals(
            "Dialog 规范档 = extra_large = 28dp",
            28, M3Ui.RADIUS_DIALOG,
        )
    }

    /** 两者必须是不同档位，避免有人「统一」成一个值。 */
    @Test
    fun `卡片与对话框圆角是两个不同档位`() {
        assertEquals(
            "M3 里 Card(medium=12) 与 Dialog(extraLarge=28) 是不同的 shape 档，不应合并",
            true, M3Ui.RADIUS_CARD != M3Ui.RADIUS_DIALOG,
        )
    }
}
