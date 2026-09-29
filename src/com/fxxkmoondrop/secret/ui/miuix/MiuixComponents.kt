package com.fxxkmoondrop.secret.ui.miuix

import android.os.Build

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.height
import top.yukonga.miuix.kmp.basic.Card
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
    var expanded by remember { mutableStateOf(false) }
    val current = if (selectedIndex in items.indices) items[selectedIndex] else ""

    // ── 记录手指位置（问题 1：菜单要弹在手指处）────────────────────
    //
    // Material 轨的做法（`M3Ui.dropdownRow`）：OnTouchListener 记 ACTION_DOWN 的
    // rawX/rawY，再 `PopupWindow.showAtLocation(rootView, Gravity.NO_GRAVITY, px, py)`，
    // 菜单还会从「离手指最近的角」缩放长出。
    //
    // 这里用 Compose 的 `Popup` + `PopupPositionProvider` 复刻同一语义。
    // 不用 material3 的 `DropdownMenu` 的原因（三条都实测踩过）：
    //   1. 它的 `offset` 是 **DpOffset**（dp 单位），换算成手指像素位置要做两次
    //      density 转换，容易错；`PopupPositionProvider` 直接给 px。
    //   2. 它的容器色默认是 material3 的 surface，**深色主题下仍是浅色**（用户截图
    //      的问题 2），要改得传 `colors`，而 1.3.1 的参数名不稳定。
    //   3. 它内部用 material3 的 MenuItemColors，字色/图标色也难完全接管。
    // Popup 让我们对定位、配色、图标三件事有完全控制权。
    //
    // 无障碍点击（屏幕朗读器直接 performClick）没有 ACTION_DOWN，
    // 此时退化为「锚定到行的中心」，与 Material 版一致。
    val density = LocalDensity.current
    var fingerPx by remember { mutableStateOf(Offset.Zero) }
    var hasFinger by remember { mutableStateOf(false) }
    var rowCenterPx by remember { mutableStateOf(Offset.Zero) }
    var boxOriginInWindow by remember { mutableStateOf(Offset.Zero) }

    MiuixCard {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { c ->
                    // ⚠️ 必须用 positionInWindow 而不是 positionInRoot。
                    // PopupPositionProvider 返回的坐标是**窗口坐标系**；
                    // 而下面 pointerInput 记的 down.position 是**本节点的局部坐标**。
                    // 两者不在同一个坐标系里直接相加就是错的 ——
                    // 实测症状：菜单一律弹在屏幕顶部（y≈40），
                    // 因为局部 y 只有 0~280 的量级，与窗口 y（≈1100）差了两个数量级。
                    val win = c.positionInWindow()
                    rowCenterPx = Offset(
                        x = win.x + c.size.width / 2f,
                        y = win.y,
                    )
                    boxOriginInWindow = Offset(win.x, win.y)
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent().changes.firstOrNull { it.pressed }
                            if (down != null) {
                                // down.position 是相对本 Box 的局部坐标，
                                // 加上 Box 在窗口里的原点才是窗口坐标。
                                fingerPx = Offset(
                                    x = boxOriginInWindow.x + down.position.x,
                                    y = boxOriginInWindow.y + down.position.y,
                                )
                                hasFinger = true
                            }
                        }
                    }
                }
                .clickable { expanded = true },
        ) {
            MiuixListRow(
                title = title,
                subtitle = subtitle,
                trailing = {
                    Text(
                        text = current,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            )
        }
    }

    val anchor = if (hasFinger) fingerPx else rowCenterPx

    if (expanded) {
        // 3.2.13：参数名必须是 `popupPositionProvider`（Compose ui 1.11.2 的
        // Popup 签名就是这个名字，不是 `positionProvider`）。
        // PopupPositionProvider 接口在 1.11.2 只有一个 calculatePosition 方法，
        // 之前的「Conflicting overloads」是因为参数名写错后 Kotlin 退化成了
        // 另一个重载。
        Popup(
            popupPositionProvider = object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    // 与 Material 版 `menu place: want=x,y` 同一套逻辑：
                    // 菜单水平居中于手指，超出屏幕则向内收。
                    val x = (anchor.x - popupContentSize.width / 2f)
                        .toInt()
                        .coerceIn(
                            8,
                            (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8),
                        )
                    return IntOffset(x, anchor.y.toInt())
                }
            },
            properties = PopupProperties(
                // 菜单要能超出所在行的边界（否则会被裁掉）
                clippingEnabled = false,
            ),
        ) {
            Card(
                // 3.2.13 用户截图的问题 2：原本 material3 的菜单容器是**纯白**，
                // 深色主题下与整页配色完全不搭、可读性差。
                // 这里显式用与卡片同源的 elevatedSurface()，文字用 onSurface，
                // 深浅两套主题下都保证对比度。
                colors = CardDefaults.defaultColors(
                    color = elevatedSurface(),
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .widthIn(min = 160.dp, max = 280.dp)
                    .padding(4.dp),
            ) {
                items.forEachIndexed { idx, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(MiuixSpec.CARD_RADIUS))
                            .background(
                                if (idx == selectedIndex) MiuixTheme.colorScheme.primary
                                    .copy(alpha = 0.16f) else Color.Transparent
                            )
                            .clickable {
                                expanded = false
                                onPick(idx)
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        // 3.2.13 用户要求：优先用图标库/素材库里的图标。
                        // 用项目自带的 `R.drawable.ic_check`（Material 版下拉同款，
                        // 属 Android 内置矢量素材），**不用** material-icons ——
                        // 后者会被 R8 整个裁掉（usage.txt 295 条删除记录）。
                        if (idx == selectedIndex) {
                            Image(
                                painter = androidx.compose.ui.res.painterResource(
                                    com.fxxkmoondrop.secret.R.drawable.ic_check,
                                ),
                                contentDescription = null,
                                // Compose 1.7+ 用 colorFilter 取代 tint
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                    MiuixTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// 3.2.13: 原来这里有个 Canvas 自绘的「选中勾」MiuixCheckMark，
// 现已按用户要求「尽量用图标库/素材库里的图标」改为
// `Image(painterResource(R.drawable.ic_check))` —— 项目自带的 Material 矢量素材，
// 零新增依赖、零 R8 风险。
// （中途试过 material-icons 的 Icons.Filled.Check，但那些类会被 R8 整个裁掉，
//   usage.txt 里有 295 条删除记录，运行时报 NoClassDefFoundError。）
/**
 * 卡片色：相对页面背景**明确抬升一档**，保证两种主题下都可见。
 *
 * 深色主题往白里提亮，浅色主题往黑里压暗 —— 与 HyperOS 卡片层级一致。
 */
@Composable
internal fun elevatedSurface(): Color {
    val bg = MiuixTheme.colorScheme.background
    return if (bg.luminance() > 0.5f) {
        Color(0xFF000000).copy(alpha = 0.05f).compositeOver(bg)
    } else {
        Color(0xFFFFFFFF).copy(alpha = 0.07f).compositeOver(bg)
    }
}

/**
 * 状态栏高度（px）。
 *
 * 刻意用 `View.getRootWindowInsets()` 而不是 Compose 的
 * `WindowInsets.statusBars` —— 后者在 material3 里是 experimental，
 * 需要 @OptIn；而且不同 Compose 版本里它所在的包不同（foundation / material3 都有），
 * 跨版本容易编译失败。这里用 View API，与项目 minSdk 26 完全兼容。
 */
@Composable
private fun statusBarHeightPx(): Int {
    val view = LocalView.current
    val density = LocalDensity.current
    return view.rootWindowInsets?.let { insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop
        }
    } ?: 0
}

/** 状态栏高度（dp）。给内容区留位用，与标题栏用的是同一个值。 */
@Composable
internal fun statusBarDpOf() = with(LocalDensity.current) { statusBarHeightPx().toDp() }
