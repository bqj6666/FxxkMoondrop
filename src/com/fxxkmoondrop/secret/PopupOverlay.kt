package com.fxxkmoondrop.secret

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

object PopupOverlay {

    private const val TAG = "MoondropHeadset"
    private const val SIM_MAC = "AA:BB:CC:DD:EE:FF" // alpha1.5: 模拟连接地址
    private var overlayView: View? = null
    private var wm: WindowManager? = null
    private var sBattTv: TextView? = null
    private var sMac = ""
    @JvmField var ancMode = -1
    private var ancPopupBtns: Array<TextView?>? = null
    private var ancPopupLabels: Array<TextView?>? = null
    private var popupAccent = 0
    private var popupTextColor = 0
    private val handler = Handler(Looper.getMainLooper())
    // alpha2.7: 模拟弹窗关闭钩子（模拟连接后弹窗消失自动恢复状态）
    private var simDismissHook: Runnable? = null
    private val dismissRunnable = Runnable { hide() }

    @JvmStatic
    fun setSimDismissHook(r: Runnable?) {
        simDismissHook = r
    }

    @JvmStatic
    fun clearSimDismissHook() {
        simDismissHook = null
    }

    private fun isDark(c: Context): Boolean =
            (c.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dyn(c: Context, resName: String, fallback: Int): Int {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                val id = c.resources.getIdentifier(resName, "color", "android")
                if (id != 0) return c.resources.getColor(id, c.theme)
            } catch (_: Exception) { }
        }
        return fallback
    }

    private fun vgap(c: Context, dp: Float): View {
        val d = c.resources.displayMetrics.density
        val v = View(c)
        v.layoutParams = LinearLayout.LayoutParams(1, (dp * d).toInt())
        return v
    }

    @JvmStatic
    fun show(c: Context, name: String?, mac: String?, connected: Boolean) {
        if (overlayView != null) {
            // 已有弹窗显示中：带退出动画替换（例如连接弹窗未消失时收到断开事件）
            handler.removeCallbacks(dismissRunnable)
            val old = overlayView
            overlayView = null
            animateRemove(old!!)
        }
        try {
            wm = c.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val density = c.resources.displayMetrics.density

            val dark = isDark(c)
            val accent = dyn(c, "system_accent1_400", if (dark) 0xFFD0BCFF.toInt() else 0xFF6750A4.toInt())
            val container = if (dark) dyn(c, "system_accent1_800", 0xFF4F378B.toInt())
            else dyn(c, "system_accent1_50", 0xFFE8DEF8.toInt())
            val textMain = if (dark) dyn(c, "system_neutral1_0", 0xFFE6E0E9.toInt())
            else dyn(c, "system_neutral1_900", 0xFF1C1B1F.toInt())
            val textSub = if (dark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
            android.util.Log.d("MoondropMonet", "POPUP dark=" + dark
                    + " accent=#" + Integer.toHexString(accent)
                    + " container=#" + Integer.toHexString(container)
                    + " darkmode=" + (if (dark) "YES" else "NO"))

            val card = if (connected) {
                buildConnectedCard(c, name, mac, dark, accent, container, textMain, textSub, density)
            } else {
                buildDisconnectedCard(c, name, dark, textMain, textSub, density)
            }

            val root = FrameLayout(c)
            val cardLp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            val margin = (24 * density).toInt()
            cardLp.setMargins(margin, 0, margin, 0)
            root.addView(card, cardLp)
            if (!connected) {
                // 断开弹窗：原样式，点按卡片可关闭（没有确定按钮）
                root.setOnClickListener { hide() }
            }

            overlayView = root

            val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT)
            // 弹窗位置：屏幕下方
            lp.gravity = Gravity.BOTTOM
            lp.y = (40 * density).toInt()
            wm!!.addView(root, lp)

            root.alpha = 0f
            root.translationY = 60 * density
            root.scaleX = 0.96f
            root.scaleY = 0.96f
            root.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator(1.4f))
                    .start()

            handler.removeCallbacks(dismissRunnable)
            handler.postDelayed(dismissRunnable, if (connected) 6000L else 3000L)
            android.util.Log.i(TAG, "overlay " + (if (connected) "connected" else "disconnected") + " for " + name
                    + " autoClose=" + (if (connected) 6000 else 3000) + "ms")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "overlay failed: " + e)
        }
    }

    // 文字入场：fade + 轻微上滑，post 到挂载后播放，delay 控制先后顺序
    private fun textIn(tv: TextView, density: Float, delay: Long) {
        tv.post {
            tv.alpha = 0f
            tv.translationY = 14 * density
            tv.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(320)
                    .setStartDelay(delay)
                    .setInterpolator(DecelerateInterpolator(1.4f))
                    .withEndAction {
                        tv.alpha = 1f
                        tv.translationY = 0f
                    }
                    .start()
        }
    }

    /** 连接弹窗：竖向，大图标 → 大标题 → MAC → 确定按钮 */

    private fun buildDcPopupRow(c: Context, density: Float, feature: Int, maxMode: Int,
                                 accent: Int, textMain: Int, textSub: Int): LinearLayout {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val label = TextView(c)
        label.text = when (feature) { 0 -> "空间音频"; 1 -> "增益"; 2 -> "指示灯"; else -> "" }
        label.textSize = 12f
        label.setTextColor(textSub)
        label.isSingleLine = true
        row.addView(label, LinearLayout.LayoutParams(-2, -2))
        row.addView(View(c), LinearLayout.LayoutParams(0, -2, 1f))
        val names = when (feature) {
            0 -> DeviceControlBridge.trackingLabels()
            1 -> DeviceControlBridge.gainLabels().toTypedArray()
            2 -> arrayOf("开", "关")
            else -> arrayOf("")
        }
        val curState = when (feature) {
            0 -> DeviceControlBridge.spatialUiMode()
            1 -> DeviceControlBridge.getGainLevel()
            2 -> if (DeviceControlBridge.getLedState() == 1) 0 else 1
            else -> -1
        }
        for (m in 0..maxMode) {
            val active = m == curState
            val col = LinearLayout(c)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val btn = android.widget.FrameLayout(c)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(if (active) accent else (textMain and 0x00FFFFFF) or 0x1A000000)
            btn.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33000000), g, null)
            val iv = android.widget.ImageView(c)
            iv.setImageDrawable(DcIcons.build(c, feature, m, (22 * density).toInt(),
                if (active) 0xFFFFFFFF.toInt() else textMain))
            val il = android.widget.FrameLayout.LayoutParams(
                (22 * density).toInt(), (22 * density).toInt(), Gravity.CENTER)
            btn.addView(iv, il)
            btn.setOnClickListener {
                when (feature) {
                    0 -> {
                        if (!DeviceControlBridge.isSpatialOn())
                            DeviceControlBridge.setSpatialEnabled(true)
                        DeviceControlBridge.setTrackingMode(m)
                    }
                    1 -> DeviceControlBridge.setGain(m)
                    2 -> DeviceControlBridge.setLed(if (DeviceControlBridge.getLedState() == 1) 0 else 1)
                }
                extendDismiss()
            }
            val sz = (44 * density).toInt()
            col.addView(btn, LinearLayout.LayoutParams(sz, sz))
            col.addView(vgap(c, 2f))
            val lbl = TextView(c)
            lbl.text = names[m]
            lbl.textSize = 10f
            lbl.gravity = Gravity.CENTER
            lbl.isSingleLine = true
            lbl.setTextColor(if (active) accent else textMain)
            col.addView(lbl, LinearLayout.LayoutParams(-2, -2))
            row.addView(col, LinearLayout.LayoutParams(-2, -2))
        }
        return row
    }

    private fun buildConnectedCard(c: Context, name: String?, mac: String?, dark: Boolean,
                                   accent: Int, container: Int, textMain: Int, textSub: Int,
                                   density: Float): View {
        val iconBgColor = container
        val iconColor = accent
        val cardBgColor = if (dark) dyn(c, "system_accent1_800", 0xFF1E1B22.toInt())
        else dyn(c, "system_neutral1_10", 0xFFFFFBFE.toInt())
        val strokeColor = (accent and 0x00FFFFFF) or 0x33000000

        val card = MaterialCardView(c)
        card.setRadius(28 * density)
        card.setCardBackgroundColor(cardBgColor)
        card.setStrokeColor(strokeColor)
        card.setStrokeWidth((1.5 * density).toInt())
        card.setCardElevation(16 * density)
        card.setPadding((24 * density).toInt(), (30 * density).toInt(),
                (24 * density).toInt(), (24 * density).toInt())
        val cv = LinearLayout(c)
        cv.orientation = LinearLayout.VERTICAL
        cv.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(cv, FrameLayout.LayoutParams(-1, -2))

        // 1) 大图标：居中放大，排在最上面
        val iconWrap = FrameLayout(c)
        val iconBg = GradientDrawable()
        iconBg.shape = GradientDrawable.OVAL
        iconBg.setColor(iconBgColor)
        iconWrap.background = iconBg
        val earIcon = InEarIcon(c)
        earIcon.setColor(iconColor)
        iconWrap.addView(earIcon, FrameLayout.LayoutParams(
                (78 * density).toInt(), (78 * density).toInt(), Gravity.CENTER))
        cv.addView(iconWrap, LinearLayout.LayoutParams(
                (122 * density).toInt(), (122 * density).toInt()))

        // 图标入场：从下向上渐显弹出（fade + slide-up + scale，Material 弹簧感）
        val iTrans = 46 * density
        iconWrap.post {
            iconWrap.alpha = 0f
            iconWrap.translationY = iTrans
            iconWrap.scaleX = 0.75f
            iconWrap.scaleY = 0.75f
            iconWrap.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(460)
                    .setStartDelay(140)
                    .setInterpolator(OvershootInterpolator(1.15f))
                    .withEndAction {
                        iconWrap.alpha = 1f
                        iconWrap.translationY = 0f
                        iconWrap.scaleX = 1f
                        iconWrap.scaleY = 1f
                    }
                    .start()
        }

        cv.addView(vgap(c, 22f))

        // 2) 大标题：耳机名字
        val title = TextView(c)
        title.text = if (name != null && name.length > 0) name else "已连接"
        title.textSize = 22f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setTextColor(textMain)
        title.gravity = Gravity.CENTER
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        cv.addView(title, LinearLayout.LayoutParams(-1, -2))
        textIn(title, density, 300)

        cv.addView(vgap(c, 8f))

        // 3) 小字：耳机电量（图标下方、MAC 上方，与系统蓝牙设置同源）
        var level = BatteryStore.get(mac)
        if (level < 0 && mac != null && android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                val ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                if (ba != null) {
                    try {
                        val dev = ba.getRemoteDevice(mac)
                        val m = dev.javaClass.getMethod("getBatteryLevel")
                        val r = m.invoke(dev)
                        if (r is Int) {
                            val lv = r
                            if (lv >= 0) {
                                level = lv
                                BatteryStore.set(mac, lv)
                            }
                        }
                    } catch (_: Throwable) { }
                }
            } catch (_: Exception) { }
        }
        val battTv = TextView(c)
        sBattTv = battTv
        sMac = if (mac == null) "" else mac
        updateBatteryText(battTv, mac)
        battTv.textSize = 11f
        battTv.setTextColor(textSub)
        battTv.gravity = Gravity.CENTER
        cv.addView(battTv, LinearLayout.LayoutParams(-1, -2))
        textIn(battTv, density, 380)

        cv.addView(vgap(c, 4f))

        // 4) 小字：耳机 MAC 地址
        val macTv = TextView(c)
        macTv.text = "MAC：" + (if (mac != null && mac.length > 0) mac else "未知")
        macTv.textSize = 11f
        macTv.setTextColor(textSub)
        macTv.gravity = Gravity.CENTER
        cv.addView(macTv, LinearLayout.LayoutParams(-1, -2))
        textIn(macTv, density, 460)

        cv.addView(vgap(c, 16f))

        // 4.5) 降噪快捷按钮（fixF v3.8 同款：🔇关闭 / 🎧降噪 / 👂透传）
        // alpha1.5: 仅真实耳机连接时显示（模拟连接不显示，降噪控制不跟随模拟态）
        val realHeadset = mac != null && !SIM_MAC.equals(mac, ignoreCase = true)
        if (realHeadset) {
            // alpha2.28: 4 按钮（加抗风），跟随 show_wind 设置
            val showWind = c.getSharedPreferences("cfg", 0).getBoolean("show_wind", true)
            val ancCount = if (showWind) 4 else 3
            ancPopupBtns = arrayOfNulls(4)
            ancPopupLabels = arrayOfNulls(4)
            popupAccent = accent
            popupTextColor = textMain
            val ancRow = LinearLayout(c)
            ancRow.orientation = LinearLayout.HORIZONTAL
            ancRow.gravity = Gravity.CENTER
            val ancEmoji = arrayOf("🔇", "🎧", "👂", "🌬️")
            val ancNames = AncProfileLib.ANC_MODE_NAMES
            for (m in 0 until ancCount) {
                val fm = m
                val col = LinearLayout(c)
                col.orientation = LinearLayout.VERTICAL
                col.gravity = Gravity.CENTER
                val btn = TextView(c)
                btn.text = ancEmoji[fm]
                btn.textSize = 24f
                btn.gravity = Gravity.CENTER
                btn.isSingleLine = true
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                if (fm == ancMode) {
                    g.setColor(accent)
                } else {
                    g.setColor((textMain and 0x00FFFFFF) or 0x1F000000)
                }
                btn.background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x33000000), g, null)
                btn.setOnClickListener {
                    AncBridge.setAncMode(fm)
                    setAncMode(fm)
                    extendDismiss()
                }
                ancPopupBtns!![fm] = btn
                val sz = (52 * density).toInt()
                col.addView(btn, LinearLayout.LayoutParams(sz, sz))
                col.addView(vgap(c, 2f))
                val lbl = TextView(c)
                lbl.text = ancNames[fm]
                lbl.textSize = 11f
                lbl.gravity = Gravity.CENTER
                lbl.isSingleLine = true
                lbl.setTextColor(if (fm == ancMode) accent else textMain)
                ancPopupLabels!![fm] = lbl
                col.addView(lbl, LinearLayout.LayoutParams(-2, -2))
                ancRow.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            }
            cv.addView(ancRow, LinearLayout.LayoutParams(-1, -2))

            // alpha2.32: 扩展设备控制——型号档案 + 圆形 Material 图标按钮
            val dcClient = GaiaBleClient.getInstance()
            val dcDevName = dcClient.getConnectedDeviceName()
            val dcProfile = AncProfileLib.resolveDc(dcDevName)
            val dcHasSpatial = dcProfile.hasSpatial || try { dcClient.hasSpatialSupport() } catch (e: Exception) { false }
            val dcHasGain = dcProfile.hasGain || try { dcClient.hasGainSupport() } catch (e: Exception) { false }
            val dcHasLed = dcProfile.hasLed || try { dcClient.hasLedSupport() } catch (e: Exception) { false }
            if (dcHasSpatial || dcHasGain || dcHasLed) {
                cv.addView(vgap(c, 10f))
                if (dcHasSpatial) {
                    cv.addView(buildDcPopupRow(c, density, 0, 2, accent, textMain, textSub))
                }
                if (dcHasGain) {
                    if (dcHasSpatial) cv.addView(vgap(c, 8f))
                    cv.addView(buildDcPopupRow(c, density, 1, DeviceControlBridge.gainCount() - 1, accent, textMain, textSub))
                }
                if (dcHasLed) {
                    if (dcHasSpatial || dcHasGain) cv.addView(vgap(c, 8f))
                    cv.addView(buildDcPopupRow(c, density, 2, 1, accent, textMain, textSub))
                }
            }
        } // alpha1.5: 真实连接才显示降噪快捷按钮

        cv.addView(vgap(c, 6f))

        // 5) 底部关闭按钮：圆形对勾（fixF v3.8 同款 CheckIcon）
        val okBtn = FrameLayout(c)
        val okBg = GradientDrawable()
        okBg.shape = GradientDrawable.OVAL
        okBg.setColor(accent)
        val checkIcon = CheckIcon(c)
        checkIcon.setColor(if (dark) 0xFFFFFFFF.toInt() else 0xFF1C1B1F.toInt())
        okBtn.addView(checkIcon, FrameLayout.LayoutParams(
                (40 * density).toInt(), (40 * density).toInt(), Gravity.CENTER))
        okBtn.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33000000), okBg, null)
        okBtn.setOnClickListener { hide() }
        cv.addView(okBtn, LinearLayout.LayoutParams((56 * density).toInt(), (56 * density).toInt()))
        return card
    }

    /** 断开弹窗：横向原样式，无确定按钮，加「已断开连接」文字 */
    private fun buildDisconnectedCard(c: Context, name: String?, dark: Boolean,
                                      textMain: Int, textSub: Int, density: Float): View {
        val titleColor = if (dark) 0xFFCAC4D0.toInt() else 0xFF757575.toInt()
        val iconBgColor = if (dark) 0xFF2B2930.toInt() else 0xFFE7E0EC.toInt()
        val iconColor = if (dark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
        val cardBgColor = if (dark) 0xFF211F26.toInt() else 0xFFF2F2F4.toInt()
        val strokeColor = if (dark) 0x26FFFFFF.toInt() else 0x1F000000.toInt()

        val card = MaterialCardView(c)
        card.setRadius(28 * density)
        card.setCardBackgroundColor(cardBgColor)
        card.setStrokeColor(strokeColor)
        card.setStrokeWidth((1.5 * density).toInt())
        card.setCardElevation(16 * density)
        card.setPadding((20 * density).toInt(), (16 * density).toInt(),
                (20 * density).toInt(), (16 * density).toInt())
        val cv = LinearLayout(c)
        cv.orientation = LinearLayout.HORIZONTAL
        cv.gravity = Gravity.CENTER_VERTICAL
        card.addView(cv, FrameLayout.LayoutParams(-1, -2))

        val iconWrap = FrameLayout(c)
        val iconBg = GradientDrawable()
        iconBg.shape = GradientDrawable.OVAL
        iconBg.setColor(iconBgColor)
        iconWrap.background = iconBg
        val earIcon = InEarIcon(c)
        earIcon.setColor(iconColor)
        iconWrap.addView(earIcon, FrameLayout.LayoutParams(
                (34 * density).toInt(), (34 * density).toInt(), Gravity.CENTER))
        val iconLp = LinearLayout.LayoutParams(
                (56 * density).toInt(), (56 * density).toInt())
        iconLp.rightMargin = (16 * density).toInt()
        cv.addView(iconWrap, iconLp)
        iconWrap.post {
            iconWrap.alpha = 0f
            iconWrap.scaleX = 0.6f
            iconWrap.scaleY = 0.6f
            iconWrap.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(420)
                    .setStartDelay(140)
                    .setInterpolator(OvershootInterpolator(1.15f))
                    .withEndAction {
                        iconWrap.alpha = 1f
                        iconWrap.scaleX = 1f
                        iconWrap.scaleY = 1f
                    }
                    .start()
        }

        val texts = LinearLayout(c)
        texts.orientation = LinearLayout.VERTICAL

        // 第一行：已断开连接
        val t1 = TextView(c)
        t1.text = "已断开连接"
        t1.textSize = 13f
        t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t1.setTextColor(titleColor)

        // 第二行：耳机名字
        val t2 = TextView(c)
        t2.text = if (name != null && name.length > 0) name else "已断开"
        t2.textSize = 19f
        t2.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t2.setTextColor(textMain)
        t2.setPadding(0, (2 * density).toInt(), 0, 0)

        texts.addView(t1)
        texts.addView(t2)
        cv.addView(texts)
        textIn(t1, density, 300)
        textIn(t2, density, 380)
        return card
    }

    @JvmStatic
    fun hide() {
        handler.removeCallbacks(dismissRunnable)
        val hook = simDismissHook
        simDismissHook = null
        if (hook != null) {
            try { hook.run() } catch (_: Throwable) { }
        }
        val v = overlayView
        overlayView = null
        if (v == null || wm == null) return
        animateRemove(v)
    }

    // Material 退出动画：向下回落 + 渐隐 + 轻微收缩
    private fun animateRemove(v: View) {
        try {
            v.animate().cancel()
            v.animate()
                    .alpha(0f)
                    .translationY(60 * v.resources.displayMetrics.density)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(240)
                    .setInterpolator(AccelerateInterpolator(1.2f))
                    .withEndAction {
                        try { wm?.removeView(v) } catch (_: Exception) { }
                    }.start()
        } catch (_: Exception) {
            try { wm?.removeView(v) } catch (_: Exception) { }
        }
    }

    /** 自绘入耳式（TWS 豆式）耳机图标，避免 emoji 无入耳式样式 */
    class InEarIcon(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            p.strokeCap = Paint.Cap.ROUND
        }

        fun setColor(color: Int) {
            p.color = color
        }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = w * 0.17f
            val gap = w * 0.20f
            p.strokeWidth = w * 0.085f
            val topY = cy - r * 0.35f
            cv.drawCircle(cx - gap, topY, r, p)
            cv.drawCircle(cx + gap, topY, r, p)
            val stemEnd = cy + r * 1.7f
            cv.drawLine(cx - gap, topY + r, cx - gap, stemEnd, p)
            cv.drawLine(cx + gap, topY + r, cx + gap, stemEnd, p)
        }
    }

    /** 自绘对勾图标（fixF v3.8 同款） */
    class CheckIcon(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
        }

        fun setColor(color: Int) {
            p.color = color
        }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            p.strokeWidth = w * 0.11f
            cv.drawLine(w * 0.26f, h * 0.52f, w * 0.44f, h * 0.70f, p)
            cv.drawLine(w * 0.44f, h * 0.70f, w * 0.76f, h * 0.32f, p)
        }
    }

    /** 更新电量文本（左右耳分离显示）
     *  alpha1.9: 先查纯 GAIA 左右耳（mac → gaiaAddr），无 GAIA 数据才用系统单值兜底 */
    private fun updateBatteryText(tv: TextView, mac: String?) {
        var levelL = BatteryStore.getGaiaLeft(mac)
        var levelR = BatteryStore.getGaiaRight(mac)
        if (levelL < 0 && levelR < 0) {
            val gaiaAddr = GaiaBleClient.getInstance().deviceAddress
            if (gaiaAddr != null && gaiaAddr != mac) {
                levelL = BatteryStore.getGaiaLeft(gaiaAddr)
                levelR = BatteryStore.getGaiaRight(gaiaAddr)
            }
        }
        val text: String
        // alpha1.9: 弹窗始终左右耳分离显示（用户要求：同时显示左耳+右耳，不合并）
        if (levelL >= 0 && levelR >= 0) {
            text = "左耳 " + levelL + "%  右耳 " + levelR + "%"
        } else if (levelL >= 0) {
            text = "左耳电量:" + levelL + "%"
        } else if (levelR >= 0) {
            text = "右耳电量:" + levelR + "%"
        } else {
            // 无 GAIA 数据：系统广播单值兜底（mac → gaiaAddr）
            var sys = BatteryStore.get(mac)
            if (sys < 0) {
                val gaiaAddr = GaiaBleClient.getInstance().deviceAddress
                if (gaiaAddr != null) sys = BatteryStore.get(gaiaAddr)
            }
            text = if (sys >= 0) "耳机电量:" + sys + "%" else "耳机电量:--%"
        }
        tv.text = text
    }

    /** GAIA 电量刷新时调用：弹窗可见则实时更新 */
    @JvmStatic
    fun setAncMode(mode: Int) {
        ancMode = mode
        refreshAncBtns()
    }

    @JvmStatic
    fun refreshAncBtns() {
        val btns = ancPopupBtns ?: return
        val labels = ancPopupLabels
        for (i in btns.indices) {
            val b = btns[i] ?: continue
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            if (i == ancMode) {
                g.setColor(popupAccent)
            } else {
                g.setColor((popupTextColor and 0x00FFFFFF) or 0x1F000000)
            }
            b.background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33000000), g, null)
            if (labels != null && labels[i] != null) {
                labels[i]!!.setTextColor(if (i == ancMode) popupAccent else popupTextColor)
            }
        }
    }

    /** 弹窗展示期间取消自动关闭（fixF 同款） */
    private fun extendDismiss() {
        handler.removeCallbacks(dismissRunnable)
        handler.postDelayed(dismissRunnable, 6000L)
    }

    @JvmStatic
    fun refreshBattery() {
        handler.post {
            if (sBattTv != null) {
                updateBatteryText(sBattTv!!, sMac)
            }
        }
    }
}
