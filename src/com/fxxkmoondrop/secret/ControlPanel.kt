package com.fxxkmoondrop.secret

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * alpha2.39: 共享「降噪面板 + 功能控制面板」UI 组件。
 *
 * 设计原则：
 *  - 本组件【只负责构建 UI + 调用回调】，不持有任何 BLE/Gaia 单例引用。
 *  - 控制行为通过 [Callbacks] 注入：主界面直连 AncBridge/DeviceControlBridge，
 *    设备详情页（Settings 进程）走跨进程广播通道。
 *  - 视觉完全复用主界面：ThemeUtil.Palette（系统 Material You 动态色）、
 *    buildMainModeIcon、DcIcons、dp/spacer，与主界面天然一致。
 *  - 功能可见性按 AncProfileLib.resolveDc(设备名) 档案决定，未命中回退 GAIA 能力探测。
 */
object ControlPanel {
    private const val TAG = "FxxkMoondrop"

    /** 面板控制回调：由调用方决定如何真正作用于设备。 */
    interface Callbacks {
        fun setAncMode(mode: Int)
        fun setSpatialEnabled(enabled: Boolean)
        fun setTrackingMode(mode: Int)
        fun setGain(level: Int)
        fun setLed(state: Int)
    }

    /** 运行状态快照：由调用方在 refresh 时提供（值来自桥 / 设备）。 */
    data class State(
        val connected: Boolean,
        val ancMode: Int,
        val spatialOn: Boolean,
        val spatialUiMode: Int,
        val gainLevel: Int,
        val ledOn: Boolean,
        val hasSpatial: Boolean,
        val hasGain: Boolean,
        val hasLed: Boolean
    )

