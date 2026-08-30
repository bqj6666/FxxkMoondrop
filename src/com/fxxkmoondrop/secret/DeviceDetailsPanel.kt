package com.fxxkmoondrop.secret

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** alpha2.39: 蓝牙设备详情页注入。只构建 ControlPanel 视图，不持有 BLE 单例；hook 由 XposedEntry 负责。 */
object DeviceDetailsPanel {
    const val KEY = "fxxk_moondrop_device_panel"

    private val nameByRoot = java.util.IdentityHashMap<View, String>()
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
        nameByRoot[root] = deviceName ?: ""
        val profile = AncProfileLib.resolveDc(deviceName)

        // 未连接守卫：isEnabled 之外的双保险，避免断开时点击穿透仍发命令
        val safeCommand: (Command) -> Unit = { cmd -> if (enabledByRoot[root] ?: true) onCommand(cmd) }
        val ancCard = ControlPanel.buildAncCard(ctx, pal, { mode -> safeCommand(Command.SetAncMode(mode)) }, cardBg = bg)
        root.addView(ancCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val callbacks = object : ControlPanel.Callbacks {
            override fun setAncMode(mode: Int) = safeCommand(Command.SetAncMode(mode))
            override fun setSpatialEnabled(enabled: Boolean) = safeCommand(Command.SetSpatialEnabled(enabled))
            override fun setTrackingMode(mode: Int) = safeCommand(Command.SetTrackingMode(mode))
            override fun setGain(level: Int) = safeCommand(Command.SetGain(level))
            override fun setLed(state: Int) = safeCommand(Command.SetLed(state))
        }
        val dcCard = ControlPanel.buildDcCard(ctx, pal, callbacks, cardBg = bg)
        root.addView(dcCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val st = ControlPanel.State(
            connected = false, ancMode = -1, spatialOn = false, spatialUiMode = -1,
            gainLevel = 0, ledOn = false,
            hasSpatial = profile.hasSpatial, hasGain = profile.hasGain, hasLed = profile.hasLed
        )
        refresh(root, st)
        return root
    }

    fun refresh(root: View, state: ControlPanel.State) {
        val deviceName = nameByRoot[root] ?: return
        refresh(root, state, AncProfileLib.resolveDc(deviceName))
    }

    fun refresh(root: View, state: ControlPanel.State, profile: AncProfileLib.DcProfile) {
        val card = root
        enabledByRoot[root] = state.connected
        val ancCard = card.findViewWithTag<LinearLayout>("fxxk_anc_card") ?: return
        val dcCard = card.findViewWithTag<LinearLayout>("fxxk_dc_card") ?: return
        val pal = ThemeUtil.Palette(card.context)
        // 降噪卡片：高亮当前模式（与主界面 updateAncStatus 一致）
        ControlPanel.refreshAncCard(ancCard, state.ancMode)
        // 功能卡片：按 profile + connected 决定可见性与高亮
        ControlPanel.refreshDcCard(dcCard, state, profile, pal.onVariant)
        // 未连接时降噪按钮禁用（与主界面 updateAncStatus 一致，完全依赖真实连接；ancMode 初始为 0 会让 OR 恒真导致断连仍可点）
        val enabled = state.connected
        val ancRow = ancCard.findViewWithTag<LinearLayout>("fxxk_anc_row") ?: return
        for (i in 0 until ancRow.childCount) {
            val col = ancRow.getChildAt(i) as? LinearLayout ?: continue
            val holder = col.getChildAt(0) as? android.widget.FrameLayout ?: continue
            holder.isEnabled = enabled
            holder.alpha = if (enabled) 1f else 0.4f
        }
    }

    sealed class Command {
        data class SetAncMode(val mode: Int) : Command()
        data class SetSpatialEnabled(val enabled: Boolean) : Command()
        data class SetTrackingMode(val mode: Int) : Command()
        data class SetGain(val level: Int) : Command()
        data class SetLed(val state: Int) : Command()
    }
}
