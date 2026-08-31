package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 查找当前已连接的 Moondrop 耳机（名字含 moondrop）。
 * alpha1.36: 修复 ANR —— 原 getConnectedMac 在主线程执行 scanViaProxy（CountDownLatch.await
 * 最长 800ms×2），从设置页返回触发 onResume 时主线程阻塞导致 ANR（/data/anr 实锤）。
 * 现在：主线程只走非阻塞探测（服务代理 + GATT 同步 API），异步补扫完成发 MAC_UPDATED 广播；
 * 结果缓存（内存 + SP），所有线程调用均安全。
 */
class HeadsetGate {
    companion object {
        private const val TAG = "HeadsetGate"

        /** 异步补扫找到 MAC 后广播（extra: mac） */
        const val ACTION_MAC_UPDATED = "com.fxxkmoondrop.secret.MAC_UPDATED"
        private const val SP_LAST_MAC = "last_connected_mac"
        private const val SCAN_THROTTLE_MS = 3000L

        @Volatile
        private var sLastMac: String? = null
        @Volatile
        private var sLastAsyncScanAt = 0L

        /** 返回已连接 Moondrop 耳机的 MAC；未连接返回 null（永不阻塞主线程） */
        @JvmStatic
        fun getConnectedMac(ctx: Context): String? {
            val cached = lastKnown(ctx)
            val mainThread = Looper.myLooper() == Looper.getMainLooper()
            if (mainThread) {
                val mac = quickScan(ctx)
                if (mac != null) {
                    remember(ctx, mac)
                    return mac
                }
                // alpha2.18: 实时探测未命中 -> 陈旧缓存立即作废（修复"耳机已断开仍显示已连接"）
                if (cached != null) {
                    Log.d(TAG, "quickScan miss -> stale cache invalidated: " + cached)
                    clearConnectedMac(ctx)
                }
                asyncRefresh(ctx)
                return null
            }
            val mac = fullScan(ctx)
            if (mac != null) {
                remember(ctx, mac)
                return mac
            }
            // alpha2.18: 全量探测也未命中 -> 陈旧缓存作废
            if (cached != null) {
                Log.d(TAG, "fullScan miss -> stale cache invalidated: " + cached)
                clearConnectedMac(ctx)
            }
            return null
        }

        /** 非阻塞探测：服务共享 proxy + GATT 同步 API（无回调等待） */
        private fun quickScan(ctx: Context): String? {
            try {
                val m = HeadsetDetectService.findMoondropMacWithProxy()
                if (m != null) return m
                val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                if (bm != null) {
                    try {
                        val m2 = scan(bm.getConnectedDevices(BluetoothProfile.GATT))
                        if (m2 != null) return m2
                    } catch (gattE: Exception) {
                        Log.w(TAG, "GATT quick scan skipped: $gattE")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "quickScan failed: $e")
            }
            return null
        }

        /** 全量扫描（原逻辑：服务代理优先 + GATT + A2DP/HEADSET profile proxy，含 await） */
        private fun fullScan(ctx: Context): String? {
            try {
                try {
                    val mac = HeadsetDetectService.findMoondropMacWithProxy()
                    if (mac != null) return mac
                } catch (spE: Exception) {
                    Log.w(TAG, "service proxy scan failed: $spE")
                }
                val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    ?: return null
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
                try {
                    val mac = scan(bm.getConnectedDevices(BluetoothProfile.GATT))
                    if (mac != null) return mac
                } catch (gattE: Exception) {
                    Log.w(TAG, "GATT scan skipped: $gattE")
                }
                try {
                    val mac = scanViaProxy(adapter, ctx, BluetoothProfile.A2DP)
                    if (mac != null) return mac
                } catch (a2dpE: Exception) {
                    Log.w(TAG, "A2DP proxy scan failed: $a2dpE")
                }
                try {
                    val mac = scanViaProxy(adapter, ctx, BluetoothProfile.HEADSET)
                    if (mac != null) return mac
                } catch (hfpE: Exception) {
                    Log.w(TAG, "HEADSET proxy scan failed: $hfpE")
                }
            } catch (e: Exception) {
                Log.w(TAG, "fullScan failed: $e")
            }
            return null
        }

        /** 异步补扫（节流 3s）：找到后缓存并广播 MAC_UPDATED */
        private fun asyncRefresh(ctx: Context) {
            val now = System.currentTimeMillis()
            if (now - sLastAsyncScanAt < SCAN_THROTTLE_MS) return
            sLastAsyncScanAt = now
            val app = ctx.applicationContext
            Thread {
                try {
                    val mac = fullScan(app)
                    if (mac != null) {
                        remember(app, mac)
                        try {
                            app.sendBroadcast(Intent(ACTION_MAC_UPDATED).putExtra("mac", mac))
                            Log.d(TAG, "async scan found: $mac")
                        } catch (e: Exception) {
                            Log.w(TAG, "broadcast failed: $e")
                        }
                    } else {
                        // alpha2.18: 补扫未命中 -> 清陈旧缓存（断连后不再残留假 MAC）
                        val stale = lastKnown(app)
                        if (stale != null) {
                            Log.d(TAG, "async refresh miss -> stale cache invalidated: $stale")
                            clearConnectedMac(app)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "asyncRefresh: $t")
                }
            }.start()
        }

        private fun lastKnown(ctx: Context): String? {
            sLastMac?.let { return it }
            return try {
                ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString(SP_LAST_MAC, null)
            } catch (_: Exception) {
                null
            }
        }

        private fun remember(ctx: Context, mac: String?) {
            if (mac == null) return
            sLastMac = mac
            try {
                ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                        .edit().putString(SP_LAST_MAC, mac).apply()
            } catch (e: Exception) {
                Log.w(TAG, "remember fail: $e")
            }
        }

        /** 外部（ACL 广播/服务）可主动记录已连接 MAC */
        @JvmStatic
        fun noteConnectedMac(ctx: Context, mac: String?) {
            if (mac != null) remember(ctx, mac)
        }

        /** alpha1.40: 耳机断开时清除已连接 MAC 缓存（内存 + SP）。
         *  原实现：断连后 getConnectedMac 主线程分支仍返回旧缓存 ->
         *  主界面 realConnected 永真 -> 降噪面板不隐藏、模拟区不显示。 */
        @JvmStatic
        fun clearConnectedMac(ctx: Context) {
            sLastMac = null
            try {
                ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                        .edit().remove(SP_LAST_MAC).apply()
            } catch (e: Exception) {
                Log.w(TAG, "clear fail: $e")
            }
        }

        /** 通过 profile proxy 获取已连接设备（同步等待 proxy 回调，最多 800ms） */
        private fun scanViaProxy(adapter: BluetoothAdapter, ctx: Context, profile: Int): String? {
            val proxyRef = AtomicReference<BluetoothProfile?>()
            val found = AtomicReference<String?>()
            val latch = CountDownLatch(1)
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        proxyRef.set(proxy)
                        val mac = scan(proxy.connectedDevices)
                        if (mac != null) found.set(mac)
                    } catch (e: Exception) {
                        Log.w(TAG, "proxy($p) getConnectedDevices fail: $e")
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onServiceDisconnected(p: Int) {
                    latch.countDown()
                }
            }
            try {
                if (!adapter.getProfileProxy(ctx, listener, profile)) return null
                latch.await(800, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "getProfileProxy($profile) fail: $e")
            } finally {
                val proxy = proxyRef.get()
                if (proxy != null) {
                    try { adapter.closeProfileProxy(profile, proxy) } catch (_: Exception) { }
                }
            }
            return found.get()
        }

        private fun scan(devices: List<BluetoothDevice>?): String? {
            if (devices == null) return null
            for (d in devices) {
                val n = d.name
                if (n != null && DeviceMatcher.isMoondrop(n)) {
                    return d.address
                }
            }
            return null
        }
    }
}
