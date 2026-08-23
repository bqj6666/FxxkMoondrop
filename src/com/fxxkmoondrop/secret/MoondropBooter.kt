package com.fxxkmoondrop.secret

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * v3.16/v3.17: 自动启动 Moondrop App 后台 GAIA 通讯服务。
 * 检测到 Moondrop 耳机连接时调用 maybeStart()：
 *  - 30 秒冷却，避免频繁拉起
 *  - 进程已在运行则不重复启动
 *  - 通过 su 执行 am start，带 fxxk_silent=true（hook 会抑制 UI）与 --exclude-from-recents（不留最近任务）
 * 需要 Root 权限。
 */
class MoondropBooter {
    companion object {
        private const val TAG = "MoondropBooter"
        private const val PKG = "com.moondroplab.moondrop.moondrop_app"
        private const val ACTIVITY = "com.moondroplab.moondrop.moondrop_app.MainActivity"
        private const val COOLDOWN_MS = 30000L
        private var lastLaunch = 0L

        @JvmStatic
        fun maybeStart(context: Context?) {
            if (context == null) return
            if (!context.getSharedPreferences("cfg", 0).getBoolean("auto_gaia", true)) return
            val now = System.currentTimeMillis()
            if (now - lastLaunch < COOLDOWN_MS) return
            if (isProcessRunning()) return
            try {
                val cmd = "am start -n $PKG/$ACTIVITY --ez fxxk_silent true --exclude-from-recents"
                val rc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
                lastLaunch = System.currentTimeMillis()
                Log.i(TAG, "launch result=$rc")
            } catch (e: Exception) {
                Log.e(TAG, "launch failed: $e")
            }
        }

        private fun isProcessRunning(): Boolean {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pidof $PKG"))
                val line = BufferedReader(InputStreamReader(p.inputStream)).readLine()
                p.waitFor()
                return line != null && line.trim().isNotEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "pidof check failed: $e")
                return true // 无法确认时保守处理，不重复拉起
            }
        }
    }
}
