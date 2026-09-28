package com.fxxkmoondrop.secret

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * 快捷设置磁贴（3.2.12）：下拉面板里一键切降噪档位。
 *
 * 为什么值得做：降噪是这副耳机最高频的操作，而现有路径都要先展开通知栏、或进应用点两下；
 * 磁贴把「切一档」压到一次点击，且不必进入任何界面。
 *
 * 设计取舍：
 *  - **档位不硬编码**，一律问 [GaiaBleClient.supportedUiModes] —— 与通知栏、主界面、
 *    官方面板共用同一份判据。耳机没连上、或能力尚未探明时，磁贴显示为不可用（灰），
 *    绝不摆出一个点了没反应的按钮。
 *  - **只对支持的耳机生效**：能力路径未知时直接不可用，不会误伤其他蓝牙设备。
 *  - 档位名走 [AncProfileLib.modeNamesFull]，跟随应用内的中英文切换。
 */
class AncTileService : TileService() {

    companion object {
        private const val TAG = "AncTile"

        /**
         * 请求系统重画本磁贴。
         *
         * 磁贴跑在应用进程里，所以模式一变（见 [AncBridge.notifyAncMode]）就地刷新即可，
         * 不必等用户下次下拉面板。用户没添加过这个磁贴时，该调用是安全的空操作。
         */
        @JvmStatic
        fun refreshAll(ctx: Context?) {
            val c = ctx ?: return
            try {
                requestListeningState(c, ComponentName(c, AncTileService::class.java))
            } catch (t: Throwable) {
                Log.d(TAG, "requestListeningState failed: $t")
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val modes = supportedModes()
        if (modes.isEmpty()) {
            // 不可用时把点击当作「打开应用」：用户此刻的意图就是控制耳机，
            // 应用里有完整的连接状态与诊断，比磁贴干瞪眼有用
            openApp()
            return
        }
        val cur = AncBridge.getCurrentMode()
        val idx = modes.indexOf(cur)
        // 当前档位不在可用列表里（刚换了耳机、或档位被设备侧改过）→ 从第一档重来。
        // 不做这个分支的话 indexOf 返回 -1，+1 恰好也落到 0，行为凑巧正确但意图不明，
        // 下次有人改成 (idx+2) 就会踩坑
        val next = if (idx < 0) modes[0] else modes[(idx + 1) % modes.size]
        Log.d(TAG, "tile click: cur=$cur modes=${modes.joinToString(",")} -> next=$next")
        AncBridge.setAncMode(next)
        // 乐观刷新：GA2 这类 AudioCuration 设备不回 ACK，等回调会显得迟钝；
        // 设备真回包时 AncBridge 会再刷一次，以设备为准
        refresh()
    }

    /** 磁贴服务由系统进程绑定，而控制链路在应用进程里 —— 进来先自我初始化一次。 */
    private fun ensureBound() {
        try {
            AncBridge.bind(applicationContext)
        } catch (t: Throwable) {
            Log.d(TAG, "bind failed: $t")
        }
    }

    private fun supportedModes(): IntArray = try {
        GaiaBleClient.getInstance().supportedUiModes()
    } catch (t: Throwable) {
        IntArray(0)
    }

    private fun refresh() {
        val t = qsTile ?: return
        ensureBound()

        val ctx = applicationContext
        val modes = supportedModes()
        if (modes.isEmpty()) {
            t.state = Tile.STATE_UNAVAILABLE
            t.label = Lang.t(ctx, "降噪", "Noise control")
            setSubtitle(t, Lang.t(ctx, "耳机未连接", "Headset not connected"))
            t.icon = Icon.createWithResource(this, R.drawable.ic_anc_off)
            t.updateTile()
            return
        }

        val cur = AncBridge.getCurrentMode()
        val names = AncProfileLib.modeNamesFull(ctx)
        // cur 未知（-1）时显示「降噪」：这是该磁贴最常见的语义，比空字符串清楚
        val name = if (cur in names.indices) names[cur] else names[1]

        t.state = if (cur == 0) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        t.label = name
        setSubtitle(t, Lang.t(ctx, "点按切换档位", "Tap to cycle"))
        t.icon = Icon.createWithResource(this, iconFor(cur))
        t.updateTile()
    }

    /** subtitle 自 API 29 起才有，低版本跳过（磁贴照常可用，只是少一行小字）。 */
    private fun setSubtitle(t: Tile, s: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.subtitle = s
    }

    /** 只有三张档位图（关 / 降噪 / 透传），其余档位复用「降噪」图 —— 不为此再画四张。 */
    private fun iconFor(mode: Int): Int = when (mode) {
        0 -> R.drawable.ic_anc_off
        2 -> R.drawable.ic_anc_passthrough
        else -> R.drawable.ic_anc_on
    }

    private fun openApp() {
        try {
            val i = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // API 34 起 Intent 重载已废弃，改用 PendingIntent；旧系统没有新重载，只能二分
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(
                        this, 0, i,
                        android.app.PendingIntent.FLAG_IMMUTABLE
                                or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "openApp failed: $t")
        }
    }
}
