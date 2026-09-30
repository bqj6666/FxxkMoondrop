package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页（Miuix 版）—— 版式参考 SonyPods 的信息层级。
 *
 * 结构：
 * ```
 *      [耳机盒渲染图]      120dp
 *
 *      FxxkMoondrop       应用名
 *   Moondrop 蓝牙耳机助手   一句话说明
 *
 * 关于本应用              分组
 *   版本 / 作者 / 兼容性
 *
 * 开源许可                分组
 *   仓库链接 / 许可协议
 * ```
 *
 * 版本号**唯一来源是 PackageManager**（与 `AboutFragment` 一致），不硬编码。
 */
@Composable
internal fun MiuixAbout(bottomBar: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current

    MiuixPage(title = "关于", bottomBar = bottomBar) {
        // ── 顶部视觉 ────────────────────────────────────────
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
                modifier = Modifier.size(120.dp),
            )
        }
        MiuixGap(height = 8.dp)
        Text(
            text = "FxxkMoondrop",
            style = MiuixTheme.textStyles.headline2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = "Moondrop 蓝牙耳机助手 · GAIA 直连",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
        MiuixGap(height = 8.dp)

        // ── 关于本应用 ──────────────────────────────────────
        MiuixSectionLabel("关于本应用")
        MiuixPressableCard(onClick = { }) {
            MiuixListRow(
                title = "版本",
                subtitle = appVersion(ctx),
            )
            MiuixDivider()
            MiuixListRow(
                title = "功能",
                subtitle = "在 Google 快速配对弹窗与系统蓝牙设备详情页注入控制面板，" +
                        "可逐档指定设备码并自定义空间音频追踪标签",
            )
            MiuixDivider()
            MiuixListRow(
                title = "隐私",
                subtitle = "不联网上传任何数据；日志仅在手动导出时打包设备信息与运行日志",
            )
        }
        MiuixGap()

        // ── 开源许可 ────────────────────────────────────────
        MiuixSectionLabel("开源许可")
        MiuixPressableCard(onClick = { }) {
            MiuixListRow(
                title = "GitHub 仓库",
                subtitle = "github.com/bqj6666/FxxkMoondrop",
            )
            MiuixDivider()
            MiuixListRow(
                title = "许可协议",
                subtitle = "GNU GPL v3.0 · 允许修改与再分发",
            )
        }
    }
}
