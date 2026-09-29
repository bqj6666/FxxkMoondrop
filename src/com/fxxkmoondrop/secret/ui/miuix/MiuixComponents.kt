package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * HyperOS 轨的基础控件库。
 *
 * ## 规格对齐（重要）
 *
 * 这些尺寸**不是随手定的**，而是对齐现有 Material 轨 [com.fxxkmoondrop.secret.M3Ui]
 * 的规格，两套主题才能有「同一款应用」的观感，而不是两个不同的应用：
 *
 * | 规格       | Material 轨          | 本文件        |
 * |------------|----------------------|---------------|
 * | 卡片圆角   | `applyCardLook(..., 20)` | 20dp      |
 * | 行最小高度 | 56dp                 | 56dp         |
 * | 行内边距   | 16dp                 | 16dp         |
 * | 卡片间距   | 12dp（groupCard 逐项） | 12dp       |
 *
 * 改这里之前先看 M3Ui 对应实现，两边要一起改。
 *
 * ## 与 Material 轨的关系
 *
 * Material 轨**一行不改**，本文件是并行的新实现。删掉整个 `ui/miuix/` 目录
 * 即可回到纯 Material，不影响任何既有功能。
 */
object MiuixSpec {
    /** 卡片圆角，与 M3Ui.applyCardLook 的 20 对齐 */
    val CARD_RADIUS = 20.dp
    /** 行最小高度，与 M3Ui.switchRow 的 56dp 对齐 */
    val ROW_MIN_HEIGHT = 56.dp
    /** 行内边距，与 M3Ui 的 16dp 对齐 */
    val ROW_PADDING = 16.dp
    /** 卡片之间的间距，与 M3Ui.groupCard 的 12dp 对齐 */
    val CARD_GAP = 12.dp
    /** 大标题栏展开高度，取自 M3Ui.HEADER_EXPANDED_DP */
    val HEADER_EXPANDED_DP = 152
    /** 大标题栏收缩高度，取自 M3Ui.HEADER_COLLAPSED_DP */
    val HEADER_COLLAPSED_DP = 64
}

/**
 * 页面容器：大标题 + 可滚动内容。
 *
 * ## 排版必须与 Material 轨一致（3.2.13 用户明确要求）
 *
 * 用户原话：「整个 MIUIX 的界面排版要跟 material 主题时排版一样」。
 * 所以这里**不是**照抄 OppoPods，而是复刻 `M3Ui.largeHeaderPage` 的结构：
 *
 * | 规格       | Material 轨                      | Miuix 轨          |
 * |------------|----------------------------------|-------------------|
 * | 大标题     | collapsingHeader（大标题+收缩）   | `MiuixTopBar`（下）|
 * | 内容区     | ScrollView，paddingTop=expanded   | Column(weight=1f)  |
 * | 卡片圆角   | 20dp                             | 20dp（MiuixSpec）  |
 * | 行高/内边距| 56dp / 16dp                      | 56dp / 16dp        |
 * | 卡片间距   | 12dp                             | 12dp               |
 *
 * 换成 Miuix 组件后**观感**变（超椭圆、字体、按压反馈），
 * 但**结构与尺寸**与 Material 轨相同 —— 这样两套主题切换时
 * 布局不会跳动，才是「同一个 app 的两套皮肤」。
 */
