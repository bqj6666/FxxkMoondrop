package com.fxxkmoondrop.secret

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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

        // 连接弹窗总开关（设置页「功能 → 连接弹窗」）。默认开，关掉后所有弹窗路径统一不弹。
        private const val PREF_POPUP = "feat_popup"

        /** 连接弹窗是否启用（用户偏好，缺省视为启用）。 */
        @JvmStatic
        fun isPopupEnabled(c: Context?): Boolean {
            val ctx = c ?: return true
            return try {
                ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean(PREF_POPUP, true)
            } catch (_: Throwable) { true }
        }

        // ── 3.2.12: 弹窗时机 ──────────────────────────────────────────
        // 四档设置都读同一份 SP（"cfg"），与设置页、引导页共用。

        /** 是否等 GAIA 就绪（读到左右耳电量）再弹。缺省 true = 与 3.2.11 行为一致。 */
        @JvmStatic
        fun waitGaia(c: Context?): Boolean = cfg(c)?.getBoolean("popup_wait_gaia", true) ?: true

        /** 连接后延迟多少秒再弹（0 = 不延迟）。 */
        @JvmStatic
        fun delaySec(c: Context?): Int = cfg(c)?.getInt("popup_delay_sec", 0) ?: 0

        /** 锁屏时是否不弹。缺省 true。 */
        @JvmStatic
        fun skipLocked(c: Context?): Boolean = cfg(c)?.getBoolean("popup_skip_locked", true) ?: true

        /** 横屏时是否不弹。缺省 true。 */
        @JvmStatic
        fun skipLandscape(c: Context?): Boolean = cfg(c)?.getBoolean("popup_skip_landscape", true) ?: true

        private fun cfg(c: Context?): android.content.SharedPreferences? = try {
            c?.applicationContext?.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        } catch (_: Throwable) { null }

        /** 当前是否处于锁屏（读取失败一律当作「没锁」，宁可多弹一次也不要该弹不弹）。 */
        private fun isLocked(c: Context): Boolean = try {
            (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        } catch (_: Throwable) { false }

        /** 当前是否横屏。 */
        private fun isLandscape(c: Context): Boolean = try {
            c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        } catch (_: Throwable) { false }

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

        /** 延迟弹窗的排队任务（同一时刻只有一个，连接弹窗本就不支持并发多设备） */
        private var pendingDelay: Runnable? = null

        private fun cancelDelay() {
            pendingDelay?.let { popupHandler.removeCallbacks(it) }
            pendingDelay = null
        }

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
        /** 弹窗入口：先按用户设置的延迟排队，再交给 [emitPopup] 真正发出。 */
        private fun showConnectedPopup(c: Context, name: String?, address: String?) {
            cancelDelay()
            val sec = delaySec(c)
            if (sec <= 0) {
                emitPopup(c, name, address)
                return
            }
            // 捕获局部变量：延迟期间 pendingName / pendingAddr 可能被下一次连接改写
            val ctx = c.applicationContext
            val r = Runnable {
                pendingDelay = null
                emitPopup(ctx, name, address)
            }
            pendingDelay = r
            popupHandler.postDelayed(r, sec * 1000L)
            Log.i(TAG, "connected popup delayed " + sec + "s for " + name)
        }

        /**
         * 真正发出 TRIGGER 广播。
         *
         * 本函数是所有弹窗路径（立即 / 延迟 / 超时 / GAIA 就绪）唯一的出口，
         * 时机门禁放在这里一处即覆盖全部。
         */
        private fun emitPopup(c: Context, name: String?, address: String?) {
            try {
                if (!isPopupEnabled(c)) {
                    Log.i(TAG, "connected popup suppressed (disabled in settings): $name")
                    return
                }
                // 3.2.12 时机门禁：锁屏 / 横屏时不弹。
                // 在这里判而不是在触发点判，是因为排队延迟结束时条件可能已经变了
                //（比如延迟那两秒里用户熄了屏），出口处再判一次才是真正生效的那一次。
                if (skipLocked(c) && isLocked(c)) {
                    Log.i(TAG, "connected popup suppressed (screen locked): $name")
                    return
                }
                if (skipLandscape(c) && isLandscape(c)) {
                    Log.i(TAG, "connected popup suppressed (landscape): $name")
                    return
                }
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

            // 3.2.12: 用户选择「连上就弹」时不必等 GAIA，直接进出口。
            // 那一刻左右耳电量通常还没读到，卡片会先出现、电量随后由 FastPairHook 侧刷新；
            // 代价是可能短暂没有电量数字，换来「戴上就看见」的即时反馈 —— 这正是该选项的取舍。
            if (!waitGaia(c)) {
                appContext = c.applicationContext
                disconnectedShown.remove(key)
                headsetConnected = true
                connectedShown.add(key)
                showConnectedPopup(c, name, address)
                Log.i(TAG, "connected popup immediate (waitGaia=off) for $name")
                return true
            }

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
            cancelDelay()   // 已在延迟排队中的弹窗也要取消：断开之后不该再冒出来
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
