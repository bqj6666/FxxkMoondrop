package com.fxxkmoondrop.secret.ui.miuix

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.fxxkmoondrop.secret.MiuixSurface

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
class MiuixHostFragment(
    private val screen: Screen,
) : Fragment() {

    /** Miuix 轨的页面枚举。 */
    enum class Screen { OVERVIEW, SETTINGS, ABOUT }

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
            MiuixSurface(colorSchemeMode = MiuixSurface.colorSchemeMode(ctx)) {
                when (screen) {
                    Screen.OVERVIEW -> MiuixOverviewScreen()
                    Screen.SETTINGS -> MiuixSettingsScreen()
                    Screen.ABOUT -> MiuixAboutScreen()
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
 * 这一页内容最简单（没有开关、没有置灰逻辑），所以选它作为
 * 「Miuix 轨能不能真的渲染出来」的第一验证点。
 */
@Composable
internal fun MiuixAboutScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    MiuixPage {
        MiuixSectionLabel("关于")
        MiuixCard {
            MiuixListRow(title = "FxxkMoondrop", subtitle = "水月雨耳机系统级控制模块")
        }
        MiuixGap()
        MiuixCard {
            MiuixListRow(
                title = "版本",
                subtitle = appVersion(ctx),
            )
        }
        MiuixGap()
        MiuixCard {
            MiuixListRow(
                title = "界面风格",
                subtitle = "当前：HyperOS（Miuix）",
            )
        }
        MiuixGap()
        MiuixCard {
            MiuixListRow(
                title = "切换到 Material You",
                subtitle = "在「设置 → 外观 → 界面风格」中切换",
            )
        }
    }
}

/**
 * 版本号。**唯一来源是 PackageManager，不硬编码** —— 与 `AboutFragment` 相同。
 */
private fun appVersion(ctx: android.content.Context): String = try {
    @Suppress("DEPRECATION")
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
} catch (_: Throwable) {
    "?"
}
