package com.fxxkmoondrop.secret

import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.SystemClock
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader

class OverviewFragment : Fragment() {

    private var svcStatus: TextView? = null
    private var statusIcon: ImageView? = null // alpha2.0 M3 英雄卡：官方图标（Material Icons）
    private var statusIconChip: android.widget.FrameLayout? = null // 指示器容器（圆角随状态）
    private var statusBadge: TextView? = null     // 英雄卡右侧徽章（当前蓝牙协议）
    /** alpha2.53: 当前编解码器缓存 + 查询节流（dumpsys 要百毫秒级，不能每次刷新都跑） */
    private var codecLabel: String? = null
    private var codecQuerying = false
    private var lastCodecQueryMs = 0L
    private var statusSub: TextView? = null       // 英雄卡副标题（版本动态）
    private var green = 0
    private var red = 0
    private var primaryColor = 0
    private var onPrimaryColor = 0
    private var onSurfaceColor = 0
    private var onContainerColor = 0
    private var containerColor = 0
    /** alpha2.53: 英雄卡前景色（卡片改强调色 primary 后，文字/图标走 onPrimary） */
    private var heroFg = 0

    /** alpha2.53: 手动刷新期间的线性进度条（BLE 重连要数秒，给了真实进度反馈） */
    /** 刷新按钮本体与其内部的官方 LoadingIndicator */
    private var refreshBtn: com.google.android.material.button.MaterialButton? = null
    private var refreshSpin: View? = null

    /** 用户已确认过的「被跳过设备」名单（3.3.2），关闭提示卡时写入。 */
    private val KEY_SKIPPED_ACK = "skipped_notice_ack"

    private val hideRefreshBar = Runnable { stopRefreshSpinner() }
    private var surfaceColor = 0
    private var onVariantColor = 0
    private var cardSurfaceColor = 0
    private var ancStatus: TextView? = null
    private var ancTitle: TextView? = null
    private var ancBtnRow: LinearLayout? = null
    private var gaiaStateVal: TextView? = null
    private var headsetStateVal: TextView? = null
    private var battVal: TextView? = null
    private var devInfoCard: LinearLayout? = null
    private var devNameVal: TextView? = null
    private var devMacVal: TextView? = null
    private var devProfileVal: TextView? = null
    private var battRow: View? = null
    private var battRowShown = false // alpha2.8: 电量行当前视觉状态（驱动出现/消失动画）
    private var ancBtns: Array<View?>? = null   // alpha1.20: 弹窗同款按钮 holder
    private var ancLabels: Array<TextView?>? = null

    /** 3.2.20: 各档位的**整列**（含图标与文字），用于按型号档案统一控制显隐。 */
    private var ancCols: Array<View?>? = null
    private var eqRow: View? = null   // 3.2.12: 均衡器入口行（按 9ECA 服务能力显隐）
    private var ancWindCol: View? = null
    /** 3.0.5: 自适应(4) 列。只有设备能力确证支持时显示（与设备详情页同规则）。 */
    private var ancAdaptCol: View? = null
    private var dcSwitchSyncing = false
    @Volatile private var lastRefreshAncMs = 0L  // alpha2.28: refreshAnc 节流 // alpha2.26.2: 抗风按钮列（用户可选隐藏）
    private var ancMode = -1
    private var dcControlCard: LinearLayout? = null  // alpha2.31
    // alpha2.28: ANC icon bitmap cache (4 modes x 2 colors = 8 slots)
    private var ancIconCache: Array<android.graphics.drawable.Drawable?>? = null
    @Volatile private var moonProcState = Lang.t("未知", "Unknown")
    private val autoRefreshHandler = Handler(Looper.getMainLooper())

