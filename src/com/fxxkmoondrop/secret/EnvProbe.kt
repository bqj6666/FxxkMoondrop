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
            // 2) 惯例位置
            dirs.add("/sbin")
            dirs.add("/su/bin")
            dirs.add("/system/bin")
            dirs.add("/system/xbin")
            dirs.add("/system/sbin")
            dirs.add("/system/bin/magisk")
            dirs.add("/debug_ramdisk")
            // 3.0.4: APatch 系（含 FolkPatch / KernelSU 变体）的入口与工作目录。
            // 注意 /data/adb 实测是 drwx------ root（普通 App 读不进去），列在这里只作
            // 「碰到放宽权限的 ROM 就多一次命中机会」，真正起作用的是 /system/bin/kp 这类世界可读路径。
            dirs.add("/data/adb")
            dirs.add("/data/adb/magisk")
            dirs.add("/data/adb/ap")
            dirs.add("/data/adb/ap/bin")
            dirs.add("/data/adb/fp")
            dirs.add("/data/adb/fp/bin")
            dirs.add("/data/adb/ksu")
            dirs.add("/data/adb/ksu/bin")
            for (d in dirs) {
                try {
                    if (File(d, "su").exists()) return true
                    // 3.0.4: APatch / FolkPatch 默认把 root 入口装成 kp
                    // （反编译 FolkPatch uapi/scdefs.h：SU_PATH="/system/bin/kp"）。
                    // 旧实现只认 su/magisk，导致这类设备被判成「未检测到 Root」。
                    if (File(d, "kp").exists()) return true
                    if (File(d, "magisk").exists()) return true
                    if (File(d, "magisk64").exists()) return true
                } catch (_: Throwable) { }
            }
            return false
        }

        /**
         * FastPairHook（LSPosed 模块）是否激活：向 GMS 发 PING，收到 PONG 即激活。
         * 阻塞等待（PING_TIMEOUT_MS，1.8s），请在子线程调用。
         *
         * 缓存策略（3.0.5 同 RootShell）：**正结果永久缓存**（激活了就不会变）；
         * 负结果可被 [retryHookProbe] 清掉，用于「刚在 LSPosed 里启用模块」的场景 ——
         * 否则一次未激活会被记到进程结束，用户启用后仍被当成无模块。
         */
        @JvmStatic
        fun isFastPairHookActive(ctx: Context?): Boolean {
            sHookActive?.let { return it }
            val h = pingHook(ctx)
            sHookActive = h
            return h
        }

        /** 非阻塞读 hook 激活缓存：null = 还没探测过（调用方按旧行为保守处理）。 */
        @JvmStatic
        fun hookActiveCached(): Boolean? = sHookActive

        /** 清掉 hook 的负结果，下次调用重新 PING（用于用户显式「重新检测」）。 */
        @JvmStatic
        fun retryHookProbe() {
            synchronized(this) {
                if (sHookActive != true) sHookActive = null
            }
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

        /**
         * 「依赖 LSPosed Hook 的功能」是否应视为可用。
         *
         * 官方面板注入、蓝牙详情页面板、弹窗图标这几项由 **GMS 进程里的 Hook** 承担，
         * 与 App 自身有没有 root 无关：没 root 但模块已启用 = 照常可用。
         * 所以只有**确定**未激活（false）才算不可用；尚未探测（null）不得当作不可用 ——
         * 否则一进设置页就把整片功能置灰（3.0.5 的「没 root 就被禁用」即源于此）。
         *
         * 无阻塞，可主线程调用。
         */
        @JvmStatic
        fun hookUsable(): Boolean = hookActiveCached() != false

        /**
         * 当前运行模式**名称**（设置页那一行的标题）：Root 模式 / 模块模式 / 无 Root 模式。
         *
         * 由「有无可用 root 入口」×「FastPairHook 模块是否已激活」两轴决定；
         * 模块尚未探测（null）时如实写「检测中」，探测落定后由设置页就地刷新。
         * 无阻塞：只读 root 与 hook 的缓存结果，可主线程调用。
         */
        @JvmStatic
        fun runModeName(ctx: Context?): String {
            if (ctx == null) return ""
            return when {
                hookActiveCached() == null -> Lang.t(ctx, "检测中…", "Detecting…")
                isRooted() -> Lang.t(ctx, "Root 模式", "Root mode")
                hookActiveCached() == true -> Lang.t(ctx, "模块模式", "Module mode")
                else -> Lang.t(ctx, "无 Root 模式", "No-root mode")
            }
        }

        /** 当前运行模式的**一句说明**（设置页那一行的副标题）。 */
        @JvmStatic
        fun runModeDetail(ctx: Context?): String {
            if (ctx == null) return ""
            val rooted = isRooted()
            return when (hookActiveCached()) {
                true -> if (rooted)
                    Lang.t(ctx, "FastPairHook 模块已激活；Root 增强功能可用",
                            "FastPairHook module active; root enhancements available")
                else
                    Lang.t(ctx, "FastPairHook 模块已激活，无需 Root；Root 增强功能不可用",
                            "FastPairHook module active without root; root enhancements unavailable")
                false -> if (rooted)
                    Lang.t(ctx, "FastPairHook 模块未激活，将使用内置自扫",
                            "FastPairHook module inactive; built-in scan will be used")
                else
                    Lang.t(ctx, "仅通知栏与主界面控制降噪（GAIA 直连）",
                            "ANC control from the notification and main UI only (GAIA direct)")
                null -> Lang.t(ctx, "正在检测 FastPairHook 模块状态…", "Detecting FastPairHook module…")
            }
        }
    }
}
