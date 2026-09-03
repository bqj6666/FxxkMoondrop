package com.fxxkmoondrop.secret

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.HashSet

class PopupGate {
    companion object {
        private const val TAG = "MoondropHeadset"
        private val connectedShown = HashSet<String?>()
        private val disconnectedShown = HashSet<String?>()
        /** alpha2.41.6: 用户主动关闭弹窗后，本次连接（断开前）不再自动重弹 */
        private val userClosedKeys = HashSet<String?>()

        // ── Google Fast Pair 弹窗（FastPairHook 模块）──
        private const val ACTION_FP_TRIGGER = "com.fxxkmoondrop.secret.FASTPAIR_TRIGGER"
        private const val EXTRA_FP_DEVICE_NAME = "device_name"
        private const val EXTRA_FP_BATTERY_LEFT = "battery_left"
        private const val EXTRA_FP_BATTERY_RIGHT = "battery_right"

        // alpha1.17: 模拟数据隔离——模拟连接（SIM_NAME/SIM_MAC）不允许污染真实弹窗流量
        private const val SIM_MAC_FAKE = "AA:BB:CC:DD:EE:FF"

        @JvmStatic
        fun isSimKey(address: String?, name: String?): Boolean {
            return SIM_MAC_FAKE == address
        }

        /** 当前是否有耳机处于连接状态 */
        @JvmField
        @Volatile
        var headsetConnected = false

        // ── alpha1.12: 连接弹窗延迟（等 GAIA 左右耳电量就绪再弹）──
        private val popupHandler = Handler(Looper.getMainLooper())

        private const val CONNECT_POPUP_TIMEOUT_MS = 30000L
        private var appContext: Context? = null
        private var pendingName: String? = null
        private var pendingAddr: String? = null
        private var pendingActive = false

        private val pendingTimeout = Runnable { onPendingTimeout() }

        private fun onPendingTimeout() {
            synchronized(PopupGate::class.java) {
                if (!pendingActive) return
                pendingActive = false
                val key = pendingName ?: pendingAddr
                connectedShown.add(key)
                val ctx = appContext
                if (ctx != null) {
                    showConnectedPopup(ctx, pendingName, pendingAddr)
                    Log.i(TAG, "deferred connected popup (timeout) for $pendingName")
                }
            }
        }

        /** alpha2.38: 连接弹窗统一走 Google Fast Pair（TRIGGER 广播） */
        private fun showConnectedPopup(c: Context, name: String?, address: String?) {
            try {
                val i = Intent(ACTION_FP_TRIGGER)
                i.putExtra(EXTRA_FP_DEVICE_NAME, name)
                var addr = address
                if (addr == null) {
                    try { addr = GaiaBleClient.getInstance().deviceAddress } catch (_: Throwable) { }
                }
                var batL: String? = null
                var batR: String? = null
                var gaiaAddr: String? = null
                try { gaiaAddr = GaiaBleClient.getInstance().deviceAddress } catch (_: Throwable) { }
                if (addr != null) {
                    var l = BatteryStore.getGaiaLeft(addr)
                    var r = BatteryStore.getGaiaRight(addr)
                    if (l < 0 && r < 0 && gaiaAddr != null && gaiaAddr != addr) {
                        l = BatteryStore.getGaiaLeft(gaiaAddr)
                        r = BatteryStore.getGaiaRight(gaiaAddr)
                    }
                    if (l < 0) l = BatteryStore.getLeft(addr)
                    if (r < 0) r = BatteryStore.getRight(addr)
                    if (l < 0 && gaiaAddr != null && gaiaAddr != addr) l = BatteryStore.get(gaiaAddr)
                    if (r < 0 && gaiaAddr != null && gaiaAddr != addr) r = BatteryStore.get(gaiaAddr)
                    if (l >= 0) batL = l.toString()
                    if (r >= 0) batR = r.toString()
                } else if (gaiaAddr != null) {
                    val l = BatteryStore.getGaiaLeft(gaiaAddr)
                    val r = BatteryStore.getGaiaRight(gaiaAddr)
                    if (l >= 0) batL = l.toString()
                    if (r >= 0) batR = r.toString()
                }
                if (batL != null) i.putExtra(EXTRA_FP_BATTERY_LEFT, batL)
                if (batR != null) i.putExtra(EXTRA_FP_BATTERY_RIGHT, batR)
                c.sendBroadcast(i)
                Log.i(TAG, "fastpair trigger sent for $name"
                        + (if (batL != null) " L=$batL" else "") + (if (batR != null) " R=$batR" else ""))
            } catch (t: Throwable) {
                Log.e(TAG, "fastpair trigger fail: $t")
            }
        }

        @JvmStatic
        @Synchronized
        fun tryShowConnected(c: Context, address: String?, name: String?): Boolean {
            if (!DeviceMatcher.isMoondrop(name)) {
                Log.i(TAG, "connected popup skip (not Moondrop): $name")
                return false
            }
            val key = if (isSimKey(address, name)) address else (name ?: address)
            if (userClosedKeys.contains(key)) {
                Log.i(TAG, "connected popup skip (user closed): $name")
                return false
            }
            if (connectedShown.contains(key)) return false
            connectedShown.add(key)
            disconnectedShown.remove(key)
            headsetConnected = true
            showConnectedPopup(c, name, address)
            Log.i(TAG, "connected popup for $name")
            return true
        }

        @JvmStatic
        @Synchronized
        fun tryShowConnectedDeferred(c: Context, address: String?, name: String?): Boolean {
            if (!DeviceMatcher.isMoondrop(name)) {
                Log.i(TAG, "deferred connected popup skip (not Moondrop): $name")
                return false
            }
            if (isSimKey(address, name)) {
                Log.i(TAG, "tryShowConnectedDeferred sim key ignored: $name ($address)")
                return false
            }
            val key = name ?: address
            if (userClosedKeys.contains(key)) {
                Log.i(TAG, "deferred connected popup skip (user closed): $name")
                return false
            }
            if (connectedShown.contains(key)) return false
            appContext = c.applicationContext
            pendingName = name
            pendingAddr = address
            pendingActive = true
            disconnectedShown.remove(key)
            headsetConnected = true
            popupHandler.removeCallbacks(pendingTimeout)
            popupHandler.postDelayed(pendingTimeout, CONNECT_POPUP_TIMEOUT_MS)
            Log.i(TAG, "deferred connected popup queued for $name ($address)")
            flushPendingIfReady()
            return true
        }

        @JvmStatic
        @Synchronized
        fun flushPendingIfReady() {
            if (!pendingActive) return
            val gaiaAddr = GaiaBleClient.getInstance().deviceAddress ?: return
            val l = BatteryStore.getGaiaLeft(gaiaAddr)
            val r = BatteryStore.getGaiaRight(gaiaAddr)
            if (l < 0 || r < 0) return
            pendingActive = false
            popupHandler.removeCallbacks(pendingTimeout)
            val key = pendingName ?: pendingAddr
            connectedShown.add(key)
            val ctx = appContext
            if (ctx != null) {
                showConnectedPopup(ctx, pendingName, pendingAddr)
                Log.i(TAG, "deferred connected popup (gaia ready L=$l% R=$r%) for $pendingName")
            }
        }

        @JvmStatic
        @Synchronized
        fun cancelPending() {
            pendingActive = false
            popupHandler.removeCallbacks(pendingTimeout)
        }

        /** alpha2.41.6: 用户主动关闭弹窗后登记该设备，本次连接断开前不再自动重弹 */
        @JvmStatic
        @Synchronized
        fun markUserClosed(address: String?, name: String?) {
            val key = if (isSimKey(address, name)) address else (name ?: address)
            if (key != null) userClosedKeys.add(key)
            cancelPending()
            Log.i(TAG, "user closed popup, suppress reconnect: " + name + " (" + address + ")")
        }

        /** alpha2.38: 断开不再弹窗（Google Fast Pair 无断开弹窗），仅更新内部状态 */
        @JvmStatic
        @Synchronized
        fun tryShowDisconnected(c: Context, address: String?, name: String?): Boolean {
            if (!DeviceMatcher.isMoondrop(name)) {
                Log.i(TAG, "disconnected skip (not Moondrop): $name")
                return false
            }
            cancelPending()
            val key = if (isSimKey(address, name)) address else (name ?: address)
            if (disconnectedShown.contains(key)) return false
            disconnectedShown.add(key)
            connectedShown.remove(key)
            userClosedKeys.remove(key)
            headsetConnected = false
            Log.i(TAG, "disconnected (Google Fast Pair only, no popup): $name")
            return true
        }

        @JvmStatic
        @Synchronized
        fun clear(address: String?, name: String?) {
            if (address != null) {
                connectedShown.remove(address)
                disconnectedShown.remove(address)
            }
            if (name != null) {
                connectedShown.remove(name)
                disconnectedShown.remove(name)
            }
        }
    }
}
