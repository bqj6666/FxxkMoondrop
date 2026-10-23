package com.fxxkmoondrop.secret

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** alpha2.39: 蓝牙设备详情页注入。只构建 ControlPanel 视图，不持有 BLE 单例；hook 由 XposedEntry 负责。 */
object DeviceDetailsPanel {
    const val KEY = "fxxk_moondrop_device_panel"

    /** 是否显示自绘的降噪档位行（设置里可选：自绘面板 / 官方面板） */
    @Volatile
    var showAncRow: Boolean = true

    private val nameByRoot = java.util.IdentityHashMap<View, String>()
    /** 最近一次刷新用到的状态；面板换了实例时用它兜底自刷。 */
    @Volatile private var latestState: ControlPanel.State? = null

    // alpha2.39.1: 每个 root 当前连接状态，用于未连接时拦截命令（isEnabled 之外的双保险）
    private val enabledByRoot = java.util.IdentityHashMap<View, Boolean>()
    // alpha2.39.2: 移除设置详情页面板的大色块背景（曾用设置页 colorBackground），改为透明让内容融入系统列表，避免突兀色块
    private const val TRANSPARENT_BG = 0x00000000

    fun buildView(ctx: Context, deviceName: String?, onCommand: (Command) -> Unit): LinearLayout {
        val pal = ThemeUtil.Palette(ctx)
        // 背景改为透明，不再给卡片上色块，完全融入设置详情页列表
        val bg = TRANSPARENT_BG
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.tag = "fxxk_device_panel"
        // 左右不加额外留白：preference 行本身已带官方那套左右边距，面板跟着官方对齐；
        // 上下各 8dp 只是让面板与上下两条官方行之间留点呼吸空间。
        val dpRoot = { px: Int -> (px * ctx.resources.displayMetrics.density).toInt() }
        root.setPadding(0, dpRoot(8), 0, dpRoot(8))
        nameByRoot[root] = deviceName ?: ""
        val profile = AncProfileLib.resolveDc(deviceName)

        // 未连接守卫：isEnabled 之外的双保险，避免断开时点击穿透仍发命令
        val safeCommand: (Command) -> Unit = { cmd -> if (enabledByRoot[root] ?: true) onCommand(cmd) }
        // 3.0.6: 撤掉「关闭/降噪/透传/抗风」四个大圆钮（降噪切换交给官方详情页自己的
        // 「耳机控制」切片），抗风改为与空间音频同款开关；空间音频/追踪/增益仍由下面那张卡提供。
        val windRow = ControlPanel.buildWindRow(ctx, pal) { on ->
            safeCommand(Command.SetAncMode(ControlPanel.windTargetMode(on)))
        }

        val callbacks = object : ControlPanel.Callbacks {
            override fun setAncMode(mode: Int) = safeCommand(Command.SetAncMode(mode))
            override fun setSpatialEnabled(enabled: Boolean) = safeCommand(Command.SetSpatialEnabled(enabled))
            override fun setTrackingMode(mode: Int) = safeCommand(Command.SetTrackingMode(mode))
            override fun setGain(level: Int) = safeCommand(Command.SetGain(level))
            override fun setLed(state: Int) = safeCommand(Command.SetLed(state))
        }
        // 自绘的降噪档位行（样式与概览页一致：官方 Icon Button、56dp、选中形变、动态取色）。
        // 用 try 包住：任何异常都不能再让整个面板构建失败（上次就是这样只剩一行标题）。
        try {
            if (showAncRow) root.addView(buildAncRow(ctx, pal, safeCommand),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT))
        } catch (t: Throwable) {
            android.util.Log.w("FxxkMoondropPanel", "anc row build fail", t)
        }

