package com.fxxkmoondrop.secret

import android.util.Log

/**
 * alpha2.34: DeviceControlBridge 重构为单一 DeviceControlCallback 实现。
 * 不再每次 fetch/set 覆写 GaiaBleClient.deviceControlCallback（旧设计 race condition
 * 导致空间音频响应丢失）。bridge 自身就是永久 callback，连接时设到 GaiaBleClient，
 * 所有 DC 响应统一路由到此处。
 *
 * 增益映射（gainMap）由 AncProfileLib.DcProfile 提供，UI index <-> device value 双向映射。
 */
object DeviceControlBridge : GaiaBleClient.DeviceControlCallback {

    private const val TAG = "DeviceControlBridge"

    /** 用户在软件内手动把追踪模式设为「关闭追踪」的偏好键。 */
    private const val KEY_USER_TRACKING_OFF = "track_user_off"


    @Volatile private var spatialState = -1
    @Volatile private var headTracking = -1
    @Volatile private var gainLevel = -1
    @Volatile private var ledState = -1
    @Volatile private var version = 0
    @Volatile private var stateListener: (() -> Unit)? = null

    @Volatile private var gainMap: IntArray = AncProfileLib.DEFAULT_DC.gainMap
    @Volatile private var gainLabels: List<String> = AncProfileLib.DEFAULT_DC.gainLabels
    @Volatile private var gainCount: Int = AncProfileLib.DEFAULT_DC.gainCount
    @Volatile private var trackingLabels: Array<String> = AncProfileLib.DEFAULT_DC.trackingLabels

    fun getVersion(): Int = version
    private fun bumpVersion() { version++ }

    @JvmStatic
    fun setStateListener(l: (() -> Unit)?) { stateListener = l }
    private fun notifyStateChanged() { bumpVersion(); stateListener?.invoke(); CtrlBus.postDcChanged() }

    @JvmStatic
    fun applyProfile(profile: AncProfileLib.DcProfile) {
        // alpha2.37: 优先读取用户自定义增益映射
        val ctx = try { GaiaBleClient.getInstance().getContext() } catch (_: Exception) { null }
        val prefs = ctx?.getSharedPreferences("cfg", 0)
        if (prefs != null) {
            // 增益映射
            val customGain = IntArray(profile.gainCount) { i ->
                prefs.getInt("gain_map_" + i, profile.gainMap.getOrElse(i) { i })
            }
            gainMap = customGain
            gainCount = profile.gainCount
            gainLabels = profile.gainLabels
            // 追踪标签
            val customLabels = Array(profile.trackingLabels.size) { i ->
                prefs.getString("track_label_" + i, profile.trackingLabels[i]) ?: profile.trackingLabels[i]
            }
            trackingLabels = customLabels
        } else {
            gainMap = profile.gainMap
            gainLabels = profile.gainLabels
            gainCount = profile.gainCount
            trackingLabels = profile.trackingLabels
        }
    }

    fun gainLabels(): List<String> = gainLabels
    fun gainCount(): Int = gainCount
    fun trackingLabels(): Array<String> = trackingLabels

    private fun uiToDevGain(uiLevel: Int): Int {
        return if (uiLevel in gainMap.indices) gainMap[uiLevel] else uiLevel
    }

    private fun devToUiGain(devLevel: Int): Int {
        val idx = gainMap.indexOf(devLevel)
        return if (idx >= 0) idx else devLevel
    }

    fun isSpatialOn(): Boolean = spatialState == 1
    /** 面板 / 官方行读到的档位：空间音频开着就不会读到「关闭」，除非用户手动关过。 */
    fun spatialUiMode(): Int = AncProfileLib.displayTrackingMode(
            spatialOn = spatialState == 1,
            gaiaTracking = headTracking,
            userClosedTracking = userClosedTrackingPref())
    fun getGainLevel(): Int = gainLevel
    fun getLedState(): Int = ledState

    override fun onSpatialResult(state: Int) {
        spatialState = state
        if (state == 1) {
            GaiaBleClient.getInstance().fetchHeadTracking()
        } else {
            headTracking = -1
        }
        notifyStateChanged()
    }

    override fun onHeadTrackingResult(state: Int) {
        headTracking = state
        fixTrackingModeIfClosed()
        notifyStateChanged()
    }

    override fun onGainResult(level: Int) {
        gainLevel = devToUiGain(level)
        notifyStateChanged()
    }