@Composable
internal fun MiuixPage(
    title: String? = null,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 24.dp,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                // 顶部留出展开态标题的高度，滚动时内容从标题下方穿过
                .padding(top = MiuixSpec.HEADER_EXPANDED_DP.dp, bottom = bottomPadding),
        ) { content() }

        if (title != null) {
            // 叠在滚动内容之上（等价 Material 的 FrameLayout + gravity=TOP）
            MiuixCollapsingHeader(
                title = title,
                scrollY = scroll.value,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * 大标题栏。**结构与尺寸严格对齐 Material 轨的 `M3Ui.collapsingHeader`**。
 *
 * 数值直接取自 Material 轨的常量，不是估的：
 * ```
 * M3Ui: HEADER_EXPANDED_DP = 152
 *       HEADER_COLLAPSED_DP = 64
 * ```
 *
 * 收缩用 `graphicsLayer` 改 translationY + scale，
 * 与 Material 的 `CollapseHeader.apply(t)` 同一思路：
 * 标题随滚动上移并缩小，到底部变成一行小标题。
 *
 * ⚠️ 刻意**不用** material3 的 TopAppBar —— 那是 Material 组件，
 * 用它等于「Miuix 轨里塞了个 Material 标题栏」，两套主题会串味。
 * 也不直接用 Miuix 的 `TopAppBar`：它的高度规格（56dp 小标题栏）
 * 与 Material 的 152dp 大标题不同，直接用会导致切换时布局跳动。
 */
@Composable
internal fun MiuixCollapsingHeader(
    title: String,
    scrollY: Int,
    modifier: Modifier = Modifier,
) {
    val expanded = MiuixSpec.HEADER_EXPANDED_DP
    val collapsed = MiuixSpec.HEADER_COLLAPSED_DP
    // t: 0 = 完全展开，1 = 完全收缩
    val t = (scrollY / (expanded - collapsed).toFloat()).coerceIn(0f, 1f)
    // 标题基线：展开时在底部，收缩时居中
    val baseY = expanded * (1f - t) + collapsed * 0.5f * t
    val scale = 1f - 0.45f * t          // 28sp -> 约 15.4sp，与 Material 收缩后的小标题相当

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(expanded.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .graphicsLayer {
                    translationY = -((1f - t) * (expanded - collapsed) * density) +
                            (t * (expanded - collapsed) * 0.5f * density)
                    scaleX = scale
                    scaleY = scale
                    // 缩放会让人看着「糊」；按位移反向补偿透明度变化在 HyperOS 里不常见，
                    // 这里只做位移+缩放，观感接近 Material 的 collapsing header。
                },
            style = MiuixTheme.textStyles.title1,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 分组标题。
 *
 * 对应 M3Ui 的 `makeSubLabel` / `sectionTitle`。
 */
@Composable
internal fun MiuixSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/**
 * 卡片：M3Ui 的 `applyCardLook` + MaterialCardView 对应物。
 *
 * Miuix 的 [Card] 自带超椭圆（squircle）造型与按压反馈，
 * 这正是 HyperOS 观感与 Material 圆角矩形的关键差异。
 */
@Composable
internal fun MiuixCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = if (onClick != null) modifier.clickable { onClick() } else modifier
    Card(modifier = m.fillMaxWidth(), content = content)
}

/**
 * 列表行：图标 + 标题 + 副标题 + trailing。
 *
 * 对应 M3Ui 的 `listRow`。行高与内边距按 [MiuixSpec] 对齐。
 */
@Composable
internal fun MiuixListRow(
    title: String,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MiuixSpec.ROW_MIN_HEIGHT)
            .then(
                if (onClick != null && enabled) Modifier.clickable { onClick() }
                else Modifier
            )
            .padding(MiuixSpec.ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = alpha),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * 开关行。
 *
 * 对应 M3Ui 的 `switchRow` + `standardSwitch`。
 *
 * ⚠️ 3.2.12 在 Material 轨踩过的坑在这里同样适用：**row 置灰不会自动
 * 传给 trailing 的开关**。所以本组件的 `enabled=false` 同时作用在
 * 行的 alpha 与 [Switch] 自身（`Switch` 没有 enabled 参数，因此直接不传 onCheckedChange），
 * 保证「看起来禁用」与「点不动」一致。
 */
@Composable
internal fun MiuixSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    MiuixCard {
        MiuixListRow(
            title = title,
            subtitle = subtitle,
            enabled = enabled,
            trailing = {
                Switch(
                    checked = checked,
                    // enabled=false 时传 null：开关看得见但拨不动。
                    // 视觉置灰由 MiuixListRow 的 alpha 负责，这里只管交互。
                    onCheckedChange = if (enabled) { v -> onCheckedChange?.invoke(v) } else null,
                )
            },
        )
    }
}

/**
 * 卡片之间的间隔。
 *
 * 对应 M3Ui 的 `spacer(dp(12))`。
 */
@Composable
internal fun MiuixGap(height: androidx.compose.ui.unit.Dp = MiuixSpec.CARD_GAP) {
    Spacer(Modifier.height(height))
}

/** 一条细分隔线（放在卡片内部用）。 */
@Composable
internal fun MiuixDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)),
    )
}

/** 纵向均匀分布的容器，替代 LinearLayout 的 weight=1f 技巧。 */
@Composable
internal fun MiuixColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** 固定尺寸的透明占位，用于图标槽等场景。 */
@Composable
internal fun MiuixIconSlot(size: androidx.compose.ui.unit.Dp = 24.dp) {
    Spacer(Modifier.size(size))
}

/** 便捷：把 Android 的 color int 转成 Compose Color。 */
internal fun Int.toComposeColor(): Color = Color(this)
