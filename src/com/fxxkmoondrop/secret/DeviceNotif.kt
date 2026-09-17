package com.fxxkmoondrop.secret

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 第 4 / 5 项：设备通知（App 进程发布）。
 *
 * **一条通知**同时承载电量与降噪控制（原来拆成两条，主人要求合并）。
 *
 * 按钮走自定义布局（[R.layout.notif_device]）而不是系统 action：
 * Android 12 起系统 action 只渲染文字胶囊、图标被丢弃，而主人要求按钮
 * **像 App 主界面那样有大图标**（圆形底 + 大图标 + 文字，当前档位高亮）。
 *
 * 折叠 / 展开两份视图：
 * - 折叠：只放按钮行。system 给折叠态自定义视图的高度上限约 48dp，
 *   带上标题电量行就超了、按钮会被整个裁掉，所以那一行让给按钮。
 * - 展开：标题 + 电量 + 按钮行，信息完整。
 *
 * **不重复重发**：同一份内容不重发通知。每次 notify 都会让系统重建视图，
 * 用户刚展开的通知会被打回折叠态 —— 这正是「通知自己折叠起来」的来源。
 *
 * 颜色全部走 M3 动态取色（[ThemeUtil.dyn]，与 App 内面板同一套 token）：
 * 选中档位 = primary 圆底 + onPrimary 图标；未选中 = container + onContainer。
 *
 * 只在 GAIA 就绪时发布 —— 未连上时没有可信数据；断开即撤下。
 * 分类开关见 `feat_notif_battery` / `feat_notif_anc`（设置页「功能开关」卡片）。
 */
object DeviceNotif {
    private const val TAG = "MoondropNotif"
    private const val CH = "moondrop_device"

    /** 旧的分离通道，合并后清掉，免得通知设置里残留两项。 */
    private const val CH_OLD_BATT = "moondrop_battery"
    private const val CH_OLD_ANC = "moondrop_anc"

    /** 合并后的唯一通知 ID（沿用原电量通知的 ID，顺便覆盖掉它）。 */
    private const val ID = 0x4D01

    /** 合并前那条独立的 ANC 通知，清掉。 */
    private const val ID_OLD_ANC = 0x4D02

    /** 通知按钮点击 -> 广播动作（由 NotifActionReceiver 处理）。 */
    const val ACTION_ANC = "com.fxxkmoondrop.secret.NOTIF_ANC"
    const val EXTRA_MODE = "anc_mode"

    /** 上一次真正发出去的内容指纹：一样就不重发，避免把用户展开的通知打回折叠态。 */
    @Volatile private var lastSig: String? = null

    /**
     * 按钮展示顺序（对齐官方 GFPS Hearable Controls 通知）：
     * 降噪 / 关闭 / 通透 打头，自适应、直播、抗风随后。
     */
    private val ACTION_ORDER = intArrayOf(1, 0, 2, 4, 5, 3)

    /**
     * 自定义布局里的按钮槽位（3.0.3: 4 -> 5）。
     *
     * ANC_V2 设备（布丁）现在宣告 5 档 [关,降,透,抗,自适应]，只有 4 个槽时
     * 末尾档位会被静默挤掉（按 ACTION_ORDER 实际丢掉的是抗风）—— 属于比
     * 「点了没反应」更难发现的静默丢档，故一并补齐。
     */
    private val SLOT = intArrayOf(
        R.id.notif_btn0, R.id.notif_btn1, R.id.notif_btn2, R.id.notif_btn3, R.id.notif_btn4)
    private val SLOT_ICON = intArrayOf(
        R.id.notif_btn0_icon, R.id.notif_btn1_icon,
        R.id.notif_btn2_icon, R.id.notif_btn3_icon, R.id.notif_btn4_icon)
    private val SLOT_TEXT = intArrayOf(
        R.id.notif_btn0_text, R.id.notif_btn1_text,
        R.id.notif_btn2_text, R.id.notif_btn3_text, R.id.notif_btn4_text)

    // ── 对外入口（调用方只给 Context，数据统一从 GaiaBleClient 取，避免多处传参走样） ──

    /** GAIA 连接成功 / 电量更新后调用。 */
    fun refreshBattery(ctx: Context?) = refresh(ctx)

    /** ANC 档位变化后调用。 */
    fun refreshAnc(ctx: Context?) = refresh(ctx)

