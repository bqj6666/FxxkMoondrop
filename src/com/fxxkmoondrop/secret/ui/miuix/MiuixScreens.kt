package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.runtime.Composable

/**
 * 概览页（Miuix 版）—— **占位**，第 3 步实装。
 *
 * 现在只显示一个说明卡片，让骨架链路（Fragment → ComposeView → Miuix）
 * 先跑通并可在真机验证；第 3 步再接入 `GaiaBleClient` 的真实数据。
 */
@Composable
internal fun MiuixOverviewScreen() {
    MiuixPage {
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
    MiuixPage {
        MiuixSectionLabel("设置")
        MiuixCard {
            MiuixListRow(
                title = "设置页（Miuix）",
                subtitle = "第 5 步实装；Material 版此时仍完整可用",
            )
        }
        MiuixGap()
        MiuixCard {
            MiuixSwitchRow(
                title = "示例开关（可拨动）",
                subtitle = "验证 Miuix Switch 的交互是否正常",
                checked = true,
                onCheckedChange = { },
            )
        }
    }
}
