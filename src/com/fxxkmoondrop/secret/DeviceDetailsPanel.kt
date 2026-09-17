package com.fxxkmoondrop.secret

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** alpha2.39: 蓝牙设备详情页注入。只构建 ControlPanel 视图，不持有 BLE 单例；hook 由 XposedEntry 负责。 */
object DeviceDetailsPanel {
    const val KEY = "fxxk_moondrop_device_panel"

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
        val dcCard = ControlPanel.buildDcCard(ctx, pal, callbacks, cardBg = bg, windRow = windRow)
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
        val dcCard = root.findViewWithTag<LinearLayout>("fxxk_dc_card")
        if (dcCard != null) {
            ControlPanel.refreshDcCard(dcCard, state, profile)
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