    /** 重绘这条通知（电量与档位任一变化都走这里）。 */
    fun refresh(ctx: Context?) {
        val c = ctx ?: return
        try {
            if (!isReady()) return
            val showBatt = enabled(c, "feat_notif_battery")
            val showAnc = enabled(c, "feat_notif_anc")
            if (!showBatt && !showAnc) {
                cancel(c, ID)
                return
            }
            val g = GaiaBleClient.getInstance()
            val addr = g.deviceAddress ?: return

            // 电量：只要左右耳（充电盒按需求不显示）
            var batt: String? = null
            if (showBatt) {
                val left = BatteryStore.getGaiaLeft(addr)
                val right = BatteryStore.getGaiaRight(addr)
                batt = when {
                    left >= 0 && right >= 0 ->
                        Lang.t(c, "左耳 %d%% · 右耳 %d%%", "Left %d%% · Right %d%%").format(left, right)
                    left >= 0 -> Lang.t(c, "左耳 %d%%", "Left %d%%").format(left)
                    right >= 0 -> Lang.t(c, "右耳 %d%%", "Right %d%%").format(right)
                    else -> null
                }
            }

            // 档位：不再占一行文字，当前档位靠按钮高亮表达。
            val modes = if (showAnc) g.supportedUiModes() else IntArray(0)
            val mode = AncBridge.getCurrentMode()
            val names = AncProfileLib.modeNamesFull(c)

            val actions = if (showAnc && modes.isNotEmpty()) orderedActions(modes) else emptyList()

            // 既没电量也没按钮 -> 没有可展示内容，不发空壳通知
            if (batt == null && actions.isEmpty()) {
                cancel(c, ID)
                return
            }

            val title = deviceName(c)
            val line = batt ?: ""

            // 内容没变就不重发：重发会让系统重建视图，把用户展开的通知打回折叠态。
            val sig = title + "|" + line + "|" + mode + "|" + actions.joinToString(",")
            if (sig == lastSig) return
            lastSig = sig

            ensureChannel(c)
            val b = NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_headphones)
                .setContentTitle(title)
                .setContentText(if (line.isEmpty()) null else line)
                .setSubText(if (line.isEmpty()) null else line)
                .setContentIntent(openApp(c))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)

            if (actions.isNotEmpty()) {
                b.setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomContentView(
                        buildContent(c, title, line, actions, names, mode, showHeader = false))
                    .setCustomBigContentView(
                        buildContent(c, title, line, actions, names, mode, showHeader = true))
            }