        // 增益三档也用我们自己的行（与概览页完全一致）；
        // ControlPanel 里那排旧样式的增益按钮随后隐藏，避免两排并存。
        try {
            if (showAncRow) root.addView(buildGainRow(ctx, pal, safeCommand),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT))
        } catch (t: Throwable) {
            android.util.Log.w("FxxkMoondropPanel", "gain row build fail", t)
        }

        val dcCard = ControlPanel.buildDcCard(ctx, pal, callbacks, cardBg = bg, windRow = windRow)
        // 直接**移除** ControlPanel 自带的旧增益行：只设 GONE 会在 refreshDcCard 里被重新设回 VISIBLE
        try {
            // 只有「自绘面板」模式才移除官方那排旧样式增益按钮；
            // 选「官方面板」时保留它（那是官方面板的用法）
            if (showAncRow) {
                val old = dcCard.findViewWithTag<View>("dc_gain_row")
                (old?.parent as? android.view.ViewGroup)?.removeView(old)
            }
        } catch (_: Throwable) { }
        root.addView(dcCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val st = ControlPanel.State(
            connected = false, gaiaReady = false, modes = IntArray(0),
            ancMode = -1, spatialOn = false, spatialUiMode = -1,
            gainLevel = 0, ledOn = false,
            hasSpatial = profile.hasSpatial, hasGain = profile.hasGain, hasLed = profile.hasLed
        )
        refresh(root, st)
        return root
    }

    /** 降噪 / 关闭 / 透传 / 抗风：官方 Icon Button 样式，与概览页同一套 */
    private fun buildAncRow(ctx: android.content.Context, pal: ThemeUtil.Palette,
                            onCommand: (Command) -> Unit): LinearLayout {
        val appCtx = ThemeUtil.moduleUiContext(ctx)
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(appCtx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER
        row.tag = "fxxk_anc_row"
        // 与卡片内容对齐：左右 4dp、上下 8dp（原来贴边，看着"太靠上"）
        row.setPadding((4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        val names = AncProfileLib.modeNamesFull(appCtx)
        // 只保留 降噪/关闭/透传；抗风由下方开关负责（官方逻辑：只在降噪档才显示那个开关）
        for (m in intArrayOf(1, 0, 2)) {
            val col = LinearLayout(appCtx)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = android.view.Gravity.CENTER
            val holder = M3Ui.iconToggle(appCtx, pal,
                    androidx.core.content.ContextCompat.getDrawable(appCtx, modeIconRes(m)),
                    48, 26)
            holder.tag = "fxxk_anc_" + m
            holder.findViewWithTag<View>("it_btn")?.apply {
                contentDescription = names.getOrNull(m)
                setOnClickListener { onCommand(Command.SetAncMode(m)) }
            }
            col.addView(holder, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
            val lbl = android.widget.TextView(appCtx)
            lbl.text = names.getOrNull(m) ?: ""
            lbl.textSize = 12f
            lbl.gravity = android.view.Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(pal.onContainer)
            lbl.tag = "fxxk_anc_label_" + m
            col.addView(lbl, LinearLayout.LayoutParams(-2, -2))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return row
    }

    /** 与概览页同款：增益三档（低/中/高），官方 Icon Button */
    private fun buildGainRow(ctx: android.content.Context, pal: ThemeUtil.Palette,
                             onCommand: (Command) -> Unit): LinearLayout {
        val appCtx = ThemeUtil.moduleUiContext(ctx)
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(appCtx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER
        row.tag = "fxxk_gain_row"
        row.setPadding((4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        val labels = DeviceControlBridge.gainLabels()
        for (g in 0 until DeviceControlBridge.gainCount()) {
            val col = LinearLayout(appCtx)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = android.view.Gravity.CENTER
            val holder = M3Ui.iconToggle(appCtx, pal,
                    DcIcons.build(appCtx, 1, g, (26 * d).toInt(), pal.onContainer), 48, 26)
            holder.tag = "fxxk_gain_" + g
            holder.findViewWithTag<View>("it_btn")?.apply {
                contentDescription = Lang.t(ctx, "增益", "Gain") + ": " + labels.getOrNull(g)
                setOnClickListener { onCommand(Command.SetGain(g)) }
            }
            col.addView(holder, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
            val lbl = android.widget.TextView(appCtx)
            lbl.text = labels.getOrNull(g) ?: ""
            lbl.textSize = 12f
            lbl.gravity = android.view.Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(pal.onContainer)
            lbl.tag = "fxxk_gain_label_" + g
            col.addView(lbl, LinearLayout.LayoutParams(-2, -2))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return row
    }

    private fun modeIconRes(mode: Int): Int = when (mode) {
        0 -> R.drawable.ic_anc_off
        2 -> R.drawable.ic_anc_passthrough
        3 -> R.drawable.ic_air
        else -> R.drawable.ic_anc_on
    }

    /** 面板挂到窗口时用最近一次已知状态自刷：新挂上去的实例不会停在默认（全显）状态。 */
    fun refreshFromLatest(root: View) {
        latestState?.let { refresh(root, it) }
    }

    fun refresh(root: View, state: ControlPanel.State) {
        val deviceName = nameByRoot[root]
        if (deviceName == null) {
            // 面板实例可能由别的路径创建/复用（型号名没登记），此处退回用状态里已经解析好的能力位，
            // 宁可少一点型号档案信息，也不能整块不刷新 —— 否则界面会卡在旧状态。
            refresh(root, state, AncProfileLib.DcProfile(
                    "", state.hasSpatial, state.hasGain, state.hasLed))
            return
        }
        refresh(root, state, AncProfileLib.resolveDc(deviceName))
    }

    fun refresh(root: View, state: ControlPanel.State, profile: AncProfileLib.DcProfile) {
        latestState = state
        // 第 2 项：可交互判据 = **GAIA 就绪**，不是「蓝牙已连」。
        // 蓝牙链路挂上后服务发现还要一段时间，窗口期内点击必然失败，
        // 所以用更强的 isGaiaReady() 作门禁，避免用户点到没反应的按钮。
        val enabled = state.gaiaReady
        enabledByRoot[root] = enabled

        // 各卡片独立处理：任一视图缺失都不该让另一张卡的禁用逻辑被跳过
        val windRow = root.findViewWithTag<LinearLayout>(ControlPanel.ROW_WIND)
        if (windRow != null) {
            ControlPanel.refreshWindRow(windRow, state, ThemeUtil.Palette(root.context))
        }
        // 自绘降噪行：按设置显示/隐藏，按当前档位高亮
        val ancRow = root.findViewWithTag<LinearLayout>("fxxk_anc_row")
        if (ancRow != null) {
            ancRow.visibility = if (showAncRow) View.VISIBLE else View.GONE
            for (m in intArrayOf(0, 1, 2, 3)) {
                val h = root.findViewWithTag<View>("fxxk_anc_" + m) ?: continue
                val b = h.findViewWithTag<View>("it_btn")
                        as? com.google.android.material.button.MaterialButton
                val on = state.ancMode == m
                b?.isChecked = on
                b?.isEnabled = enabled
                // 自绘图标不随状态变色，这里手动 tint：选中 onPrimary / 未选 onContainer
                (h.findViewWithTag<View>("it_img") as? android.widget.ImageView)?.let { iv ->
                    iv.imageTintList = android.content.res.ColorStateList.valueOf(
                            if (on) ThemeUtil.Palette(root.context).onPrimary
                            else ThemeUtil.Palette(root.context).onContainer)
                    iv.alpha = if (enabled) 1f else 0.38f
                }
                root.findViewWithTag<View>("fxxk_anc_label_" + m)?.alpha =
                        if (enabled) 1f else 0.38f
            }
        }
        // 增益行：按型号能力显示、按当前档位高亮
        val gainRow = root.findViewWithTag<LinearLayout>("fxxk_gain_row")
        if (gainRow != null) {
            gainRow.visibility = if (profile.hasGain) View.VISIBLE else View.GONE
            for (g in 0 until DeviceControlBridge.gainCount()) {
                val h = root.findViewWithTag<View>("fxxk_gain_" + g) ?: continue
                val b = h.findViewWithTag<View>("it_btn")
                        as? com.google.android.material.button.MaterialButton
                val on = state.gainLevel == g
                b?.isChecked = on
                b?.isEnabled = enabled
                (h.findViewWithTag<View>("it_img") as? android.widget.ImageView)?.let { iv ->
                    iv.imageTintList = android.content.res.ColorStateList.valueOf(
                            if (on) ThemeUtil.Palette(root.context).onPrimary
                            else ThemeUtil.Palette(root.context).onContainer)
                    iv.alpha = if (enabled) 1f else 0.38f
                }
                root.findViewWithTag<View>("fxxk_gain_label_" + g)?.alpha =
                        if (enabled) 1f else 0.38f
            }
        }
        val dcCard = root.findViewWithTag<LinearLayout>("fxxk_dc_card")
        if (dcCard != null) {
            ControlPanel.refreshDcCard(dcCard, state, profile)
            // 自绘模式下压掉官方旧样式增益行（refreshDcCard 会把它设回 VISIBLE）
            if (showAncRow) dcCard.findViewWithTag<View>("dc_gain_row")?.visibility = View.GONE
        }
    }

    /**
     * 刷新**所有仍在视图树里**的面板。
     *
     * 官方详情页重排会挪行、旧行被回收，同一页可能残留多份面板；只刷新闭包里那一份
     * 会漏掉正在显示的那份（现象：状态已变、界面不动）。脱离视图树的旧实例顺手清掉。
     */
    fun refreshAll(treeRoot: View?, state: ControlPanel.State) {
        // 覆盖面必须够：详情页重排会挪行、旧行被回收，同一页可能同时存在多份面板，
        // 而"当前正在显示的那份"未必在闭包持有的视图树里。所以两头都收：
        //   1) 从页面窗口根遍历到的；
        //   2) 本进程登记过的每一个面板实例（不筛挂载状态）。
        // 只刷其中一份会出现「状态已变、界面不动」。
        val roots = java.util.IdentityHashMap<View, Boolean>()
        fun walk(v: View) {
            if (v.tag == "fxxk_device_panel") { roots[v] = true; return }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        if (treeRoot != null) walk(treeRoot)
        for (v in ArrayList<View>(nameByRoot.keys)) roots[v] = true
        val dead = ArrayList<View>(2)
        for (v in ArrayList<View>(nameByRoot.keys)) {
            // 只回收真正死透的（既没挂窗口也没父容器），别把还在屏幕上的那份清掉。
            if (!v.isAttachedToWindow && v.parent == null && v.windowToken == null) dead.add(v)
        }
        for (d in dead) nameByRoot.remove(d)
        for (r in roots.keys) refresh(r, state)
    }

    sealed class Command {
        data class SetAncMode(val mode: Int) : Command()
        data class SetSpatialEnabled(val enabled: Boolean) : Command()
        data class SetTrackingMode(val mode: Int) : Command()
        data class SetGain(val level: Int) : Command()
        data class SetLed(val state: Int) : Command()
    }
}
