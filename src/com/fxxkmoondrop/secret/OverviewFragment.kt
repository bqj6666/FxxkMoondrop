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
    private var statusBadge: TextView? = null     // 英雄卡右侧徽章（API）
    private var statusSub: TextView? = null       // 英雄卡副标题（版本动态）
    private var green = 0
    private var red = 0
    private var primaryColor = 0
    private var onPrimaryColor = 0
    private var onSurfaceColor = 0
    private var onContainerColor = 0
    private var containerColor = 0
    private var surfaceColor = 0
    private var onVariantColor = 0
    private var cardSurfaceColor = 0
    private var mainBtn: MaterialButton? = null
    private var ancStatus: TextView? = null
    private var ancTitle: TextView? = null
    private var ancBtnRow: LinearLayout? = null
    private var gaiaStateVal: TextView? = null
    private var headsetStateVal: TextView? = null
    private var battVal: TextView? = null
    private var battRow: View? = null
    private var battRowShown = false // alpha2.8: 电量行当前视觉状态（驱动出现/消失动画）
    private var ancBtns: Array<View?>? = null   // alpha1.20: 弹窗同款按钮 holder
    private var ancLabels: Array<TextView?>? = null
    private var ancWindCol: View? = null
    private var dcSwitchSyncing = false
    @Volatile private var lastRefreshAncMs = 0L  // alpha2.28: refreshAnc 节流 // alpha2.26.2: 抗风按钮列（用户可选隐藏）
    private var ancMode = -1
    private var dcControlCard: LinearLayout? = null  // alpha2.31
    // alpha2.28: ANC icon bitmap cache (4 modes x 2 colors = 8 slots)
    private var ancIconCache: Array<android.graphics.drawable.Drawable?>? = null
    @Volatile private var moonProcState = "未知"
    @Volatile private var cachedHasRoot: Boolean? = null // alpha2.28: cache root check result
    private val autoRefreshHandler = Handler(Looper.getMainLooper())

    /** alpha1.36: HeadsetGate 异步补扫命中后推送 MAC（非阻塞链路闭环；onResume 注册 onStop 注销） */
    private val macUpdReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val mac = i.getStringExtra("mac") ?: return
            requireActivity().runOnUiThread {
                val gaia = GaiaBleClient.getInstance()
                gaia.setCallback(gaiaUiCallback)
                Thread { gaia.forceReconnect(requireActivity(), mac) }.start()
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

        // ── Material You 主题（alpha2.2：统一 ThemeUtil.Palette → 动态/种子/AMOLED 全支持）──
        val pal0 = ThemeUtil.Palette(requireContext())
        val dark = pal0.dark
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
        surfaceColor = surface
        onVariantColor = onVariant
        ancIconCache = null // alpha2.28: invalidate icon cache on theme change
        cardSurfaceColor = cardColor
        var statusBarH = 0
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) statusBarH = resources.getDimensionPixelSize(resId)

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(24), statusBarH + dp(4), dp(24), dp(24))
        root.setBackgroundColor(surface)

        // ── 官方 StatusHeader（LSPosed Manager 同款：纯色胶囊 / 圆形指示器 / 呼吸 / 徽章 / 全圆角；内容居中）──
        val statusPanel = android.widget.FrameLayout(requireContext())
        val panelBg = GradientDrawable()
        panelBg.setColor(container)
        panelBg.setCornerRadii(floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(),
                dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat()))
        statusPanel.background = panelBg
        statusPanel.setPadding(dp(20), dp(16), dp(20), dp(16))

        // 状态行：指示器（52dp 圆角方形 34% + onContainer 15% 底）+ 文本 + API 徽章
        val heroRow = LinearLayout(requireContext())
        heroRow.orientation = LinearLayout.HORIZONTAL
        heroRow.gravity = Gravity.CENTER_VERTICAL

        statusIconChip = android.widget.FrameLayout(requireContext())
        val chipBg = GradientDrawable()
        chipBg.shape = GradientDrawable.OVAL
        chipBg.setColor((onContainer and 0x00FFFFFF) or 0x26000000)
        statusIconChip!!.background = chipBg
        statusIcon = ImageView(requireContext())
        statusIcon!!.setImageResource(R.drawable.ic_check)
        statusIcon!!.imageTintList = ColorStateList.valueOf(onContainer)
        val silp = android.widget.FrameLayout.LayoutParams(dp(26), dp(26))
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
        heroRow.addView(statusIconChip, LinearLayout.LayoutParams(dp(48), dp(48)))
        val chipLp = statusIconChip!!.layoutParams as LinearLayout.LayoutParams
        chipLp.marginEnd = dp(8)
        statusIconChip!!.layoutParams = chipLp
        // 文本列：品牌（62%）+ 状态词（SemiBold）同行，下方版本 detail
        val texts = LinearLayout(requireContext())
        texts.orientation = LinearLayout.VERTICAL
        texts.gravity = Gravity.START
        val titleRow = LinearLayout(requireContext())
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        val brand = TextView(requireContext())
        brand.text = "FxxkMoondrop"
        brand.textSize = 15f
        brand.typeface = Typeface.create("sans-serif", Typeface.NORMAL) // 官方 Normal
        brand.setTextColor((onContainer and 0x00FFFFFF) or 0x9E000000.toInt()) // 62%
        titleRow.addView(brand)
        val gap = View(requireContext())
        titleRow.addView(gap, LinearLayout.LayoutParams(dp(2), 1))
        svcStatus = TextView(requireContext())
        svcStatus!!.textSize = 16f
        svcStatus!!.isSingleLine = true
        svcStatus!!.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        svcStatus!!.setTextColor(onContainer)
        svcStatus!!.text = "运行中"
        titleRow.addView(svcStatus)
        texts.addView(titleRow)
        texts.addView(spacer(dp(4)))
        statusSub = TextView(requireContext())
        statusSub!!.textSize = 11f
        statusSub!!.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        statusSub!!.setTextColor((onContainer and 0x00FFFFFF) or 0xB3000000.toInt())
        statusSub!!.isSingleLine = true
        statusSub!!.gravity = Gravity.START
        try {
            val pi = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            var vn = pi.versionName ?: ""
            if (vn.lowercase().startsWith("alpha")) vn = vn.substring(5)
            statusSub!!.text = "V" + vn + "Alpha"
        } catch (_: Exception) {
            statusSub!!.text = ""
        }
        // alpha2.7: 版本号在运行中下方，左对齐贴勾
        texts.addView(statusSub, LinearLayout.LayoutParams(-2, -2))

        heroRow.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        // 右侧徽章 pill（API，动态获取）
        statusBadge = TextView(requireContext())
        statusBadge!!.text = "API " + Build.VERSION.SDK_INT
        statusBadge!!.textSize = 10f
        statusBadge!!.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        statusBadge!!.setTextColor(onPrimaryColor)
        statusBadge!!.gravity = Gravity.CENTER
        statusBadge!!.setPadding(dp(8), dp(4), dp(8), dp(4))
        val bdg = GradientDrawable()
        bdg.cornerRadius = dp(20).toFloat()
        bdg.setColor(primaryColor)
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

        // ── 主操作：开始/停止监听（Filled 按钮）──
        mainBtn = makeButton("开始后台监听", R.drawable.ic_play, primaryColor, onPrimaryColor) {
            requestNeededPermissions()
            if (HeadsetDetectService.RUNNING) {
                requireContext().getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean("enable", false).commit()
                AliveReceiver.cancel(requireContext())
                HeadsetDetectService.RUNNING = false
                requireContext().stopService(Intent(requireContext(), HeadsetDetectService::class.java))
            } else {
                requireContext().getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean("enable", true).commit()
                HeadsetDetectService.RUNNING = true
                requireContext().startService(Intent(requireContext(), HeadsetDetectService::class.java))
            }
            updateStatus()
        }
        root.addView(mainBtn, lp(false))

        root.addView(spacer(dp(10)))

        // ── alpha2.4: 运行状态面板（GAIA 连接 / 耳机连接 / 左右耳电量）──
        buildStatusPanel(root)

        root.addView(spacer(dp(10)))

        // ── 降噪控制（GAIA 直连）──
        ancTitle = TextView(requireContext())
        ancTitle!!.text = "降噪控制"
        ancTitle!!.textSize = 13f
        ancTitle!!.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ancTitle!!.setTextColor(outline)
        root.addView(ancTitle, lp(false))
        // alpha2.7: 始终显示，未连接时按钮禁用（视觉置灰）
        ancTitle!!.visibility = View.VISIBLE

        root.addView(spacer(dp(8)))
        root.addView(spacer(dp(8)))

        // alpha1.20: 弹窗同款三按钮（圆形按钮 + 图标 + 小字），高亮=当前模式（与 Google 弹窗一致）
        ancBtns = arrayOfNulls(4)
        ancLabels = arrayOfNulls(4)
        val ancRow = LinearLayout(requireContext())
        ancRow.orientation = LinearLayout.HORIZONTAL
        ancRow.gravity = Gravity.CENTER
        // alpha2.26.2: Material Experience —— M3 圆角卡片容器（card 色 + 28dp 圆角）
        ancRow.setPadding(dp(10), dp(12), dp(10), dp(12))
        val ancCardBg = GradientDrawable()
        ancCardBg.shape = GradientDrawable.RECTANGLE
        ancCardBg.setColor(cardColor)
        ancCardBg.setCornerRadius(dp(28).toFloat())
        ancRow.background = ancCardBg
        for (m in 0..3) {
            val fm = m
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val holder = android.widget.FrameLayout(requireContext())
            val bg = View(requireContext())
            bg.tag = "fxxk_main_bg"
            val g0 = GradientDrawable()
            g0.shape = GradientDrawable.OVAL
            g0.setColor(containerColor)
            bg.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g0, null)
            holder.addView(bg, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            val icon = ImageView(requireContext())
            icon.tag = "fxxk_main_icon"
            icon.setImageDrawable(buildMainModeIcon(fm, dp(26), onContainerColor))
            val il = android.widget.FrameLayout.LayoutParams(dp(26), dp(26))
            il.gravity = Gravity.CENTER
            holder.addView(icon, il)
            holder.setOnClickListener {
                AncBridge.setAncMode(fm)
                ancMode = fm
                updateAncStatus()
            }
            ancBtns!![m] = holder
            if (fm == 3) ancWindCol = col // alpha2.26.2: 记录抗风列用于按需隐藏
            val sz = dp(60)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            col.addView(spacer(dp(2)))
            val lbl = TextView(requireContext())
            lbl.text = ANC_NAMES[fm]
            lbl.textSize = 11f
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

        root.addView(spacer(dp(8)))

        // alpha2.32: 扩展设备控制（空间音频/增益/LED）—— 圆形图标按钮 + 型号档案
        val dcCard = LinearLayout(requireContext())
        dcCard.orientation = LinearLayout.VERTICAL
        dcCard.setPadding(dp(10), dp(12), dp(10), dp(12))
        val dcBg = GradientDrawable()
        dcBg.shape = GradientDrawable.RECTANGLE
        dcBg.setColor(cardColor)
        dcBg.setCornerRadius(dp(28).toFloat())
        dcCard.background = dcBg

        // 空间音频行（总开关）
        val dcSpatialRow = LinearLayout(requireContext())
        dcSpatialRow.orientation = LinearLayout.HORIZONTAL
        dcSpatialRow.gravity = Gravity.CENTER_VERTICAL
        dcSpatialRow.tag = "dc_spatial_row"
        val sLabel = TextView(requireContext())
        sLabel.text = "空间音频"
        sLabel.textSize = 12f
        sLabel.setTextColor(onContainerColor)
        dcSpatialRow.addView(sLabel, LinearLayout.LayoutParams(-2, -2))
        dcSpatialRow.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
        val spatialSwitch = com.google.android.material.materialswitch.MaterialSwitch(requireContext())
        spatialSwitch.tag = "dc_spatial_switch"
        spatialSwitch.trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(primaryColor, if (dark) 0x33FFFFFF else 0x22000000))
        spatialSwitch.thumbTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(onPrimaryColor, 0xFF888888.toInt()))
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
        tLabel.text = "追踪模式"
        tLabel.textSize = 11f
        tLabel.setTextColor(onContainerColor)
        dcTrackingRow.addView(tLabel, LinearLayout.LayoutParams(-2, -2))
        dcTrackingRow.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
        for (tm in 0..2) {
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val holder = android.widget.FrameLayout(requireContext())
            val bg = View(requireContext())
            bg.tag = "dc_bg"
            val g0 = GradientDrawable()
            g0.shape = GradientDrawable.OVAL
            g0.setColor(containerColor)
            bg.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g0, null)
            holder.addView(bg, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            val icon = ImageView(requireContext())
            icon.tag = "dc_icon"
            icon.setImageDrawable(DcIcons.build(requireContext(), 0, tm, dp(22), onContainerColor))
            val il = android.widget.FrameLayout.LayoutParams(dp(22), dp(22))
            il.gravity = Gravity.CENTER
            holder.addView(icon, il)
            holder.tag = "dc_btn_spatial_" + tm
            holder.setOnClickListener {
                DeviceControlBridge.setTrackingMode(tm)
                refreshDcHighlight()
            }
            val sz = dp(48)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            val lbl = TextView(requireContext())
            lbl.text = DeviceControlBridge.trackingLabels()[tm]
            lbl.textSize = 10f
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
        gLabel.text = "增益"
        gLabel.textSize = 12f
        gLabel.setTextColor(onContainerColor)
        dcGainRow.addView(gLabel, LinearLayout.LayoutParams(-2, -2))
        dcGainRow.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
        for (gm in 0 until DeviceControlBridge.gainCount()) {
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val holder = android.widget.FrameLayout(requireContext())
            val bg = View(requireContext())
            bg.tag = "dc_bg"
            val g0 = GradientDrawable()
            g0.shape = GradientDrawable.OVAL
            g0.setColor(containerColor)
            bg.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g0, null)
            holder.addView(bg, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            val icon = ImageView(requireContext())
            icon.tag = "dc_icon"
            icon.setImageDrawable(DcIcons.build(requireContext(), 1, gm, dp(22), onContainerColor))
            val il = android.widget.FrameLayout.LayoutParams(dp(22), dp(22))
            il.gravity = Gravity.CENTER
            holder.addView(icon, il)
            holder.tag = "dc_btn_gain_" + gm
            holder.setOnClickListener {
                DeviceControlBridge.setGain(gm)
                refreshDcHighlight()
            }
            val sz = dp(48)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            val lbl = TextView(requireContext())
            lbl.text = DeviceControlBridge.gainLabels()[gm]
            lbl.textSize = 10f
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
        lLabel.text = "指示灯"
        lLabel.textSize = 12f
        lLabel.setTextColor(onContainerColor)
        dcLedRow.addView(lLabel, LinearLayout.LayoutParams(-2, -2))
        dcLedRow.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
        val ledNames = arrayOf("开", "关")
        for (lm in 0..1) {
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val holder = android.widget.FrameLayout(requireContext())
            val bg = View(requireContext())
            bg.tag = "dc_bg"
            val g0 = GradientDrawable()
            g0.shape = GradientDrawable.OVAL
            g0.setColor(containerColor)
            bg.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g0, null)
            holder.addView(bg, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            val icon = ImageView(requireContext())
            icon.tag = "dc_icon"
            icon.setImageDrawable(DcIcons.build(requireContext(), 2, lm, dp(22), onContainerColor))
            val il = android.widget.FrameLayout.LayoutParams(dp(22), dp(22))
            il.gravity = Gravity.CENTER
            holder.addView(icon, il)
            holder.tag = "dc_btn_led_" + lm
            holder.setOnClickListener {
                DeviceControlBridge.setLed(lm)
                refreshDcHighlight()
            }
            val sz = dp(48)
            col.addView(holder, LinearLayout.LayoutParams(sz, sz))
            val lbl = TextView(requireContext())
            lbl.text = ledNames[lm]
            lbl.textSize = 10f
            lbl.gravity = Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(onContainerColor)
            col.addView(lbl, LinearLayout.LayoutParams(-1, -2))
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(4), 0, dp(4), 0)
            dcLedRow.addView(col, lp)
        }
        dcCard.addView(dcLedRow, LinearLayout.LayoutParams(-1, -2))

        root.addView(dcCard, lp(false))
        dcControlCard = dcCard
        root.addView(spacer(dp(8)))

        root.addView(makeButton("刷新状态 / 检查 Moondrop", R.drawable.ic_refresh, container, onContainer) { refreshAnc() })

        root.addView(spacer(dp(12)))

        root.addView(spacer(dp(20)))

        val sv = ScrollView(requireContext())
        sv.setBackgroundColor(surface)
        sv.addView(root)

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
        dumpDynColors(if (isDark()) "DARK" else "LIGHT")
        requestNeededPermissions()
        updateStatus()
        return sv
    }

    private fun dumpDynColors(tag: String) {
        val names = arrayOf("system_accent1_400", "system_accent1_400_dark", "system_accent1_400_light",
                "system_accent1_50", "system_accent1_100", "system_accent1_200", "system_accent1_700",
                "system_accent1_800", "system_accent1_900",
                "system_neutral1_0", "system_neutral1_10", "system_neutral1_50", "system_neutral1_100",
                "system_neutral1_900", "system_neutral1_1000")
        val sb = StringBuilder("DYN " + tag + ": ")
        for (n in names) {
            val id = resources.getIdentifier(n, "color", "android")
            var v = 0
            if (id != 0) {
                try { v = resources.getColor(id, requireContext().theme) } catch (_: Exception) { }
            }
            sb.append(n).append("=").append(if (id != 0) ("#" + Integer.toHexString(v)) else "MISS").append(" ")
        }
        android.util.Log.d("MoondropMonet", sb.toString())
    }

    /** alpha2.0: 主题架构官方化——模式/动态色/AMOLED/种子色全部走 ThemeUtil（SP 设置） */
    private fun isDark(): Boolean = ThemeUtil.isDark(requireContext())

    private fun dyn(resName: String, fallback: Int): Int = ThemeUtil.dyn(requireContext(), resName, fallback)

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
        val hasSpatial = profile.hasSpatial || (connected && try { gaia.hasSpatialSupport() } catch (e: Exception) { false })
        val hasGain = profile.hasGain || (connected && try { gaia.hasGainSupport() } catch (e: Exception) { false })
        val hasLed = profile.hasLed || (connected && try { gaia.hasLedSupport() } catch (e: Exception) { false })

        val spatialOn = DeviceControlBridge.isSpatialOn()
        val sMode = DeviceControlBridge.spatialUiMode()
        val gLevel = DeviceControlBridge.getGainLevel()
        val ledOn = DeviceControlBridge.getLedState() == 1
        val greyColor = 0xFF888888.toInt()

        val spatialRow = card.findViewWithTag<LinearLayout>("dc_spatial_row")
        val trackingRow = card.findViewWithTag<LinearLayout>("dc_tracking_row")
        val gainRow = card.findViewWithTag<LinearLayout>("dc_gain_row")
        val ledRow = card.findViewWithTag<LinearLayout>("dc_led_row")
        spatialRow?.visibility = if (connected && profile.hasSpatial) View.VISIBLE else View.GONE
        trackingRow?.visibility = if (connected && profile.hasSpatial) View.VISIBLE else View.GONE
        gainRow?.visibility = if (connected && profile.hasGain) View.VISIBLE else View.GONE
        ledRow?.visibility = if (connected && profile.hasLed) View.VISIBLE else View.GONE

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
                    val bgV = holder.findViewWithTag<View>("dc_bg")
                    val iv = holder.findViewWithTag<View>("dc_icon") as? ImageView
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
                    val iconColor = if (active) 0xFFFFFFFF.toInt() else onContainerColor
                    val featType = when (feature) { "spatial" -> 0; "gain" -> 1; "led" -> 2; else -> 0 }
                    val spatialDisabled = feature == "spatial" && !spatialOn
                    if (!connected || spatialDisabled) {
                        holder.isEnabled = false
                        holder.alpha = 0.4f
                        if (bgV != null) {
                            val g = GradientDrawable()
                            g.shape = GradientDrawable.OVAL
                            g.setColor(greyColor)
                            bgV.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g, null)
                        }
                        iv?.setImageDrawable(DcIcons.build(requireContext(), featType, idx, dp(22), 0xFFAAAAAA.toInt()))
                    } else {
                        holder.isEnabled = true
                        holder.alpha = 1f
                        if (bgV != null) {
                            val g = GradientDrawable()
                            g.shape = GradientDrawable.OVAL
                            g.setColor(if (active) primaryColor else containerColor)
                            if (active) g.setStroke(dp(2), 0xFFFFFFFF.toInt())
                            bgV.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g, null)
                        }
                        iv?.setImageDrawable(DcIcons.build(requireContext(), featType, idx, dp(22), iconColor))
                    }
                }
            }
        }
    }

    private fun spacer(h: Int): View {
        val v = View(requireContext())
        v.layoutParams = LinearLayout.LayoutParams(1, h)
        return v
    }

    private fun makeButton(text: String, iconRes: Int, bgColor: Int, textColor: Int,
                           l: View.OnClickListener): MaterialButton {
        // alpha2.0: 官方 MaterialButton（Material3 按钮）+ 官方 Material Icons 图标
        val b = MaterialButton(requireContext())
        b.text = text
        b.textSize = 16f
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.setTextColor(textColor)
        b.isAllCaps = false
        b.gravity = Gravity.CENTER
        b.insetTop = 0
        b.insetBottom = 0
        b.minHeight = dp(52)
        b.minimumHeight = dp(52)
        b.cornerRadius = dp(28)
        b.backgroundTintList = ColorStateList.valueOf(bgColor)
        b.elevation = if (bgColor == primaryColor) dp(1).toFloat() else 0f
        if (iconRes != 0) {
            b.setIconResource(iconRes)
            b.iconTint = ColorStateList.valueOf(textColor)
            b.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            b.iconPadding = dp(8)
            b.iconSize = dp(22)
        }
        b.setOnClickListener(l)
        return b
    }

    private fun scanBonded(): String {
        try {
            if (Build.VERSION.SDK_INT >= 31
                    && requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return "缺少蓝牙权限，无法扫描"
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) return "此设备无蓝牙"
            val bonded = adapter.bondedDevices
            val sb = StringBuilder()
            if (bonded != null) {
                for (d in bonded) {
                    val nm = d.name
                    if (nm != null && nm.lowercase().contains("moondrop")) {
                        sb.append(nm).append(" · ").append(d.address).append("\n")
                    }
                }
            }
            if (sb.length == 0) return "未找到已配对的 Moondrop 设备"
            return sb.toString().trim()
        } catch (e: Exception) {
            return "扫描失败: " + e.message
        }
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
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
        head.text = if (missing == 0) "✅  所有必要权限均已就绪" else "⚠️  发现 " + missing + " 项权限缺失"
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
            name.text = per.name + (if (per.ok) "" else (if (per.action == PermissionChecker.ACTION_NONE) "（需手动处理）" else "（点击修复）"))
            name.textSize = 15f
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
                val rd = RippleDrawable(
                        ColorStateList.valueOf(if (isDark()) 0x33FFFFFF else 0x22000000), bg, null)
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
        btnRow.addView(makeMaterialTextButton(if (missing == 0) "知道了" else "稍后处理",
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
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                .start()
    }

    /** 跳转授权：运行时权限 / 悬浮窗 / 电池优化 / 打开 App */
    private fun fixPermission(it: PermissionChecker.Item) {
        try {
            when (it.action) {
                PermissionChecker.ACTION_RUNTIME -> {
                    if (it.requestCode == 1) {
                        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
                    } else if (it.requestCode == 2) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                    }
                }
                PermissionChecker.ACTION_OVERLAY ->
                    requireActivity().startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + requireContext().packageName)))
                PermissionChecker.ACTION_BATTERY ->
                    requireActivity().startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                else -> toast(it.detail)
            }
        } catch (e: Exception) {
            toast("跳转失败: " + e.message)
        }
    }

    private fun updateStatus() {
        updateRunStatus()
        val svc = HeadsetDetectService.RUNNING
        mainBtn?.let {
            it.text = if (svc) "停止后台监听" else "开始后台监听"
            it.setIconResource(if (svc) R.drawable.ic_stop else R.drawable.ic_play)
        }
        // alpha2.4: onResume 同步刷新状态面板（修复模拟恢复后电量行残留显示）
        updateStatusPanel()
    }

    /** alpha1.11: 监听服务 + 降噪控制服务 整合为一项运行状态 */
    private fun updateRunStatus() {
        val svc = HeadsetDetectService.RUNNING
        var ancSt = moonProcState
        if (ancSt == null) ancSt = "未知"
        val ancOk = ancSt.contains("✅")
        val ancFrozen = ancSt.contains("❄️")
        val text: String
        val color: Int
        if (svc && ancOk) {
            text = "✅  运行中"
            color = green
        } else if (svc && ancFrozen) {
            text = "❄️  运行中（降噪控制已冻结）"
            color = onVariantColor
        } else if (svc) {
            text = "⚠️  监听运行中 · 降噪控制未运行"
            color = red
        } else if (ancOk) {
            text = "⚠️  监听未运行 · 降噪控制运行中"
            color = red
        } else {
            text = "⛔  未运行"
            color = red
        }
        setHeroStatus(text, color)
    }

    /** alpha2.0 M3 英雄卡：状态 -> 官方图标（Material Icons）+ 纯文本，指示器色随状态 */
    /** 官方 compositeOver：把半透明 src 叠在 dst 上（Compose compositeOverSurface 的 View 版） */
    private fun mixOver(src: Int, dst: Int): Int {
        val a = (src ushr 24) and 0xFF
        if (a == 0) return dst
        val inv = 255 - a
        val r = (((src ushr 16) and 0xFF) * a + (((dst ushr 16) and 0xFF) * inv)) / 255
        val g = (((src ushr 8) and 0xFF) * a + (((dst ushr 8) and 0xFF) * inv)) / 255
        val b = ((src and 0xFF) * a + ((dst and 0xFF) * inv)) / 255
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private fun setHeroStatus(text: String, color: Int) {
        var iconRes = R.drawable.ic_pause
        var label = text
        if (text.startsWith("✅")) {
            iconRes = R.drawable.ic_check
            label = text.substring(2).trim()
        } else if (text.startsWith("❄️")) {
            iconRes = R.drawable.ic_ac_unit
            label = text.substring(2).trim()
        } else if (text.startsWith("⚠️")) {
            iconRes = R.drawable.ic_warning
            label = text.substring(2).trim()
        } else if (text.startsWith("⛔")) {
            iconRes = R.drawable.ic_block
            label = text.substring(2).trim()
        }
        // 官方 StatusHeader：品牌后跟短状态词（Active/…），完整说明交给 detail 行
        val shortWord: String
        if (iconRes == R.drawable.ic_check) shortWord = "运行中"
        else if (iconRes == R.drawable.ic_ac_unit) shortWord = "运行中"
        else if (iconRes == R.drawable.ic_warning) shortWord = if (label.contains("监听未运行")) "已停止" else "监听中"
        else if (iconRes == R.drawable.ic_block) shortWord = "未运行"
        else shortWord = "检查中"
        statusIcon?.let {
            it.setImageResource(iconRes)
            it.imageTintList = ColorStateList.valueOf(onContainerColor)
        }
        statusIconChip?.let {
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor((onContainerColor and 0x00FFFFFF) or 0x26000000)
            it.background = g
        }
        svcStatus?.text = shortWord
        svcStatus?.setTextColor(onContainerColor)
    }

    override fun onResume() {
        super.onResume()
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
        try {
            requireContext().unregisterReceiver(macUpdReceiver)
        } catch (_: Exception) { }
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
    }

    private fun iconCustomExists(): Boolean {
        // alpha2.28: still sync but fast (test -f is ~50ms on su)
        val r = runRoot("test -f " + GMS_ICON_PATH + " && echo CUSTOM")
        return r != null && r.contains("CUSTOM")
    }

    /** alpha2.28: async version for UI callers */
    private fun iconCustomExistsAsync(callback: (Boolean) -> Unit) {
        Thread {
            val r = runRoot("test -f " + GMS_ICON_PATH + " && echo CUSTOM")
            val exists = r != null && r.contains("CUSTOM")
            requireActivity().runOnUiThread { callback(exists) }
        }.start()
    }

    // ── alpha1.14fix5: 弹窗图标选择 → Material experience 卡片（与权限弹窗同风格）──
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
        head.text = "弹窗图标（当前：" + (if (custom) "已自定义" else "默认") + "）"
        head.textSize = 22f
        head.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        head.setTextColor(accent)
        head.gravity = Gravity.CENTER_HORIZONTAL
        cardBody.addView(head, lp(false))
        cardBody.addView(spacer(dp(10)))

        val items = if (custom) arrayOf("📷  从相册选择", "🧹  恢复默认图标") else arrayOf("📷  从相册选择")
        val subs = if (custom) arrayOf("选择一张图片，替换 Google 弹窗显示的耳机图标", "删除自定义图标，恢复软件自带默认图")
        else arrayOf("选择一张图片，替换 Google 弹窗显示的耳机图标")
        for (i in items.indices) {
            val which = i
            val row = LinearLayout(requireContext())
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            val rowBg = GradientDrawable()
            rowBg.setColor(if (isDark()) 0x14FFFFFF else 0x0A000000)
            rowBg.cornerRadius = dp(14).toFloat()
            val rd = RippleDrawable(
                    ColorStateList.valueOf(if (isDark()) 0x33FFFFFF else 0x22000000), rowBg, null)
            row.background = rd
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            val t1 = TextView(requireContext())
            t1.text = items[i]
            t1.textSize = 15f
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
                    val pick = Intent(Intent.ACTION_GET_CONTENT)
                    pick.type = "image/*"
                    pick.addCategory(Intent.CATEGORY_OPENABLE)
                    try {
                        requireActivity().startActivityForResult(
                                Intent.createChooser(pick, "选择耳机图标"), REQ_PICK_ICON)
                    } catch (t: Throwable) {
                        toast("无法打开选择器: " + t.message)
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
        btnRow.addView(makeMaterialTextButton("取消", accent) { dlg.dismiss() })
        cardBody.addView(btnRow, lp(false))

        dlg.setContentView(card)
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.85).toInt(), -2)
        dlg.show()
    }

    private fun resetCustomIcon() {
        Thread {
            val r = runRoot("rm -f " + GMS_ICON_PATH + " && echo OK")
            val ok = r != null && r.contains("OK")
            requireActivity().runOnUiThread { toast(if (ok) "已恢复默认图标（下次连接生效）" else "恢复失败，请检查 Root") }
        }.start()
    }

    private fun saveIconFromUri(uri: Uri) {
        Thread {
            try {
                val input = requireContext().contentResolver.openInputStream(uri)
                if (input == null) {
                    requireActivity().runOnUiThread { toast("无法读取所选图片") }
                    return@Thread
                }
                val bmp = BitmapFactory.decodeStream(input)
                try { input.close() } catch (_: Exception) { }
                if (bmp == null) {
                    requireActivity().runOnUiThread { toast("图片解码失败") }
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
                    requireActivity().runOnUiThread { toast("图片仍超 1MB，请换小图") }
                    return@Thread
                }
                val uid = runRoot("stat -c %u:%g /data/user/0/com.google.android.gms")?.trim()
                if (uid == null || !uid.contains(":")) {
                    requireActivity().runOnUiThread { toast("读取 GMS 属主失败") }
                    return@Thread
                }
                val cmd = "cp '" + out.absolutePath + "' " + GMS_ICON_PATH +
                        " && chown " + uid + " " + GMS_ICON_PATH +
                        " && chmod 644 " + GMS_ICON_PATH + " && echo OK"
                val r = runRoot(cmd)
                val ok = r != null && r.contains("OK")
                requireActivity().runOnUiThread { toast(if (ok) "弹窗图标已更新（下次连接生效）" else "写入图标失败，请检查 Root") }
                if (scaled !== bmp) scaled.recycle()
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
            } catch (t: Throwable) {
                requireActivity().runOnUiThread { toast("图标处理失败: " + t.message) }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PICK_ICON && resultCode == android.app.Activity.RESULT_OK &&
                data != null && data.data != null) {
            saveIconFromUri(data.data!!)
        }
    }

    // ── Root 强力保活 ──
    private fun showRootWarnDialog(sw: PillSwitch) {
        val (d, box) = M3Ui.materialDialog(requireContext(), primaryColor, cardSurfaceColor)
        box.addView(M3Ui.dialogTitle(requireContext(), "权限风险警告", onSurfaceColor),
                LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(14)))
        val msg = TextView(requireContext())
        msg.text = "开启后将使用 Root 权限执行系统命令：\n" +
                "• 将本应用加入系统电池优化白名单（防 Doze 杀后台）\n" +
                "• 允许后台运行，写入 Magisk 开机脚本实现开机自启\n\n" +
                "请确认：\n" +
                "• 设备已获取 Root 权限\n" +
                "• 你了解 Root 操作的风险\n" +
                "• 本应用来源可信"
        msg.textSize = 14f
        msg.setTextColor(onVariantColor)
        msg.setLineSpacing(dp(3).toFloat(), 1.3f)
        box.addView(msg, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(24)))
        val btnRow = LinearLayout(requireContext())
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.gravity = Gravity.END
        btnRow.addView(makeMaterialTextButton("取消", onVariantColor) {
            sw.setChecked(false)
            d.dismiss()
        })
        btnRow.addView(spacer(dp(6)))
        btnRow.addView(makeMaterialTextButton("继续开启", primaryColor) {
            d.dismiss()
            applyRootProtect(true)
        })
        box.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        d.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        d.setCancelable(false)
        d.show()
    }

    /** alpha2.28: cached root check (first call on background thread, result cached) */
    private fun hasRoot(): Boolean {
        cachedHasRoot?.let { return it }
        // Run on background thread to avoid blocking UI
        val latch = java.util.concurrent.CountDownLatch(1)
        Thread {
            val out = runRoot("id")
            cachedHasRoot = out != null && out.contains("uid=0")
            latch.countDown()
        }.start()
        try { latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { }
        return cachedHasRoot ?: false
    }

    private fun runRoot(cmd: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val br = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            var l: String?
            while (br.readLine().also { l = it } != null) sb.append(l).append("\n")
            p.waitFor()
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

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
    private fun buildMainModeIcon(mode: Int, px: Int, color: Int): android.graphics.drawable.Drawable {
        // alpha2.28: use cache (key = mode*2 + (color==white?1:0))
        val cacheKey = mode * 2 + (if (color == 0xFFFFFFFF.toInt()) 1 else 0)
        val cache = ancIconCache
        if (cache != null && cacheKey < cache.size) {
            cache[cacheKey]?.let { return it }
        }
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.style = android.graphics.Paint.Style.STROKE
        val density = resources.displayMetrics.density
        p.strokeWidth = 2f * density
        p.strokeCap = android.graphics.Paint.Cap.ROUND
        p.strokeJoin = android.graphics.Paint.Join.ROUND
        p.color = color
        val cx = px / 2f
        val cy = px / 2f
        val ir = px * 0.36f
        when (mode) {
            0 -> { // 电源符号
                val rect = android.graphics.RectF(cx - ir, cy - ir, cx + ir, cy + ir)
                c.drawArc(rect, 50f, 260f, false, p)
                c.drawLine(cx, cy - ir * 1.25f, cx, cy - ir * 0.35f, p)
            }
            1 -> { // 降噪：三条水平波浪
                for (i in -1..1) {
                    val yy = cy + i * px * 0.16f
                    val wv = android.graphics.Path()
                    wv.moveTo(cx - px * 0.30f, yy)
                    wv.cubicTo(cx - px * 0.10f, yy - px * 0.14f,
                            cx + px * 0.10f, yy + px * 0.14f, cx + px * 0.30f, yy)
                    c.drawPath(wv, p)
                }
            }
            2 -> { // 透传：耳朵（双 C 弧 + 耳道圆点）
                val rect = android.graphics.RectF(cx - ir, cy - ir, cx + ir, cy + ir)
                c.drawArc(rect, 60f, 240f, false, p)
                val ir2 = ir * 0.5f
                val rect2 = android.graphics.RectF(cx - ir2, cy - ir2, cx + ir2, cy + ir2)
                c.drawArc(rect2, 90f, 180f, false, p)
                val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                dot.style = android.graphics.Paint.Style.FILL
                dot.color = color
                c.drawCircle(cx + ir * 0.10f, cy + ir * 0.14f, px * 0.05f, dot)
            }
            3 -> { // 抗风：旋风/三弧线
                val rect3 = android.graphics.RectF(cx - ir, cy - ir, cx + ir, cy + ir)
                c.drawArc(rect3, 70f, 220f, false, p)
                val rect4 = android.graphics.RectF(cx - ir*0.7f, cy - ir*0.7f, cx + ir*0.7f, cy + ir*0.7f)
                c.drawArc(rect4, 90f, 200f, false, p)
                val rect5 = android.graphics.RectF(cx - ir*0.4f, cy - ir*0.4f, cx + ir*0.4f, cy + ir*0.4f)
                c.drawArc(rect5, 110f, 180f, false, p)
            }
        }
        val drawable = android.graphics.drawable.BitmapDrawable(resources, bmp)
        // alpha2.28: store in cache
        if (ancIconCache == null) ancIconCache = arrayOfNulls(8)
        if (cacheKey < 8) ancIconCache!![cacheKey] = drawable
        return drawable
    }

    private fun refreshAnc() {
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
                gaia.setCallback(gaiaUiCallback)
                // alpha1.4: 前台刷新强制重连（重新解析 LE 地址/扫描）
                // alpha1.36: 移出主线程（原主线程 BLE 重连 + 阻塞探测导致 ANR）
                Thread { gaia.forceReconnect(requireActivity(), mac) }.start()
            } else if (showToast) {
                // alpha1.35: 移除"未检测到 Moondrop 耳机"toast（用户反馈打扰）
            }
        }
    }

    private fun updateAncStatus() {
        PopupOverlay.setAncMode(ancMode)
        updateRunStatus()
        // alpha1.4: 降噪控制区块仅随真实耳机连接显示（模拟按钮不影响）
        val realConnected = HeadsetGate.getConnectedMac(requireContext()) != null
        var st3 = moonProcState
        if (st3 == null) st3 = "未知"
        val ancEnabled = realConnected && st3.contains("✅")
        ancTitle?.let {
            it.visibility = View.VISIBLE
            it.alpha = if (ancEnabled) 1f else 0.45f
        }
        ancBtnRow?.let { it.visibility = View.VISIBLE }
        ancBtns?.let { btns ->
            for (i in btns.indices) {
                val hol = btns[i] ?: continue
                hol.isEnabled = ancEnabled
                hol.alpha = if (ancEnabled) 1f else 0.4f
                val sel = (i == ancMode)
                val bgV = hol.findViewWithTag<View>("fxxk_main_bg")
                if (bgV != null) {
                    val g = GradientDrawable()
                    g.shape = GradientDrawable.OVAL
                    g.setColor(if (sel) primaryColor else containerColor)
                    if (sel) g.setStroke(dp(2), 0xFFFFFFFF.toInt()) // alpha1.20: 选中白描边
                    bgV.background = RippleDrawable(ColorStateList.valueOf(0x33000000), g, null)
                }
                val iv = hol.findViewWithTag<View>("fxxk_main_icon") as? ImageView
                if (iv != null) {
                    iv.setImageDrawable(buildMainModeIcon(i, dp(26),
                            if (sel) 0xFFFFFFFF.toInt() else onContainerColor))
                }
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
                "✅ 运行中" else "❌ 未运行"

    /** alpha2.4: 左右耳电量 → 运行状态面板（与弹窗同源 BatteryStore） */
    private fun updateBatteryStatus() {
        val bVal = battVal ?: return
        val gaia = GaiaBleClient.getInstance()
        var mac = gaia.deviceAddress
        if (mac == null && GaiaBleClient.isSimConnected()) mac = SIM_MAC
        val text: String
        if (mac != null) {
            val l = BatteryStore.getLeft(mac)
            val r = BatteryStore.getRight(mac)
            if (l >= 0 && r >= 0) {
                text = "左耳 " + l + "%  ·  右耳 " + r + "%"
            } else if (l >= 0) {
                text = "左耳 " + l + "%"
            } else if (r >= 0) {
                text = "右耳 " + r + "%"
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
                        row.animate().alpha(1f).translationY(0f).setDuration(250).start()
                    }
                } else {
                    if (row.isLaidOut) {
                        row.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(200)
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
        val bg = GradientDrawable()
        bg.setColor(cardSurfaceColor)
        bg.cornerRadius = dp(20).toFloat()
        card.background = bg
        card.addView(makeStatusRow(R.drawable.ic_bluetooth, "GAIA 状态", 0),
                LinearLayout.LayoutParams(-1, -2))
        card.addView(makeStatusRow(R.drawable.ic_headphones, "耳机连接", 1),
                LinearLayout.LayoutParams(-1, -2))
        battRow = makeStatusRow(R.drawable.ic_check, "左右耳电量", 2)
        card.addView(battRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(card, lp(false))
    }

    private fun makeStatusRow(iconRes: Int, title: String, which: Int): LinearLayout {
        val row = LinearLayout(requireContext())
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(10), dp(14), dp(10))
        val ic = ImageView(requireContext())
        ic.setImageResource(iconRes)
        ic.imageTintList = ColorStateList.valueOf(onContainerColor)
        val ilp = LinearLayout.LayoutParams(dp(20), dp(20))
        ilp.marginEnd = dp(12)
        row.addView(ic, ilp)
        val t = TextView(requireContext())
        t.text = title
        t.textSize = 14f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(onSurfaceColor)
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        val valTv = TextView(requireContext())
        valTv.textSize = 13f
        valTv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        valTv.setTextColor(onVariantColor)
        valTv.gravity = Gravity.END
        valTv.isSingleLine = true
        row.addView(valTv, LinearLayout.LayoutParams(-2, -2))
        when (which) {
            0 -> gaiaStateVal = valTv
            1 -> headsetStateVal = valTv
            else -> battVal = valTv
        }
        return row
    }

    /** alpha2.4: 刷新运行状态面板（GAIA / 耳机 / 电量） */
    private fun updateStatusPanel() {
        val gVal = gaiaStateVal ?: return
        val gaia = GaiaBleClient.getInstance()
        val g = gaia.isConnected()
        gVal.text = if (g) "已连接" else "未连接"
        gVal.setTextColor(if (g) green else onVariantColor)
        val mac = HeadsetGate.getConnectedMac(requireContext())
        val sim = GaiaBleClient.isSimConnected()
        val hs = if (mac != null) "已连接" else (if (sim) "已连接（模拟）" else "未连接")
        headsetStateVal?.text = hs
        headsetStateVal?.setTextColor(if (mac != null || sim) green else onVariantColor)
        updateBatteryStatus()
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
                if (addr != null) BatteryStore.setGaiaLevel(addr, batteryId, level)
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

    private fun applyRootProtect(enable: Boolean) {
        if (enable) {
            if (!hasRoot()) {
                showSimpleDialog("未检测到 Root", "未检测到 Root 权限，无法启用强力保活。请确认设备已 root 且允许本应用使用 su。")
                requireContext().getSharedPreferences("cfg", Context.MODE_PRIVATE)
                        .edit().putBoolean("root_protect", false).commit()
                return
            }
            val script = "#!/system/bin/sh\n" +
                    "dumpsys deviceidle whitelist +com.fxxkmoondrop.secret\n" +
                    "appops set com.fxxkmoondrop.secret RUN_IN_BACKGROUND allow\n" +
                    "appops set com.fxxkmoondrop.secret RUN_ANY_IN_BACKGROUND allow\n" +
                    "appops set com.fxxkmoondrop.secret START_FOREGROUND allow\n"
            runRoot("mkdir -p /data/adb/service.d && echo '" + script + "' > /data/adb/service.d/50-moondrop-keepalive.sh && chmod 755 /data/adb/service.d/50-moondrop-keepalive.sh")
            val out = runRoot("dumpsys deviceidle whitelist +com.fxxkmoondrop.secret; " +
                    "appops set com.fxxkmoondrop.secret RUN_IN_BACKGROUND allow; " +
                    "appops set com.fxxkmoondrop.secret RUN_ANY_IN_BACKGROUND allow; " +
                    "appops set com.fxxkmoondrop.secret START_FOREGROUND allow; echo DONE")
            requireContext().getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .edit().putBoolean("root_protect", true).commit()
            toast(if (out != null && out.contains("DONE")) "已启用：电池白名单 + 开机脚本" else "已写入配置，请重启后生效")
        } else {
            runRoot("rm -f /data/adb/service.d/50-moondrop-keepalive.sh")
            requireContext().getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .edit().putBoolean("root_protect", false).commit()
            toast("已关闭强力保活")
        }
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
        g.cornerRadius = dp(24).toFloat()
        val rd = RippleDrawable(
                ColorStateList.valueOf(if (filled) 0x33FFFFFF else 0x33000000), g, null)
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
        b.setPadding(dp(22), dp(11), dp(22), dp(11))
        val g = GradientDrawable()
        g.setColor(0x14000000)
        g.cornerRadius = dp(22).toFloat()
        val rd = RippleDrawable(
                ColorStateList.valueOf(0x1F000000), g, null)
        b.background = rd
        b.setTextColor(color)
        b.setOnClickListener(onClick)
        return b
    }

    private fun showSimpleDialog(t: String, m: String) {
        val (d, box) = M3Ui.materialDialog(requireContext(), primaryColor, cardSurfaceColor)
        box.addView(M3Ui.dialogTitle(requireContext(), t, onSurfaceColor),
                LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(10)))
        val msg = TextView(requireContext())
        msg.text = m
        msg.textSize = 14f
        msg.setTextColor(onVariantColor)
        msg.setLineSpacing(dp(2).toFloat(), 1.25f)
        box.addView(msg, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(20)))
        val btnRow = LinearLayout(requireContext())
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.gravity = Gravity.END
        btnRow.addView(makeSmallButton("知道了", true) { d.dismiss() })
        box.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        d.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val AUTO_REFRESH_MS = 30000L
        private val ANC_NAMES = AncProfileLib.ANC_MODE_NAMES_FULL
        private const val SIM_MAC = "AA:BB:CC:DD:EE:FF"
        private const val SIM_NAME = "Moondrop Golden Ages 2"
        private const val REQ_PICK_ICON = 0xE16
        private const val GMS_ICON_PATH = "/data/user/0/com.google.android.gms/files/moondrop_icon.png"
    }
}
