package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Background monitoring starts on boot/connection events; its fallback alarm never wakes the CPU.
 * Battery exemptions remain a user choice, including on rooted devices.
 */
object KeepAlive {

    private const val TAG = "KeepAlive"

    /** 「是否已经问过白名单」的标记：只主动弹一次系统窗，不骚扰用户。 */
    private const val SP_ASKED = "whitelist_asked"

    /** 保活总开关：沿用「后台监听」总开关（enable && auto_service）。 */
    @JvmStatic
    fun enabled(ctx: Context?): Boolean {
        val c = ctx ?: return false
        val sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        return sp.getBoolean("enable", true) && sp.getBoolean("auto_service", true)
    }

    // Persist intentional idle so an already-queued alarm cannot mistake it for process death.
    private const val SP_IDLE = "monitor_idle"

    @JvmStatic
    fun shouldRecover(ctx: Context): Boolean = enabled(ctx) &&
            !ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean(SP_IDLE, false)

    @JvmStatic
    fun markIdle(ctx: Context) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean(SP_IDLE, true).apply()
        AliveReceiver.cancel(ctx)
    }

    @JvmStatic
    fun wake(ctx: Context) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean(SP_IDLE, false).apply()
    }

    /** Keep all settings screens and onboarding on the same start/stop path. */
    @JvmStatic
    fun setEnabled(c: Context, enabled: Boolean) {
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putBoolean("enable", enabled).putBoolean("auto_service", enabled)
            .putBoolean(SP_IDLE, !enabled).apply()
        if (enabled) {
            try {
                c.startService(Intent(c, HeadsetDetectService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start monitor failed", e)
            }
        } else {
            AliveReceiver.cancel(c)
            try {
                c.stopService(Intent(c, HeadsetDetectService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stop monitor failed", e)
            }
            GaiaBleClient.instance?.shutdown()
        }
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
     * 幂等入口：排下一次非唤醒看门狗，不修改用户的电池优化设置。
     * App 启动、服务创建、开机自启都会调它。
     */
    @JvmStatic
    fun ensure(ctx: Context?) {
        val c = ctx ?: return
        if (!shouldRecover(c)) {
            AliveReceiver.cancel(c)
            return
        }
        try {
            AliveReceiver.scheduleNext(c)
        } catch (t: Throwable) {
            Log.w(TAG, "schedule keepalive failed: $t")
        }
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

}