    /** 构建降噪面板卡片（标题 + 4 档模式圆钮）。返回根 LinearLayout。 */
    fun buildAncCard(
        ctx: Context,
        pal: ThemeUtil.Palette,
        onMode: (Int) -> Unit,
        cardBg: Int? = null
    ): LinearLayout {
        val dp = { px: Int -> (px * ctx.resources.displayMetrics.density).toInt() }
        val containerCol = pal.container
        val onContainerC = pal.onContainer
        val cardC = cardBg ?: pal.card

        val title = TextView(ctx)
        title.text = Lang.t(ctx, "降噪控制", "Noise Control")
        title.textSize = 14f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setTextColor(pal.outline)
        title.setPadding(dp(16), 0, dp(16), 0)

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(dp(16), dp(16), dp(16), dp(16))
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.setColor(cardC)
        bg.setCornerRadius(dp(28).toFloat())
        row.background = bg

        for (m in 0..3) {
            val fm = m
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val holder = FrameLayout(ctx)
            val b = View(ctx)
            b.tag = "fxxk_main_bg"
            val g0 = GradientDrawable()
            g0.shape = GradientDrawable.OVAL
            g0.setColor(containerCol)
            b.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g0, null)
            holder.addView(b, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val icon = ImageView(ctx)
            icon.tag = "fxxk_main_icon"
            icon.setImageDrawable(buildMainModeIcon(ctx, fm, dp(32), onContainerC))
            val il = FrameLayout.LayoutParams(dp(32), dp(32))
            il.gravity = Gravity.CENTER
            holder.addView(icon, il)
            holder.setOnClickListener { onMode(fm) }
            val sz = dp(72)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            col.addView(spacer(ctx, dp(2)))
            val lbl = TextView(ctx)
            lbl.text = AncProfileLib.modeNamesFull(ctx)[fm]
            lbl.textSize = 12f
            lbl.gravity = Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(onContainerC)
            col.addView(lbl, LinearLayout.LayoutParams(-1, -2))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        // 抗风列可见性沿用用户设置（与主界面一致）
        val showWind = ctx.getSharedPreferences("cfg", 0).getBoolean("show_wind", true)
        row.getChildAt(3)?.visibility = if (showWind) View.VISIBLE else View.GONE

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        card.addView(spacer(ctx, dp(8)))
        card.addView(row)
        // 保留标题供外部刷新引用
        card.tag = "fxxk_anc_card"
        row.tag = "fxxk_anc_row"
        return card
    }

    /** 构建功能控制面板卡片（空间音频 / 追踪 / 增益 / 指示灯）。返回根 LinearLayout。 */
    fun buildDcCard(
        ctx: Context,
        pal: ThemeUtil.Palette,
        callbacks: Callbacks,
        cardBg: Int? = null
    ): LinearLayout {
        val dp = { px: Int -> (px * ctx.resources.displayMetrics.density).toInt() }
        val containerCol = pal.container
        val onContainerC = pal.onContainer
        val cardC = cardBg ?: pal.card

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(10), dp(12), dp(10), dp(12))
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.setColor(cardC)
        bg.setCornerRadius(dp(28).toFloat())
        card.background = bg

        // 空间音频行
        val spatialRow = LinearLayout(ctx)
        spatialRow.orientation = LinearLayout.HORIZONTAL
        spatialRow.gravity = Gravity.CENTER_VERTICAL
        spatialRow.tag = "dc_spatial_row"
        val sLabel = TextView(ctx)
        sLabel.text = Lang.t(ctx, "空间音频", "Spatial Audio")
        sLabel.textSize = 12f
        sLabel.setTextColor(onContainerC)
        spatialRow.addView(sLabel, LinearLayout.LayoutParams(-2, -2))
        spatialRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        // alpha2.53: 直接复用设置 App 原版开关控件。
        //
        // 踩过的两个坑：
        //  1) 直接 new MaterialSwitch(ctx) 会崩 —— Settings 的活动主题不是 Theme.AppCompat，
        //     构造时抛 IllegalArgumentException，整个面板注入失败。
        //  2) 退回 android.widget.Switch 不再崩，但那是 AOSP 旧样式，与设置页其余开关不一致。
        // Settings 自己的做法是 inflate layout/preference_widget_switch_compat，该布局里的
        // MaterialSwitch 带 android:theme="@style/Theme.Material3.DynamicColors.DayNight" 覆盖，
        // 所以能正常构造。此处照搬同一路径 —— 拿到的就是设置页原版开关。
        val spatialSwitch: android.widget.CompoundButton = buildSettingsSwitch(ctx, "dc_spatial_switch")
                ?: android.widget.Switch(ctx).apply {
                    tag = "dc_spatial_switch"
                    trackTintList = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(pal.primary, pal.surfaceContainerHighest))
                    thumbTintList = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(pal.onPrimary, pal.outline))
                }
        spatialSwitch.setOnCheckedChangeListener { _, isChecked -> callbacks.setSpatialEnabled(isChecked) }
        spatialRow.addView(spatialSwitch, LinearLayout.LayoutParams(-2, -2))
        card.addView(spatialRow, LinearLayout.LayoutParams(-1, -2))

        // 追踪子模式行
        card.addView(spacer(ctx, dp(6)))
        val trackingRow = LinearLayout(ctx)
        trackingRow.orientation = LinearLayout.HORIZONTAL
        trackingRow.gravity = Gravity.CENTER_VERTICAL
        trackingRow.tag = "dc_tracking_row"
        val tLabel = TextView(ctx)
        tLabel.text = Lang.t(ctx, "追踪模式", "Tracking Mode")
        tLabel.textSize = 11f
        tLabel.setTextColor(onContainerC)
        trackingRow.addView(tLabel, LinearLayout.LayoutParams(-2, -2))
        trackingRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        for (tm in 0..2) {
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val holder = FrameLayout(ctx)
            val b = View(ctx)
            b.tag = "dc_bg"
            val g0 = GradientDrawable()
            g0.shape = GradientDrawable.OVAL
            g0.setColor(containerCol)
            b.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g0, null)
            holder.addView(b, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val icon = ImageView(ctx)
            icon.tag = "dc_icon"
            icon.setImageDrawable(DcIcons.build(ctx, 0, tm, dp(22), onContainerC))
            val il = FrameLayout.LayoutParams(dp(22), dp(22))
            il.gravity = Gravity.CENTER
            holder.addView(icon, il)
            holder.tag = "dc_btn_spatial_" + tm
            holder.setOnClickListener { callbacks.setTrackingMode(tm) }
            val sz = dp(48)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            val lbl = TextView(ctx)
            lbl.text = AncProfileLib.trackingLabels(ctx)[tm]
            lbl.textSize = 10f
            lbl.gravity = Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(onContainerC)
            col.addView(lbl, LinearLayout.LayoutParams(-1, -2))
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(4), 0, dp(4), 0)
            trackingRow.addView(col, lp)
        }
        card.addView(trackingRow, LinearLayout.LayoutParams(-1, -2))

        // 增益行
        card.addView(spacer(ctx, dp(8)))
        val gainRow = LinearLayout(ctx)
        gainRow.orientation = LinearLayout.HORIZONTAL
        gainRow.gravity = Gravity.CENTER_VERTICAL
        gainRow.tag = "dc_gain_row"
        val gLabel = TextView(ctx)
        gLabel.text = Lang.t(ctx, "增益", "Gain")
        gLabel.textSize = 12f
        gLabel.setTextColor(onContainerC)
        gainRow.addView(gLabel, LinearLayout.LayoutParams(-2, -2))
        gainRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        for (gm in 0..2) {
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val holder = FrameLayout(ctx)
            val b = View(ctx)
            b.tag = "dc_bg"
            val g0 = GradientDrawable()
            g0.shape = GradientDrawable.OVAL
            g0.setColor(containerCol)
            b.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g0, null)
            holder.addView(b, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val icon = ImageView(ctx)
            icon.tag = "dc_icon"
            icon.setImageDrawable(DcIcons.build(ctx, 1, gm, dp(22), onContainerC))
            val il = FrameLayout.LayoutParams(dp(22), dp(22))
            il.gravity = Gravity.CENTER
            holder.addView(icon, il)
            holder.tag = "dc_btn_gain_" + gm
            holder.setOnClickListener { callbacks.setGain(gm) }
            val sz = dp(48)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            val lbl = TextView(ctx)
            lbl.text = AncProfileLib.gainLabels(ctx)[gm]
            lbl.textSize = 10f
            lbl.gravity = Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(onContainerC)
            col.addView(lbl, LinearLayout.LayoutParams(-1, -2))
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(4), 0, dp(4), 0)
            gainRow.addView(col, lp)
        }
        card.addView(gainRow, LinearLayout.LayoutParams(-1, -2))

        // 指示灯行
        card.addView(spacer(ctx, dp(8)))
        val ledRow = LinearLayout(ctx)
        ledRow.orientation = LinearLayout.HORIZONTAL
        ledRow.gravity = Gravity.CENTER_VERTICAL
        ledRow.tag = "dc_led_row"
        val lLabel = TextView(ctx)
        lLabel.text = Lang.t(ctx, "指示灯", "Indicator")
        lLabel.textSize = 12f
        lLabel.setTextColor(onContainerC)
        ledRow.addView(lLabel, LinearLayout.LayoutParams(-2, -2))
        ledRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val ledNames = arrayOf(Lang.t(ctx, "开", "On"), Lang.t(ctx, "关", "Off"))
        for (lm in 0..1) {
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val holder = FrameLayout(ctx)
            val b = View(ctx)
            b.tag = "dc_bg"
            val g0 = GradientDrawable()
            g0.shape = GradientDrawable.OVAL
            g0.setColor(containerCol)
            b.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g0, null)
            holder.addView(b, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val icon = ImageView(ctx)
            icon.tag = "dc_icon"
            icon.setImageDrawable(DcIcons.build(ctx, 2, lm, dp(22), onContainerC))
            val il = FrameLayout.LayoutParams(dp(22), dp(22))
            il.gravity = Gravity.CENTER
            holder.addView(icon, il)
            holder.tag = "dc_btn_led_" + lm
            holder.setOnClickListener { callbacks.setLed(lm) }
            val sz = dp(48)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            val lbl = TextView(ctx)
            lbl.text = ledNames[lm]
            lbl.textSize = 10f
            lbl.gravity = Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(onContainerC)
            col.addView(lbl, LinearLayout.LayoutParams(-1, -2))
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(4), 0, dp(4), 0)
            ledRow.addView(col, lp)
        }
        card.addView(ledRow, LinearLayout.LayoutParams(-1, -2))

        card.tag = "fxxk_dc_card"
        return card
    }

    /** 刷新降噪卡片（标题 + 4 档模式）高亮。mode 为当前 UI 模式（0..3）。 */
    fun refreshAncCard(card: LinearLayout, mode: Int) {
        val row = card.findViewWithTag<LinearLayout>("fxxk_anc_row") ?: return
        val ctx = card.context
        val modeOn = mode in 0..3
        for (i in 0 until row.childCount) {
            val col = row.getChildAt(i) as? LinearLayout ?: continue
            val holder = col.getChildAt(0) as? FrameLayout ?: continue
            val bg = holder.findViewWithTag<View>("fxxk_main_bg")
            val icon = holder.findViewWithTag<ImageView>("fxxk_main_icon")
            val active = modeOn && i == mode
            val primaryC = ThemeUtil.dyn(ctx, "system_accent1_400",
                if (ThemeUtil.isDark(ctx)) 0xFFD0BCFF.toInt() else 0xFF6750A4.toInt())
            // 与 buildAncCard 初始底色一致：深色用 system_accent1_800（深紫），避免发白
            val containerC = ThemeUtil.dyn(ctx, "system_accent1_800",
                if (ThemeUtil.isDark(ctx)) 0xFF4F378B.toInt() else 0xFFE8DEF8.toInt())
            if (bg != null) {
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(if (active) primaryC else containerC)
                if (active) g.setStroke((2 * ctx.resources.displayMetrics.density).toInt(), 0xFFFFFFFF.toInt())
                bg.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g, null)
            }
            icon?.setImageDrawable(buildMainModeIcon(ctx, i, dp(ctx, 26), if (active) 0xFFFFFFFF.toInt() else onContainerOf(ctx)))
        }
    }

    /** 刷新功能卡片高亮。state 由调用方提供；可见性按 resolveDc + 能力探测决定。 */
    fun refreshDcCard(card: LinearLayout, state: State, profile: AncProfileLib.DcProfile) {
        val ctx = card.context
        val dp = { px: Int -> (px * ctx.resources.displayMetrics.density).toInt() }
        val spatialRow = card.findViewWithTag<LinearLayout>("dc_spatial_row")
        val trackingRow = card.findViewWithTag<LinearLayout>("dc_tracking_row")
        val gainRow = card.findViewWithTag<LinearLayout>("dc_gain_row")
        val ledRow = card.findViewWithTag<LinearLayout>("dc_led_row")

        // 设备详情页：未连接也显示功能卡（按型号档案），未连接时按钮置灰；主界面仍为“未连接隐藏”。
        spatialRow?.visibility = if (profile.hasSpatial) View.VISIBLE else View.GONE
        trackingRow?.visibility = if (profile.hasSpatial) View.VISIBLE else View.GONE
        gainRow?.visibility = if (profile.hasGain) View.VISIBLE else View.GONE
        ledRow?.visibility = if (profile.hasLed) View.VISIBLE else View.GONE

        val anyVisible = profile.hasSpatial || profile.hasGain || profile.hasLed
        card.visibility = if (anyVisible) View.VISIBLE else View.GONE

        val spSwitch = card.findViewWithTag<android.widget.CompoundButton>("dc_spatial_switch")
        spSwitch?.let { sw ->
            // alpha2.39.2: 未连接时完全禁用（交互+视觉），并用系统原生禁用灰样式
            sw.isEnabled = state.connected
            sw.isClickable = state.connected
            sw.isFocusable = state.connected
            sw.alpha = if (state.connected) 1f else 0.4f
            if (sw.isChecked != state.spatialOn) sw.isChecked = state.spatialOn
        }

        // alpha2.39.1: 禁用态用暗中性灰（dark 下 onVariant 偏浅会发白），不发白
        val grey = ThemeUtil.dyn(ctx, "system_neutral1_80",
            if (ThemeUtil.isDark(ctx)) 0xFF2A2A2E.toInt() else 0xFFE0E0E0.toInt())
        val primary = ThemeUtil.dyn(ctx, "system_accent1_400",
            if (ThemeUtil.isDark(ctx)) 0xFFD0BCFF.toInt() else 0xFF6750A4.toInt())
        // 与 buildDcCard 初始底色一致：深色用 system_accent1_800（深紫），避免发白
        val containerCal = ThemeUtil.dyn(ctx, "system_accent1_800",
            if (ThemeUtil.isDark(ctx)) 0xFF4F378B.toInt() else 0xFFE8DEF8.toInt())

        val spatialOn = state.spatialOn
        val sMode = state.spatialUiMode
        val gLevel = state.gainLevel
        val ledOn = state.ledOn
        for (i in 0 until card.childCount) {
            val row = card.getChildAt(i) as? LinearLayout ?: continue
            val rowTag = row.tag as? String ?: ""
            if (!rowTag.startsWith("dc_")) continue
            for (j in 0 until row.childCount) {
                val col = row.getChildAt(j) as? LinearLayout ?: continue
                for (k in 0 until col.childCount) {
                    val holder = col.getChildAt(k) as? FrameLayout ?: continue
                    val tag = holder.tag as? String ?: continue
                    if (!tag.startsWith("dc_btn_")) continue
                    val bgV = holder.findViewWithTag<View>("dc_bg")
                    val iv = holder.findViewWithTag<View>("dc_icon") as? ImageView
                    val parts = tag.substring(7).split("_")
                    if (parts.size != 2) continue
                    val feature = parts[0]
                    val idx = parts[1].toInt()
                    val active = when (feature) {
                        "spatial" -> spatialOn && idx == sMode
                        "gain" -> idx == gLevel
                        "led" -> idx == if (ledOn) 0 else 1
                        else -> false
                    }
                    val iconColor = if (active) 0xFFFFFFFF.toInt() else onContainerOf(ctx)
                    val featType = when (feature) { "spatial" -> 0; "gain" -> 1; "led" -> 2; else -> 0 }
                    val spatialDisabled = feature == "spatial" && !spatialOn
                    if (!state.connected || spatialDisabled) {
                        holder.isEnabled = false
                        holder.alpha = 0.4f
                        if (bgV != null) {
                            val g = GradientDrawable()
                            g.shape = GradientDrawable.OVAL
                            g.setColor(grey)
                            bgV.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g, null)
                        }
                        iv?.setImageDrawable(DcIcons.build(ctx, featType, idx, dp(22), grey))
                    } else {
                        holder.isEnabled = true
                        holder.alpha = 1f
                        if (bgV != null) {
                            val g = GradientDrawable()
                            g.shape = GradientDrawable.OVAL
                            g.setColor(if (active) primary else containerCal)
                            if (active) g.setStroke(dp(2), 0xFFFFFFFF.toInt())
                            bgV.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g, null)
                        }
                        iv?.setImageDrawable(DcIcons.build(ctx, featType, idx, dp(22), iconColor))
                    }
                }
            }
        }
    }

    /**
     * alpha2.53: 取设置 App 原版开关控件。
     *
     * 优先 inflate 设置自己的 preference_widget_switch_compat（内含带 Material3 主题覆盖的
     * MaterialSwitch），旧版 preference_widget_switch 作为兜底；失败返回 null 由调用方降级。
     * 该布局原本 clickable=false（点击由 preference 行负责），这里恢复为可点。
     */
    private fun buildSettingsSwitch(ctx: Context, tag: String): android.widget.CompoundButton? {
        // Android 16 的开关偏好走 expressive 布局，旧版本走 compat / 平台布局；按新到旧试。
        for (name in arrayOf("settingslib_expressive_preference_switch",
                "preference_widget_switch_compat", "preference_widget_switch")) {
            val id = try {
                ctx.resources.getIdentifier(name, "layout", "com.android.settings")
            } catch (_: Throwable) { 0 }
            android.util.Log.d(TAG, "settings switch candidate=$name id=" + Integer.toHexString(id))
            if (id == 0) continue
            try {
                val v = android.view.LayoutInflater.from(ctx).inflate(id, null)
                val sw = (v as? android.widget.CompoundButton) ?: (v as? android.view.ViewGroup)?.let { findCompound(it) }
                android.util.Log.d(TAG, "settings switch inflated=$name root=" + v.javaClass.name +
                        " sw=" + (sw?.javaClass?.name ?: "null"))
                if (sw != null) {
                    sw.tag = tag
                    sw.isClickable = true
                    sw.isFocusable = true
                    return sw
                }
            } catch (t: Throwable) {
                android.util.Log.d(TAG, "settings switch inflate fail $name: " + t)
            }
        }
        android.util.Log.d(TAG, "settings switch: all candidates failed, fallback to android.widget.Switch")
        return null
    }

    private fun findCompound(v: android.view.ViewGroup): android.widget.CompoundButton? {
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c is android.widget.CompoundButton) return c
            if (c is android.view.ViewGroup) findCompound(c)?.let { return it }
        }
        return null
    }

    /** 图标构建：与主界面 buildMainModeIcon 一致（系统 Canvas 绘制）。 */
    private fun buildMainModeIcon(ctx: Context, mode: Int, px: Int, color: Int): android.graphics.drawable.Drawable? {
        // alpha2.52: 统一走 M3Ui 的 Material Symbols 矢量图标（设置 hook 面板 / GMS 弹窗同源）
        return M3Ui.ancModeDrawable(ctx, mode, px, color)
    }

    private fun onContainerOf(ctx: Context): Int = ThemeUtil.dyn(ctx, "system_accent1_50",
        if (ThemeUtil.isDark(ctx)) 0xFF4F378B.toInt() else 0xFFE8DEF8.toInt())

    private fun dp(ctx: Context, px: Int): Int = (px * ctx.resources.displayMetrics.density).toInt()

    private fun spacer(ctx: Context, h: Int): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(1, h)
        return v
    }
}
