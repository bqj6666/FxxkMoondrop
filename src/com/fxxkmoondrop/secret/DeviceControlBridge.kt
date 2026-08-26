package com.fxxkmoondrop.secret

import android.util.Log

class DeviceControlBridge {
    companion object {
        private const val TAG = "DeviceControlBridge"

        val TRACKING_NAMES = arrayOf("关闭追踪", "30°", "全方位")
        val GAIN_NAMES = arrayOf("低", "中", "高")

        @Volatile private var spatialState = -1
        @Volatile private var headTracking = -1
        @Volatile private var gainLevel = -1
        @Volatile private var ledState = -1
        @Volatile private var version = 0
        @Volatile private var stateListener: (() -> Unit)? = null

        fun setStateListener(l: (() -> Unit)?) { stateListener = l }
        private fun notifyStateChanged() { bumpVersion(); stateListener?.invoke() }
        fun isSpatialOn(): Boolean = spatialState == 1
        fun spatialUiMode(): Int = if (spatialState == 1 && headTracking in 0..2) headTracking else -1
        fun getGainLevel(): Int = gainLevel
        fun getLedState(): Int = ledState
        fun getVersion(): Int = version
        private fun bumpVersion() { version++ }

        @JvmStatic
        fun fetchSpatial() {
            GaiaBleClient.getInstance().fetchSpatial(object : GaiaBleClient.DeviceControlCallback {
                override fun onSpatialResult(state: Int) {
                    spatialState = state
                    if (state == 1) fetchHeadTracking()
                    else { headTracking = -1; notifyStateChanged() }
                }
                override fun onHeadTrackingResult(state: Int) { headTracking = state; notifyStateChanged() }
                override fun onDeviceControlError(message: String) { Log.w(TAG, "fetchSpatial: " + message) }
            })
        }

        @JvmStatic
        fun fetchHeadTracking() {
            GaiaBleClient.getInstance().fetchHeadTracking(object : GaiaBleClient.DeviceControlCallback {
                override fun onHeadTrackingResult(state: Int) { headTracking = state; notifyStateChanged() }
                override fun onDeviceControlError(message: String) { Log.w(TAG, "fetchHT: " + message) }
            })
        }

        /** 总开关：开启/关闭空间音频 */
        @JvmStatic
        fun setSpatialEnabled(enabled: Boolean) {
            spatialState = if (enabled) 1 else 0
            if (!enabled) headTracking = -1
            notifyStateChanged()
            GaiaBleClient.getInstance().setSpatial(if (enabled) 1 else 0,
                if (enabled) object : GaiaBleClient.DeviceControlCallback {
                    override fun onSpatialResult(state: Int) {
                        spatialState = state
                        if (state == 1) fetchHeadTracking()
                        notifyStateChanged()
                    }
                    override fun onDeviceControlError(message: String) {
                        spatialState = -1; notifyStateChanged()
                        Log.w(TAG, "setSpatial: " + message)
                    }
                } else null)
        }

        /** 子模式：设置头部追踪模式 */
        @JvmStatic
        fun setTrackingMode(mode: Int) {
            if (mode !in 0..2) return
            headTracking = mode
            notifyStateChanged()
            GaiaBleClient.getInstance().setHeadTracking(mode, object : GaiaBleClient.DeviceControlCallback {
                override fun onHeadTrackingResult(state: Int) {
                    headTracking = state; notifyStateChanged()
                }
                override fun onDeviceControlError(message: String) {
                    Log.w(TAG, "setTracking: " + message)
                }
            })
        }

        @JvmStatic
        fun fetchGain() {
            GaiaBleClient.getInstance().fetchGain(object : GaiaBleClient.DeviceControlCallback {
                override fun onGainResult(level: Int) { gainLevel = level; notifyStateChanged() }
                override fun onDeviceControlError(message: String) { Log.w(TAG, "fetchGain: " + message) }
            })
        }

        @JvmStatic
        fun setGain(level: Int) {
            if (level !in 0..2) return
            gainLevel = level; bumpVersion()
            GaiaBleClient.getInstance().setGain(level, null)
            stateListener?.invoke()
        }

        @JvmStatic
        fun fetchLed() {
            GaiaBleClient.getInstance().fetchLed(object : GaiaBleClient.DeviceControlCallback {
                override fun onLedResult(state: Int) { ledState = state; notifyStateChanged() }
                override fun onDeviceControlError(message: String) { Log.w(TAG, "fetchLed: " + message) }
            })
        }

        @JvmStatic
        fun setLed(state: Int) {
            ledState = state; bumpVersion()
            GaiaBleClient.getInstance().setLed(state, null)
            stateListener?.invoke()
        }

        @JvmStatic
        fun fetchAll() {
            try {
                val c = GaiaBleClient.getInstance()
                fetchSpatial()
                fetchGain()
                fetchLed()
            } catch (e: Exception) { Log.w(TAG, "fetchAll error", e) }
        }

        @JvmStatic
        fun reset() {
            spatialState = -1; headTracking = -1; gainLevel = -1; ledState = -1
            stateListener?.invoke()
        }
    }
}
