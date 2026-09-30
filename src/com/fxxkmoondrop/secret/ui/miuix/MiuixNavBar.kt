package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 版底部导航栏。
 *
 * ## 用官方组件，不自绘
 *
 * 用 Miuix 官方 `NavigationBar` + `NavigationBarItem`，选中态的超椭圆药丸
 * 指示器与按压形变全部由库保证（`basic/NavigationBar.kt:74/145`），
 * 与 OppoPods / SonyPods 的观感一致。
 *
 * ## 图标为什么用项目的 Material Symbols 矢量
 *
 * Miuix 0.9.4 **没有** Home / Settings / Info 图标。实测全部 artifact：
 * `miuix-core` / `miuix-ui` / `miuix-preference` / `miuix-shader` / `miuix-squircle`
 * 里 `MiuixIcons.Basic` 编译后是**空壳**，图标以顶层函数形式存在，只有 7 个：
 *   ArrowRight / ArrowUpDown / Check / Close / Search / SearchCleanup / Sidebar
 * （icon/basic 下各 *Kt.class）
 *
 * 所以复用项目已有的 Material Symbols 矢量（`ic_home` / `ic_settings` / `ic_info`），
 * 用 Compose 官方 `ImageVector.vectorResource(resId)` 加载 —— 不画新路径，
 * 与 Material 轨同一套图标，两套主题观感统一。
 *
 * `NavigationBarItem` 的 `icon` 参数类型是 `ImageVector`（硬性要求），
 * 不能直接传 Painter，所以必须走 `vectorResource`。
 */
@Composable
internal fun MiuixNavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    NavigationBar(
        color = MiuixTheme.colorScheme.surface,
        showDivider = true,
    ) {
        NavItem(selected, TAB_OVERVIEW, com.fxxkmoondrop.secret.R.drawable.ic_home, "概览", onSelect)
        NavItem(selected, TAB_SETTINGS, com.fxxkmoondrop.secret.R.drawable.ic_settings, "设置", onSelect)
        NavItem(selected, TAB_ABOUT, com.fxxkmoondrop.secret.R.drawable.ic_info, "关于", onSelect)
    }
}

@Composable
private fun RowScope.NavItem(
    selected: Int,
    id: Int,
    iconRes: Int,
    label: String,
    onSelect: (Int) -> Unit,
) {
    NavigationBarItem(
        selected = selected == id,
        onClick = { onSelect(id) },
        icon = ImageVector.vectorResource(iconRes),
        label = label,
    )
}

/** tab id —— 与 MainActivity.showTab 的 1/2/3 一致。 */
internal const val TAB_OVERVIEW = 1
internal const val TAB_SETTINGS = 2
internal const val TAB_ABOUT = 3

/**
 * MiuixHostFragment 通过它把 tab 事件送回 MainActivity.showTab。
 *
 * 不用回调 lambda 存进 arguments（Bundle 存不了 lambda），
 * 也不用单例持有 Activity 引用（会泄漏）—— Fragment 在 onAttach 时
 * 把 Activity 强转成本接口，onDetach 置空。
 */
internal interface MiuixNavHost {
    fun onMiuixTab(id: Int)
}
