package com.fxxkmoondrop.secret

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * v3.17: 事件驱动版。
 * 监听开机广播（保留）与蓝牙 hook 组件发出的 BT_EVENT：
 *  - 开机：启动后台监听服务（保留 v3.15 功能）
 *  - BT_EVENT connected：Moondrop 耳机已连接 -> 唤醒服务做增量轮询
 *  - BT_EVENT disconnected：已断开 -> 唤醒服务清理状态
 * 服务本身带 30 秒无连接自动休眠，不驻留。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (i == null) return
        val action = i.action
        Log.i(TAG, "BootReceiver: $action")
        try {
            if (Intent.ACTION_BOOT_COMPLETED == action) {
                if (c.getSharedPreferences("cfg", 0).getBoolean("enable", true)) {
                    c.startService(Intent(c, HeadsetDetectService::class.java))
                    Log.i(TAG, "service started on boot")
                }
            } else if (ACTION_BT_EVENT == action) {
                // 蓝牙事件唤醒（连接/断开时由 Xposed hook 发出）
                c.startService(Intent(c, HeadsetDetectService::class.java)
                        .setAction(ACTION_BT_EVENT)
                        .putExtra("evt", i.getStringExtra("evt")))
                Log.i(TAG, "service woken by BT_EVENT")
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed: $e")
        }
    }

    companion object {
        private const val TAG = "MoondropHeadset"
        const val ACTION_BT_EVENT = "com.fxxkmoondrop.secret.BT_EVENT"
    }
}
