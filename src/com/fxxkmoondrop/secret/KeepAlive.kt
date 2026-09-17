package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 保活（3.0.5 起默认常开，不再需要 Root，也不再保留开关）。
 *
 * 组成：
 * 1. 开机自启 —— `BootReceiver`（BOOT_COMPLETED）+ 服务拉起，无需 Root；
 * 2. 看门狗 —— `AliveReceiver` 30 秒 AlarmManager + `START_STICKY` 兜底，无需 Root；
 * 3. 电池优化白名单 —— 防 Doze 冻结，改用系统弹窗
 *    `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，同样**无需 Root**
 *    （旧实现只能用 `dumpsys deviceidle whitelist +pkg` 走 Root，才需要授权）。
 *
 * 有 Root 时仍额外做两件纯增强（静默，失败不影响）：进白名单 + appops 放开后台运行。
 * 也就是说：**Root 从此只是可选增强，不再是保活的前提**。
 */
object KeepAlive {

    private const val TAG = "KeepAlive"

    /** 「是否已经问过白名单」的标记：只主动弹一次系统窗，不骚扰用户。 */
    private const val SP_ASKED = "whitelist_asked"

    /** 是否有 root 时做过静默增强（每进程一次）。 */
    @Volatile private var rootEnhanceDone = false

    /** 保活总开关：沿用「后台监听」总开关（enable && auto_service）。 */
    @JvmStatic
    fun enabled(ctx: Context?): Boolean {
        val c = ctx ?: return false
        val sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        return sp.getBoolean("enable", true) && sp.getBoolean("auto_service", true)
    }

    /** 是否已在电池优化白名单里。 */
    @JvmStatic
    fun isWhitelisted(ctx: Context?): Boolean {
        val c = ctx ?: return false
        return try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm != null && pm.isIgnoringBatteryOptimizations(c.packageName)
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * 幂等入口：排下一次看门狗 + （有 root 时）静默做系统级增强。
     * App 启动、服务创建、开机自启都会调它。
     */
    @JvmStatic
    fun ensure(ctx: Context?) {
        val c = ctx ?: return
        if (!enabled(c)) return
        try {
            AliveReceiver.scheduleNext(c)
        } catch (t: Throwable) {
            Log.w(TAG, "schedule keepalive failed: $t")
        }
        enhanceWithRootIfPossible(c)
    }

    /**
     * 无 root 路径：请求加入电池优化白名单（系统弹窗，用户点一下即可）。
     * 只在「还没进白名单且没问过」时弹一次；用户拒绝过就不再骚扰，
     * 之后可在「设置 → 检查权限」里手动再点。
     */
    @JvmStatic
    fun requestWhitelistOnce(act: Activity?) {
        val a = act ?: return
        if (isWhitelisted(a)) return
        val sp = a.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (sp.getBoolean(SP_ASKED, false)) return
        sp.edit().putBoolean(SP_ASKED, true).apply()
        requestWhitelist(a)
    }

    /** 手动请求（权限检查页点击时用）：直接弹系统白名单确认框。 */
    @JvmStatic
    fun requestWhitelist(act: Activity?) {
        val a = act ?: return
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:" + a.packageName)
            a.startActivity(i)
            Log.i(TAG, "request ignore battery optimizations")
        } catch (t: Throwable) {
            Log.w(TAG, "request whitelist failed: $t")
            // 厂商 ROM 上该动作可能不存在，回退到电池优化列表页
            try {
                a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) { }
        }
    }

    /** 有 root 时的纯增强：deviceidle 白名单 + appops 后台运行（静默，每进程一次）。 */
    private fun enhanceWithRootIfPossible(ctx: Context) {
        if (rootEnhanceDone) return
        if (!enabled(ctx)) return
        rootEnhanceDone = true
        Thread {
            try {
                if (!RootShell.isAvailable()) return@Thread
                val pkg = ctx.packageName
                val cmd = "dumpsys deviceidle whitelist +" + pkg + "; " +
                        "appops set " + pkg + " RUN_IN_BACKGROUND allow; " +
                        "appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; " +
                        "appops set " + pkg + " START_FOREGROUND allow; echo DONE"
                val out = RootShell.exec(cmd)
                Log.i(TAG, "root 保活增强: " + (if (out != null && out.contains("DONE")) "ok" else "fail"))
            } catch (t: Throwable) {
                Log.w(TAG, "root enhance failed: $t")
            }
        }.start()
    }
}
