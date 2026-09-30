package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/**
 * 什么都不画的 [Indication] —— 去掉 Miuix 组件内部的按压高亮。
 *
 * ## 背景
 *
 * 用户参照 SonyPods 提出「点整行 → 整卡变深色」，我们原本是「卡片内一圈高亮」。
 * 根因：`BasicComponent`（Component.kt:148）调 `Modifier.clickable(...)` 时
 * **不传 indication**，自动取 `LocalIndication.current`；Miuix 在
 * `MiuixTheme.kt:36` provide 的是官方 `MiuixIndication`，其 `draw()` 写死
 * `drawRect`（MiuixIndication.kt:133），画出来就是通栏**直角色块**。
 *
 * ## 为什么不能 provide null
 *
 * `androidx.compose.foundation.LocalIndication` 类型是
 * `ProvidableCompositionLocal<Indication>`，`Indication` **非空**，
 * `provides null` 编译不过（实测报
 * `Null cannot be a value of a non-null type 'Indication'`）。
 *
 * ## 为什么用 IndicationNodeFactory 而不是 IndicationInstance
 *
 * `IndicationInstance` / `rememberUpdatedInstance` 在 Compose 1.12 已废弃
 * （编译期直接报 deprecation error）。现代写法是实现
 * `IndicationNodeFactory` —— 与我们 `MiuixSquircleIndication` 同一套机制，
 * 那个类已验证可编译可运行。
 */
internal object NoOpIndication : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : Modifier.Node() {
            // 空的 Node：不注册任何 interaction 监听，绘制时什么也不做。
        }

    override fun hashCode(): Int = 0

    override fun equals(other: Any?): Boolean = other === this
}
