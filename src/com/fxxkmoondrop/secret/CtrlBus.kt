package com.fxxkmoondrop.secret

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * alpha2.39: 跨进程状态推送中枢（ContentObserver 推模式）。
 * 模块进程状态源（ANC/DC）变化时调用 postDcChanged()，通过 ContentResolver.notifyChange
 * 通知所有观察 dc_cmd uri 的进程（含 com.android.settings 设备详情页），由其拉取最新状态刷新。
 * 不依赖广播，绕开 Android 13+ RECEIVER_NOT_EXPORTED 对跨 UID 广播的拦截。
 */
object CtrlBus {
    private const val TAG = "CtrlBus"

    @Volatile private var sCtx: Context? = null

    /** 由模块进程入口（AncBridge.bind / PrefsProvider.onCreate）绑定，取 applicationContext。 */
    @JvmStatic
    fun bind(ctx: Context?) {
        if (ctx != null) sCtx = ctx.applicationContext
    }

    /** 推送 DC/ANC 状态变化。无 Context 时静默。 */
    @JvmStatic
    fun postDcChanged() {
        val c = sCtx ?: return
        try {
            c.contentResolver.notifyChange(Uri.parse(PrefsProvider.DC_CMD_URI), null)
        } catch (t: Throwable) {
            Log.w(TAG, "postDcChanged fail: $t")
        }
    }
}
