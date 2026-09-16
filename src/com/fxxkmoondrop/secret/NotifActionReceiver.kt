package com.fxxkmoondrop.secret

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 第 5 项：ANC 通知按钮的落地端。
 * 通知里的档位按钮 -> 广播到这里 -> AncBridge.setAncMode（GAIA 未就绪时
 * AncBridge 自身会拒绝，不会误发指令）。切完由 GAIA 回调刷新通知高亮。
 */
class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DeviceNotif.ACTION_ANC) return
        val mode = intent.getIntExtra(DeviceNotif.EXTRA_MODE, -1)
        if (mode !in 0..5) return
        Log.i("MoondropNotif", "notif action -> anc mode " + mode)
        try {
            AncBridge.setAncMode(mode)
        } catch (t: Throwable) {
            Log.w("MoondropNotif", "setAncMode from notif fail", t)
        }
    }
}