            notify(c, ID, b.build())
        } catch (t: Throwable) {
            Log.w(TAG, "refresh fail", t)
        }
    }

    private fun buildContent(
        c: Context,
        title: String,
        line: String,
        actions: List<Int>,
        names: Array<String>,
        mode: Int,
        showHeader: Boolean
    ): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.notif_device)

        // 折叠版把标题与电量行整体收起，把系统给的高度全让给按钮行
        if (!showHeader) {
            rv.setViewVisibility(R.id.notif_title, android.view.View.GONE)
            rv.setViewVisibility(R.id.notif_text, android.view.View.GONE)
        } else {
            rv.setViewVisibility(R.id.notif_title, android.view.View.VISIBLE)
            rv.setTextViewText(R.id.notif_title, title)
            if (line.isEmpty()) {
                rv.setViewVisibility(R.id.notif_text, android.view.View.GONE)
            } else {
                rv.setViewVisibility(R.id.notif_text, android.view.View.VISIBLE)
                rv.setTextViewText(R.id.notif_text, line)
            }
        }

        // 颜色统一走 M3 动态取色（与 App 内面板同一套 token，见 ControlPanel.refreshAncCard）
        val dark = ThemeUtil.isDark(c)
        val primary = ThemeUtil.dyn(c, "system_accent1_400",
            if (dark) 0xFFD0BCFF.toInt() else 0xFF6750A4.toInt())
        val container = ThemeUtil.dyn(c, "system_accent1_800",
            if (dark) 0xFF4F378B.toInt() else 0xFFE8DEF8.toInt())
        val onContainer = ThemeUtil.dyn(c, "system_accent1_50",
            if (dark) 0xFF4F378B.toInt() else 0xFFE8DEF8.toInt())
        val onPrimary = 0xFFFFFFFF.toInt()
        val fg = if (dark) 0xFFFFFFFF.toInt() else 0xFF1D1B20.toInt()

        rv.setTextColor(R.id.notif_title, fg)
        rv.setTextColor(R.id.notif_text, fg)

        // 没有按钮时整行收起，否则只留 padding 白占折叠视图的宝贵高度
        rv.setViewVisibility(R.id.notif_btns,
            if (actions.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)

        for (i in SLOT.indices) {
            val m = actions.getOrNull(i)
            if (m == null) {
                rv.setViewVisibility(SLOT[i], android.view.View.GONE)
                continue
            }
            rv.setViewVisibility(SLOT[i], android.view.View.VISIBLE)
            val selected = m == mode
            val fgc = if (selected) onPrimary else onContainer
            rv.setTextViewText(SLOT_TEXT[i], names[m])
            rv.setTextColor(SLOT_TEXT[i], fgc)
            rv.setImageViewResource(SLOT_ICON[i], modeIcon(m))
            // 图标本身是纯黑矢量，必须染色才在通知上可见
            rv.setInt(SLOT_ICON[i], "setColorFilter", fgc)
            // 圆底按动态取色染色（RemoteViews 的 ColorStateList 通道是 API 31+）
            if (Build.VERSION.SDK_INT >= 31) {
                rv.setColorStateList(SLOT_ICON[i], "setBackgroundTintList",
                    ColorStateList.valueOf(if (selected) primary else container))
            } else {
                rv.setInt(SLOT_ICON[i], "setBackgroundResource",
                    if (selected) R.drawable.notif_btn_circle_on else R.drawable.notif_btn_circle)
            }
            rv.setOnClickPendingIntent(SLOT[i], actionPi(c, m))
        }
        return rv
    }

    /** GAIA 掉线 / 断开：通知撤下（没有数据就不该留残影）。 */
    fun cancelAll(ctx: Context?) {
        val c = ctx ?: return
        lastSig = null
        cancel(c, ID)
        cancel(c, ID_OLD_ANC)
    }

    private fun isReady(): Boolean {
        val g = GaiaBleClient.getInstance()
        return g.isGaiaReady() && g.deviceAddress != null
    }

    /** 按 [ACTION_ORDER] 排，只保留本设备支持的档位。 */
    private fun orderedActions(modes: IntArray): List<Int> {
        val out = ArrayList<Int>(modes.size)
        for (m in ACTION_ORDER) {
            if (modes.contains(m)) out.add(m)
        }
        // 兜底：能力表里出现了排序表没收录的档位也不能漏，按原顺序补在后面。
        for (m in modes) {
            if (m in 0..5 && !out.contains(m)) out.add(m)
        }
        return out
    }

    /** 模式图标与 App 内面板同源（见 `M3Ui.ancModeDrawable`）。 */
    private fun modeIcon(mode: Int): Int = when (mode) {
        0 -> R.drawable.ic_anc_off
        1 -> R.drawable.ic_anc_on
        2 -> R.drawable.ic_anc_passthrough
        3 -> R.drawable.ic_air
        // 3.0.3: 自适应(4) 暂与降噪同源（仓库无 Material Symbols 的 noise_aware 矢量，
        // 不自造 path 以免画出错形状）；资源到位后只改这一行。
        4 -> R.drawable.ic_anc_on
        else -> R.drawable.ic_air
    }

    private fun actionPi(ctx: Context, mode: Int): PendingIntent {
        val i = Intent(ctx, NotifActionReceiver::class.java).apply {
            action = ACTION_ANC
            putExtra(EXTRA_MODE, mode)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        // requestCode 带 mode：每个档位各自独立的 PendingIntent，否则 extras 会被后一个覆盖。
        return PendingIntent.getBroadcast(ctx, 0x4D00 + mode, i, flags)
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(ctx, 0x4D0F, i, flags)
    }

    // ── 公共设施 ──

    private fun deviceName(ctx: Context): String {
        val n = GaiaBleClient.getInstance().getConnectedDeviceName()
        if (!n.isNullOrEmpty()) return n
        return Lang.t(ctx, "Moondrop 耳机", "Moondrop earbuds")
    }

    /** 分类开关（设置页写入同一份 cfg SP）+ 系统通知权限，任一不满足则不发。 */
    private fun enabled(ctx: Context, key: String): Boolean {
        if (!ctx.getSharedPreferences("cfg", 0).getBoolean(key, true)) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun notify(ctx: Context, id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (t: Throwable) {
            Log.w(TAG, "notify $id fail", t)
        }
    }

    private fun cancel(ctx: Context, id: Int) {
        try {
            NotificationManagerCompat.from(ctx).cancel(id)
        } catch (_: Throwable) {
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CH,
                    Lang.t(ctx, "耳机状态", "Earbuds status"),
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = Lang.t(ctx, "显示耳机电量，并可直接切换降噪模式",
                        "Shows earbud battery and lets you switch noise control")
                    setShowBadge(false)
                    setSound(null, null)
                })
            }
            // 合并前的两个旧通道清掉，避免通知设置里残留无关项
            nm.deleteNotificationChannel(CH_OLD_BATT)
            nm.deleteNotificationChannel(CH_OLD_ANC)
        } catch (t: Throwable) {
            Log.w(TAG, "ensureChannel fail", t)
        }
    }
}
