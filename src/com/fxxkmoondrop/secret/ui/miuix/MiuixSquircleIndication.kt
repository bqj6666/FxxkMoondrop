package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.interfaces.HoldDownInteraction
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 官方 `MiuixIndication` 的超椭圆（squircle）版本。
 *
 * 3.2.13 用户反馈：「主方框高亮那里还是方形的」。
 *
 * 根因（读 ref_miuix master 源码确认，不是猜测）：
 *   utils/MiuixIndication.kt:133 写死
 *       drawRect(color = color, alpha = alpha, size = size)   // 硬编码矩形通栏
 *   升到 0.9.4 / master 也没改成圆角，库确实没修。
 *
 * 为什么只能从 LocalIndication 注入：
 *   下拉行真正画高亮的是 `BasicComponent`（Component.kt:151），它调
 *   `Modifier.clickable(...)` **不传 indication**，于是自动取
 *   CompositionLocal 的 `LocalIndication`。
 *   - Card（Card.kt:114）只有 `showIndication` 开关，不接受 indication 参数；
 *   - BasicComponent（Component.kt:59/126）连 showIndication 都没有；
 *   两者都不透传，所以组件参数这条路封死。
 *   → `androidx.compose.foundation.LocalIndication` 是**唯一**注入点。
 *
 * ⚠️ 更正一条此前的错误结论：
 *   旧注释写过「LocalIndication 不可用，material3 的 LocalIndication 是
 *   internal API」。那说的是 material3 自己的同名 LocalIndication。
 *   Miuix 在 MiuixTheme.kt:36/65 provide 的是
 *   **androidx.compose.foundation.LocalIndication**，公开 API，可以用。
 *
 * 与库的唯一差别：ContentDrawScope.draw() 里 drawRect → drawRRect。
 * 其余（alpha 档位、folmeSpring 曲线、HoldDown 语义）逐行照抄官方，
 * 保证按压手感与 HyperOS 一致。
 */
@Immutable
internal class MiuixSquircleIndication(
    private val color: Color,
    private val radius: androidx.compose.ui.unit.Dp,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        MiuixSquircleIndicationInstance(interactionSource, color, radius)

    override fun hashCode(): Int = 31 * color.hashCode() + radius.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MiuixSquircleIndication) return false
        return color == other.color && radius == other.radius
    }

    private class MiuixSquircleIndicationInstance(
        private val interactionSource: InteractionSource,
        private val color: Color,
        private val radius: androidx.compose.ui.unit.Dp,
    ) : Modifier.Node(), DrawModifierNode {

        private var isPressed = false
        private var isHovered = false
        private var isFocused = false
        private var isHoldDown = false
        private val animatedAlpha = Animatable(0f)
        private var pressedAnimation: Job? = null
        private var restingAnimation: Job? = null

        private fun targetAlpha(): Float {
            var target = 0.0f
            if (isHovered) target += 0.06f
            if (isFocused) target += 0.08f
            if (isPressed && !isHoldDown) target += 0.10f
            if (isHoldDown) target += 0.10f
            return target
        }

        private fun animateOverlay(spring: SpringSpec<Float>, fromPressRelease: Boolean) {
            val target = targetAlpha()
            if (fromPressRelease || target == 0f) {
                restingAnimation?.cancel()
                restingAnimation = coroutineScope.launch {
                    pressedAnimation?.join()
                    animatedAlpha.animateTo(targetValue = target, animationSpec = spring)
                }
            } else {
                pressedAnimation?.cancel()
                restingAnimation?.cancel()
                pressedAnimation = coroutineScope.launch {
                    animatedAlpha.animateTo(targetValue = target, animationSpec = spring)
                }
            }
        }

        override fun onAttach() {
            coroutineScope.launch {
                interactionSource.interactions.collect { interaction ->
                    val prevPressed = isPressed
                    val prevHovered = isHovered
                    val prevFocused = isFocused
                    val prevHoldDown = isHoldDown

                    when (interaction) {
                        is PressInteraction.Press -> isPressed = true
                        is PressInteraction.Release, is PressInteraction.Cancel -> isPressed = false
                        is HoverInteraction.Enter -> isHovered = true
                        is HoverInteraction.Exit -> isHovered = false
                        is FocusInteraction.Focus -> isFocused = true
                        is FocusInteraction.Unfocus -> isFocused = false
                        is HoldDownInteraction.HoldDown -> isHoldDown = true
                        is HoldDownInteraction.Release -> isHoldDown = false
                        else -> return@collect
                    }

                    val spring = when {
                        prevPressed != isPressed ->
                            if (isPressed) pressIn else pressOut
                        prevHoldDown != isHoldDown ->
                            if (isHoldDown) pressIn else pressOut
                        prevHovered != isHovered -> if (isHovered) hoverIn else hoverOut
                        prevFocused != isFocused -> if (isFocused) hoverIn else hoverOut
                        else -> return@collect
                    }
                    val fromPressRelease =
                        (prevPressed && !isPressed) || (prevHoldDown && !isHoldDown)
                    animateOverlay(spring, fromPressRelease)
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val alpha = animatedAlpha.value
            if (alpha > 0f) {
                // ⭐ 全文件唯一与库不同的一行
                // shape 存的是 Dp, 在 DrawScope 里换算成 px
                // (cornerRadius 要的是像素, 不是 Dp —— 同 ProgressIndicator.kt:78)
                val r = radius.toPx()
                drawRoundRect(
                    color = color,
                    alpha = alpha,
                    size = size,
                    cornerRadius = CornerRadius(r, r),
                )
            }
        }
    }
}

/* 与官方 MiuixIndication 逐项对齐的 folmeSpring 曲线 */
private val pressIn: SpringSpec<Float> = folmeSpring(damping = 1.0f, response = 0.2f)
private val pressOut: SpringSpec<Float> = folmeSpring(damping = 0.95f, response = 0.35f)
private val hoverIn: SpringSpec<Float> = folmeSpring(damping = 1.0f, response = 0.6f)
private val hoverOut: SpringSpec<Float> = folmeSpring(damping = 0.96f, response = 0.2f)

/**
 * 把 squircle 版 indication 注入 Miuix 页面。
 * 这是唯一入口（见类 KDoc）；用 Miuix 官方 default alpha 色 onBackground。
 */
@Composable
internal fun ProvideMiuixSquircleIndication(content: @Composable () -> Unit) {
    val color = MiuixTheme.colorScheme.onBackground
    // 16dp = Miuix 官方 CardDefaults.CornerRadius (Card.kt:190) 与
    // ListPopup 的 popupClipReveal 圆角 (ListPopup.kt:604)，两者一致。
    // 之前用 MiuixSpec.CARD_RADIUS(20dp) 是我们自己定的，比官方大，不对齐。
    val radius = 16.dp
    val indication = remember(color, radius) { MiuixSquircleIndication(color, radius) }
    CompositionLocalProvider(
        androidx.compose.foundation.LocalIndication provides indication,
        content = content,
    )
}
