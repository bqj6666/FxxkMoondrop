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

    val TRACKING_NAMES = arrayOf("关闭追踪", "30°", "全方位")
    private val DEFAULT_GAIN_LABELS = listOf("低", "中", "高")

    @Volatile private var spatialState = -1
    @Volatile private var headTracking = -1
    @Volatile private var gainLevel = -1
    @Volatile private var ledState = -1
    @Volatile private var version = 0
    @Volatile private var stateListener: (() -> Unit)? = null

    @Volatile private var gainMap: IntArray = intArrayOf(0, 1, 2)
    @Volatile private var gainLabels: List<String> = DEFAULT_GAIN_LABELS
    @Volatile private var gainCount: Int = 3

    fun getVersion(): Int = version
    private fun bumpVersion() { version++ }

    @JvmStatic
    fun setStateListener(l: (() -> Unit)?) { stateListener = l }
    private fun notifyStateChanged() { bumpVersion(); stateListener?.invoke() }

    @JvmStatic
    fun applyProfile(profile: AncProfileLib.DcProfile) {
        gainMap = profile.gainMap
        gainLabels = profile.gainLabels
        gainCount = profile.gainCount
    }

    fun gainLabels(): List<String> = gainLabels
    fun gainCount(): Int = gainCount

    private fun uiToDevGain(uiLevel: Int): Int {
        return if (uiLevel in gainMap.indices) gainMap[uiLevel] else uiLevel
    }

    private fun devToUiGain(devLevel: Int): Int {
        val idx = gainMap.indexOf(devLevel)
        return if (idx >= 0) idx else devLevel
    }

    fun isSpatialOn(): Boolean = spatialState == 1
    fun spatialUiMode(): Int = if (spatialState == 1 && headTracking in 0..2) headTracking else -1
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
        } catch (e: Exception) { Log.w(TAG, "fetchAll error", e) }
    }

    @JvmStatic
    fun reset() {
        spatialState = -1; headTracking = -1; gainLevel = -1; ledState = -1
        stateListener?.invoke()
    }
}
