package com.fxxkmoondrop.secret.ui.miuix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fxxkmoondrop.secret.Lang
import com.fxxkmoondrop.secret.ThemeUtil
import com.fxxkmoondrop.secret.MainActivity
import com.fxxkmoondrop.secret.UiStyle

/**
 * 概览页（Miuix 版）—— 实装见 [MiuixOverview]。
 *
 * 这里只做转发，页面本体在 `MiuixOverview.kt`。
 */
@Composable
internal fun MiuixOverviewScreen(bottomBar: (@Composable () -> Unit)? = null) {
    // 3.2.13 实机截图发现：页面大标题已是「概览」，再放一个同名的
    // MiuixSectionLabel("概览") 就重复了。分组标签只用于**页内**分节。
    // 3.2.13: 概览页实装到 MiuixOverview.kt（参考 SonyPods 层级）
    MiuixOverview(bottomBar)
}

/**
 * 设置页（Miuix 版）—— 按 SonyPods 的信息层级重排。
 *
 * ## 分组依据
 *
 * SonyPods 设置页（本机实测截图）：一组同类项放进**一张大卡片**，
 * 组间留白，标题弱化。我们照此分为三组：
 *
 *   外观 —— 界面风格 / 动态取色 / AMOLED 纯黑
 *   功能 —— 官方降噪面板 / 后台监听 / 切后台隐藏
 *   维护 —— 弹窗图标 / 使用引导
 *
 * ## key 全部取自真实实现
 *
 * 读用 `ThemeUtil.dynColor()` / `ThemeUtil.amoled()`（只读 getter，无 setter），
 * 写用 `getSP().edit().putBoolean(...).commit()` —— 与 `SettingsFragment.makeThemeSwitch`
 * 完全同一套，不臆造 API，不新增 key。
 */
@Composable
internal fun MiuixSettingsScreen(bottomBar: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE) }

    // 开关状态提升到页面级，切换后立即重组（与 Material 版一致）
    var dynColor by remember { mutableStateOf(ThemeUtil.dynColor(ctx)) }
    var amoled by remember { mutableStateOf(ThemeUtil.amoled(ctx)) }
    var officialPanel by remember { mutableStateOf(sp.getBoolean("feat_official_panel", true)) }
    var autoService by remember { mutableStateOf(sp.getBoolean("auto_service", true)) }
    var bgHide by remember { mutableStateOf(sp.getBoolean("bg_hide", false)) }
    var featPopup by remember { mutableStateOf(sp.getBoolean("feat_popup", true)) }
    var onboarding by remember { mutableStateOf(sp.getBoolean("show_guide", true)) }

    MiuixPage(title = "设置", bottomBar = bottomBar) {

        // ── 外观 ────────────────────────────────────────────
        MiuixSectionLabel(Lang.t("外观", "Appearance"))
        MiuixDropdownRow(
            title = Lang.t("界面风格", "Interface style"),
            subtitle = Lang.t(
                "选择控件观感；切换不影响下方亮暗设置",
                "Control appearance; independent of light/dark",
            ),
            items = listOf(
                Lang.t("Material You", "Material You"),
                Lang.t("HyperOS (Miuix)", "HyperOS (Miuix)"),
            ),
            selectedIndex = UiStyle.entries.indexOf(UiStyle.current(ctx)),
        ) { si ->
            UiStyle.set(ctx, UiStyle.entries[si])
            // 立即重建当前页，不等冷启动。不能用 recreate()：
            // 那条路 savedInstanceState 非空，showTab 不会被调用。
            (ctx as? MainActivity)?.applyStyleSwitch()
        }
        MiuixGap()

        MiuixPressableCard(onClick = { /* 整卡按压反馈，点击由行内控件处理 */ }) {
            MiuixSwitchRow(
                title = Lang.t("动态取色", "Dynamic color"),
                subtitle = Lang.t("从系统壁纸取色，两套主题共用",
                        "Seed from wallpaper; shared by both themes"),
                checked = dynColor,
                onCheckedChange = { v ->
                    dynColor = v
                    sp.edit().putBoolean("dynamic_color", v).commit()
                    (ctx as? MainActivity)?.applyStyleSwitch()
                },
            )
            MiuixDivider()
            MiuixSwitchRow(
                title = Lang.t("AMOLED 纯黑", "AMOLED black"),
                subtitle = Lang.t("深色模式下用纯黑背景省电",
                        "Pure black background in dark mode"),
                checked = amoled,
                onCheckedChange = { v ->
                    amoled = v
                    sp.edit().putBoolean("amoled", v).commit()
                    (ctx as? MainActivity)?.applyStyleSwitch()
                },
            )
        }
        MiuixGap()

        // ── 功能 ────────────────────────────────────────────
        MiuixSectionLabel(Lang.t("功能", "Features"))
        MiuixPressableCard(onClick = { }) {
            MiuixSwitchRow(
                title = Lang.t("官方降噪面板", "Official ANC panel"),
                subtitle = Lang.t("在系统蓝牙设备详情页注入降噪与功能控制卡片",
                        "Inject ANC & control card into system Bluetooth page"),
                checked = officialPanel,
                onCheckedChange = { v ->
                    officialPanel = v
                    sp.edit().putBoolean("feat_official_panel", v).commit()
                },
            )
            MiuixDivider()
            MiuixSwitchRow(
                title = Lang.t("后台监听", "Background service"),
                subtitle = Lang.t("自动连接已配对的耳机",
                        "Auto-connect paired earphones"),
                checked = autoService,
                onCheckedChange = { v ->
                    autoService = v
                    sp.edit().putBoolean("auto_service", v).commit()
                },
            )
            MiuixDivider()
            MiuixSwitchRow(
                title = Lang.t("切后台时隐藏主界面", "Hide on background"),
                subtitle = Lang.t("离开主界面时自动隐藏任务",
                        "Hide task when leaving main screen"),
                checked = bgHide,
                onCheckedChange = { v ->
                    bgHide = v
                    sp.edit().putBoolean("bg_hide", v).commit()
                },
            )
        }
        MiuixGap()

        // ── 维护 ────────────────────────────────────────────
        MiuixSectionLabel(Lang.t("维护", "Maintenance"))
        MiuixPressableCard(onClick = { }) {
            MiuixSwitchRow(
                title = Lang.t("启用弹窗", "Enable popup"),
                subtitle = Lang.t("连接时显示 Google 官方弹窗",
                        "Show Google popup when connecting"),
                checked = featPopup,
                onCheckedChange = { v ->
                    featPopup = v
                    sp.edit().putBoolean("feat_popup", v).commit()
                },
            )
            MiuixDivider()
            MiuixSwitchRow(
                title = Lang.t("使用引导", "Onboarding"),
                subtitle = Lang.t("首次启动的功能说明",
                        "Feature guide on first launch"),
                checked = onboarding,
                onCheckedChange = { v ->
                    onboarding = v
                    sp.edit().putBoolean("show_guide", v).commit()
                },
            )
        }
    }
}