    /** alpha1.36: HeadsetGate 异步补扫命中后推送 MAC（非阻塞链路闭环；onResume 注册 onStop 注销） */
    private val macUpdReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val mac = i.getStringExtra("mac") ?: return
            requireActivity().runOnUiThread {
                val gaia = GaiaBleClient.getInstance()
                val generation = gaia.setCallback(gaiaUiCallback)
                val appContext = c.applicationContext
                Thread { gaia.forceReconnect(appContext, mac, gaiaUiCallback, generation) }.start()
                updateStatus()
            }
        }
    }

    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            // alpha2.28: autoRefresh only fetches ANC mode; battery is polled by HeadsetDetectService every 30s
            refreshAnc(false, skipBattery = true)
            autoRefreshHandler.postDelayed(this, AUTO_REFRESH_MS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, containerView: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, containerView, savedInstanceState)
        Lang.refresh(requireContext())

        // ── Material You 主题（alpha2.2：统一 ThemeUtil.Palette → 动态/种子/AMOLED 全支持）──
        val pal0 = ThemeUtil.Palette(requireContext())
        primaryColor = pal0.primary
        onPrimaryColor = pal0.onPrimary
        val container = pal0.container
        val onContainer = pal0.onContainer
        val surface = pal0.surface
        val cardColor = pal0.card
        val onSurface = pal0.onSurface
        val onVariant = pal0.onVariant
        val outline = pal0.outline
        green = pal0.green
        red = pal0.red

        onSurfaceColor = onSurface
        onContainerColor = onContainer
        containerColor = container
        heroFg = pal0.onPrimary
        surfaceColor = surface
        onVariantColor = onVariant
        ancIconCache = null // alpha2.28: invalidate icon cache on theme change
        cardSurfaceColor = cardColor
        var statusBarH = 0
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) statusBarH = resources.getDimensionPixelSize(resId)

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL
        // alpha2.52: 页面边距 24 -> 16dp（对齐 LSPosed / M3 屏幕边距 16dp）
        root.setPadding(dp(16), dp(4), dp(16), dp(16))
        root.setBackgroundColor(surface)

        // ── 官方 StatusHeader（LSPosed Manager 同款：纯色胶囊 / 圆形指示器 / 呼吸 / 徽章 / 全圆角；内容居中）──
        val statusPanel = android.widget.FrameLayout(requireContext())
        val panelBg = GradientDrawable()
        // alpha2.53: 英雄卡改用强调色（原来用 container，深色下偏灰发闷）
        panelBg.setColor(pal0.primary)
        panelBg.setCornerRadii(floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(),
                dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat()))
        statusPanel.background = panelBg
        statusPanel.setPadding(dp(20), dp(20), dp(20), dp(20))

        // 状态行：指示器（52dp 圆角方形 34% + onContainer 15% 底）+ 文本 + API 徽章
        val heroRow = LinearLayout(requireContext())
        heroRow.orientation = LinearLayout.HORIZONTAL
        heroRow.gravity = Gravity.CENTER_VERTICAL

        statusIconChip = android.widget.FrameLayout(requireContext())
        val chipBg = GradientDrawable()
        chipBg.shape = GradientDrawable.OVAL
        chipBg.setColor((pal0.onPrimary and 0x00FFFFFF) or 0x26000000)
        statusIconChip!!.background = chipBg
        statusIcon = ImageView(requireContext())
        statusIcon!!.setImageResource(R.drawable.ic_check)
        statusIcon!!.imageTintList = ColorStateList.valueOf(pal0.onPrimary)
        val silp = android.widget.FrameLayout.LayoutParams(dp(22), dp(22))
        silp.gravity = Gravity.CENTER
        statusIconChip!!.addView(statusIcon, silp)
        // 官方 Active 呼吸（1.0 -> 1.05, 1900ms REVERSE）
        val pulse = android.animation.ValueAnimator.ofFloat(1f, 1.05f)
        pulse.duration = 1900
        pulse.repeatCount = android.animation.ValueAnimator.INFINITE
        pulse.repeatMode = android.animation.ValueAnimator.REVERSE
        pulse.addUpdateListener { a ->
            val v = a.animatedValue as Float
            statusIconChip!!.scaleX = v
            statusIconChip!!.scaleY = v
        }
        pulse.start()
        heroRow.addView(statusIconChip, LinearLayout.LayoutParams(dp(44), dp(44)))
        val chipLp = statusIconChip!!.layoutParams as LinearLayout.LayoutParams
        chipLp.marginEnd = dp(14)
        statusIconChip!!.layoutParams = chipLp
        // 文本列：状态词（titleMedium）+ 版本（bodyMedium）——对齐 LSPosed 英雄卡
        val texts = LinearLayout(requireContext())
        texts.orientation = LinearLayout.VERTICAL
        texts.gravity = Gravity.START
        svcStatus = TextView(requireContext())
        svcStatus!!.textSize = 16f
        svcStatus!!.isSingleLine = true
        svcStatus!!.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        svcStatus!!.setTextColor(pal0.onPrimary)
        svcStatus!!.text = Lang.t("运行中", "Running")
        texts.addView(svcStatus)
        texts.addView(spacer(dp(4)))
        statusSub = TextView(requireContext())
        statusSub!!.textSize = 14f
        statusSub!!.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        statusSub!!.setTextColor((pal0.onPrimary and 0x00FFFFFF) or 0xB3000000.toInt())
        statusSub!!.isSingleLine = true
        statusSub!!.gravity = Gravity.START
        try {
            val pi = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            // 3.0.3: 去掉 "Alpha" 后缀，直接显示正式版本号（V3.0.3）
            statusSub!!.text = "V" + (pi.versionName ?: "").removePrefix("alpha")
        } catch (_: Exception) {
            statusSub!!.text = ""
        }
        texts.addView(statusSub, LinearLayout.LayoutParams(-2, -2))

        heroRow.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        // 右侧徽章 pill（API，动态获取）
        statusBadge = TextView(requireContext())
        statusBadge!!.text = badgeText()
        statusBadge!!.textSize = 12f
        statusBadge!!.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        statusBadge!!.setTextColor(primaryColor)
        statusBadge!!.gravity = Gravity.CENTER
        statusBadge!!.setPadding(dp(10), dp(5), dp(10), dp(5))
        val bdg = GradientDrawable()
        bdg.cornerRadius = dp(20).toFloat()
        bdg.setColor(onPrimaryColor)
        statusBadge!!.background = bdg
        val blp = LinearLayout.LayoutParams(-2, -2)
        blp.marginStart = dp(6)
        heroRow.addView(statusBadge, blp)

        val heroLp = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        statusPanel.addView(heroRow, heroLp)
        root.addView(statusPanel, lp(false))

        root.addView(spacer(dp(16)))

        // ── alpha2.41.10: 主界面「开始/停止后台监听」按钮已移除 ──
        // 监听启停统一由 设置 → 行为 → 后台监听 总开关控制，避免两处重复入口。

        // ── alpha2.4: 运行状态面板（GAIA 连接 / 耳机连接 / 左右耳电量）──
        buildStatusPanel(root)

        root.addView(spacer(dp(16)))

        // ── alpha2.52: 设备信息卡（设备名称 / 设备地址 / 型号档案）——
        // 三个值均取自既有数据源（GAIA 连接名 / HeadsetGate / 型号档案库），未连接时占位
        buildDeviceInfoCard(root)

        root.addView(spacer(dp(16)))

        // ── 降噪控制（GAIA 直连）──
        ancTitle = TextView(requireContext())
        ancTitle!!.text = Lang.t("降噪控制", "Noise Control")
        ancTitle!!.textSize = 14f
        ancTitle!!.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // alpha2.52: 与设置页 M3Ui.sectionTitle 统一（onSurfaceVariant），原先用 outline 偏暗
        ancTitle!!.setTextColor(onVariant)
        root.addView(ancTitle, lp(false))
        // alpha2.7: 始终显示，未连接时按钮禁用（视觉置灰）
        ancTitle!!.visibility = View.VISIBLE

        root.addView(spacer(dp(8)))
        root.addView(spacer(dp(8)))

        // alpha1.20: 弹窗同款三按钮（圆形按钮 + 图标 + 小字），高亮=当前模式（与 Google 弹窗一致）
        // 3.0.5: 4 -> 5 列。自适应列初始隐藏，由 updateAncStatus 按设备能力决定可见性
        // （与设备详情页面板同一规则：能力未确证支持就不出现，避免点了没反应的空档位）。
        ancBtns = arrayOfNulls(5)
        ancLabels = arrayOfNulls(5)
        ancCols = arrayOfNulls(5)   // 3.2.20: 记录整列，供按档案收敛档位
        val ancRow = LinearLayout(requireContext())
        ancRow.orientation = LinearLayout.HORIZONTAL
        ancRow.gravity = Gravity.CENTER
        // alpha2.26.2: Material Experience —— M3 圆角卡片容器（card 色 + 28dp 圆角）
        ancRow.setPadding(dp(16), dp(16), dp(16), dp(16))
        // alpha2.52: 走统一卡片外观（AMOLED 纯黑下带发丝描边，避免卡片与背景同色"消失"）

        // 出场顺序与通知栏按钮同源（[AncProfileLib.ANC_UI_ORDER]）：降噪 / 关闭 / 通透 打头，
        // 自适应、抗风随后。数组仍按档位 id 索引，这里只改列的先后。
        // 直播(5) 在主界面没有槽位，仍只在通知里出现。
        for (modeId in AncProfileLib.ANC_UI_ORDER) {
            if (modeId > 4) continue
            val fm = modeId
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            // 官方 MaterialButton 只负责**形状 / 状态层 / 按压形变**，图标用一张居中的 ImageView 叠上去。
            // 之前把自绘 drawable 塞进 MaterialButton.icon，走的是「文字前图标」布局：
            // 没有文字时图标会被 paddingStart 顶到左边（也就是这次看到的“偏左、不在正中”）。
            // 叠一层 ImageView + Gravity.CENTER，位置就是几何中心，与图标尺寸、padding 全部解耦。
            val holder = android.widget.FrameLayout(requireContext())
            val btn = com.google.android.material.button.MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialIconButtonStyle)
            btn.text = ""
            btn.isCheckable = true
            btn.isChecked = false
            btn.insetLeft = 0
            btn.insetRight = 0
            btn.insetTop = 0
            btn.insetBottom = 0
            btn.setPadding(0, 0, 0, 0)
            btn.tag = "anc_btn"
            // 这四个按钮是内联建的不是 M3Ui.iconToggle，之前漏了按压形变（用户反馈「增益有、降噪没有」）
            M3Ui.attachPressMorph(requireContext(), btn, 28f, 12f)
            btn.contentDescription = AncProfileLib.modeNamesFull(requireContext())[fm]
            // 未选中 = surfaceContainer 圆底 / 选中 = primary 底（M3 filled icon button toggle 的配色）
            btn.backgroundTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(primaryColor, containerColor))
            btn.setOnClickListener {
                AncBridge.setAncMode(fm)
                ancMode = fm
                updateAncStatus()
            }
            holder.addView(btn, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            val icon = ImageView(requireContext())
            icon.tag = "fxxk_main_icon"
            icon.isClickable = false          // 触摸交给底下的按钮，图标只负责画
            icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            icon.setImageDrawable(buildMainModeIcon(fm, dp(28), onContainerColor))
            holder.addView(icon, android.widget.FrameLayout.LayoutParams(
                    dp(28), dp(28), Gravity.CENTER))
            ancBtns!![fm] = holder
            if (fm == 3) ancWindCol = col // alpha2.26.2: 记录抗风列用于按需隐藏
            if (fm == 4) ancAdaptCol = col // 3.0.5: 记录自适应列用于按能力隐藏
            ancCols!![fm] = col         // 3.2.20: 统一显隐用
            // 官方 Icon Button M 档：容器 56dp（图标 28dp + 四周 14dp），触摸区天然 56dp ≥ 48dp
            val sz = dp(56)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            col.addView(spacer(dp(2)))
            val lbl = TextView(requireContext())
            lbl.text = AncProfileLib.modeNamesFull(requireContext())[fm]
            lbl.textSize = 12f
            lbl.gravity = Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(onContainerColor)
            ancLabels!![fm] = lbl
            col.addView(lbl, LinearLayout.LayoutParams(-1, -2))
            ancRow.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        ancBtnRow = ancRow
        root.addView(ancBtnRow, lp(false))
        ancBtnRow!!.visibility = View.VISIBLE // alpha2.7: 始终显示，未连接时按钮禁用
        // alpha2.26.2: 按用户设置应用抗风按钮可见性
        val showWindInit = requireContext().getSharedPreferences("cfg", 0)
                .getBoolean("show_wind", true)
        ancWindCol?.visibility = if (showWindInit) View.VISIBLE else View.GONE
        ancAdaptCol?.visibility = View.GONE // 3.0.5: 等能力上报再决定

        root.addView(spacer(dp(8)))

        // ── 3.2.12: 均衡器入口 ──
        // 均衡器走 9ECA 私有协议，只有蓝讯 / 中科系主控的型号才提供该服务（高通 GAIA 系没有）。
        // 与降噪区块同一条规则：能力不确证就整行隐藏 —— 摆一个点进去说「不支持」的入口，
        // 不如干脆不显示。显隐由 updateAncStatus 里的 refreshEqEntry 决定，这里先建好藏着。
        val eqBox = LinearLayout(requireContext())
        eqBox.orientation = LinearLayout.VERTICAL
        eqBox.addView(M3Ui.groupCard(requireActivity(), pal0,
                M3Ui.listRow(requireActivity(), pal0, R.drawable.ic_anc_on,
                        Lang.t("均衡器", "Equalizer"),
                        Lang.t("读写耳机内置的 5 段参数均衡（SPP）",
                                "Read and write the headset's 5-band parametric EQ"),
                        M3Ui.chevron(requireContext(), pal0.onVariant)) {
                    startActivity(Intent(requireContext(), EqEditorActivity::class.java))
                }), LinearLayout.LayoutParams(-1, -2))
        eqBox.visibility = View.GONE
        eqRow = eqBox
        root.addView(eqRow, lp(false))

        root.addView(spacer(dp(8)))

        // alpha2.32: 扩展设备控制（空间音频/增益/LED）—— 圆形图标按钮 + 型号档案
        val dcCard = LinearLayout(requireContext())
        dcCard.orientation = LinearLayout.VERTICAL
        dcCard.setPadding(dp(16), dp(16), dp(16), dp(16))   // M3：卡片内容内边距 16dp（原 10/12 是散值）


        // 空间音频行（总开关）
        val dcSpatialRow = LinearLayout(requireContext())
        dcSpatialRow.orientation = LinearLayout.HORIZONTAL
        dcSpatialRow.gravity = Gravity.CENTER_VERTICAL
        dcSpatialRow.tag = "dc_spatial_row"
        val sLabel = TextView(requireContext())
        sLabel.text = Lang.t("空间音频", "Spatial Audio")
        sLabel.textSize = 12f
        sLabel.setTextColor(onContainerColor)
        dcSpatialRow.addView(sLabel, LinearLayout.LayoutParams(-2, -2))
        dcSpatialRow.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
        val spatialSwitch = com.google.android.material.materialswitch.MaterialSwitch(requireContext())
        spatialSwitch.tag = "dc_spatial_switch"
        spatialSwitch.contentDescription = sLabel.text
        M3Ui.standardSwitch(spatialSwitch, pal0)
        spatialSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!dcSwitchSyncing) {
                DeviceControlBridge.setSpatialEnabled(isChecked)
                refreshDcHighlight()
            }
        }
        dcSpatialRow.addView(spatialSwitch, LinearLayout.LayoutParams(-2, -2))
        dcCard.addView(dcSpatialRow, LinearLayout.LayoutParams(-1, -2))

        // 空间追踪子模式行
        dcCard.addView(spacer(dp(6)))
        val dcTrackingRow = LinearLayout(requireContext())
        dcTrackingRow.orientation = LinearLayout.HORIZONTAL
        dcTrackingRow.gravity = Gravity.CENTER_VERTICAL
        dcTrackingRow.tag = "dc_tracking_row"
        val tLabel = TextView(requireContext())
        tLabel.text = Lang.t("追踪模式", "Tracking Mode")
        tLabel.textSize = 11f
        tLabel.setTextColor(onContainerColor)
        dcTrackingRow.addView(tLabel, LinearLayout.LayoutParams(-2, -2))
        dcTrackingRow.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
        for (tm in 0..2) {
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            // 官方 Icon Button（toggle）：形状/状态层/形变交给 MaterialButton，
            // 图标仍是 DcIcons 自绘；不再手绘 OVAL 圆底 + 2dp 描边表示选中。
            val holder = M3Ui.iconToggle(requireContext(), pal0,
                    DcIcons.build(requireContext(), 0, tm, dp(24), onContainerColor), 48, 24)
            holder.tag = "dc_btn_spatial_" + tm
            holder.findViewWithTag<View>("it_btn")?.contentDescription =
                Lang.t("追踪模式", "Tracking Mode") + ": " + DeviceControlBridge.trackingLabels()[tm]
            // 必须挂在 MaterialButton 上：按钮本身可点会吃掉事件，挂在 FrameLayout 上的
            // listener 永远不触发 —— 之前就是这个错，表现是「点了没反应、但按钮自己来回亮」。
            holder.findViewWithTag<View>("it_btn")?.setOnClickListener {
                DeviceControlBridge.setTrackingModeByUser(tm)
                refreshDcHighlight()
            }
            val sz = dp(48)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            val lbl = TextView(requireContext())
            lbl.text = DeviceControlBridge.trackingLabels()[tm]
            lbl.textSize = 11f
            lbl.gravity = Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(onContainerColor)
            col.addView(lbl, LinearLayout.LayoutParams(-1, -2))
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(4), 0, dp(4), 0)
            dcTrackingRow.addView(col, lp)
        }
        dcCard.addView(dcTrackingRow, LinearLayout.LayoutParams(-1, -2))

        // 增益行
        dcCard.addView(spacer(dp(8)))
        val dcGainRow = LinearLayout(requireContext())
        dcGainRow.orientation = LinearLayout.HORIZONTAL
        dcGainRow.gravity = Gravity.CENTER_VERTICAL
        dcGainRow.tag = "dc_gain_row"
        val gLabel = TextView(requireContext())
        gLabel.text = Lang.t("增益", "Gain")
        gLabel.textSize = 12f
        gLabel.setTextColor(onContainerColor)
        dcGainRow.addView(gLabel, LinearLayout.LayoutParams(-2, -2))
        dcGainRow.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
        for (gm in 0 until DeviceControlBridge.gainCount()) {
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            // 官方 Icon Button（toggle）：形状/状态层/形变交给 MaterialButton，
            // 图标仍是 DcIcons 自绘；不再手绘 OVAL 圆底 + 2dp 描边表示选中。
            val holder = M3Ui.iconToggle(requireContext(), pal0,
                    DcIcons.build(requireContext(), 1, gm, dp(24), onContainerColor), 48, 24)
            holder.tag = "dc_btn_gain_" + gm
            holder.findViewWithTag<View>("it_btn")?.contentDescription =
                Lang.t("增益", "Gain") + ": " + DeviceControlBridge.gainLabels()[gm]
            holder.findViewWithTag<View>("it_btn")?.setOnClickListener {
                DeviceControlBridge.setGain(gm)
                refreshDcHighlight()
            }
            val sz = dp(48)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            val lbl = TextView(requireContext())
            lbl.text = DeviceControlBridge.gainLabels()[gm]
            lbl.textSize = 11f
            lbl.gravity = Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(onContainerColor)
            col.addView(lbl, LinearLayout.LayoutParams(-1, -2))
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(4), 0, dp(4), 0)
            dcGainRow.addView(col, lp)
        }
        dcCard.addView(dcGainRow, LinearLayout.LayoutParams(-1, -2))

        // 指示灯行
        dcCard.addView(spacer(dp(8)))
        val dcLedRow = LinearLayout(requireContext())
        dcLedRow.orientation = LinearLayout.HORIZONTAL
        dcLedRow.gravity = Gravity.CENTER_VERTICAL
        dcLedRow.tag = "dc_led_row"
        val lLabel = TextView(requireContext())
        lLabel.text = Lang.t("指示灯", "Indicator")
        lLabel.textSize = 12f
        lLabel.setTextColor(onContainerColor)
        dcLedRow.addView(lLabel, LinearLayout.LayoutParams(-2, -2))
        dcLedRow.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
        val ledNames = arrayOf(Lang.t("开", "On"), Lang.t("关", "Off"))
        for (lm in 0..1) {
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            // 官方 Icon Button（toggle）：形状/状态层/形变交给 MaterialButton，
            // 图标仍是 DcIcons 自绘；不再手绘 OVAL 圆底 + 2dp 描边表示选中。
            val holder = M3Ui.iconToggle(requireContext(), pal0,
                    DcIcons.build(requireContext(), 2, lm, dp(24), onContainerColor), 48, 24)
            holder.tag = "dc_btn_led_" + lm
            holder.findViewWithTag<View>("it_btn")?.contentDescription =
                Lang.t("指示灯", "Indicator") + ": " + ledNames[lm]
            holder.findViewWithTag<View>("it_btn")?.setOnClickListener {
                DeviceControlBridge.setLed(lm)
                refreshDcHighlight()
            }
            val sz = dp(48)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            val lbl = TextView(requireContext())
            lbl.text = ledNames[lm]
            lbl.textSize = 11f
            lbl.gravity = Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(onContainerColor)
            col.addView(lbl, LinearLayout.LayoutParams(-1, -2))
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(4), 0, dp(4), 0)
            dcLedRow.addView(col, lp)
        }
        dcCard.addView(dcLedRow, LinearLayout.LayoutParams(-1, -2))

        root.addView(M3Ui.wrapInCard(requireContext(), ThemeUtil.Palette(requireContext()), dcCard), lp(false))
        dcControlCard = dcCard
        root.addView(spacer(dp(8)))

        // 3.3.2: 设备被跳过时的主动提示（issue #12 教训）。
        //
        // 此前拉黑是**静默**的：界面看不出异常，用户只看到「连不上」，
        // 自然不会想到去点「刷新状态」自救。
        // 3.2.20: 两个来源都要提示 ——
        //   rejectedNames   = 协议指纹已证伪
        //   exhaustedNames  = 探测预算用尽（刻意不写 rejected，见 ProbeBudget 说明）
        // 只提示后者之一都会让用户「看不出为什么要点刷新」。
        val skipped = unackedSkipped(
            (DeviceMatcher.rejectedNames(requireContext()) + ProbeBudget.exhaustedNames())
                .distinct()
        )
        if (skipped.isNotEmpty()) {
            root.addView(makeSkippedNotice(skipped))
            root.addView(spacer(dp(8)))
        }

        // alpha2.52: 点击后短暂禁用，防止连点重复触发刷新
        // 3.3.x: 加载反馈改为**按钮内部**替换成官方 LoadingIndicator（M3 Expressive 加载组件），
        // 不再在页面顶部单独挂一条 linear 进度条 —— 进度就该长在触发它的那个控件上。
        val refreshBox = android.widget.FrameLayout(requireContext())
        // 禁用/启用完全由加载态驱动（见 showRefreshBar / stopRefreshSpinner）：
        // 之前用 1500ms 防连点，时间一到按钮就自己解禁，而刷新还在跑 —— 加载中看着像可点。
        val refreshBtn = makeButton(Lang.t("刷新状态", "Refresh"),
                R.drawable.ic_refresh, container, onContainer, 0L) { showRefreshBar(); refreshAnc() }
        refreshBox.addView(refreshBtn, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        val refreshSpin = com.google.android.material.loadingindicator.LoadingIndicator(requireContext())
        refreshSpin.indicatorSize = dp(22)
        refreshSpin.setIndicatorColor(onContainerColor)
        refreshSpin.visibility = View.GONE
        refreshBox.addView(refreshSpin, android.widget.FrameLayout.LayoutParams(
                dp(22), dp(22), Gravity.CENTER))
        refreshBox.addView(View(requireContext()), android.widget.FrameLayout.LayoutParams(-1, dp(2)))
        this.refreshBtn = refreshBtn
        this.refreshSpin = refreshSpin
        root.addView(refreshBox, LinearLayout.LayoutParams(-1, -2))

        root.addView(spacer(dp(12)))

        root.addView(spacer(dp(20)))

        // alpha2.53: M3 LargeTopAppBar —— 大标题钉在上层，内容从下方穿过并随滚动收缩
        val page = M3Ui.largeHeaderPage(requireActivity(), pal0, "MoonEar")
        page.sv.addView(root)

        val outer = LinearLayout(requireContext())
        outer.orientation = LinearLayout.VERTICAL
        outer.setBackgroundColor(surface)
        outer.setPadding(0, statusBarH, 0, 0)

        outer.addView(page.container, LinearLayout.LayoutParams(-1, 0, 1f))

        // alpha1.4: 注册后台服务状态广播（电量/降噪更新）
        try {
            val sf = IntentFilter("com.fxxkmoondrop.secret.STATE_UPDATED")
            if (Build.VERSION.SDK_INT >= 33) {
                requireContext().registerReceiver(stateReceiver, sf, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                requireContext().registerReceiver(stateReceiver, sf)
            }
        } catch (_: Exception) { }

        // alpha1.20: 绑定广播上下文 + 响应 GMS 弹窗模式请求（高亮同步通道）
        AncBridge.bind(requireContext())
        try {
            val rf = IntentFilter(AncBridge.ACTION_FP_MODE_REQUEST)
            if (Build.VERSION.SDK_INT >= 33) {
                requireContext().registerReceiver(modeRequestReceiver, rf, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                requireContext().registerReceiver(modeRequestReceiver, rf)
            }
        } catch (_: Exception) { }

        refreshAnc()
        // alpha2.31: 刷新设备控制高亮
        refreshDcHighlight()
        requestNeededPermissions()
        updateStatus()
        return outer
    }

    /** alpha2.0: 主题架构官方化——模式/动态色/AMOLED/种子色全部走 ThemeUtil（SP 设置） */
    private fun isDark(): Boolean = ThemeUtil.isDark(requireContext())

    private fun lp(center: Boolean): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(-1, -2)
        if (center) p.gravity = Gravity.CENTER_HORIZONTAL
        return p
    }

    /**
     * alpha2.32: 刷新扩展设备控制按钮。
     * - 按型号档案（AncProfileLib.resolveDc）决定行可见性
     * - 未连接时灰禁用
     * - 连接后按当前状态高亮
     */
    private fun refreshDcHighlight() {
        val card = dcControlCard ?: return
        val gaia = GaiaBleClient.getInstance()
        val connected = try { gaia.isConnected() } catch (e: Exception) { false }
        val devName = if (connected) gaia.getConnectedDeviceName() else null
        val profile = AncProfileLib.resolveDc(devName)

        val spatialOn = DeviceControlBridge.isSpatialOn()
        val sMode = DeviceControlBridge.spatialUiMode()
        val gLevel = DeviceControlBridge.getGainLevel()
        val ledOn = DeviceControlBridge.getLedState() == 1
        val greyColor = onVariantColor

        val spatialRow = card.findViewWithTag<LinearLayout>("dc_spatial_row")
        val trackingRow = card.findViewWithTag<LinearLayout>("dc_tracking_row")
        val gainRow = card.findViewWithTag<LinearLayout>("dc_gain_row")
        val ledRow = card.findViewWithTag<LinearLayout>("dc_led_row")
        spatialRow?.visibility = if (connected && profile.hasSpatial) View.VISIBLE else View.GONE
        trackingRow?.visibility = if (connected && profile.hasSpatial) View.VISIBLE else View.GONE
        gainRow?.visibility = if (connected && profile.hasGain) View.VISIBLE else View.GONE
        ledRow?.visibility = if (connected && profile.hasLed) View.VISIBLE else View.GONE

        // alpha2.38.8: 未连接或无 DC 功能时隐藏整个卡片，避免空背景残留
        val anyDcVisible = connected && (profile.hasSpatial || profile.hasGain || profile.hasLed)
        card.visibility = if (anyDcVisible) View.VISIBLE else View.GONE

        val spSwitch = card.findViewWithTag<com.google.android.material.materialswitch.MaterialSwitch>("dc_spatial_switch")
        spSwitch?.let { sw ->
            sw.isEnabled = connected
            if (sw.isChecked != spatialOn) {
                dcSwitchSyncing = true
                sw.isChecked = spatialOn
                dcSwitchSyncing = false
                sw.setOnCheckedChangeListener { _, isChecked ->
                    if (!dcSwitchSyncing) {
                        DeviceControlBridge.setSpatialEnabled(isChecked)
                        refreshDcHighlight()
                    }
                }
            }
        }

        if (card.childCount >= 7) {
            card.getChildAt(1)?.visibility = if (connected) View.VISIBLE else View.GONE
            card.getChildAt(3)?.visibility = if (connected) View.VISIBLE else View.GONE
            card.getChildAt(5)?.visibility = if (connected) View.VISIBLE else View.GONE
        }

        for (i in 0 until card.childCount) {
            val row = card.getChildAt(i) as? LinearLayout ?: continue
            val rowTag = row.tag as? String ?: ""
            if (!rowTag.startsWith("dc_")) continue
            for (j in 0 until row.childCount) {
                val col = row.getChildAt(j) as? LinearLayout ?: continue
                for (k in 0 until col.childCount) {
                    val holder = col.getChildAt(k) as? android.widget.FrameLayout ?: continue
                    val tag = holder.tag as? String ?: continue
                    if (!tag.startsWith("dc_btn_")) continue
                    val btn = holder.findViewWithTag<View>("it_btn")
                            as? com.google.android.material.button.MaterialButton
                    val iv = holder.findViewWithTag<View>("it_img") as? ImageView
                    val parts = tag.substring(7).split("_")
                    if (parts.size != 2) continue
                    val feature = parts[0]
                    val idx = parts[1].toInt()
                    val active = when (feature) {
                        "spatial" -> spatialOn && idx == sMode
                        "gain" -> idx == gLevel
                        "led" -> idx == if (ledOn) 0 else 1
                        else -> false
                    }
                    val featType = when (feature) { "spatial" -> 0; "gain" -> 1; "led" -> 2; else -> 0 }
                    // 空间音频总开关关着时，追踪档位整组不可选
                    val enabled = connected && !(feature == "spatial" && !spatialOn)
                    // 状态全部交给官方控件：isChecked 驱动容器色，isEnabled 走官方 disabled 视觉。
                    // 不再手绘 OVAL 圆底/描边，也不再自己压 alpha。
                    btn?.isChecked = active
                    btn?.isEnabled = enabled
                    iv?.alpha = if (enabled) 1f else 0.38f
                    iv?.setImageDrawable(DcIcons.build(requireContext(), featType, idx, dp(24),
                            if (active) onPrimaryColor else if (enabled) onContainerColor else onVariantColor))
                }
            }
        }
    }

    private fun spacer(h: Int): View {
        val v = View(requireContext())
        v.layoutParams = LinearLayout.LayoutParams(1, h)
        return v
    }

    /** alpha2.52: 点击后短暂禁用并置灰，防止重复触发（默认 1.5 秒后恢复） */
    private fun guardClicks(v: View, ms: Long = 1500L, action: () -> Unit) =
            object : View.OnClickListener {
                override fun onClick(w: View?) {
                    if (!v.isEnabled) return
                    v.isEnabled = false
                    v.alpha = 0.45f
                    action()
                    v.postDelayed({
                        v.isEnabled = true
                        v.alpha = 1f
                    }, ms)
                }
            }

    /** alpha2.52: [debounceMs] > 0 时点击后短暂禁用并置灰，防止连点重复触发 */
    /**
     * 「有设备被跳过检测」提示卡。
     *
     * 只在有**尚未确认**的被跳过设备时出现；右上角可手动关闭。
     * 关闭后记住这些设备名，不再重复打扰；若之后又出现**新的**被跳过设备，
     * 会重新提示（自动只列新的那部分）。
     *
     * 用文字标题而非符号：项目 UI 不放 emoji / 装饰性符号。
     */
    private fun makeSkippedNotice(names: List<String>): View {
        val ctx = requireContext()
        val scheme = ThemeUtil.Palette(ctx)

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(14))


        // 标题行：标题（占满剩余宽度）+ 关闭按钮
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(ctx)
        title.text = Lang.t("有设备被跳过检测", "Device(s) skipped")
        title.textSize = 14f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setTextColor(red)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        val close = ImageView(ctx)
        close.setImageResource(R.drawable.ic_close)
        close.imageTintList = ColorStateList.valueOf(onVariantColor)
        close.contentDescription = Lang.t("关闭提示", "Dismiss")
        close.setPadding(dp(4), dp(4), dp(4), dp(4))
        close.setOnClickListener {
            ackSkipped(names)
            box.visibility = View.GONE
        }
        header.addView(close, LinearLayout.LayoutParams(dp(28), dp(28)))
        box.addView(header)

        val desc = TextView(ctx)
        desc.text = Lang.t(
            names.joinToString("、") + " 已被暂时跳过检测。"
                    + "若其中有你的耳机，点下方「刷新状态」即可重新检测。",
            names.joinToString(", ") + " were skipped."
                    + " If one is your headset, tap Refresh below to re-check.")
        desc.textSize = 13f
        desc.setTextColor(onVariantColor)
        desc.setPadding(0, dp(4), 0, 0)
        box.addView(desc)
        return M3Ui.wrapInCard(ctx, scheme, box)
    }

    /** 记下用户已确认过的被跳过设备名，之后同一设备不再重复提示。 */
    private fun ackSkipped(names: List<String>) {
        try {
            requireContext().getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .edit().putString(KEY_SKIPPED_ACK, names.joinToString("|")).apply()
        } catch (_: Throwable) { /* 存不下就下次再提示，不影响功能 */ }
    }

    /** 从全部被跳过设备里，筛出**用户还没确认过**的那些 —— 只有这些值得提示。 */
    private fun unackedSkipped(all: List<String>): List<String> {
        if (all.isEmpty()) return emptyList()
        val ack = try {
            requireContext().getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getString(KEY_SKIPPED_ACK, null)
                ?.split("|")?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet()
        } catch (_: Throwable) { emptySet<String>() }
        return all.filter { it !in ack }
    }

    private fun makeButton(text: String, iconRes: Int, bgColor: Int, textColor: Int,
                           debounceMs: Long = 0L, l: View.OnClickListener): MaterialButton {
        // alpha2.0: 官方 MaterialButton（Material3 按钮）+ 官方 Material Icons 图标
        // 交给官方 expressive 按钮：尺寸档（视觉 40dp + 4dp inset = 48dp 触摸区）、
        // 圆角、按压缩放形变全由 materialSizeOverlay / shape state list 决定。
        // 旧实现把 insetTop/Bottom 归零又手写 cornerRadius，触摸区只剩 40dp，还盖掉了形变。
        val b = M3Ui.actionButton(requireContext(), text, iconRes, bgColor, textColor, l, medium = true)
        b.elevation = if (bgColor == primaryColor) dp(1).toFloat() else 0f
        if (debounceMs > 0L) b.setOnClickListener(guardClicks(b, debounceMs) { l.onClick(b) })
        return b
    }

    private fun scanBonded(): String {
        try {
            if (Build.VERSION.SDK_INT >= 31
                    && requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return Lang.t("缺少蓝牙权限，无法扫描", "Missing BLUETOOTH permission, cannot scan")
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) return Lang.t("此设备无蓝牙", "No Bluetooth on this device")
            val bonded = adapter.bondedDevices
            val sb = StringBuilder()
            if (bonded != null) {
                for (d in bonded) {
                    val nm = d.name
                    if (nm != null && DeviceMatcher.isMoondrop(nm)) {
                        sb.append(nm).append(" · ").append(d.address).append("\n")
                    }
                }
            }
            if (sb.length == 0) return Lang.t("未找到已配对的 Moondrop 设备", "No paired Moondrop device found")
            return sb.toString().trim()
        } catch (e: Exception) {
            return Lang.t("扫描失败: ", "Scan failed: ") + e.message
        }
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 1)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
    }

    /** 自动检查：仅在权限缺失时弹窗告知（启动时调用，子线程执行避免阻塞 UI） */
    private fun autoCheckPermissions() {
        Thread {
            val items = PermissionChecker.checkAll(requireContext())
            requireActivity().runOnUiThread {
                if (PermissionChecker.countMissing(items) > 0) {
                    showPermissionDialog(items)
                }
            }
        }.start()
    }

    /** 权限自检（按钮）：子线程执行 + 弹窗告知 */
    /** alpha1.35: 权限检测改为整页二级界面（PermissionActivity），替代原 Dialog 浮窗 */
    private fun checkAndShowPermissions() {
        try {
            requireActivity().startActivity(Intent(requireContext(), PermissionActivity::class.java))
        } catch (t: Throwable) {
            android.util.Log.w("Fxxk", "open permissions: " + t.message)
        }
    }

    /** 权限自检结果弹窗：缺失项可点击直接跳转授权
     *  alpha1.13: 自绘 Material 卡片（动态取色 + 深浅色），与连接弹窗视觉统一 */
    private fun showPermissionDialog(items: List<PermissionChecker.Item>) {
        val missing = PermissionChecker.countMissing(items)
        val dlg = Dialog(requireContext())
        val win = dlg.window
        if (win != null) {
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            win.setDimAmount(0.5f)
        }

        val accent = primaryColor
        val onSurf = onSurfaceColor
        val onVariant = onVariantColor
        val cardColor = cardSurfaceColor
        val density = resources.displayMetrics.density

        val card = MaterialCardView(requireContext())
        card.setRadius(28 * density)
        card.setCardBackgroundColor(cardColor)
        card.setStrokeColor((accent and 0x00FFFFFF) or 0x33000000)
        card.setStrokeWidth((1.5f * density).toInt())
        card.setCardElevation(16 * density)
        val cardBody = LinearLayout(requireContext())
        cardBody.orientation = LinearLayout.VERTICAL
        cardBody.setPadding(dp(24), dp(22), dp(24), dp(24))
        card.addView(cardBody, android.widget.FrameLayout.LayoutParams(-1, -2))

        // 标题：缺失用主题色，全就绪用绿色（均已随深浅色取值）
        val head = TextView(requireContext())
        head.text = if (missing == 0) Lang.t("所有必要权限均已就绪", "All required permissions ready") else Lang.t("发现 ", "Found ") + missing + Lang.t(" 项权限缺失", " permission(s) missing")
        head.textSize = 22f
        head.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        head.setTextColor(if (missing == 0) green else accent)
        head.gravity = Gravity.CENTER_HORIZONTAL
        cardBody.addView(head, lp(false))
        cardBody.addView(spacer(dp(10)))

        // 权限列表（ScrollView 防内容溢出）
        val sv = ScrollView(requireContext())
        val box = LinearLayout(requireContext())
        box.orientation = LinearLayout.VERTICAL
        for (per in items) {
            val row = LinearLayout(requireContext())
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(8), dp(10), dp(8), dp(10))

            val dot = TextView(requireContext())
            dot.text = if (per.ok) "✔" else "✘"
            dot.textSize = 16f
            dot.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            dot.setTextColor(if (per.ok) green else red)
            row.addView(dot, LinearLayout.LayoutParams(dp(34), -2))

            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            val name = TextView(requireContext())
            name.text = per.name + (if (per.ok) "" else (if (per.action == PermissionChecker.ACTION_NONE) Lang.t("（需手动处理）", " (manual required)") else Lang.t("（点击修复）", " (tap to fix)")))
            name.textSize = 16f
            name.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            name.setTextColor(onSurf)
            col.addView(name, LinearLayout.LayoutParams(-2, -2))
            val detail = TextView(requireContext())
            detail.text = per.detail
            detail.textSize = 12f
            detail.setTextColor(onVariant)
            col.addView(detail, LinearLayout.LayoutParams(-2, -2))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            if (!per.ok) {
                // alpha1.13: 缺失项底色/水波纹随深浅色适配（深色用白色半透明）
                val bg = GradientDrawable()
                bg.setColor(if (isDark()) 0x14FFFFFF else 0x0A000000)
                bg.cornerRadius = dp(12).toFloat()
                val rd = M3Ui.pressRipple(requireContext(), bg)
                row.background = rd
                row.setOnClickListener { fixPermission(per) }
            }
            box.addView(row, lp(false))
        }
        sv.addView(box, FrameLayout.LayoutParams(-1, -2))
        cardBody.addView(sv, LinearLayout.LayoutParams(-1, -2))

        // M3 TextButton 风格按钮（主题色文字 + 水波纹）
        val btnRow = LinearLayout(requireContext())
        btnRow.gravity = Gravity.END
        btnRow.addView(makeMaterialTextButton(if (missing == 0) Lang.t("知道了", "Got it") else Lang.t("稍后处理", "Later"),
                accent) { dlg.dismiss() })
        cardBody.addView(btnRow, lp(false))

        dlg.setContentView(card)
        dlg.setCanceledOnTouchOutside(true)
        dlg.show()
        if (win != null) {
            val wlp = win.attributes
            wlp.width = (resources.displayMetrics.widthPixels - 48 * density).toInt()
            wlp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            win.attributes = wlp
        }

        // 入场动画（与连接悬浮卡片一致：fade + scale 300ms）
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(Motion.MEDIUM2)
                .setInterpolator(Motion.enter())
                .start()
    }

    /** 跳转授权：运行时权限 / 电池优化 / 打开 App */
    private fun fixPermission(it: PermissionChecker.Item) {
        try {
            when (it.action) {
                PermissionChecker.ACTION_RUNTIME -> {
                    if (it.requestCode == 1) {
                        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 1)
                    } else if (it.requestCode == 2) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                    }
                }
                // 3.0.5: 一键申请电池优化白名单（无需 Root）
                PermissionChecker.ACTION_BATTERY -> KeepAlive.requestWhitelist(requireActivity())
                else -> toast(it.detail)
            }
        } catch (e: Exception) {
            toast(Lang.t("跳转失败: ", "Open failed: ") + e.message)
        }
    }

    private fun updateStatus() {
        // alpha2.53: 徽章显示当前耳机在用的编解码器，随连接状态刷新
        statusBadge?.text = badgeText()
        refreshCodecBadge()
        updateRunStatus()
        // alpha2.41.10: 主按钮已移除，运行状态统一由英雄卡展示
        // alpha2.4: onResume 同步刷新状态面板（修复模拟恢复后电量行残留显示）
        updateStatusPanel()
    }

    /** alpha1.11: 监听服务 + 降噪控制服务 整合为一项运行状态 */
    private fun updateRunStatus() {
        val svc = HeadsetDetectService.RUNNING
        var ancSt = moonProcState
        if (ancSt == null) ancSt = Lang.t("未知", "Unknown")
        val ancOk = ancSt.contains("运行中")
        val ancFrozen = ancSt.contains("冻结")
        val text: String
        if (svc && ancOk) {
            text = Lang.t("运行中", "Running")
        } else if (svc && ancFrozen) {
            text = Lang.t("运行中（降噪控制已冻结）", "Running (ANC frozen)")
        } else if (svc) {
            text = Lang.t("监听运行中 · 降噪控制未运行", "Monitor running · ANC not running")
        } else if (context?.let { KeepAlive.enabled(it) && !KeepAlive.shouldRecover(it) } == true) {
            text = Lang.t("等待耳机连接", "Waiting for headset")
        } else if (ancOk) {
            text = Lang.t("监听未运行 · 降噪控制运行中", "Monitor not running · ANC running")
        } else {
            text = Lang.t("未运行", "Not running")
        }
        setHeroStatus(text)
    }

    private fun setHeroStatus(text: String) {
        var iconRes = R.drawable.ic_hourglass_empty
        val label = text
        // 3.3.2: 判据从 emoji 前缀改为**文字前缀**。
        //
        // 原实现靠 "✅" / "❄️" / "⚠️" / "⛔" 前缀区分状态 —— 等于把「状态」
        // 编码进「展示文案」再解析回来：既脆弱（改文案就静默失效），
        // 又逼着 UI 文案必须带 emoji。文案去掉 emoji 后这几行会全部落空，
        // 状态一律显示成「检查中」+ 沙漏，属严重回归。
        //
        // 「冻结」必须**先于**「运行中」判断 —— 冻结态的文案是
        // 「运行中（降噪控制已冻结）」，同样以「运行中」开头。
        if (text.contains("冻结")) {
            iconRes = R.drawable.ic_ac_unit
        } else if (text.startsWith("运行中")) {
            iconRes = R.drawable.ic_check
        } else if (text.startsWith("监听")) {
            iconRes = R.drawable.ic_warning
        } else if (text.startsWith("未运行")) {
            iconRes = R.drawable.ic_block
        }
        // 官方 StatusHeader：品牌后跟短状态词（Active/…），完整说明交给 detail 行
        val shortWord: String
        if (iconRes == R.drawable.ic_check) shortWord = Lang.t("运行中", "Running")
        else if (iconRes == R.drawable.ic_ac_unit) shortWord = Lang.t("运行中", "Running")
        else if (iconRes == R.drawable.ic_warning) shortWord = if (label.contains("监听未运行")) Lang.t("已停止", "Stopped") else Lang.t("监听中", "Monitoring")
        else if (iconRes == R.drawable.ic_block) shortWord = Lang.t("未运行", "Not running")
        else shortWord = Lang.t("检查中", "Checking")
        statusIcon?.let {
            it.setImageResource(iconRes)
            it.imageTintList = ColorStateList.valueOf(heroFg)
        }
        statusIconChip?.let {
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor((heroFg and 0x00FFFFFF) or 0x26000000)
            it.background = g
        }
        svcStatus?.text = shortWord
        svcStatus?.setTextColor(heroFg)
    }

    override fun onResume() {
        super.onResume()
        GaiaBleClient.getInstance().setCallback(gaiaUiCallback)
        try {
            requireContext().registerReceiver(macUpdReceiver,
                    IntentFilter(HeadsetGate.ACTION_MAC_UPDATED))
        } catch (_: Exception) { }
        updateStatus()
        refreshAnc()
        // alpha2.32: 回前台时刷新 DC 按钮状态（设备可能已连接）
        refreshDcHighlight()
        // alpha2.33: DC 状态变化监听（空间/增益/LED 回包后刷新 UI）
        DeviceControlBridge.setStateListener {
            activity?.runOnUiThread { refreshDcHighlight() }
        }
        // alpha2.26.2: 每次回到前台按最新设置应用抗风按钮可见性（设置页切换后返回即时生效）
        try {
            val sw = requireContext().getSharedPreferences("cfg", 0)
                    .getBoolean("show_wind", true)
            ancWindCol?.visibility = if (sw) View.VISIBLE else View.GONE
        } catch (_: Exception) { }
        // alpha1.11: 前台自动刷新耳机信息（30 秒一次）
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        autoRefreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_MS)
    }

    override fun onStop() {
        super.onStop()
        GaiaBleClient.instance?.releaseCallback(gaiaUiCallback)
        try {
            requireContext().unregisterReceiver(macUpdReceiver)
        } catch (_: Exception) { }
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
    }

    /** 3.0.5: 自定义图标存在性改为查本应用 filesDir（无需 Root，毫秒级）。 */
    private fun iconCustomExists(): Boolean = IconStore.exists(requireContext().applicationContext)

    private fun iconCustomExistsAsync(callback: (Boolean) -> Unit) {
        callback(iconCustomExists())
    }

    // ── alpha1.14fix5: 弹窗图标选择 → Material experience 卡片（与权限弹窗同风格）──
    /**
     * 3.0.5: 相册选图改用 ActivityResultLauncher。
     *
     * 旧实现走 `requireActivity().startActivityForResult` + Fragment.onActivityResult，
     * 实测（Android 15 / ColorOS）结果根本不回调到 Fragment —— 选中图片后什么都没发生，
     * 也就是用户看到的「图标怎么改都没反应」。
     */
    private val iconPicker = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) saveIconFromUri(uri) }

    private fun showIconDialog(custom: Boolean) {
        val dlg = Dialog(requireContext())
        val win = dlg.window
        if (win != null) {
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            win.setDimAmount(0.5f)
        }
        val accent = primaryColor
        val onSurf = onSurfaceColor
        val onVariant = onVariantColor
        val cardColor = cardSurfaceColor
        val density = resources.displayMetrics.density

        val card = MaterialCardView(requireContext())
        card.setRadius(28 * density)
        card.setCardBackgroundColor(cardColor)
        card.setStrokeColor((accent and 0x00FFFFFF) or 0x33000000)
        card.setStrokeWidth((1.5f * density).toInt())
        card.setCardElevation(16 * density)
        val cardBody = LinearLayout(requireContext())
        cardBody.orientation = LinearLayout.VERTICAL
        cardBody.setPadding(dp(24), dp(22), dp(24), dp(24))
        card.addView(cardBody, android.widget.FrameLayout.LayoutParams(-1, -2))

        val head = TextView(requireContext())
        head.text = Lang.t("弹窗图标（当前：", "Popup icon (current: ") + (if (custom) Lang.t("已自定义", "Custom") else Lang.t("默认", "Default")) + ")"
        head.textSize = 22f
        head.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        head.setTextColor(accent)
        head.gravity = Gravity.CENTER_HORIZONTAL
        cardBody.addView(head, lp(false))
        cardBody.addView(spacer(dp(10)))

        val items = if (custom) arrayOf(Lang.t("从相册选择", "Choose from gallery"), Lang.t("恢复默认图标", "Restore default icon")) else arrayOf(Lang.t("从相册选择", "Choose from gallery"))
        val subs = if (custom) arrayOf(Lang.t("替换 Google 弹窗显示的耳机图标", "Replace the earbud icon in the Google popup"), Lang.t("恢复软件默认图标", "Restore the built-in default icon"))
        else arrayOf(Lang.t("替换 Google 弹窗显示的耳机图标", "Replace the earbud icon in the Google popup"))
        for (i in items.indices) {
            val which = i
            val row = LinearLayout(requireContext())
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            val rowBg = GradientDrawable()
            rowBg.setColor(if (isDark()) 0x14FFFFFF else 0x0A000000)
            rowBg.cornerRadius = dp(12).toFloat()   // M3 形状档 small=8 / medium=12，14 是散值
            val rd = M3Ui.pressRipple(requireContext(), rowBg)
            row.background = rd
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            val t1 = TextView(requireContext())
            t1.text = items[i]
            t1.textSize = 16f
            t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            t1.setTextColor(onSurf)
            col.addView(t1, LinearLayout.LayoutParams(-2, -2))
            val t2 = TextView(requireContext())
            t2.text = subs[i]
            t2.textSize = 12f
            t2.setTextColor(onVariant)
            t2.alpha = 0.7f
            col.addView(t2, LinearLayout.LayoutParams(-2, -2))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(M3Ui.chevron(requireActivity(), accent), LinearLayout.LayoutParams(-2, -2))
            row.setOnClickListener {
                dlg.dismiss()
                if (which == 0) {
                    try {
                        iconPicker.launch("image/*")
                    } catch (t: Throwable) {
                        toast(Lang.t("无法打开选择器：", "Cannot open picker: ") + t.message)
                    }
                } else {
                    resetCustomIcon()
                }
            }
            cardBody.addView(row, lp(false))
            cardBody.addView(spacer(dp(8)))
        }

        val btnRow = LinearLayout(requireContext())
        btnRow.gravity = Gravity.END
        btnRow.addView(makeMaterialTextButton(Lang.t("取消", "Cancel"), accent) { dlg.dismiss() })
        cardBody.addView(btnRow, lp(false))

        dlg.setContentView(card)
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.85).toInt(), -2)
        dlg.show()
    }

    private fun resetCustomIcon() {
        // 3.0.5: 只删本应用内的图标文件（无需 Root）
        val ok = IconStore.clear(requireContext().applicationContext)
        toast(if (ok) Lang.t("已恢复默认图标（下次连接生效）", "Default icon restored (takes effect next connect)")
        else Lang.t("恢复失败", "Restore failed"))
    }

    private fun saveIconFromUri(uri: Uri) {
        Thread {
            try {
                val input = requireContext().contentResolver.openInputStream(uri)
                if (input == null) {
                    requireActivity().runOnUiThread { toast(Lang.t("无法读取所选图片", "Cannot read the selected image")) }
                    return@Thread
                }
                val bmp = BitmapFactory.decodeStream(input)
                try { input.close() } catch (_: Exception) { }
                if (bmp == null) {
                    requireActivity().runOnUiThread { toast(Lang.t("图片解码失败", "Image decode failed")) }
                    return@Thread
                }
                val w = bmp.width
                val h = bmp.height
                val longSide = Math.max(w, h)
                var target = if (longSide > 512) 512 else longSide
                var scaled = bmp
                val out = File(requireContext().cacheDir, "moondrop_custom_icon.png")
                for (i in 0 until 4) {
                    if (target < 128) break
                    val sc = target / Math.max(longSide, 1).toFloat()
                    if (sc < 1f) {
                        val s2 = Bitmap.createScaledBitmap(bmp,
                                Math.max(1, (w * sc).toInt()), Math.max(1, (h * sc).toInt()), true)
                        if (s2 !== scaled && scaled !== bmp) scaled.recycle()
                        scaled = s2
                    }
                    val fos = FileOutputStream(out)
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    fos.close()
                    if (out.length() <= 1024 * 1024) break
                    target = (target * 0.75f).toInt()
                }
                val size = out.length()
                if (size > 1024 * 1024) {
                    requireActivity().runOnUiThread { toast(Lang.t("图片超过 1 MB", "Image exceeds 1 MB")) }
                    return@Thread
                }
                // 3.0.5: 写本应用 filesDir（无需 Root），GMS 侧 Hook 经 ContentProvider 读取
                val ok = IconStore.save(requireContext().applicationContext, out.readBytes())
                requireActivity().runOnUiThread { toast(if (ok) Lang.t("弹窗图标已更新（下次连接生效）", "Popup icon updated (takes effect next connect)") else Lang.t("写入图标失败", "Write icon failed")) }
                if (scaled !== bmp) scaled.recycle()
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
            } catch (t: Throwable) {
                requireActivity().runOnUiThread { toast(Lang.t("图标处理失败: ", "Icon process failed: ") + t.message) }
            }
        }.start()
    }



    /** 3.0.4: 统一走 RootShell（su / kp 自适应）；失败返回 null。 */
    private fun runRoot(cmd: String): String? = RootShell.exec(cmd)

    override fun onDestroyView() {
        super.onDestroyView()
        DeviceControlBridge.setStateListener(null)
        try { requireContext().unregisterReceiver(ancModeReceiver) } catch (_: Exception) { }
        try { requireContext().unregisterReceiver(stateReceiver) } catch (_: Exception) { }
        try { requireContext().unregisterReceiver(modeRequestReceiver) } catch (_: Exception) { }
    }

    /** alpha1.4: 后台服务 GAIA 状态广播（电量/降噪更新） */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            requireActivity().runOnUiThread {
                hideRefreshBar.run()
                updateAncStatus()
                // alpha2.32: STATE_UPDATED 到来时连接已就绪，刷新 DC 按钮启用状态
                refreshDcHighlight()
            }
        }
    }

    private val ancModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val mode = i.getIntExtra("mode", -1)
            if (mode >= 0 && mode < ANC_NAMES.size) {
                ancMode = mode
                requireActivity().runOnUiThread { updateAncStatus() }
            }
        }
    }

    /** alpha1.20: GMS 弹窗请求当前模式 -> 回发 MODE_STATE（弹窗按钮高亮同步） */
    private val modeRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            AncBridge.sendModeState()
            // alpha2.22: 一并回发 ANC 能力状态，驱动弹窗降噪按钮三态
            AncBridge.sendAncStatus(GaiaBleClient.getInstance().ancCapabilityStatus())
        }
    }

    /** alpha1.20: 主界面模式按钮图标（与 Google 弹窗同款绘制：电源/波浪/耳朵），颜色动态 */
    private fun buildMainModeIcon(mode: Int, px: Int, color: Int): android.graphics.drawable.Drawable? {
        // alpha2.52: 统一走 M3Ui 的 Material Symbols 矢量图标（主界面 / GMS 弹窗 / hook 面板同源）
        // 选中态前景是 onPrimary（深色主题下不是白色），缓存键必须跟着改，否则深色下永不命中
        val cacheKey = mode * 2 + (if (color == onPrimaryColor) 1 else 0)
        val cache = ancIconCache
        if (cache != null && cacheKey < cache.size) {
            cache[cacheKey]?.let { return it }
        }
        val drawable = M3Ui.ancModeDrawable(requireContext(), mode, px, color)
        if (ancIconCache == null) ancIconCache = arrayOfNulls(8)
        if (cacheKey < 8) ancIconCache!![cacheKey] = drawable
        return drawable
    }

    /** alpha2.53: 刷新进度条显隐（超时兜底，避免状态回包丢失时长亮） */
    /**
     * 点击刷新的加载反馈：把按钮文字换成官方 LoadingIndicator，刷新结果回来自动复原。
     * 位置就在按钮里面（替代原先挂在页面顶部、跟触发点分离的 linear 进度条）。
     */
    private fun showRefreshBar() {
        val b = refreshBtn ?: return
        val spin = refreshSpin ?: return
        b.text = ""
        b.icon = null
        b.isEnabled = false          // 加载期间保持禁用：整块按钮不可点，也不会被误判为「可再点一次」
        spin.visibility = View.VISIBLE
        b.removeCallbacks(hideRefreshBar)
        b.postDelayed(hideRefreshBar, 6000L)
    }

    /** 复原按钮文案与图标（刷新完成/超时都走这里） */
    private fun stopRefreshSpinner() {
        val b = refreshBtn ?: return
        if (b.text.isNullOrEmpty()) b.text = Lang.t("刷新状态", "Refresh")
        if (b.icon == null) b.icon = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.ic_refresh)
        refreshSpin?.visibility = View.GONE
        b.isEnabled = true
    }

    /**
     * alpha2.53: 当前耳机实际在用的蓝牙协议（原先是固定显示 API 级别，与设备无关）。
     *
     * 用公开的 AudioManager 输出设备类型判定，不做私有 API 猜测：
     *  LE Audio（TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER，API 31+）
     *  > A2DP 经典音频 > 仅 GAIA BLE 数据链路 > 未连接
     */
    /**
     * alpha2.53: 当前耳机在用的蓝牙音频编解码器（原先是固定显示 API 级别，与设备无关）。
     *
     * 编解码信息只有系统侧蓝牙栈知道（BluetoothA2dp.getCodecStatus 是 @SystemApi，
     * 需要 BLUETOOTH_PRIVILEGED，普通应用拿不到），因此走已有的 Root 通道读 dumpsys。
     * 读不到时退回链路类型，再退到「未连接」，任何情况下不空着。
     */
    private fun refreshCodecBadge() {
        // 无链路时不查（dumpsys 也在百毫秒级），并顺手清掉可能存在的过期值
        if (linkTypeLabel() == null) {
            codecLabel = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (codecQuerying || now - lastCodecQueryMs < 5000L) return
        codecQuerying = true
        lastCodecQueryMs = now
        Thread {
            val out = runRoot("dumpsys bluetooth_manager | grep -m1 'mCodecConfig:'")
            val name = Regex("codecName:([A-Za-z0-9 ]+)").find(out ?: "")?.groupValues?.get(1)?.trim()
            activity?.runOnUiThread {
                codecQuerying = false
                if (!name.isNullOrEmpty()) codecLabel = name
                statusBadge?.text = badgeText()
            }
        }.start()
    }

    /**
     * 徽章文案：编解码器 > 链路类型 > 未连接。
     *
     * 断开时必须把缓存的编解码器一起丢掉 —— 否则耳机断连后徽章仍停留在
     * 「LDAC」这种已失效的值上（实测踩到）。
     */
    private fun badgeText(): String {
        val link = linkTypeLabel()
        if (link == null) {
            codecLabel = null
            return Lang.t("未连接", "Not connected")
        }
        codecLabel?.let { if (it.isNotEmpty()) return it }
        return link
    }

    /**
     * 链路类型：系统侧标记语义（ACL BR/EDR / LE / LE Audio）。
     *
     * 无任何链路时返回 **null**（而不是「未连接」字符串）—— 调用方要据此判断
     * 「是不是该把缓存的编解码器丢掉」，用字符串比较会很脆。
     */
    private fun linkTypeLabel(): String? {
        return try {
            val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val types = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
            when {
                Build.VERSION.SDK_INT >= 31 &&
                        (android.media.AudioDeviceInfo.TYPE_BLE_HEADSET in types ||
                                android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER in types) -> "LE Audio"
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types ||
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types -> "ACL"
                GaiaBleClient.getInstance().isConnected() -> "LE"
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    private fun refreshAnc() {
        // alpha2.52: 用户主动点「刷新状态」= 明确要求重试。
        // 清空探测失败名单，让此前被判定为「无 GAIA/9ECA」的设备重新走一遍
        // 协议指纹探测（HeadsetDetectService 下一轮轮询内放行，最长约 5 秒）。
        DeviceMatcher.clearRejected(requireContext())
        // 3.3.2: 同时重置探测预算 —— 否则「用满即停」之后用户点刷新也不会有任何反应，
        // 按钮看起来是坏的。用户显式要求重试 = 重新给一轮额度。
        ProbeBudget.reset()
        refreshAnc(true, force = true)
    }

    /** alpha1.11: showToast=false 供自动刷新调用（未连接时不打扰）
     *  alpha2.28: 加 3 秒节流，防止 onResume/广播/autoRefresh 短时间内重复触发 forceReconnect */
    private fun refreshAnc(showToast: Boolean, skipBattery: Boolean = false, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshAncMs < 3000) return
        lastRefreshAncMs = now
        val gaia = GaiaBleClient.getInstance()
        val conn = gaia.isConnected()
        ancMode = AncBridge.getCurrentMode()
        moonProcState = serviceState()
        updateAncStatus()
        updateBatteryStatus()
        if (conn) {
            AncBridge.fetchAncMode()
            if (!skipBattery) gaia.fetchBatteryLevels()
        } else {
            val mac = HeadsetGate.getConnectedMac(requireContext())
            if (mac != null) {
                val generation = gaia.setCallback(gaiaUiCallback)
                // alpha1.4: 前台刷新强制重连（重新解析 LE 地址/扫描）
                // alpha1.36: 移出主线程（原主线程 BLE 重连 + 阻塞探测导致 ANR）
                val appContext = requireContext().applicationContext
                Thread { gaia.forceReconnect(appContext, mac, gaiaUiCallback, generation) }.start()
            } else if (showToast) {
                // alpha1.35: 移除"未检测到 Moondrop 耳机"toast（用户反馈打扰）
            }
        }
    }

    private fun updateAncStatus() {
        updateRunStatus()
        // alpha2.53: 徽章随连接状态同步刷新。
        // 断开时 stateReceiver 只走 updateAncStatus（不走 updateStatus），
        // 不在这里刷就会出现「已断连但仍显示 LDAC」。
        refreshCodecBadge()
        statusBadge?.text = badgeText()
        // alpha1.4: 降噪控制区块仅随真实耳机连接显示（模拟按钮不影响）
        val realConnected = HeadsetGate.getConnectedMac(requireContext()) != null
        // 3.2.10: 判据修正 —— moonProcState 取自 serviceState()，说的是「后台监听服务是否在跑」，
        // 而 enable 默认 true，于是它几乎恒为「✅ 运行中」。拿它当 GAIA 连接状态，结果是
        // 蓝牙刚连上、GAIA 还没握手的窗口期里，降噪按钮已经显示成可用，点下去毫无反应。
        // 真正的 GAIA 连接态只有 GaiaBleClient 知道 —— 等它握手成功再放行按钮。
        val gaiaConnected = try {
            GaiaBleClient.getInstance().isConnected()
        } catch (_: Throwable) {
            false
        }
        val ancEnabled = realConnected && gaiaConnected
        // 3.2.7: 设备已连接且能力探测**明确判定无 ANC**（probe.status()==2）时，
        // 整个降噪区块（标题 + 按钮行）隐藏。此前是无条件显示，于是像 U.C.T.S(MD-OWS-014)
        // 这种硬件本身就没有降噪的型号，主界面会挂着一排点了没反应的降噪按钮（issue #9）。
        // 弹窗与通知本来就按能力处理（status==2 隐藏 mode bar / supportedUiModes 为空则无按钮），
        // 这里只是把主界面补齐到同一条规则上。
        // 未连接、或能力尚未探明（status==0）时维持既有行为：显示但置灰 —— 避免连接期间闪烁。
        val showAncBlock = !realConnected || try {
            GaiaBleClient.getInstance().ancCapabilityStatus() != 2
        } catch (_: Throwable) {
            true
        }
        ancTitle?.let {
            it.visibility = if (showAncBlock) View.VISIBLE else View.GONE
            it.alpha = if (ancEnabled) 1f else 0.45f
        }
        ancBtnRow?.let { it.visibility = if (showAncBlock) View.VISIBLE else View.GONE }
        // 3.2.12: 均衡器入口只在设备确实提供 9ECA 服务时出现
        eqRow?.let {
            // 均衡器现在走经典蓝牙 SPP（0A05 读 / 0A06 写），不再是 9ECA 私有服务：
            // 只要是水月雨设备就先显示，页面自己读一次曲线来确认这台型号真的支持。
            val isMd = try {
                DeviceMatcher.isMoondrop(GaiaBleClient.getInstance().getConnectedDeviceName())
            } catch (_: Throwable) {
                false
            }
            it.visibility = if (isMd) View.VISIBLE else View.GONE
        }
        // 3.2.20: 档位可见性统一按「型号档案声明支持」+「用户设置」决定。
        //
        // 原来只门控自适应(4) 一档，0..3 恒显示 —— 于是像知更鸟这类只有 2 档的设备
        // （档案 setMap=[-1,1,2,-1]）会多出「关闭」「抗风噪」两个点了没反应的死按钮。
        // 用户的原话是「透传按钮貌似是个摆设」，正是同一类问题。
        //
        // 规则（与设备详情面板、通知栏共用同一份 supportedUiModes）：
        //   · 档案声明支持该档 -> 显示；声明不支持(-1) -> 隐藏
        //   · 能力尚未探明（空数组）-> 维持既有「显示但置灰」，避免连接期间闪烁
        //   · 抗风(3) 另受用户开关 show_wind 约束（沿用既有设置项）
        //   · 自适应(4) 另要求 realConnected（连接后能力才会被探到；断开即收回）
        ancCols?.let { cols ->
            val uiModes = try {
                GaiaBleClient.getInstance().supportedUiModes()
            } catch (_: Throwable) {
                IntArray(0)
            }
            val windByUser = try {
                requireContext().getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .getBoolean("show_wind", true)
            } catch (_: Exception) {
                true
            }
            for (i in cols.indices) {
                val col = cols[i] ?: continue
                val supported = uiModes.isEmpty() || uiModes.contains(i)
                val byUser = if (i == 3) windByUser else true
                val needsConn = if (i == 4) realConnected else true
                col.visibility = if (supported && byUser && needsConn) View.VISIBLE else View.GONE
            }
        }
        ancBtns?.let { btns ->
            for (i in btns.indices) {
                val hol = btns[i] ?: continue
                hol.isEnabled = ancEnabled   // 官方 disabled 态自带 38% 视觉，不额外压 alpha
                val sel = (i == ancMode)
                // 选中态交给官方 MaterialButton（isChecked 驱动容器色），图标单独换色
                val mb = hol.findViewWithTag<View>("anc_btn")
                        as? com.google.android.material.button.MaterialButton
                mb?.isChecked = sel
                mb?.isEnabled = ancEnabled
                val iv = hol.findViewWithTag<View>("fxxk_main_icon") as? ImageView
                iv?.setImageDrawable(buildMainModeIcon(i, dp(28),
                        if (sel) onPrimaryColor else onContainerColor))
                iv?.alpha = if (ancEnabled) 1f else 0.38f
                ancLabels?.get(i)?.let {
                    it.setTextColor(if (sel) primaryColor else onContainerColor)
                    it.alpha = if (ancEnabled) 1f else 0.4f
                }
            }
        }
        AncBridge.sendModeState() // alpha1.20: 把当前模式同步给 GMS 弹窗（按钮高亮）
        updateStatusPanel()
    }

    /** 降噪控制服务真实状态：监听服务运行中即为运行（模拟按钮不影响） */
    private fun serviceState(): String =
            if (HeadsetDetectService.RUNNING ||
                    requireContext().getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("enable", true))
                Lang.t("运行中", "Running") else Lang.t("未运行", "Not running")

    /** alpha2.4: 左右耳电量 → 运行状态面板（与弹窗同源 BatteryStore）
     *  alpha2.52: 显隐判据绑定耳机「实时连接态」，与同一卡片内「耳机连接」行完全一致。
     *  原先只看 gaia.deviceAddress —— 那是最后锁定的地址，耳机断开后可能被 GATT
     *  重连逻辑保活，于是未连接时电量行仍残留显示。
     *  @param connectedMac 面板路径传入已算好的连接态；省略则实时查询
     *                      （HeadsetGate.getConnectedMac 主线程走非阻塞 quickScan） */
    private fun updateBatteryStatus(
            connectedMac: String? = HeadsetGate.getConnectedMac(requireContext()),
            sim: Boolean = GaiaBleClient.isSimConnected()) {
        val bVal = battVal ?: return
        val gaia = GaiaBleClient.getInstance()
        var mac: String? = if (connectedMac != null) (gaia.deviceAddress ?: connectedMac) else null
        if (mac == null && sim) mac = SIM_MAC
        val text: String
        if (mac != null) {
            val l = BatteryStore.getLeft(mac)
            val r = BatteryStore.getRight(mac)
            if (l >= 0 && r >= 0) {
                text = Lang.t("左耳 ", "L ") + l + "%  ·  " + Lang.t("右耳 ", "R ") + r + "%"
            } else if (l >= 0) {
                text = Lang.t("左耳 ", "L ") + l + "%"
            } else if (r >= 0) {
                text = Lang.t("右耳 ", "R ") + r + "%"
            } else {
                text = "--%"
            }
        } else {
            text = "--%"
        }
        bVal.text = text
        // alpha2.8: 左右耳电量行出现/消失动画（Material fade+slide）
        battRow?.let { row ->
            val show = mac != null
            if (show != battRowShown) {
                battRowShown = show
                if (show) {
                    row.visibility = View.VISIBLE
                    if (row.isLaidOut) {
                        row.alpha = 0f
                        row.translationY = dp(8).toFloat()
                        row.animate().alpha(1f).translationY(0f)
                                .setDuration(Motion.MEDIUM1)
                                .setInterpolator(Motion.enter()).start()
                    }
                } else {
                    if (row.isLaidOut) {
                        row.animate().alpha(0f).translationY(dp(8).toFloat())
                                .setDuration(Motion.SHORT4)
                                .setInterpolator(Motion.exit())
                                .withEndAction { if (!battRowShown) row.visibility = View.GONE }
                                .start()
                    } else {
                        row.visibility = View.GONE
                    }
                }
            }
            if (!row.isLaidOut) {
                // 首次布局前：直接应用可见性，避免默认 VISIBLE 与逻辑状态不一致
                row.visibility = if (show) View.VISIBLE else View.GONE
                row.alpha = 1f
                row.translationY = 0f
            }
        }
    }

    /** alpha2.4: 运行状态面板（GAIA 连接 / 耳机连接 / 左右耳电量；分组卡官方样式） */
    private fun buildStatusPanel(root: LinearLayout) {
        val card = LinearLayout(requireContext())
        card.orientation = LinearLayout.VERTICAL

        card.addView(makeStatusRow(R.drawable.ic_bluetooth, Lang.t("GAIA 状态", "GAIA Status"), 0),
                LinearLayout.LayoutParams(-1, -2))
        card.addView(makeStatusRow(R.drawable.ic_headphones, Lang.t("耳机连接", "Earbud Connection"), 1),
                LinearLayout.LayoutParams(-1, -2))
        battRow = makeStatusRow(R.drawable.ic_battery_full, Lang.t("左右耳电量", "L/R Battery"), 2)
        // alpha2.52: 新建行默认 GONE 并把动画状态复位。
        // 原实现新建行是默认 VISIBLE，而 battRowShown 初始为 false，
        // 当 show==false 时 `show != battRowShown` 判定不成立 -> 漏掉隐藏 -> 未连接时残留显示。
        battRowShown = false
        battRow?.visibility = View.GONE
        card.addView(battRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(M3Ui.wrapInCard(requireContext(), ThemeUtil.Palette(requireContext()), card), lp(false))
    }

    private fun makeStatusRow(iconRes: Int, title: String, which: Int): LinearLayout {
        val row = LinearLayout(requireContext())
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        // M3 单行列表规范：行高 ≥56dp，水平/垂直内边距 16dp
        row.setPadding(dp(16), dp(16), dp(16), dp(16))
        row.minimumHeight = dp(56)
        val ic = ImageView(requireContext())
        ic.setImageResource(iconRes)
        ic.imageTintList = ColorStateList.valueOf(onContainerColor)
        val ilp = LinearLayout.LayoutParams(dp(24), dp(24))
        ilp.marginEnd = dp(16)
        row.addView(ic, ilp)
        val t = TextView(requireContext())
        t.text = title
        t.textSize = 14f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(onSurfaceColor)
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        val valTv = TextView(requireContext())
        valTv.textSize = 14f
        valTv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        valTv.setTextColor(onVariantColor)
        valTv.gravity = Gravity.END
        valTv.isSingleLine = true
        row.addView(valTv, LinearLayout.LayoutParams(-2, -2))
        when (which) {
            0 -> gaiaStateVal = valTv
            1 -> headsetStateVal = valTv
            2 -> battVal = valTv
            3 -> devNameVal = valTv
            4 -> devMacVal = valTv
            else -> devProfileVal = valTv
        }
        return row
    }

    /**
     * alpha2.52: 设备信息卡（设备名称 / 设备地址 / 型号档案）。
     * 行结构与状态卡一致（M3 单行列表规范），数据全部来自既有来源，未连接时占位。
     */
    private fun buildDeviceInfoCard(root: LinearLayout) {
        val card = LinearLayout(requireContext())
        card.orientation = LinearLayout.VERTICAL

        card.addView(makeStatusRow(R.drawable.ic_devices,
                Lang.t("设备名称", "Device name"), 3), LinearLayout.LayoutParams(-1, -2))
        card.addView(makeStatusRow(R.drawable.ic_settings_ethernet,
                Lang.t("设备地址", "Device address"), 4), LinearLayout.LayoutParams(-1, -2))
        card.addView(makeStatusRow(R.drawable.ic_tune,
                Lang.t("型号档案", "Model profile"), 5), LinearLayout.LayoutParams(-1, -2))
        // alpha2.52: 未连接 GAIA 时整卡隐藏（名称/地址/档案均无意义）
        card.visibility = View.GONE
        devInfoCard = card
        root.addView(M3Ui.wrapInCard(requireContext(), ThemeUtil.Palette(requireContext()), card), lp(false))
    }

    private val DASH = "—"

    /** alpha2.4: 刷新运行状态面板（GAIA / 耳机 / 电量） */
    private fun updateStatusPanel() {
        val gVal = gaiaStateVal ?: return
        val gaia = GaiaBleClient.getInstance()
        val g = gaia.isConnected()
        gVal.text = if (g) Lang.t("已连接", "Connected") else Lang.t("未连接", "Not connected")
        gVal.setTextColor(if (g) green else onVariantColor)
        val mac = HeadsetGate.getConnectedMac(requireContext())
        val sim = GaiaBleClient.isSimConnected()
        val hs = if (mac != null) Lang.t("已连接", "Connected") else (if (sim) Lang.t("已连接（模拟）", "Connected (simulated)") else Lang.t("未连接", "Not connected"))
        headsetStateVal?.text = hs
        headsetStateVal?.setTextColor(if (mac != null || sim) green else onVariantColor)
        // alpha2.52: 设备信息卡仅在 GAIA 已连接时显示
        devInfoCard?.visibility = if (g) View.VISIBLE else View.GONE
        val dn = GaiaBleClient.getInstance().getConnectedDeviceName()
        devNameVal?.text = dn ?: DASH
        devMacVal?.text = mac ?: DASH
        devProfileVal?.text = AncProfileLib.matchedProfileName(dn)
        updateBatteryStatus(mac, sim)
    }

    private val gaiaUiCallback = object : GaiaBleClient.Callback {
        override fun onConnected(address: String) {
            if (!isAdded) return
            val act = activity ?: return
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                moonProcState = serviceState()
                ancMode = AncBridge.getCurrentMode()
                updateAncStatus()
                updateBatteryStatus()
                // alpha1.4: 延迟等 notification descriptor 写完后请求
                Handler(Looper.getMainLooper()).postDelayed({
                    AncBridge.fetchAncMode()
                    GaiaBleClient.getInstance().fetchBatteryLevels()
                    val g = GaiaBleClient.getInstance(); g.setDcBridge(DeviceControlBridge); DeviceControlBridge.applyProfile(AncProfileLib.resolveDc(g.getConnectedDeviceName())); DeviceControlBridge.fetchAll()
                    refreshDcHighlight()
                }, 800)
                // alpha2.31.1: 二次刷新等能力探测完成
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isAdded) refreshDcHighlight()
                }, 2500)
            }
        }

        override fun onDisconnected(address: String) {
            if (!isAdded) return
            val act = activity ?: return
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                DeviceNotif.cancelAll(act.applicationContext)
                moonProcState = serviceState()
                updateAncStatus()
                updateBatteryStatus()
                refreshDcHighlight()
            }
        }

        override fun onBatteryLevel(batteryId: Int, level: Int) {
            if (!isAdded) return
            val act = activity ?: return
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val addr = GaiaBleClient.getInstance().deviceAddress
                if (addr != null) {
                    BatteryStore.setGaiaLevel(addr, batteryId, level)
                    PopupGate.flushPendingIfReady()
                }
                DeviceNotif.refreshBattery(act.applicationContext)
                updateBatteryStatus()
            }
        }

        override fun onAncMode(mode: Int) {
            if (!isAdded) return
            val act = activity ?: return
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                ancMode = mode
                updateAncStatus()
            }
        }

        override fun onError(message: String) {
            // alpha2.26.8: Fragment 已 detach 时只记日志，不触碰 UI
            if (!isAdded) return
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }


    /** 弹窗小按钮：filled=true 实心主色，false 浅色容器色 */
    private fun makeSmallButton(text: String, filled: Boolean,
                                onClick: View.OnClickListener): TextView {
        val b = TextView(requireContext())
        b.text = text
        b.textSize = 14f
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(24), dp(11), dp(24), dp(11))
        val g = GradientDrawable()
        g.setColor(if (filled) primaryColor else containerColor)
        g.cornerRadius = dp(20).toFloat()   // 与弹窗里另一颗按钮统一：40dp 高 -> 全圆 20dp
        // M3 统一状态层：不再区分「填充/未填充」两套写死的白色/黑色蒙层
        val rd = M3Ui.pressRipple(requireContext(), g)
        b.background = rd
        b.setTextColor(if (filled) onPrimaryColor else onContainerColor)
        b.setOnClickListener(onClick)
        return b
    }

    /** Material 风格文字按钮（圆角 tonal 底 + 涟漪） */
    private fun makeMaterialTextButton(text: String, color: Int,
                                       onClick: View.OnClickListener): TextView {
        val b = TextView(requireContext())
        b.text = text
        b.textSize = 14f
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(24), dp(11), dp(24), dp(11))
        val g = GradientDrawable()
        g.setColor(0x14000000)
        g.cornerRadius = dp(20).toFloat()   // 同上，22/24 两种圆角是历史遗留，统一成全圆
        val rd = RippleDrawable(
                ColorStateList.valueOf(0x1F000000), g, null)
        b.background = rd
        b.setTextColor(color)
        b.setOnClickListener(onClick)
        return b
    }

    /** 3.3.0: M3 官方 MaterialAlertDialogBuilder（标题/正文/按钮交给官方组件）。 */
    private fun showSimpleDialog(t: String, m: String) {
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(t)
                .setMessage(m)
                .setPositiveButton(Lang.t("知道了", "Got it"), null)
                .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val AUTO_REFRESH_MS = 30000L
        private val ANC_NAMES = AncProfileLib.ANC_MODE_NAMES_FULL
        private const val SIM_MAC = "AA:BB:CC:DD:EE:FF"
        private const val SIM_NAME = "Moondrop Golden Ages 2"
    }
}
