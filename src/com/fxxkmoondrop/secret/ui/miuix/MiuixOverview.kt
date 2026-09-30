package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 概览页 —— 排版参考 SonyPods（本机 `com.mercury.sonypods.noroot` v1.5.1 实测）。
 *
 * ## 层级（对齐 SonyPods）
 *
 * ```
 * MOONDROP            小标签，弱化
 * Zen Pro             大标题
 * + 状态行
 *
 *      [耳机盒渲染图]      160dp，视觉主体
 *
 * 降噪模式
 *  (关闭) (降噪) (透传)    圆形按钮
 *
 * 电量 / 音量 / 多点        本轮占位
 * ```
 *
 * ## 字体一律用官方 textStyles
 *
 * Miuix 的 `TextStyles` 提供（javap 实测）：
 *   title1..4 / headline1,2 / subtitle / body1,2 / footnote1,2
 * 不自己写 sp 值 —— 官方样式已按 HyperOS 规范定好字号与行高。
 *
 * ## 耳机图复用弹窗那张
 *
 * `ga2_icon.png` 本来在 `assets/` 给 Google FastPair 弹窗用
 * （`FastPairHookEntry.MOD_ASSET_ICON`）。assets 里的图**不能**直接
 * `painterResource`，故复制一份到 `res/drawable-nodpi/` ——
 * nodpi 避免被密度桶二次缩放，512x512 原图按 dp 直接渲染。
 *
 * ## 占位说明（用户已确认本轮不做 GAIA 接入）
 *
 * 按钮只做外观，状态一律显示 `-`，**不伪造读数、不碰协议层**。
 */
@Composable
internal fun MiuixOverview(bottomBar: (@Composable () -> Unit)? = null) {
    val scheme = MiuixTheme.colorScheme
    val ts = MiuixTheme.textStyles

    MiuixPage(title = "概览", bottomBar = bottomBar) {
        // ── 顶部标识区 ──────────────────────────────────────
        Text(
            text = "MOONDROP",
            style = ts.footnote2,
            color = scheme.onSurfaceVariantSummary,
        )
        Text(
            text = "Zen Pro",
            style = ts.headline1,
            color = scheme.onSurface,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(scheme.onSurfaceVariantSummary, CircleShape),
            )
            Text(
                text = "  GAIA 实时数据待接入",
                style = ts.body2,
                color = scheme.onSurfaceVariantActions,
            )
        }

        MiuixGap(height = 20.dp)

        // ── 耳机盒渲染图 ────────────────────────────────────
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(
                    com.fxxkmoondrop.secret.R.drawable.miuiix_earphone_case,
                ),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(160.dp),
            )
        }

        MiuixGap(height = 20.dp)

        // ── 降噪模式 ────────────────────────────────────────
        MiuixSectionLabel("降噪模式")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionButton(
                iconRes = com.fxxkmoondrop.secret.R.drawable.ic_anc_off,
                label = "关闭", selected = false, modifier = Modifier.weight(1f),
            )
            ActionButton(
                iconRes = com.fxxkmoondrop.secret.R.drawable.ic_anc_on,
                label = "降噪", selected = false, modifier = Modifier.weight(1f),
            )
            ActionButton(
                iconRes = com.fxxkmoondrop.secret.R.drawable.ic_anc_passthrough,
                label = "透传", selected = false, modifier = Modifier.weight(1f),
            )
        }

        MiuixGap(height = 16.dp)

        // ── 其他功能（本轮占位） ────────────────────────────
        MiuixSectionLabel("耳机")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionButton(
                iconRes = com.fxxkmoondrop.secret.R.drawable.ic_battery_full,
                label = "电量", selected = false, modifier = Modifier.weight(1f),
            )
            ActionButton(
                iconRes = com.fxxkmoondrop.secret.R.drawable.ic_gain_2,
                label = "音量", selected = false, modifier = Modifier.weight(1f),
            )
            ActionButton(
                iconRes = com.fxxkmoondrop.secret.R.drawable.ic_devices,
                label = "多点", selected = false, modifier = Modifier.weight(1f),
            )
        }

        MiuixGap(height = 10.dp)
        Text(
            text = "以上功能需连接耳机后使用（GAIA 实时数据接入中）",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 圆形图标按钮 —— 排版对齐 SonyPods 的功能按钮。
 *
 * 选中：图标染 primary + 底色 `secondaryContainer`。
 * 未选中：`onSurfaceVariantActions`。
 * `value` 本轮统一占位 `-`。
 */
@Composable
private fun ActionButton(
    iconRes: Int,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    value: String = "-",
) {
    val scheme = MiuixTheme.colorScheme
    val ts = MiuixTheme.textStyles
    val tint = if (selected) scheme.primary else scheme.onSurfaceVariantActions

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    color = if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
                    shape = CircleShape,
                )
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = label,
            style = ts.body2,
            color = scheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(text = value, style = ts.footnote2, color = scheme.onSurfaceVariantSummary)
    }
}
