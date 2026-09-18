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
        /** GAIA 是否就绪（服务发现完成）。UI 可交互判据 —— 比 [connected] 更强。 */
        val gaiaReady: Boolean = false,
        /** 该设备实际支持的 UI 档位（能力探测驱动）。空 = 未知，按型号档案显示。 */
        val modes: IntArray = IntArray(0),
        /** 用户的「显示抗风档」偏好。由状态源给出（设置进程拿不到 app 私有偏好）。 */
        val showWind: Boolean = true,
        val ancMode: Int,
        val spatialOn: Boolean,
        val spatialUiMode: Int,
        /** 系统侧（官方 Spatializer，官方那个开关同一套 API）空间音频状态：-1 未知 / 0 关 / 1 开。官方判定优先。 */
        /** 系统侧头部追踪状态：-1 未知 / 0 关 / 1 开。官方没有头部追踪能力时也是 -1。 */
        val gainLevel: Int,
        val ledOn: Boolean,
        val hasSpatial: Boolean,
        val hasGain: Boolean,
        val hasLed: Boolean
    )

    /** 抗风档的 UI 档位号（[AncProfileLib.modeNamesFull] 顺序固定：0 关 / 1 降噪 / 2 透传 / 3 抗风）。 */
    const val WIND_UI_MODE = AncProfileLib.UI_MODE_WIND

    /** 退出抗风后回到的档位（降噪）。 */
    private const val ANC_UI_MODE = AncProfileLib.UI_MODE_ANC

    /** 抗风开关行标记（详情页面板按它找行）。 */
    const val ROW_WIND = "fxxk_wind_row"

    private const val SWITCH_WIND = "fxxk_wind_switch"

    /** 程序化 setChecked 时用它挡住回调，避免把界面回填当成用户操作又发一条命令。 */
    private val syncing = java.util.WeakHashMap<android.widget.CompoundButton, Boolean>()

    /**
     * 开关行：左侧标签 + 右侧设置页原版开关；[rowTag] 标识整行、[switchTag] 标识开关。
     *
     * 面板里所有「开关式」功能（空间音频、抗风…）共用这一行，样式天然一致，不会各自漂移。
     * 开关控件优先取设置 App 自己的布局（见 [buildSettingsSwitch]），拿不到再按动态色兜底。
     */
    fun buildSwitchRow(
        ctx: Context,
        pal: ThemeUtil.Palette,
        rowTag: String,
        switchTag: String,
        label: CharSequence
    ): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.tag = rowTag
        val tv = TextView(ctx)
        tv.text = label
        tv.textSize = 12f
        tv.setTextColor(pal.onContainer)
        row.addView(tv, LinearLayout.LayoutParams(-2, -2))
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(buildSwitch(ctx, pal, switchTag), LinearLayout.LayoutParams(-2, -2))
        return row
    }

    /**
     * 抗风开关（与空间音频同一行样式）。
     *
     * 降噪档位切换交给官方详情页自己的「耳机控制」切片，我们只留这一个快捷键：
     * 开 = 切到抗风档，关 = 回到降噪档。返回的是**整块**（行 + 行后 4dp 间距），
     * 由 [refreshWindRow] 整块显隐；是否出现由当前档位 + 设备能力 + 用户偏好决定。
     */
    fun buildWindRow(ctx: Context, pal: ThemeUtil.Palette, onToggle: (Boolean) -> Unit): LinearLayout {
        val row = buildSwitchRow(ctx, pal, ROW_WIND + "_inner", SWITCH_WIND,
                AncProfileLib.modeNamesFull(ctx)[WIND_UI_MODE])
        row.findViewWithTag<android.widget.CompoundButton>(SWITCH_WIND)?.setOnCheckedChangeListener { v, checked ->
            if (syncing.containsKey(v)) return@setOnCheckedChangeListener
            onToggle(checked)
        }
        // 行 + 行后间距合成一整块（[ROW_WIND] 标在这块上）：一起显隐，隐藏时不留空档。
        val unit = LinearLayout(ctx)
        unit.orientation = LinearLayout.VERTICAL
        unit.tag = ROW_WIND
        unit.addView(row, LinearLayout.LayoutParams(-1, -2))
        // spacer 自带 (1, h) 的 params，别再传一套覆盖掉，否则间距会变 0。
        unit.addView(spacer(ctx, (4 * ctx.resources.displayMetrics.density).toInt()))
        return unit
    }

    /** 抗风开关要切到的目标档位：开 -> 抗风，关 -> 降噪。 */
    fun windTargetMode(on: Boolean): Int = if (on) WIND_UI_MODE else ANC_UI_MODE

    /**
     * 刷新抗风开关。
     *
     * 出现条件两条，缺一即整块隐藏：
     *  1) 设备实际支持抗风档、且用户没关掉「显示抗风档」偏好（[AncProfileLib.ancColumnVisible]），不写死机型；
     *  2) 当前处于「降噪」或「抗风」档（[AncProfileLib.windSwitchAvailable]）—— 抗风是降噪的加强档，
     *     用户从抗风直接切到通透/关闭后开关自动弹回关闭并隐藏，不用手动收尾。
     * 勾选态 = 当前正处于抗风档；未就绪（GAIA 未完成服务发现）时置灰。
     */
    fun refreshWindRow(row: LinearLayout, state: State, pal: ThemeUtil.Palette) {
        val supported = AncProfileLib.ancColumnVisible(WIND_UI_MODE, state.modes, state.showWind)
        // 档位未知（=-1）：维持现有姿态，别让换档途中一次抖动刷新把开关闪没。
        val show = when {
            !supported -> false
            state.ancMode >= 0 -> AncProfileLib.windSwitchAvailable(state.ancMode)
            else -> row.visibility == View.VISIBLE
        }
        row.visibility = if (show) View.VISIBLE else View.GONE
        android.util.Log.d(TAG, "wind row vis=" + row.visibility + " supported=" + supported +
                " ancMode=" + state.ancMode)
        val sw = row.findViewWithTag<android.widget.CompoundButton>(SWITCH_WIND) ?: return
        val enabled = state.gaiaReady && supported
        sw.isEnabled = enabled
        sw.isClickable = enabled
        sw.isFocusable = enabled
        sw.alpha = if (enabled) 1f else 0.4f
        setCheckedSilently(sw, state.ancMode == WIND_UI_MODE)
    }

    /** 设置页原版开关；[buildSettingsSwitch] 拿不到时退回平台 Switch + 动态色。 */
    private fun buildSwitch(ctx: Context, pal: ThemeUtil.Palette, tag: String): android.widget.CompoundButton =
            buildSettingsSwitch(ctx, tag) ?: android.widget.Switch(ctx).apply {
                this.tag = tag
                trackTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(pal.primary, pal.surfaceContainerHighest))
                thumbTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(pal.onPrimary, pal.outline))
            }

    /** 静默勾选：值已一致时不做任何事，改动期间挡掉该开关的回调。 */
    private fun setCheckedSilently(sw: android.widget.CompoundButton, checked: Boolean) {
        if (sw.isChecked == checked) return
        syncing[sw] = true
        try { sw.isChecked = checked } finally { syncing.remove(sw) }
    }

    /** 构建功能控制面板卡片（增益 / 指示灯；空间音频与头部追踪由官方详情页自己那两行承担）。 */
    fun buildDcCard(
        ctx: Context,
        pal: ThemeUtil.Palette,
        callbacks: Callbacks,
        cardBg: Int? = null,
        /** 可选的开关式快捷行（如抗风），排在本卡片最上面，与其它行同一套边距。 */
        windRow: View? = null
    ): LinearLayout {
        val dp = { px: Int -> (px * ctx.resources.displayMetrics.density).toInt() }
        val containerCol = pal.container
        val onContainerC = pal.onContainer
        val cardC = cardBg ?: pal.card

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        // 8dp：官方详情页行的左右 32dp 之外再收 8dp，内容与官方切片行的内容同一条竖线，不贴边。
        card.setPadding(dp(8), dp(12), dp(8), dp(12))
        if (windRow != null) {
            // 行后间距由 [buildWindRow] 的整块自带：整块隐藏时不会残留一段空白。
            card.addView(windRow, LinearLayout.LayoutParams(-1, -2))
        }
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.setColor(cardC)
        bg.setCornerRadius(dp(28).toFloat())
        card.background = bg


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

    /** 刷新功能卡片高亮。state 由调用方提供；可见性按 resolveDc + 能力探测决定。 */
    fun refreshDcCard(card: LinearLayout, state: State, profile: AncProfileLib.DcProfile) {
        val ctx = card.context
        val dp = { px: Int -> (px * ctx.resources.displayMetrics.density).toInt() }
        val gainRow = card.findViewWithTag<LinearLayout>("dc_gain_row")
        val ledRow = card.findViewWithTag<LinearLayout>("dc_led_row")

        // 设备详情页：未连接也显示功能卡（按型号档案），未连接时按钮置灰；主界面仍为“未连接隐藏”。
        // 空间音频与头部追踪这两行已经交回官方详情页自己渲染（见 XposedEntry.hookOfficialSpatialRows），
        // 卡片里只留抗风 / 增益 / 指示灯。
        gainRow?.visibility = if (profile.hasGain) View.VISIBLE else View.GONE
        ledRow?.visibility = if (profile.hasLed) View.VISIBLE else View.GONE

        // 抗风那一行也在这个卡里：它可见时整卡就得在（例如只支持抗风、不支持空间音频的机型）。
        val windVisible = card.findViewWithTag<LinearLayout>(ROW_WIND)?.visibility == View.VISIBLE
        val anyVisible = profile.hasGain || profile.hasLed || windVisible
        card.visibility = if (anyVisible) View.VISIBLE else View.GONE

        // alpha2.39.1: 禁用态用暗中性灰（dark 下 onVariant 偏浅会发白），不发白
        val grey = ThemeUtil.dyn(ctx, "system_neutral1_80",
            if (ThemeUtil.isDark(ctx)) 0xFF2A2A2E.toInt() else 0xFFE0E0E0.toInt())
        val primary = ThemeUtil.dyn(ctx, "system_accent1_400",
            if (ThemeUtil.isDark(ctx)) 0xFFD0BCFF.toInt() else 0xFF6750A4.toInt())
        // 与 buildDcCard 初始底色一致：深色用 system_accent1_800（深紫），避免发白
        val containerCal = ThemeUtil.dyn(ctx, "system_accent1_800",
            if (ThemeUtil.isDark(ctx)) 0xFF4F378B.toInt() else 0xFFE8DEF8.toInt())

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
                        "gain" -> idx == gLevel
                        "led" -> idx == if (ledOn) 0 else 1
                        else -> false
                    }
                    val iconColor = if (active) 0xFFFFFFFF.toInt() else onContainerOf(ctx)
                    val featType = when (feature) { "gain" -> 1; "led" -> 2; else -> 0 }
                    if (!state.gaiaReady) {
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
