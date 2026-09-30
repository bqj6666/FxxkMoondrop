package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.pressable

/**
 * 整卡片按下高亮 —— 对齐 SonyPods「点整行 → 整卡变深色」的观感。
 *
 * ## 为什么不直接用 Card 的 onClick + showIndication
 *
 * `Card` 的 `interactionSource` 是**私有**的（Card.kt:98 内部 remember），
 * 外部拿不到它的按压态；而 `showIndication` 画的是库自带的
 * `MiuixIndication`（直角通栏 drawRect），正是我们要修的那个形状问题。
 *
 * 所以自己建 `MutableInteractionSource`，用它同时驱动：
 *   1. `pressable(PressFeedbackType.Sink)` → HyperOS 的「按下轻微下沉」
 *   2. `CardDefaults.defaultColors(color = 按压色)` → **整卡背景**变色
 *
 * ## 圆角
 *
 * 16.dp = 官方 `CardDefaults.CornerRadius`（Card.kt:190），
 * 与 `ListPopup` 弹层圆角（ListPopup.kt:604）一致。
 *
 * ⚠️ 与 `MiuixSpec.CARD_RADIUS`(20.dp) 的区别：后者是我们早期为对齐
 * M3Ui.applyCardLook 定的容器圆角，本组件走 Miuix 官方口径，两者不混用。
 */
@Composable
internal fun MiuixPressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scheme = MiuixTheme.colorScheme

    // 按下时整卡变深色；松手回弹。surfaceContainerHigh 是 Miuix 自己的
    // 抬升色层级，不自造颜色。
    val bg by animateColorAsState(
        targetValue = if (pressed && enabled) scheme.surfaceContainerHigh
        else scheme.surfaceContainer,
        label = "miuixCardPressBg",
    )

    Card(
        modifier = modifier,
        cornerRadius = MiuixPressableCard.RADIUS,
        colors = CardDefaults.defaultColors(color = bg),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .pressable(
                    // ⭐ 官方按压形变：HyperOS 的「按下轻微下沉」
                    //   pressable 只管反馈，不管点击（Pressable.kt:85）
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,   // 高亮由卡片背景承担，不要第二个 indication
                    onClick = onClick,
                ),
        ) {
            content()
        }
    }
}

/** 与 Miuix 官方 CardDefaults.CornerRadius 一致（basic/Card.kt:190）。 */
internal object MiuixPressableCard {
    val RADIUS: Dp = 16.dp
}
