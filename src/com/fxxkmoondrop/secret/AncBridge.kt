package com.fxxkmoondrop.secret

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 降噪控制桥（alpha1.0 直连 GAIA 版）：
 * 通过 GaiaBleClient 直接发送 GAIA V3 ANC 命令，
 * 不再依赖 Moondrop 官方 App。
 */
class AncBridge {
    companion object {
        private const val TAG = "AncBridge"

        @JvmField
        val MODE_NAMES = arrayOf("关闭", "降噪", "透传", "抗风", "自适应", "直播")

        /** alpha1.20: 跨进程模式状态同步（应用 -> GMS 弹窗高亮；GMS -> 应用请求当前值） */
        const val ACTION_FP_MODE_STATE = "com.fxxkmoondrop.secret.FASTPAIR_MODE_STATE"
        const val ACTION_FP_MODE_REQUEST = "com.fxxkmoondrop.secret.FASTPAIR_MODE_REQUEST"
        /** alpha2.22: app -> GMS 广播 ANC 能力状态（驱动弹窗降噪按钮三态） */
        const val ACTION_FP_ANC_STATUS = "com.fxxkmoondrop.secret.FASTPAIR_ANC_STATUS"

        /** 广播用 Context（由 MainActivity / HeadsetDetectService 绑定，取 applicationContext） */
        @Volatile
        private var sCtx: Context? = null

        @JvmStatic
        fun bind(ctx: Context?) {
            if (ctx != null) sCtx = ctx.applicationContext
        }

        /** 最新 ANC 模式缓存 */
        @Volatile
        private var currentMode = -1
        @Volatile
        private var currentModeVersion = 0 // 用于 UI 刷新

        /** alpha1.20: 把当前模式广播给 GMS（弹窗按钮高亮用）；无 Context 时静默 */
        @JvmStatic
        fun sendModeState() {
            val c = sCtx ?: return
            try {
                val i = Intent(ACTION_FP_MODE_STATE)
                i.putExtra("mode", currentMode)
                i.setPackage("com.google.android.gms")
                c.sendBroadcast(i)
            } catch (t: Throwable) {
                Log.w(TAG, "sendModeState fail: $t")
            }
        }

        /** alpha2.22: 广播 ANC 能力状态给 GMS（驱动弹窗降噪按钮三态）。
         *  status: 0=探测中 1=有ANC 2=无ANC/截断 */
        @JvmStatic
        fun sendAncStatus(status: Int) {
            val c = sCtx ?: return
            try {
                val i = Intent(ACTION_FP_ANC_STATUS)
                i.putExtra("status", status)
                i.setPackage("com.google.android.gms")
                c.sendBroadcast(i)
            } catch (t: Throwable) {
                Log.w(TAG, "sendAncStatus fail: $t")
            }
        }

        /** 获取当前缓存的 ANC 模式（-1 表示未知） */
        @JvmStatic
        fun getCurrentMode(): Int = currentMode

        @JvmStatic
        fun getCurrentModeVersion(): Int = currentModeVersion

        /** 由 GaiaBleClient 回调调用，更新模式 */
        @JvmStatic
        fun notifyAncMode(mode: Int) {
            if (mode >= 0 && mode < MODE_NAMES.size) {
                currentMode = mode
                currentModeVersion++
                Log.d(TAG, "ANC mode updated: $mode (${MODE_NAMES[mode]})")
                sendModeState() // alpha1.20: 通知 GMS 弹窗同步高亮
            }
        }

        /** 设置 ANC 模式（UI：0=关闭 1=降噪 2=透传 3=抗风 4=自适应 5=直播）
         *  alpha2.14: UI 模式直传 GaiaBleClient，由能力探测结果选择协议路径
         *  （AudioCuration / ANC V2 / ANC V1），设备枚举映射在 GaiaCommands.ancDevFromUi */
        @JvmStatic
        fun setAncMode(mode: Int) {
            if (mode < 0 || mode > 5) return
            Log.d(TAG, "setAncMode ui=$mode (path 由能力探测自动选择)")
            // alpha1.21: 乐观更新——GA2 AudioCuration 设备不回 ACK，不能依赖回调刷新
            // 缓存/弹窗高亮；先立即确认缓存并广播 GMS（设备真回包仍以设备为准覆盖）
            val prevMode = currentMode
            notifyAncMode(mode)
            GaiaBleClient.getInstance().setAncMode(mode, object : GaiaBleClient.AncControlCallback {
                override fun onAncModeResult(m: Int) {
                    notifyAncMode(m)
                }

                override fun onAncError(message: String) {
                    // alpha2.22: 发送被拒（能力未就绪/无ANC/不支持）时回滚乐观高亮，
                    // 让用户明确看到按钮没真正生效，避免"假高亮"误导
                    Log.w(TAG, "setAncMode error: $message -> rollback highlight")
                    notifyAncMode(prevMode)
                }
            })
        }

        /** 查询当前 ANC 模式 */
        @JvmStatic
        fun fetchAncMode() {
            GaiaBleClient.getInstance().fetchAncMode(object : GaiaBleClient.AncControlCallback {
                override fun onAncModeResult(mode: Int) {
                    notifyAncMode(mode)
                }

                override fun onAncError(message: String) {
                    Log.w(TAG, "fetchAncMode error: $message")
                }
            })
        }
    }
}
