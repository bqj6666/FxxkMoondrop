package com.fxxkmoondrop.secret.ui.miuix

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.fxxkmoondrop.secret.MiuixSurface
import com.fxxkmoondrop.secret.ThemeUtil

/**
 * HyperOS 轨的宿主 Fragment。
 *
 * ## 为什么需要它
 *
 * 现有三个页面都是 `Fragment`（`SettingsFragment` / `OverviewFragment` /
 * `AboutFragment`），由 `MainActivity.showTab()` 按 tab id 创建。
 * 要让两套主题并存且**分派点唯一**，最省事的方式是让 Miuix 轨也提供
 * 一个 Fragment —— 于是分派只剩一处 `when`：
 *
 * ```kotlin
 * val f: Fragment = when (id) {
 *     2 -> if (MiuixSurface.enabled(ctx)) MiuixHostFragment(Screen.SETTINGS)
 *          else SettingsFragment()
 *     ...
 * }
 * ```
 *
 * ## 生命周期
 *
 * 本 Fragment 只负责提供 `ComposeView` 容器，**不管理** Composition 的生命周期 ——
 * 交给 `ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed`，
 * 它会在宿主 View 树销毁时自动释放 Composition。
 *
 * 刻意不用 `DisposeOnDetachedFromWindow`：Fragment 的 view 可能被复用
 * （放进返回栈再回来），那时还没 detach，Composition 会残留。
 */
class MiuixHostFragment : Fragment() {

    /** Miuix 轨的页面枚举。 */
    enum class Screen { OVERVIEW, SETTINGS, ABOUT }

    /**
     * 3.2.13: 底部导航栏的 tab 事件出口。
     *
     * onAttach 时从 Activity 拿，onDetach 必须置空 —— 否则 Fragment 存活
     * 期间一直持有 Activity 引用，切页时泄漏。
     */
    private var navHost: MiuixNavHost? = null

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        navHost = context as? MiuixNavHost
    }

    override fun onDetach() {
        navHost = null
        super.onDetach()
    }

    /** 当前选中的 tab id，供导航栏显示。 */
    private val currentTabId: Int
        get() = when (screen) {
            Screen.OVERVIEW -> TAB_OVERVIEW
            Screen.SETTINGS -> TAB_SETTINGS
            Screen.ABOUT -> TAB_ABOUT
        }

    private val screen: Screen
        get() = Screen.valueOf(arguments?.getString(ARG_SCREEN) ?: Screen.OVERVIEW.name)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 3.2.13: 崩溃修复。
        //
        // 原实现是 `class MiuixHostFragment(private val screen: Screen)` ——
        // 带参构造。首次由 MainActivity 直接 new 没问题，但 `recreate()` 时
        // FragmentManager 要**自己**重建 Fragment，它只认无参构造：
        //
        //   Unable to instantiate fragment sp0: could not find Fragment constructor
        //   Caused by: java.lang.NoSuchMethodException: sp0.<init> []
        //
        // 这就是「从 Miuix 切回 Material 会闪退」的确切原因：
        // 关于页点了「切回 Material You」→ recreate() → 系统按无参构造重建
        // 上一瞬间的 MiuixHostFragment → 找不到构造器 → FATAL。
        //
        // 参数必须走 arguments（会被 FragmentManager 保存与恢复），
        // 字段只在 onCreate 后读取。
        if (arguments == null) {
            arguments = Bundle().apply { putString(ARG_SCREEN, Screen.OVERVIEW.name) }
        }
    }

    companion object {
        private const val ARG_SCREEN = "miuix_screen"

        /** 工厂：带页面的 Fragment 必须用这个创建，不能用构造参数。 */
        fun newInstance(screen: Screen): MiuixHostFragment = MiuixHostFragment().apply {
            arguments = Bundle().apply { putString(ARG_SCREEN, screen.name) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val view = ComposeView(ctx)
        view.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        view.setContent {
            // 亮暗沿用既有的 ThemeUtil（theme_mode 0/1/2），
            // 与 Material 轨共用同一份偏好，两套主题的明暗始终一致。
            MiuixSurface(
                colorSchemeMode = MiuixSurface.colorSchemeMode(ctx),
                // 种子色与 Material 轨共用同一份偏好（ThemeUtil.seedColor），
                // 由 Miuix 自己按 HCT 色轮算出整套 surface 层级色。
                keyColor = Color(ThemeUtil.seedColor(ctx)),
            ) {
                // 3.2.13 (D1): 底部导航栏用 Miuix 官方 NavigationBar，
                // 挂进 MiuixPage 的 Scaffold.bottomBar 插槽。
                // Material 轨的 BottomNavigationView 一行未动。
                val nav = @Composable {
                    MiuixNavBar(
                        selected = currentTabId,
                        onSelect = { id -> navHost?.onMiuixTab(id) },
                    )
                }
                when (screen) {
                    Screen.OVERVIEW -> MiuixOverviewScreen(nav)
                    Screen.SETTINGS -> MiuixSettingsScreen(nav)
                    Screen.ABOUT -> MiuixAboutScreen(nav)
                }
            }
        }
        return view
    }
}

/**
 * 关于页（Miuix 版）。
 *
 * 对应 Material 轨的 `AboutFragment`。数据来源与 Material 版完全一致 ——
 * 版本号仍从 PackageManager 读，不硬编码。
 *
 * ## 排版参考
 *
 * 版式对齐 OppoPods 的 `EarphonesTabPage` / `SettingsTabPage`：
 * 大标题在上、卡片分组、卡片间距 12dp。
 */
@Composable
internal fun MiuixAboutScreen(bottomBar: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    MiuixPage(title = "关于", bottomBar = bottomBar) {
        // 页面大标题已是「关于」，不再重复放同名分组标签。
        MiuixCard {
            MiuixListRow(title = "FxxkMoondrop", subtitle = "水月雨耳机系统级控制模块")
        }
        MiuixGap()
        MiuixCard {
            MiuixListRow(title = "版本", subtitle = appVersion(ctx))
        }
        MiuixGap()
        MiuixCard {
            MiuixListRow(title = "界面风格", subtitle = "当前：HyperOS（Miuix）")
        }
        MiuixGap()

        // 说明行：切换入口已移到设置页（用户要求）
        MiuixCard {
            MiuixListRow(
                title = "切换界面风格",
                subtitle = "在「设置 → 外观 → 界面风格」中切换",
            )
        }
    }
}

/**
 * 版本号。**唯一来源是 PackageManager，不硬编码** —— 与 `AboutFragment` 相同。
 */
internal fun appVersion(ctx: android.content.Context): String = try {
    @Suppress("DEPRECATION")
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
} catch (_: Throwable) {
    "?"
}
