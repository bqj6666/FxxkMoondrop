package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fxxkmoondrop.secret.MainActivity
import com.fxxkmoondrop.secret.UiStyle

/**
 * 概览页（Miuix 版）—— **占位**，第 3 步实装。
 *
 * 现在只显示说明卡片，让骨架链路（Fragment → ComposeView → Miuix）
 * 先跑通并可在真机验证；第 3 步再接入 `GaiaBleClient` 的真实数据。
 */
@Composable
internal fun MiuixOverviewScreen() {
    MiuixPage(title = "概览") {
        MiuixSectionLabel("概览")
        MiuixCard {
            MiuixListRow(
                title = "概览页（Miuix）",
                subtitle = "第 3 步接入 GAIA 实时数据",
            )
        }
    }
}

/**
 * 设置页（Miuix 版）—— **占位**，第 5 步实装。
 *
 * 设置页最复杂：1168 行、含大量开关与置灰逻辑，还有 3.2.12 踩过的
 * MaterialSwitch 置灰陷阱。最后做，届时逐项对照 Material 版迁移。
 */
@Composable
internal fun MiuixSettingsScreen() {
    val ctx = LocalContext.current
    MiuixPage(title = "设置") {
        MiuixSectionLabel("设置")
        MiuixCard {
            MiuixListRow(
                title = "设置页（Miuix）",
                subtitle = "第 5 步实装；Material 版此时仍完整可用",
            )
        }
        MiuixGap()

        // ── 外观 ────────────────────────────────────────────────
        MiuixSectionLabel("外观")
        MiuixCard {
            MiuixListRow(
                title = "界面风格",
                subtitle = "当前：HyperOS（Miuix）",
            )
        }
        MiuixGap()
        MiuixCard {
            MiuixListRow(
                title = "切回 Material You",
                subtitle = "点击立即切换（与 Material 版同一个 SP 键）",
                onClick = {
                    UiStyle.set(ctx, UiStyle.MATERIAL)
                    // 立即重建当前页，不等冷启动。
                    // 不能用 recreate()：那条路 savedInstanceState 非空，
                    // showTab 不会被调用，界面不会变（详见 MainActivity.applyStyleSwitch 注释）。
                    (ctx as? MainActivity)?.applyStyleSwitch()
                },
            )
        }
        MiuixGap()
        MiuixCard {
            MiuixListRow(
                title = "切到 HyperOS（Miuix）",
                subtitle = "当前已是 Miuix 轨，此项用于确认切换通路",
                onClick = {
                    UiStyle.set(ctx, UiStyle.MIUIX)
                    (ctx as? MainActivity)?.applyStyleSwitch()
                },
            )
        }
        MiuixGap()
        MiuixCard {
            // 3.2.13 修 bug：原来这里是 onCheckedChange = { } 的空回调，
            // 视觉上是开关但状态永远不变 —— 用户反馈「能点但没法切换状态」。
            // 现在真正写进 cfg SP，重启后仍保持。
            var demoOn by remember { mutableStateOf(demoSwitch(ctx)) }
            MiuixSwitchRow(
                title = "示例开关",
                subtitle = "写入 cfg SP（key=demo_miuix_switch），重启后仍保持",
                checked = demoOn,
                onCheckedChange = { v ->
                    demoOn = v
                    writeDemoSwitch(ctx, v)
                },
            )
        }
    }
}

private const val SP = "cfg"
private const val KEY_DEMO = "demo_miuix_switch"

private fun demoSwitch(ctx: android.content.Context): Boolean = try {
    ctx.getSharedPreferences(SP, android.content.Context.MODE_PRIVATE)
        .getBoolean(KEY_DEMO, false)
} catch (_: Throwable) { false }

private fun writeDemoSwitch(ctx: android.content.Context, on: Boolean) {
    try {
        ctx.getSharedPreferences(SP, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEMO, on).apply()
    } catch (_: Throwable) { /* 写不进去就用内存态，不崩 */ }
}
