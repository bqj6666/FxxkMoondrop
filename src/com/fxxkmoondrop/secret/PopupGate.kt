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

        // ── alpha1.15: Google Fast Pair 弹窗（FastPairHook 模块）──
        private const val ACTION_FP_TRIGGER = "com.fxxkmoondrop.secret.FASTPAIR_TRIGGER"
        private const val EXTRA_FP_DEVICE_NAME = "device_name"
        private const val EXTRA_FP_BATTERY_LEFT = "battery_left"
        private const val EXTRA_FP_BATTERY_RIGHT = "battery_right"

        /** 连接弹窗样式开关（true=Google Fast Pair 半屏弹窗，false=应用自带弹窗），默认 Google 弹窗 */
        const val CFG_FASTPAIR_POPUP = "fastpair_popup"

        // alpha1.17: 模拟数据隔离——模拟连接（SIM_NAME/SIM_MAC）不允许污染真实弹窗流量
        private const val SIM_MAC_FAKE = "AA:BB:CC:DD:EE:FF"

        // 注意：不能用名字匹配模拟（真实耳机名就叫 "Moondrop Golden Ages 2"，会误拦真实流量）
        @JvmStatic
        fun isSimKey(address: String?, name: String?): Boolean {
            return SIM_MAC_FAKE == address
        }

        /** 当前是否有耳机处于连接状态（fixF 同款，用于主界面降噪区块显隐） */
        @JvmField
        @Volatile
        var headsetConnected = false

        // ── alpha1.12: 连接弹窗延迟（等 GAIA 左右耳电量就绪再弹）──
        private val popupHandler = Handler(Looper.getMainLooper())

        /** alpha1.14fix2: GAIA 建立超时兜底：等待 GAIA 连接+电量就绪（实测链路最长约 26s），超时才兜底弹（显示系统电量/--%） */
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

        /** alpha1.15: 连接弹窗统一入口——按设置分发到 Google Fast Pair（TRIGGER 广播）或应用自带弹窗 */
        private fun showConnectedPopup(c: Context, name: String?, address: String?) {
            if (useGooglePopup(c)) {
                try {
                    val i = Intent(ACTION_FP_TRIGGER)
                    i.putExtra(EXTRA_FP_DEVICE_NAME, name)
                    var addr = address
                    if (addr == null) {
                        try { addr = GaiaBleClient.getInstance().deviceAddress } catch (_: Throwable) { }
                    }
                    // alpha1.17: 电量获取对齐 PopupOverlay——先按传入地址查 GAIA 左右耳，
                    // 查不到再用 GAIA 实际连接地址（LE）查（BR/EDR 地址下 GAIA 电量在 LE 地址表里），
                    // 再不行用系统单值兜底（也走 gaiaAddr 映射）
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
                        if (l < 0) l = BatteryStore.getLeft(addr)   // 兜底：系统电量
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
                    Log.e(TAG, "fastpair trigger fail, fallback overlay: $t")
                    PopupOverlay.show(c, name, address, true)
                }
                return
            }
            PopupOverlay.show(c, name, address, true)
        }

        /** alpha1.15: 读取连接弹窗样式设置（默认 Google 弹窗） */
        private fun useGooglePopup(c: Context): Boolean {
            return try {
                c.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean(CFG_FASTPAIR_POPUP, true)
            } catch (_: Throwable) {
                true
            }
        }

        @JvmStatic
        @Synchronized
        fun tryShowConnected(c: Context, address: String?, name: String?): Boolean {
            // alpha1.14+ 设备过滤：仅 Moondrop 品牌耳机弹窗（DeviceMatcher 品牌匹配，不写死型号）
            if (!DeviceMatcher.isMoondrop(name)) {
                Log.i(TAG, "connected popup skip (not Moondrop): $name")
                return false
            }
            // alpha1.9: 双模设备（GA2 有 BR/EDR + LE 两个地址）按 name 去重，避免同设备弹两次
            // alpha1.17: 模拟按钮显式入口（isSimKey 不拦这里）；真实路径走 tryShowConnectedDeferred（已拦）
            // alpha1.14fix3: 模拟 key 隔离——模拟连接用地址作 key（SIM_NAME 与真实耳机同名，避免污染真实去重表）
            val key = if (isSimKey(address, name)) address else (name ?: address)
            if (connectedShown.contains(key)) return false
            connectedShown.add(key)
            disconnectedShown.remove(key)
            headsetConnected = true
            showConnectedPopup(c, name, address)
            Log.i(TAG, "connected popup for $name")
            return true
        }

        /** alpha1.12: 连接弹窗延迟——入队等待 GAIA 左右耳电量就绪（flushPendingIfReady）或超时兜底 */
        @JvmStatic
        @Synchronized
        fun tryShowConnectedDeferred(c: Context, address: String?, name: String?): Boolean {
            // alpha1.14+ 设备过滤：仅 Moondrop 品牌耳机弹窗（DeviceMatcher 品牌匹配，不写死型号）
            if (!DeviceMatcher.isMoondrop(name)) {
                Log.i(TAG, "deferred connected popup skip (not Moondrop): $name")
                return false
            }
            // alpha1.17: 模拟 key 隔离
            if (isSimKey(address, name)) {
                Log.i(TAG, "tryShowConnectedDeferred sim key ignored: $name ($address)")
                return false
            }
            // alpha1.9: 双模设备按 name 去重
            val key = name ?: address
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
            // alpha1.14fix3: GAIA 已连接且电量已缓存时立即弹（不干等超时）
            flushPendingIfReady()
            return true
        }

        /** alpha1.12: GAIA 左右耳电量都就绪时调用（HeadsetDetectService.onBatteryLevel），立即弹连接窗 */
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

        /** alpha1.12: 取消待弹连接窗（断开时调用） */
        @JvmStatic
        @Synchronized
        fun cancelPending() {
            pendingActive = false
            popupHandler.removeCallbacks(pendingTimeout)
        }

        @JvmStatic
        @Synchronized
        fun tryShowDisconnected(c: Context, address: String?, name: String?): Boolean {
            // alpha1.14+ 设备过滤：仅 Moondrop 品牌耳机弹窗（DeviceMatcher 品牌匹配，不写死型号）
            if (!DeviceMatcher.isMoondrop(name)) {
                Log.i(TAG, "disconnected popup skip (not Moondrop): $name")
                return false
            }
            // alpha1.12: 断开时取消未弹的连接窗（避免断开后还弹连接）
            cancelPending()
            // alpha1.9: 与 tryShowConnected 对称，按 name 去重
            // alpha1.14fix3: 模拟 key 隔离（同上）
            val key = if (isSimKey(address, name)) address else (name ?: address)
            if (disconnectedShown.contains(key)) return false
            disconnectedShown.add(key)
            connectedShown.remove(key)
            headsetConnected = false
            PopupOverlay.show(c, name, address, false)
            Log.i(TAG, "disconnected popup for $name")
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
