package com.fxxkmoondrop.secret.ui.miuix

import android.os.Build

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.height
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.basic.CardDefaults
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

    // 3.2.13: 骨架换成 Miuix **官方** `Scaffold`。
    //
    // ## 为什么必须换
    //
    // `Scaffold` 的 `popupHost` 参数默认是 `MiuixPopupHost()`，它提供
    // `LocalPopupStates` —— 而 `LocalPopupStates` 全库**只由 Scaffold 提供**
    // （basic/Scaffold.kt 的 `LocalPopupStates provides popupStates`）。
    // 没有它，Miuix 的 `OverlayListPopup` / `OverlayDropdownPreference`
    // 会**静默不显示**：不崩、不报错、点了没反应。
    //
    // 之前两次尝试都卡在这里：
    //   1. 只加 Scaffold → 崩 `No NavigationEventDispatcher`
    //      （Scaffold 内部用 LocalWindowInfo，要求 Activity 实现
    //        NavigationEventDispatcherOwner）
    //   2. 只加 navigation → Scaffold 仍不可用
    // 现在两半都齐了（见 MiuixSurface 的 CompositionLocalProvider），
    // Scaffold 这条路才真正可行。
    //
    // 参考：OppoPods 的 MainTabs.kt:178 就是这么做的。
    //
    // ## 为什么不用 Compose 的 Scaffold
    //
    // Miuix 有自己的 `Scaffold`（top.yukonga.miuix.kmp.basic.Scaffold），
    // 它才提供 Miuix 的 popupHost 体系；用 Compose/Material3 的那个
    // 不提供 LocalPopupStates，弹层依旧不显示。
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.background,
        // 状态栏/导航栏 inset 由我们自己处理（见 MiuixCollapsingHeader 的
        // statusBarDpOf 与 bottomPadding），避免与 Scaffold 的默认行为叠加两次。
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { _ ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                // 顶部留出展开态标题的高度，滚动时内容从标题下方穿过
                .padding(
                    top = MiuixSpec.HEADER_EXPANDED_DP.dp + statusBarDpOf(),
                    bottom = bottomPadding,
                ),
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

    // 3.2.13 实机截图：标题被状态栏压住。Material 轨靠 `M3Ui.fitSystemBars`
    // 处理（Activity 层面 setDecorFitsSystemWindows(false) + 手动 padding），
    // Miuix 轨走 Compose，用 WindowInsets 取状态栏高度加在标题上方。
    val statusBarPx = statusBarHeightPx()

    val statusBarDp = with(LocalDensity.current) { statusBarPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // ⚠️ 高度是 expanded **加上**状态栏，不是 padding(top=)：
            // 固定高度后再 padding 会把内容区压缩，标题又 align 在 Bottom，
            // 于是标题被顶到状态栏底下 —— 实机截图就是这个症状。
            // 正确做法是把状态栏高度算进总高度，标题的下沿位置保持不变。
            .height((expanded.dp) + statusBarDp),
    ) {
        Text(
            text = title,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = 16.dp, end = 16.dp,
                    bottom = 16.dp,
                    top = statusBarDp,
                )
                .graphicsLayer {
                    translationY = -((1f - t) * (expanded - collapsed) * density) +
                            (t * (expanded - collapsed) * 0.5f * density)
                    scaleX = scale
                    scaleY = scale
                    // 缩放会让人看着「糊」；按位移反向补偿透明度变化在 HyperOS 里不常见，
                    // 这里只做位移+缩放，观感接近 Material 的 collapsing header。
                },
            style = MiuixTheme.textStyles.headline1.copy(fontSize = 28.sp),
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
    // 3.2.13: 透传 Miuix Card 官方的按压反馈参数。
    // `showIndication=false` + `pressFeedbackType=Sink` 是 HyperOS 观感的正解：
    // 官方默认的 indication 是硬编码 drawRect（直角通栏色块），
    // 而 pressFeedbackType 走 Card 自己的 `Modifier.pressable`（squircle 感知），
    // 表现为「按下轻微下沉」。
    showIndication: Boolean = false,
    pressFeedbackType: top.yukonga.miuix.kmp.utils.PressFeedbackType =
        top.yukonga.miuix.kmp.utils.PressFeedbackType.None,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = if (onClick != null) modifier.clickable { onClick() } else modifier
    Card(
        // 3.2.13 实机截图发现：不显式给 containerColor 时，Miuix 的 Card 在浅色
        // 主题下背景与页面底色几乎一致，卡片「看不见」——只有文字浮在背景上。
        // 显式用 onSurface 的低透明度当卡片底，靠 alpha 区分层级。
        // Miuix 0.9.2 的取色入口是 `CardDefaults.defaultColors(color, contentColor)`，
        // **不是** material3 的 CardDefaults.cardColors()；参数名也不是 containerColor。
        // 默认值本来就是 colorScheme.surfaceContainer（HyperOS 的卡片色），
        // 显式写出来是为了让这个选择可见 —— 否则将来被误改会很难发现。
        // 3.2.13 实机截图（浅色 + 深色各一轮）：`surfaceContainer` 与 `background`
        // 过于接近，卡片在两种主题下都「看不见」——像一堆浮空的文字。
        // Miuix 色板里有三档 surface 层级，HyperOS 的卡片用的是更高一档：
        //   surfaceContainer         基础容器
        //   surfaceContainerHigh     卡片 / 抬升面
        //   surfaceContainerHighest  最上层
        // 这里用 High，层级关系与 HyperOS 系统应用一致。
        // 3.2.13 实机截图（浅色/深色各两轮）实测：Miuix 自带的 surfaceContainer 系列
        // 在「keyColor=null 走默认配色」场景下与 background 亮度差极小，
        // 卡片完全看不见。改为按「相对背景抬升一档」自己算色，见 elevatedSurface()。
        colors = CardDefaults.defaultColors(
            color = elevatedSurface(),
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
        // 内边距由 Card 统一提供（与 Material 轨「内边距在行上」结构对齐），
        // 所以 MiuixListRow 自己不加 padding —— 否则两层叠加会变成 32dp。
        // 显式写成 MiuixSpec.ROW_PADDING 而不是依赖 CardDefaults.InsideMargin，
        // 是为了让「16dp」这个与 Material 轨对齐的数值可见、可控。
        insideMargin = androidx.compose.foundation.layout.PaddingValues(
            MiuixSpec.ROW_PADDING,
        ),
        showIndication = showIndication,
        pressFeedbackType = pressFeedbackType,
        modifier = m.fillMaxWidth(),
        content = content,
    )
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
            // 刻意不加 padding：Miuix 的 Card 自带 insideMargin（默认已给足留白），
            // 再叠一层 16dp 会变成 32dp，把卡片内容挤扁、看不出卡片轮廓。
            // 3.2.13 实机截图就是这个症状：像是一堆浮空的文字而不是卡片。
            ,
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
                // 3.2.13 实机截图：副标题若不限制行数会与 trailing 抢宽度导致折行错乱，
                // 但限制成 1 行又会把长文案截断得看不出含义。
                // 这里给 2 行 —— 与 Material 轨 listRow 的实际观感接近，
                // 常见的「切换不影响上方亮暗设置」这类文案能完整显示。
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = alpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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

/**
 * 下拉选择行 —— 样式与交互对齐 Material 轨的 `M3Ui.dropdownRow`。
 *
 * 3.2.13 用户要求：「那个切换的开关也要做成 material 一样的、
 * 展开选项切换的那种样式」。
 *
 * Material 版行为：行内右侧显示**当前值**（由官方 showValue 负责），
 * 点击整行弹出菜单，选中项带勾。语义完全一致。
 */
@Composable
internal fun MiuixDropdownRow(
    title: String,
    subtitle: String? = null,
    items: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
) {
    // 3.2.13: 与 OppoPods (ThemeSettingsPage.kt:51 / SettingsPage.kt:164) **完全同款**调用。
    //
    // 为什么最终是 OverlayDropdownPreference:
    //   WindowDropdownMenu 是「不需要 Scaffold 宿主」的权宜之计，那是 Scaffold 和
    //   NavigationEventDispatcher 两半都缺时的临时方案。现在 MiuixPage 已是 Miuix
    //   Scaffold (提供 LocalPopupStates), 换回这个标准组件。
    //
    // 为什么只传这四个参数 —— 每一处都读过 ref_miuix 0.9.4 源码确认:
    //
    //  1. 不传 icon / 不构造 DropdownEntry
    //     Dropdown.kt:206 库自己画 `MiuixIcons.Basic.Check`, 20dp, 染
    //     selectedIndicatorColor。DropdownItem.icon 一旦非空就会**顶掉**它。
    //     我们之前自绘的 ic_check 正是这个错误。
    //
    //  2. 不传 dropdownColors
    //     DropdownDefaults.dropdownColors() 默认值逐项相同:
    //       contentColor            = onSurfaceContainer
    //       containerColor          = surfaceContainer
    //       selectedContentColor    = primary
    //       selectedIndicatorColor  = primary
    //     我们手写的四项一字不差, 属于纯死代码。
    //
    //  3. summary 只放说明文字, 不放当前值
    //     showValue 默认 true, 组件自己在行尾显示选中项。
    //     OppoPods 也是这么用的: summary = language_summary (说明), 不是 「中文」。
    //     我们之前把 items[selectedIndex] 塞进 summary, 会和行尾重复显示两遍。
    //
    //  4. 不传 renderInRootScaffold
    //     默认 true = 弹层覆盖全屏, 与 HyperOS 系统观感一致。
    MiuixCard {
        OverlayDropdownPreference(
            title = title,
            items = items,
            selectedIndex = selectedIndex,
            summary = subtitle,
            onSelectedIndexChange = { onPick(it) },
        )
    }
}

/** 状态栏高度（dp）。给内容区留位用，与标题栏用的是同一个值。 */
@Composable
internal fun statusBarDpOf() = with(LocalDensity.current) { statusBarHeightPx().toDp() }

/**
 * 状态栏高度（px）。
 *
 * 用 `View.getRootWindowInsets()` 而不是 Compose 的 `WindowInsets.statusBars`：
 * 后者在 material3 里是 experimental，需要 @OptIn，且不同 Compose 版本
 * 所在包不同（foundation / material3 都有），跨版本容易编译失败。
 * View API 与本项目 minSdk 26 完全兼容。
 */
@Composable
private fun statusBarHeightPx(): Int {
    val view = LocalView.current
    return view.rootWindowInsets?.let { insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop
        }
    } ?: 0
}

/** 卡片色 —— 取 Miuix 自己的 surface 层级，不自造（见 MiuixSurface 的 keyColor 说明）。 */
@Composable
internal fun elevatedSurface(): Color = MiuixTheme.colorScheme.surfaceContainerHigh
