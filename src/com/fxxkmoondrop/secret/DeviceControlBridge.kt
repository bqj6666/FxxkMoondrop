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

        fun spatialUiMode(): Int =
            if (spatialState == 1 && headTracking in 0..2) headTracking else -1

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
                    else { headTracking = -1; bumpVersion() }
                }
                override fun onHeadTrackingResult(state: Int) { headTracking = state; bumpVersion() }
                override fun onDeviceControlError(message: String) { Log.w(TAG, "fetchSpatial: $message") }
            })
        }

        @JvmStatic
        fun fetchHeadTracking() {
            GaiaBleClient.getInstance().fetchHeadTracking(object : GaiaBleClient.DeviceControlCallback {
                override fun onHeadTrackingResult(state: Int) { headTracking = state; bumpVersion() }
                override fun onDeviceControlError(message: String) { Log.w(TAG, "fetchHT: $message") }
            })
        }

        @JvmStatic
        fun setSpatialMode(uiMode: Int) {
            if (uiMode < 0) {
                spatialState = 0; headTracking = -1; bumpVersion()
                GaiaBleClient.getInstance().setSpatial(0, null)
                return
            }
            spatialState = 1; headTracking = uiMode; bumpVersion()
            GaiaBleClient.getInstance().setSpatial(1, object : GaiaBleClient.DeviceControlCallback {
                override fun onSpatialResult(state: Int) {
                    if (state == 1) GaiaBleClient.getInstance().setHeadTracking(uiMode, null)
                }
            })
            GaiaBleClient.getInstance().setHeadTracking(uiMode, null)
        }

        @JvmStatic
        fun fetchGain() {
            GaiaBleClient.getInstance().fetchGain(object : GaiaBleClient.DeviceControlCallback {
                override fun onGainResult(level: Int) { gainLevel = level; bumpVersion() }
                override fun onDeviceControlError(message: String) { Log.w(TAG, "fetchGain: $message") }
            })
        }

        @JvmStatic
        fun setGain(level: Int) {
            if (level !in 0..2) return
            gainLevel = level; bumpVersion()
            GaiaBleClient.getInstance().setGain(level, null)
        }

        @JvmStatic
        fun fetchLed() {
            GaiaBleClient.getInstance().fetchLed(object : GaiaBleClient.DeviceControlCallback {
                override fun onLedResult(state: Int) { ledState = state; bumpVersion() }
                override fun onDeviceControlError(message: String) { Log.w(TAG, "fetchLed: $message") }
            })
        }

        @JvmStatic
        fun setLed(state: Int) {
            ledState = state; bumpVersion()
            GaiaBleClient.getInstance().setLed(state, null)
        }

        @JvmStatic
        fun fetchAll() {
            try {
                val c = GaiaBleClient.getInstance()
                if (c.hasSpatialSupport()) fetchSpatial()
                if (c.hasGainSupport()) fetchGain()
                if (c.hasLedSupport()) fetchLed()
            } catch (e: Exception) { Log.w(TAG, "fetchAll error", e) }
        }

        @JvmStatic
        fun reset() {
            spatialState = -1; headTracking = -1; gainLevel = -1; ledState = -1
        }
    }
}
