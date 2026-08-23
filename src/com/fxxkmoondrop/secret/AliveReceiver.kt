package com.fxxkmoondrop.secret

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

class AliveReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (i == null || ACTION != i.action) return
        // 用户主动停止过监听：不再拉起，也不排下一次
        if (!c.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("enable", true)) {
            Log.i(TAG, "keepalive skipped (user stopped)")
            return
        }
        try {
            c.startService(Intent(c, HeadsetDetectService::class.java))
            Log.i(TAG, "keepalive: service ensured")
        } catch (e: Exception) {
            Log.e(TAG, "keepalive start failed: $e")
        }
        scheduleNext(c)
    }

    companion object {
        private const val TAG = "MoondropHeadset"
        private const val ACTION = "com.fxxkmoondrop.secret.KEEPALIVE"
        private const val INTERVAL_MS = 30000L

        @JvmStatic
        fun cancel(c: Context) {
            try {
                val am = c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val i = Intent(c, AliveReceiver::class.java).setAction(ACTION)
                val pi = PendingIntent.getBroadcast(c, 0, i,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.cancel(pi)
                Log.i(TAG, "keepalive alarm cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "alarm cancel failed: $e")
            }
        }

        @JvmStatic
        fun scheduleNext(c: Context) {
            try {
                val am = c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val i = Intent(c, AliveReceiver::class.java).setAction(ACTION)
                val pi = PendingIntent.getBroadcast(c, 0, i,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + INTERVAL_MS, pi)
                Log.i(TAG, "keepalive alarm scheduled")
            } catch (e: Exception) {
                Log.e(TAG, "alarm schedule failed: $e")
            }
        }
    }
}