    override fun onLedResult(state: Int) {
        ledState = state
        notifyStateChanged()
    }

    override fun onDeviceControlError(message: String) {
        Log.w(TAG, "DC error: " + message)
    }

    @JvmStatic
    fun fetchSpatial() {
        GaiaBleClient.getInstance().fetchSpatial()
    }

    @JvmStatic
    fun fetchHeadTracking() {
        GaiaBleClient.getInstance().fetchHeadTracking()
    }

    @JvmStatic
    fun fetchGain() {
        GaiaBleClient.getInstance().fetchGain()
    }

    @JvmStatic
    fun fetchLed() {
        GaiaBleClient.getInstance().fetchLed()
    }

    @JvmStatic
    fun setSpatialEnabled(enabled: Boolean) {
        Log.d("DeviceControlBridge", "setSpatialEnabled=" + enabled)
        spatialState = if (enabled) 1 else 0
        if (!enabled) headTracking = -1
        notifyStateChanged()
        GaiaBleClient.getInstance().setSpatial(if (enabled) 1 else 0)
    }

    /**
     * 用户自己在软件界面点的三档。
     *
     * 「本来在 30° / 全方位、用户自己切到关闭追踪」= 手动关，记下来之后不再自动补档；
     * 用户点回 30° / 全方位说明他要追踪，把记录清掉。
     */
    @JvmStatic
    fun setTrackingModeByUser(mode: Int) {
        if (mode != 0) {
            cfg()?.edit()?.putBoolean(KEY_USER_TRACKING_OFF, false)?.apply()
        } else if (AncProfileLib.isManualTrackingClose(pickedMode = mode, currentMode = spatialUiMode())) {
            cfg()?.edit()?.putBoolean(KEY_USER_TRACKING_OFF, true)?.apply()
        }
        setTrackingMode(mode)
    }

    /**
     * 打开空间音频时耳机端报来「关闭追踪」的话，补上应有的档位（默认 30°）。
     *
     * 只在空间音频开着、且用户没在软件内手动关过追踪时才补；否则保持耳机端报来的值。
     */
    private fun fixTrackingModeIfClosed() {
        val fix = AncProfileLib.correctedTrackingMode(
                spatialOn = spatialState == 1,
                gaiaTracking = headTracking,
                userClosedTracking = userClosedTrackingPref()) ?: return
        Log.d(TAG, "tracking mode auto-fixed to " + fix)
        headTracking = fix
        GaiaBleClient.getInstance().setHeadTracking(fix)
    }

    /** 用户手动关过追踪吗（偏好在 cfg 里，与增益映射同一份）。 */
    private fun userClosedTrackingPref(): Boolean =
            cfg()?.getBoolean(KEY_USER_TRACKING_OFF, false) ?: false

    /** 用户偏好在 cfg 里，与增益映射同一份。 */
    private fun cfg() = try {
        GaiaBleClient.getInstance().getContext()?.getSharedPreferences("cfg", 0)
    } catch (_: Exception) { null }

    @JvmStatic
    fun setTrackingMode(mode: Int) {
        headTracking = mode
        bumpVersion()
        GaiaBleClient.getInstance().setHeadTracking(mode)
        stateListener?.invoke()
    }

    @JvmStatic
    fun setGain(level: Int) {
        Log.d("DeviceControlBridge", "setGain uiLevel=" + level + " gainCount=" + gainCount + " gainMap=" + gainMap.contentToString())
        if (level !in 0 until gainCount) return
        gainLevel = level
        bumpVersion()
        GaiaBleClient.getInstance().setGain(uiToDevGain(level))
        stateListener?.invoke()
    }

    @JvmStatic
    fun setLed(state: Int) {
        ledState = state
        bumpVersion()
        GaiaBleClient.getInstance().setLed(state)
        stateListener?.invoke()
    }

    @JvmStatic
    fun fetchAll() {
        Log.d("DeviceControlBridge", "fetchAll called")
        try {
            val c = GaiaBleClient.getInstance()
            c.fetchSpatial()
            c.fetchGain()
            c.fetchLed()
            c.fetchHeadTracking()
        } catch (e: Exception) { Log.w(TAG, "fetchAll error", e) }
    }

    @JvmStatic
    fun reset() {
        spatialState = -1; headTracking = -1; gainLevel = -1; ledState = -1
        stateListener?.invoke()
        CtrlBus.postDcChanged()
    }
}
