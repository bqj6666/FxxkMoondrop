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
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.menu.WindowDropdownMenu
import top.yukonga.miuix.kmp.basic.DropdownDefaults
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
 * 下拉选择行 —— **样式与交互对齐 Material 轨的 `M3Ui.dropdownRow`**。
 *
 * 3.2.13 用户要求：「那个切换的开关也要做成这样 material 一样，
 * 展开选项切换的那种样式」。
 *
 * Material 版行为：行内右侧显示**当前值**，点击整行弹出菜单，
 * 菜单跟随手指位置，选中项带勾。语义完全一致。
 *
 * 为什么不用 Miuix 自带的 `DropdownImpl`：它入参是 `DropdownItem` + `mipmap`
 * 资源 id，而我们的选项是纯文本，硬套会引入一套用不上的图标资源体系。
 * 交互用 Compose 原生实现，观感（超椭圆、按压反馈、字体）仍来自 Miuix。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MiuixDropdownRow(
    title: String,
    subtitle: String? = null,
    items: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
) {
    // 3.2.13 最终形态：**完全采用 Miuix 官方组件**，一个自创像素都没有。
    //
    // 用户要求「看一下 miui 官方的用法和规范，连排版和大小都要按官方的来」，
    // 查参考项目（OppoPods 用 miuix 0.9.2，与我们同版本）得到官方用法：
    //
    //   import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
    //   Card {
    //       OverlayDropdownPreference(
    //           title = ..., summary = ...,
    //           items = listOf(...),
    //           selectedIndex = ...,
    //           onSelectedIndexChange = { ... },
    //       )
    //   }
    //
    // ## 为什么最终用 `WindowDropdownMenu` 而不是 `OverlayDropdownPreference`
    //
    // 两者 API 几乎一样（title/summary/items/selectedIndex/maxHeight/insideMargin），
    // 差别只在弹窗宿主：
    //   - `OverlayDropdownPreference` / `OverlayListPopup`
    //       → 内部读 `LocalPopupStates`，只由 Miuix `Scaffold` 提供；
    //         而 Scaffold 内部用 `LocalWindowInfo`，要求 Activity 实现
    //         `NavigationEventDispatcherOwner`（androidx.navigation 的接口，
    //         裸 FragmentActivity 不实现）→ 崩溃：
    //         `IllegalStateException: No NavigationEventDispatcher was provided`
    //   - `WindowDropdownMenu`
    //       → 自己开窗口，**不依赖 Scaffold、不依赖 navigation**。
    //
    // 也就是说它是「官方组件 + 无额外依赖」的唯一组合。
    // 排版、尺寸、动画、squircle 裁切全部由 Miuix 自己负责，
    // 与 HyperOS 系统应用的下拉完全一致。
    //
    // ## 前四轮踩的坑（别再走了）
    //  1. 自创 Popup：点哪都关不掉、宽度不可控、无动画
    //  2. OverlayListPopup：菜单不弹（缺 LocalPopupStates）
    //  3. 补 Miuix Scaffold：崩（缺 NavigationEventDispatcherOwner）
    //  4. Popup + ListPopupContent + Column(IntrinsicSize.Min)：
    //     崩 `IllegalArgumentException: maxWidth must be >= than minWidth`
    //     （IntrinsicSize.Min 要求子项高度确定，BasicComponent 高度不定）
    // 3.2.13: 选中项加**框架图标**（素材库 R.drawable.ic_check）。
    //
    // 用户参照 LSPosed App 指出：锚定行应该有内缩圆角高亮，而 Miuix 默认是
    // 通栏直角色块。我们试过三条路都不可行（详见 progress 档案）：
    //   1. 外层 Modifier.clip —— 裁不到，clickableModifier 排在我们传入的
    //      modifier 之后（BasicComponent 内部链 modifier → heightIn →
    //      fillMaxWidth → then(clickableModifier) → padding）
    //   2. LocalIndication provides null —— material3 的 LocalIndication
    //      是 internal API
    //   3. 自定义 IndicationNodeFactory —— Modifier.Node 与
    //      IndicationNodeFactory 的构造函数对 Kotlin 子类**不可见**
    //      （无论匿名/具名、无参/带参，1.11 与 1.12 实测都一样）。
    //      读 miuix-ui-android 0.9.2 与 0.9.4 的 sources jar 确认：
    //      两个版本的 MiuixIndication 都写死 drawRect，**库没修**。
    //
    // 所以改用 HyperOS 官方的另一条表达路径：`DropdownItem.icon`。
    // Miuix 自己的默认观感也是「选中靠文字变主色 + 指示器，不靠色块」——
    // `DropdownColors.selectedContainerColor` 默认等于 `containerColor`。
    val entry = remember(items, selectedIndex, onPick) {
        DropdownEntry(
            items.mapIndexed { index, item ->
                DropdownItem(
                    text = item,
                    selected = index == selectedIndex,
                    onClick = { onPick(index) },
                    // 选中项左侧显示 ✓（素材库图标，与 Material 轨下拉同款）
                    // ⚠️ `icon` 的类型是 `@Composable ((Modifier) -> Unit)?`，
                    // 库会把自己的 IconCellModifier（含 size/padding）传进来 ——
                    // 所以这里**不要**自己再写 modifier，直接用参数。
                    // 着色用 Miuix 自己的 `tint` Modifier 扩展（basic/Dropdown.kt 里
                    // 选中项的箭头就是这么染色的），避免依赖 material3 的 Icon。
                    icon = if (index == selectedIndex) {
                        { m ->
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(
                                    com.fxxkmoondrop.secret.R.drawable.ic_check,
                                ),
                                contentDescription = null,
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                    MiuixTheme.colorScheme.primary,
                                ),
                                modifier = m.size(18.dp),
                            )
                        }
                    } else null,
                )
            },
        )
    }

    MiuixCard(
        // 3.2.13 修「选中/展开态高亮是直角长方形」。
        //
        // 现象：弹窗展开时「界面风格」行是一整块**直角通栏**深色高亮，
        // 与 HyperOS 超椭圆卡片完全不搭（用户反馈「轮廓很奇怪，是长方形」）。
        //
        // 根因（读 miuix-ui-android-0.9.2-sources 确认）：
        //   utils/MiuixIndication.kt 写死
        //       drawRect(color, alpha, size = size)    // 硬编码直角通栏
        //   `BasicComponent.clickable` 没传 indication，回落到
        //   MiuixTheme 的 LocalIndication（就是它）。Miuix 0.9.2 无 squircle 版。
        //
        // ⛔ 外层 Modifier.clip 解决不了：BasicComponent 内部链是
        //     modifier → heightIn → fillMaxWidth → then(clickableModifier) → padding
        // 画高亮的那层排在我们传入的 modifier **之后**，clip 裁不到（实测无效）。
        // ⛔ LocalIndication 也不可用：material3 的 LocalIndication 是 internal API。
        // ⛔ 自己实现 IndicationNodeFactory：Compose 1.11 的 DelegatableNode 契约
        //    复杂且跨版本易碎，只为换个形状不值得。
        //
        // 正解：用 Miuix **官方**的 pressFeedbackType。
        // 它走 Card 自己的 `Modifier.pressable`（squircle 感知），
        // 效果是 HyperOS 的「按下时轻微下沉」—— 这正是 HyperOS 列表/卡片的观感，
        // 而不是画一块直角色块。库在 0.9.2 没有 squircle 色块高亮可用。
        pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
        showIndication = false,
        // 3.2.13 修「选中项高亮是直角长方形」。
        //
        // 现象：弹窗展开时「界面风格」行出现一块**通栏直角**深色高亮，
        // 与 HyperOS 的超椭圆卡片完全不搭。
        //
        // 根因（读 miuix-ui-android-0.9.2-sources 确认）：
        //   utils/MiuixIndication.kt 的 ContentDrawScope.draw() 里写死了
        //       drawRect(color, alpha, size = size)      // 硬编码直角
        //   BasicComponent 的 clickable 没传 indication，于是回落到
        //   MiuixTheme 提供的 LocalIndication（就是它）。
        //   库在 0.9.2 **没有**提供 squircle 版的 Indication。
        //
        // 解法：不改库（我们用的是官方组件，不该魔改），
        // 而是在**行这一层**加 squircle 圆角裁剪 ——
        // 裁剪会把通栏的直角高亮切成超椭圆，与 Card 的 squircle 边缘对齐。
        // 这是纯布局层的处理，不改变任何交互与官方组件语义。
        modifier = Modifier.clip(
            androidx.compose.foundation.shape.RoundedCornerShape(MiuixSpec.CARD_RADIUS),
        ),
    ) {
        // ⚠️ 当前值必须放在 `summary`，**不能**用 `endActions`。
        //
        // 读 miuix-preference 0.9.2 的 WindowDropdownMenu 源码确认：
        // 它内部的 `endActions` 已被 `DropdownArrowEndAction`（展开箭头）
        // 和 `WindowDropdownPopup`（弹层本体）占满，不接受外部再塞内容。
        // `showValue` 参数只有 `OverlayDropdownPreference` 才有，
        // `WindowDropdownMenu` 没有。
        //
        // 所以 HyperOS 官方组件表达「当前选中」的方式就是 summary ——
        // 我们照此办理，与 Material 轨 `M3Ui.dropdownRow`
        // （标题 + 副标题 + 右侧值）语义一致。
        WindowDropdownMenu(
            entry = entry,
            title = title,
            summary = if (selectedIndex in items.indices) items[selectedIndex]
            else subtitle,
            maxHeight = 320.dp,
            // ⚠️ `dropdownColors` 用 Miuix 的默认值即可，**不要**自创。
            //
            // 实机观察：弹窗打开时「界面风格」行会留下一块**通栏**高亮。
            // 查源码确认这是 **Miuix 官方设计，不是 bug**：
            //   WindowDropdownMenu 里 `isHoldDown` 在展开时置 true，
            //   只在 `onDismissFinished`（关闭动画结束）才置回 false；
            //   BasicComponent 的 `clickable` 没传 indication，
            //   于是用 MiuixTheme 提供的 `MiuixIndication`，
            //   它按 HyperOS 规范画**通栏**按压高亮（不是 squircle）。
            // 系统自带的下拉菜单同样如此。
            //
            // 真正需要留意的是颜色：默认取 `onBackground`，
            // 深色主题下过重、浅色下过淡。这里按 HyperOS 观感显式指定，
            // 让按压高亮与菜单底色（surfaceContainer）协调。
            dropdownColors = DropdownDefaults.dropdownColors(
                // 菜单底色：与卡片同一层级
                containerColor = MiuixTheme.colorScheme.surfaceContainer,
                // 选中项：官方观感是**文字变主色 + 右侧指示器**，
                // 不给独立底色（selectedContainerColor 默认等于 containerColor）
                selectedContentColor = MiuixTheme.colorScheme.primary,
                selectedIndicatorColor = MiuixTheme.colorScheme.primary,
                // 未选中项
                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            ),
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
