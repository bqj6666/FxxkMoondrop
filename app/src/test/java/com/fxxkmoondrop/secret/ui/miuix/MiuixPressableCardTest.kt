package com.fxxkmoondrop.secret.ui.miuix

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MiuixPressableCard] 的圆角约束测试。
 *
 * ## 为什么要单独钉死圆角
 *
 * 用户参照 SonyPods 提出「点整行 → 整卡变深色」，而我们原本是
 * 「卡片内一圈高亮」。视觉对齐的前提是**卡片圆角必须与官方一致**，
 * 否则整卡高亮的边缘和卡片本身的边缘会错位，看起来还是两个形状。
 *
 * 官方值（读 ref_miuix 源码确认，不是猜的）：
 *   - `CardDefaults.CornerRadius = 16.dp`   (basic/Card.kt:190)
 *   - `ListPopup` 弹层圆角 `= 16.dp`      (basic/ListPopup.kt:604)
 *
 * ⚠️ 与 `MiuixSpec.CARD_RADIUS`(20.dp) 的区别：
 *   20dp 是我们早期为了对齐 M3Ui.applyCardLook 定的容器圆角；
 *   16dp 才是 Miuix 官方值。本组件走官方口径。
 */
class MiuixPressableCardTest {

    @Test
    fun `整卡圆角必须对齐官方 16dp`() {
        assertEquals(
            "PressableCard 圆角应 = 官方 CardDefaults.CornerRadius (16.dp)",
            16f, MiuixPressableCard.RADIUS.value, 0.01f,
        )
    }

    @Test
    fun `容器圆角与整卡圆角是两个不同口径 不要混用`() {
        // MiuixSpec.CARD_RADIUS 保持 20dp 不动（Material 轨对齐用）
        assertEquals(
            "MiuixSpec.CARD_RADIUS 是容器口径，不应被 PressableCard 改动",
            20f, MiuixSpec.CARD_RADIUS.value, 0.01f,
        )
        // 但两者不同是有意的，注释已说明原因
        assertEquals(
            16f, MiuixPressableCard.RADIUS.value, 0.01f,
        )
    }
}
