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
        val MODE_NAMES = AncProfileLib.ANC_MODE_NAMES_FULL

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
            CtrlBus.bind(ctx)
        }

        /** 最新 ANC 模式缓存 */
        @Volatile
        private var currentMode = -1
        @Volatile
        private var currentModeVersion = 0 // 用于 UI 刷新
        private val modeLock = Any()

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
                // 数据驱动：把设备**实际可用**的 UI 档位一并上报给 GMS 桥接层，
                // 让官方面板只呈现这副耳机真正支持的模式（不硬编码型号）。
                i.putExtra("ui_modes", supportedUiModesOrEmpty())
                i.setPackage("com.google.android.gms")
                c.sendBroadcast(i)
            } catch (t: Throwable) {
                Log.w(TAG, "sendAncStatus fail: $t")
            }
        }

        /** 设备可用的 UI 档位（能力未就绪时给空数组，桥接层会保持保守宣告）。 */
        private fun supportedUiModesOrEmpty(): IntArray =
            try { GaiaBleClient.getInstance().supportedUiModes() } catch (_: Throwable) { IntArray(0) }

        /** 获取当前缓存的 ANC 模式（-1 表示未知） */
        @JvmStatic
        fun getCurrentMode(): Int = currentMode

        @JvmStatic
        fun getCurrentModeVersion(): Int = currentModeVersion

        /** 由 GaiaBleClient 回调调用，更新模式 */
        @JvmStatic
        fun notifyAncMode(mode: Int) {
            updateAncMode(mode)
        }

        private fun updateAncMode(mode: Int, expectedVersion: Int? = null): Pair<Int, Int>? {
            // Rejected first commands must restore unknown state, not retain the optimistic mode.
            if (mode >= -1 && mode < MODE_NAMES.size) {
                val change = synchronized(modeLock) {
                    if (expectedVersion != null && currentModeVersion != expectedVersion) return null
                    val previous = currentMode
                    currentMode = mode
                    currentModeVersion++
                    previous to currentModeVersion
                }
                // Never hold the state lock while notifying observers or entering the transport.
                Log.d(TAG, "ANC mode updated: $mode (${MODE_NAMES.getOrNull(mode) ?: "unknown"})")
                sendModeState() // alpha1.20: 通知 GMS 弹窗同步高亮
                CtrlBus.postDcChanged() // alpha2.39: 推送跨进程状态给设备详情页
                // 第 5 项：ANC 通知高亮跟随模式。
                // 挂在这里而不是 GaiaBleClient.Callback.onAncMode —— 实测 GA2 的模式数据
                // 走 AncBridge 内部回调（fetchAncMode / setAncMode 的 callback），
                // 外层 callback 不一定被触发，挂外层会漏刷新。
                sCtx?.let { DeviceNotif.refreshAnc(it) }
                // 切片内容（蓝牙详情页那行）也要跟着变：notifyChange 才会让
                // SliceLiveData 重新 bind，否则一直显示缓存里的旧 Slice。
                sCtx?.let { c ->
                    try {
                        val addr = GaiaBleClient.getInstance().deviceAddress
                        if (!addr.isNullOrEmpty()) c.contentResolver.notifyChange(
                                HearableSliceProvider.uriFor(addr, null), null)
                    } catch (_: Throwable) { }
                }
                // 3.2.12: 磁贴也挂这里 —— 它与通知栏是同一份「当前档位」的两种呈现，
                // 挂在同一个点上才不会出现「通知栏已变、磁贴还是旧档位」
                AncTileService.refreshAll(sCtx)
                return change
            }
            return null
        }

        /** 设置 ANC 模式（UI：0=关闭 1=降噪 2=透传 3=抗风 4=自适应 5=直播）
         *  alpha2.14: UI 模式直传 GaiaBleClient，由能力探测结果选择协议路径
         *  （AudioCuration / ANC V2 / ANC V1），设备枚举映射在 GaiaCommands.ancDevFromUi */
        /** 最近一次下发（仅用于 200ms 重入去重） */
        private var lastSentMode = -1
        private var lastSentAt = 0L

        // The client rejects synchronously; serialize requests so rollback never captures another pending mode.
        @JvmStatic @Synchronized
        fun setAncMode(mode: Int) {
            if (mode < 0 || mode > 5) return
            // 只挡「同一次事件的重入」：同档位 200ms 内重复调用直接丢弃。
            // ⚠️ 不要放大这个窗口 —— 连接后同步档位、重连对齐都走这里，
            // 窗口一大就会吞掉正常命令（上一版 1.2s+限流那次就是这么把耳机搞成「连不上」的）。
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (mode == lastSentMode && nowMs - lastSentAt < 200L) {
                Log.d(TAG, "setAncMode reentry ignored: " + mode)
                return
            }
            lastSentMode = mode
            lastSentAt = nowMs
            Log.d(TAG, "setAncMode ui=$mode (path 由能力探测自动选择)")
            // alpha1.21: 乐观更新——GA2 AudioCuration 设备不回 ACK，不能依赖回调刷新
            // 缓存/弹窗高亮；先立即确认缓存并广播 GMS（设备真回包仍以设备为准覆盖）
            val (prevMode, optimisticVersion) = updateAncMode(mode) ?: return
            GaiaBleClient.getInstance().setAncMode(mode, object : GaiaBleClient.AncControlCallback {
                override fun onAncModeResult(m: Int) {
                    notifyAncMode(m)
                }

                override fun onAncError(message: String) {
                    // alpha2.22: 发送被拒（能力未就绪/无ANC/不支持）时回滚乐观高亮，
                    // 让用户明确看到按钮没真正生效，避免"假高亮"误导
                    Log.w(TAG, "setAncMode error: $message -> rollback highlight")
                    updateAncMode(prevMode, expectedVersion = optimisticVersion)
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
