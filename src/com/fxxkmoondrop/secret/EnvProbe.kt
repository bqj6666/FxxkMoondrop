package com.fxxkmoondrop.secret

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * alpha1.34: 运行环境探测（Root / FastPairHook(LSPosed) 激活状态）。
 * 目的：决定「FastPairHook GMS 桥接」与「应用内置 BLE 自扫（备用模式）」二选一，
 *       并供权限自检弹窗显示真实环境状态。
 * 规则（用户约定）：应用内置自扫仅在【未检测到 Root 且 未检测到 LSPosed 模块】的纯净环境下启用。
 * Root 检测只探测文件存在（不执行 su，零副作用）；模块检测用 ping/pong 广播。
 * 注意：isFastPairHookActive 会阻塞等待 PONG（1.8s），必须在子线程调用；
 *       探测结果用 volatile 缓存（不做类锁互斥，避免长阻塞相互卡死）。
 */
class EnvProbe private constructor() {
    companion object {
        /** 探测广播：应用 -> GMS(FastPairHook) */
        const val ACTION_FASTPAIR_PING = "com.fxxkmoondrop.secret.FASTPAIR_PING"

        /** 探测广播：FastPairHook -> 应用 */
        const val ACTION_FASTPAIR_PONG = "com.fxxkmoondrop.secret.FASTPAIR_PONG"

        private const val PKG_GMS = "com.google.android.gms"
        private const val PING_TIMEOUT_MS = 4000L

        @Volatile
        private var sRooted: Boolean? = null
        @Volatile
        private var sHookActive: Boolean? = null

        /** 是否检测到 Root（结果进程内缓存；无阻塞，可主线程调用） */
        @JvmStatic
        fun isRooted(): Boolean {
            sRooted?.let { return it }
            val r = detectRoot()
            sRooted = r
            return r
        }

        private fun detectRoot(): Boolean {
            // 1) 动态遍历 PATH 目录（不硬编码具体设备路径，仅处理系统惯例位置）
            val dirs = ArrayList<String>()
            try {
                val path = System.getenv("PATH")
                if (path != null) {
                    for (d in path.split(":")) {
                        if (d.isNotBlank()) dirs.add(d.trim())
                    }
                }
            } catch (_: Throwable) { }
            // 2) 惯例位置（su / Magisk 常见安装点）
            dirs.add("/sbin")
            dirs.add("/su/bin")
            dirs.add("/system/bin")
            dirs.add("/system/xbin")
            dirs.add("/system/sbin")
            dirs.add("/system/bin/magisk")
            dirs.add("/data/adb")
            dirs.add("/data/adb/magisk")
            for (d in dirs) {
                try {
                    if (File(d, "su").exists()) return true
                    if (File(d, "magisk").exists()) return true
                    if (File(d, "magisk64").exists()) return true
                } catch (_: Throwable) { }
            }
            return false
        }

        /**
         * FastPairHook（LSPosed 模块）是否激活：向 GMS 发 PING，收到 PONG 即激活。
         * 阻塞等待（PING_TIMEOUT_MS，1.8s），请在子线程调用；结果进程内缓存。
         */
        @JvmStatic
        fun isFastPairHookActive(ctx: Context?): Boolean {
            sHookActive?.let { return it }
            val h = pingHook(ctx)
            sHookActive = h
            return h
        }

        private fun pingHook(ctx: Context?): Boolean {
            if (ctx == null) return false
            val got = AtomicBoolean(false)
            try {
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                val r = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent?) {
                        got.set(true)
                        android.util.Log.d("EnvProbe", "PONG received: " + (i?.action ?: "null"))
                    }
                }
                // alpha1.34: 注册/发送不依赖主线程 post（主线程繁忙时会错过 1.8s 窗口），
                // 直接在调用线程注册+发送；注销仍走主线程延迟执行，避免 onReceive 未投递即注销。
                try {
                    val f = IntentFilter(ACTION_FASTPAIR_PONG)
                    if (Build.VERSION.SDK_INT >= 33) {
                        ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
                    } else {
                        ctx.registerReceiver(r, f)
                    }
                    android.util.Log.d("EnvProbe", "PONG receiver registered, sending PING")
                    val ping = Intent(ACTION_FASTPAIR_PING)
                    ping.setPackage(PKG_GMS)
                    ctx.sendBroadcast(ping)
                    android.util.Log.d("EnvProbe", "PING sent -> GMS")
                    // 稍后自动注销，避免泄漏
                    main.postDelayed({
                        try { ctx.unregisterReceiver(r) } catch (_: Throwable) { }
                    }, PING_TIMEOUT_MS + 400)
                } catch (t: Throwable) {
                    android.util.Log.w("EnvProbe", "ping send: " + t.message)
                }
                // 调用线程等待 PONG（分段休眠，响应立即返回）
                val end = System.currentTimeMillis() + PING_TIMEOUT_MS
                while (System.currentTimeMillis() < end && !got.get()) {
                    Thread.sleep(80)
                }
            } catch (t: Throwable) {
                android.util.Log.w("EnvProbe", "pingHook: " + t.message)
            }
            android.util.Log.d("EnvProbe", "hook probe result: " + got.get())
            return got.get()
        }

        /** 纯净环境：未检测到 Root 且未检测到 FastPairHook（允许内置自扫）；阻塞，子线程调用 */
        @JvmStatic
        fun isCleanEnv(ctx: Context?): Boolean {
            return !isRooted() && !isFastPairHookActive(ctx)
        }
    }
}
