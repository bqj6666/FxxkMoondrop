package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader

/**
 * alpha1.35: 设置&关于 —— 整页二级界面（兼容页，主设置入口为 SettingsFragment）。
 * Material You 动态取色 + 深浅色自适应（ThemeUtil 与主界面同色板）。
 */
class SettingsActivity : Activity() {

    private lateinit var pal: ThemeUtil.Palette
    private var statusBarH = 0
    private var simConnBtn: com.google.android.material.button.MaterialButton? = null
    private var simDiscBtn: com.google.android.material.button.MaterialButton? = null
    private var seedRow: LinearLayout? = null // alpha2.8: 种子颜色行（动态取色关闭时显示）
    private val simRestoreHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val simRestoreRunnable: Runnable = object : Runnable {
        override fun run() {
            PopupOverlay.clearSimDismissHook()
            simRestoreHandler.removeCallbacks(this)
            if (!GaiaBleClient.isSimConnected()) return
            GaiaBleClient.setSimConnected(false)
            BatteryStore.clearGaia(SIM_MAC)
            PopupGate.clear(SIM_MAC, SIM_NAME)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        pal = ThemeUtil.Palette(this)

        // alpha1.36: 窗口背景=surface + 系统栏透明（顶部完全铺满，无空白带；深浅色自适应）
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(pal.surface))
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) statusBarH = resources.getDimensionPixelSize(resId)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        root.setPadding(0, 0, 0, 0)

        // ── M3 Top App Bar：矢量返回按钮（alpha1.36 修复变形/错位）──
        root.addView(M3Ui.topBarTitle(this, pal, "设置"), LinearLayout.LayoutParams(-1, -2))
        root.addView(spacer(dp(8)))

        // ── 内容（可滚动）──
        val sv = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), 0, dp(16), dp(24))

        box.addView(M3Ui.sectionTitle(this, pal, "外观"))

        // ── 外观（官方 HomeAppearanceSheet 对位：主题模式 / 动态取色 / AMOLED / 种子色）──
        val appear = LinearLayout(this)
        appear.orientation = LinearLayout.VERTICAL
        appear.setPadding(dp(14), dp(12), dp(14), dp(12))
        val appearBg = GradientDrawable()
        appearBg.setColor(pal.card)
        appearBg.setCornerRadius(dp(24).toFloat())
        appear.background = appearBg
        // 主题模式：跟随系统 / 浅色 / 深色（3 段 pill）
        val modeRow = LinearLayout(this)
        modeRow.orientation = LinearLayout.HORIZONTAL
        modeRow.gravity = Gravity.CENTER
        val modeNames = arrayOf("跟随系统", "浅色", "深色")
        val curMode = ThemeUtil.themeMode(this)
        val modeBtns = arrayOfNulls<TextView>(3)
        for (mi in 0..2) {
            val fmi = mi
            val mb = TextView(this)
            mb.text = modeNames[mi]
            mb.textSize = 12f
            mb.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            mb.gravity = Gravity.CENTER
            mb.setPadding(dp(8), dp(8), dp(8), dp(8))
            val mg = GradientDrawable()
            mg.cornerRadius = dp(20).toFloat()
            mg.setColor(if (mi == curMode) pal.primary else if (pal.dark) 0x14FFFFFF else 0x0A000000)
            mb.background = mg
            mb.setTextColor(if (mi == curMode) pal.onPrimary else pal.onVariant)
            mb.setOnClickListener {
                getSP().edit().putInt("theme_mode", fmi).commit()
                recreate()
            }
            modeBtns[mi] = mb
            modeRow.addView(mb, LinearLayout.LayoutParams(0, -2, 1f))
            if (mi < 2) modeRow.addView(spacer(dp(6)))
        }
        appear.addView(modeRow, LinearLayout.LayoutParams(-1, -2))
        appear.addView(spacer(dp(6)))
        appear.addView(makeAppearDivider(), appearDividerLp())

        // 动态取色 + AMOLED 开关（官方主题设置）
        val swDyn = makeSwitchRow("动态取色",
                "跟随壁纸调色；关闭后使用下方种子颜色", makeThemeSwitch("dynamic_color", true, "动态取色"))
        appear.addView(swDyn, LinearLayout.LayoutParams(-1, -2))
        appear.addView(makeAppearDivider(), appearDividerLp())
        val swAmoled = makeSwitchRow("AMOLED 纯黑",
                "深色模式下使用纯黑背景", makeThemeSwitch("amoled", false, "AMOLED"))
        appear.addView(swAmoled, LinearLayout.LayoutParams(-1, -2))

        // 种子颜色（仅动态取色关闭时显示）：5 个官方种子色点
        seedRow = LinearLayout(this)
        seedRow!!.orientation = LinearLayout.HORIZONTAL
        seedRow!!.gravity = Gravity.CENTER_VERTICAL
        val seedLabel = TextView(this)
        seedLabel.text = "种子颜色"
        seedLabel.textSize = 14f
        seedLabel.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        seedLabel.setTextColor(pal.onSurface)
        seedRow!!.addView(seedLabel, LinearLayout.LayoutParams(0, -2, 1f))
        val curSeed = ThemeUtil.seed(this)
        for (si in ThemeUtil.SEEDS.indices) {
            val fsi = si
            val seedCol = ThemeUtil.SEEDS[si]
            val dot = View(this)
            val dg = GradientDrawable()
            dg.shape = GradientDrawable.OVAL
            dg.setColor(seedCol)
            if (si == curSeed) dg.setStroke(dp(3), pal.onSurface)
            dot.background = dg
            val dlp = LinearLayout.LayoutParams(dp(26), dp(26))
            dlp.marginStart = dp(6)
            dot.setOnClickListener {
                // 选种子色 = 官方互斥：自动关闭动态取色，储存并即时重建
                getSP().edit().putInt("seed", fsi).putBoolean("dynamic_color", false).commit()
                recreate()
            }
            seedRow!!.addView(dot, dlp)
        }
        if (!ThemeUtil.dynColor(this)) {
            appear.addView(makeAppearDivider(), appearDividerLp())
            appear.addView(seedRow, LinearLayout.LayoutParams(-1, -2))
            // alpha2.8: 种子颜色行入场动画（fade + slide，Material emphasized）
            seedRow!!.alpha = 0f
            seedRow!!.translationY = dp(8).toFloat()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                seedRow!!.animate().alpha(1f).translationY(0f).setDuration(250).start()
            }, 120)
        }

        box.addView(appear, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(14)))

        box.addView(M3Ui.sectionTitle(this, pal, "通用"))

        // ── 检查权限 / 日志抓取 / 弹窗图标：官方分组卡片 ──
        val rowPerm = M3Ui.listRow(this, pal, R.drawable.ic_search, "检查权限",
                "蓝牙、通知、悬浮窗、Root/模块环境",
                M3Ui.chevron(this, pal.onVariant),
                { startActivity(Intent(this, PermissionActivity::class.java)) })
        val rowLog = M3Ui.listRow(this, pal, R.drawable.ic_log, "日志抓取（设备适配）",
                "收集设备信息与运行日志，导出 ZIP（含隐私声明）",
                M3Ui.chevron(this, pal.onVariant), { showLogDialog() })
        val iconState = TextView(this)
        iconState.text = if (iconCustomExists()) "已自定义" else "默认"
        iconState.textSize = 13f
        iconState.setTextColor(pal.primary)
        iconState.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val rowIcon = M3Ui.listRow(this, pal, R.drawable.ic_image, "弹窗图标",
                "Google 弹窗显示的耳机图标（从相册选择，或恢复默认）", iconState,
                {
                    iconState.text = if (iconCustomExists()) "已自定义" else "默认"
                    showIconDialog(iconCustomExists())
                })
        box.addView(M3Ui.groupCard(this, pal, rowPerm, rowLog, rowIcon))

        box.addView(spacer(dp(10)))

        box.addView(M3Ui.sectionTitle(this, pal, "行为"))

        // ── Root 强力保活 ──
        val swRoot = com.google.android.material.materialswitch.MaterialSwitch(this)
        swRoot.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.primary, if (pal.dark) 0x33FFFFFF else 0x22000000))
        swRoot.thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.onPrimary, pal.onSurface))
        swRoot.isChecked = getSP().getBoolean("root_protect", false)
        val rowRoot = M3Ui.listRow(this, pal, R.drawable.ic_power, "Root 强力保活",
                "开机自启 + 后台防杀（需 Root）", swRoot, null)
        swRoot.setOnCheckedChangeListener { b, checked ->
            if (checked) showRootWarnDialog(swRoot)
            else applyRootProtect(false)
        }

        // ── 后台隐藏 ──
        val swBg = com.google.android.material.materialswitch.MaterialSwitch(this)
        swBg.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.primary, if (pal.dark) 0x33FFFFFF else 0x22000000))
        swBg.thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.onPrimary, pal.onSurface))
        swBg.isChecked = getSP().getBoolean("bg_hide", false)
        val rowBg = M3Ui.listRow(this, pal, R.drawable.ic_block, "后台隐藏",
                "切到后台自动隐藏主界面（不驻留最近任务）", swBg, null)
        swBg.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean("bg_hide", checked).commit()
        }

        // ── 启动自动监听 ──
        val swAuto = com.google.android.material.materialswitch.MaterialSwitch(this)
        swAuto.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.primary, if (pal.dark) 0x33FFFFFF else 0x22000000))
        swAuto.thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.onPrimary, pal.onSurface))
        swAuto.isChecked = getSP().getBoolean("auto_service", true)
        val rowAuto = M3Ui.listRow(this, pal, R.drawable.ic_play, "启动自动监听",
                "启动应用时自动监听；连接耳机自动直连 GAIA 读取电量与控制降噪", swAuto, null)
        swAuto.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean("auto_service", checked).commit()
        }

        // ── Google 弹窗（Fast Pair）──
        val swGp = com.google.android.material.materialswitch.MaterialSwitch(this)
        swGp.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.primary, if (pal.dark) 0x33FFFFFF else 0x22000000))
        swGp.thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.onPrimary, pal.onSurface))
        swGp.isChecked = getSP().getBoolean(PopupGate.CFG_FASTPAIR_POPUP, true)
        val rowGp = M3Ui.listRow(this, pal, R.drawable.ic_bluetooth, "Google 弹窗（Fast Pair）",
                "连接时使用谷歌半屏配对弹窗（含电量与降噪控制）；关闭则用应用自带弹窗", swGp, null)
        swGp.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean(PopupGate.CFG_FASTPAIR_POPUP, checked).commit()
        }

        // 行为区分组卡片
        box.addView(M3Ui.groupCard(this, pal, rowRoot, rowBg, rowAuto, rowGp))
        box.addView(spacer(dp(14)))

        // ── 模拟测试（alpha2.3 从主页迁入；真实耳机连接时禁用）──
        box.addView(M3Ui.sectionTitle(this, pal, "模拟测试"))
        val simBox = LinearLayout(this)
        simBox.orientation = LinearLayout.VERTICAL
        simBox.setPadding(dp(14), dp(12), dp(14), dp(12))
        val simBg = GradientDrawable()
        simBg.setColor(pal.card)
        simBg.setCornerRadius(dp(24).toFloat())
        simBox.background = simBg
        simConnBtn = makeM3Button("模拟连接 耳机", R.drawable.ic_bluetooth, pal.container, pal.onContainer) {
            // 模拟连接：GAIA 模拟态 + 左右耳模拟电量 + 默认降噪模式 + 弹窗（可重复点击）
            GaiaBleClient.setSimConnected(true)
            BatteryStore.setGaiaLevel(SIM_MAC, 1, 86)
            BatteryStore.setGaiaLevel(SIM_MAC, 2, 72)
            AncBridge.notifyAncMode(1)
            PopupGate.clear(SIM_MAC, SIM_NAME)
            PopupGate.tryShowConnected(this, SIM_MAC, SIM_NAME)
            // alpha2.7: 模拟弹窗消失后自动恢复（自带弹窗用 hide 钩子，GMS 弹窗用 30s 兜底）
            simRestoreHandler.removeCallbacks(simRestoreRunnable)
            PopupOverlay.setSimDismissHook(simRestoreRunnable)
            simRestoreHandler.postDelayed(simRestoreRunnable, 30000)
        }
        simBox.addView(simConnBtn, LinearLayout.LayoutParams(-1, -2))
        simBox.addView(spacer(dp(10)))
        simDiscBtn = makeM3Button("模拟断开 耳机", R.drawable.ic_bluetooth, pal.container, pal.onContainer) {
            // 模拟断开：退出 GAIA 模拟态 + 清模拟电量 + 弹窗（可重复点击）
            simRestoreHandler.removeCallbacks(simRestoreRunnable)
            PopupOverlay.clearSimDismissHook()
            GaiaBleClient.setSimConnected(false)
            BatteryStore.clearGaia(SIM_MAC)
            PopupGate.clear(SIM_MAC, SIM_NAME)
            PopupGate.tryShowDisconnected(this, SIM_MAC, SIM_NAME)
        }
        simBox.addView(simDiscBtn, LinearLayout.LayoutParams(-1, -2))
        box.addView(simBox, LinearLayout.LayoutParams(-1, -2))

        sv.addView(box, FrameLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(M3Ui.navBar(this, pal, 2) { id ->
                    if (id == 1) M3Ui.goPage(this, MainActivity::class.java)
                    else if (id == 3) M3Ui.goPage(this, AboutActivity::class.java)
                },
                LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        // alpha2.11: 内容入场动画（底栏静止，仅内容区淡入上滑）
        M3Ui.contentEnter(this, sv)

        // 状态栏/导航栏随色板
        window.statusBarColor = pal.surface
        window.navigationBarColor = pal.surface
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController
            if (c != null) c.setSystemBarsAppearance(
                    if (pal.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        }
    }

    // ── UI 辅助 ──
    private fun getSP(): android.content.SharedPreferences =
            getSharedPreferences("cfg", MODE_PRIVATE)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun spacer(h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(1, h)
        return v
    }

    private fun makeM3Button(text: String, iconRes: Int, bgColor: Int, textColor: Int,
                             l: View.OnClickListener): com.google.android.material.button.MaterialButton {
        val b = com.google.android.material.button.MaterialButton(this)
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
        if (iconRes != 0) {
            b.setIconResource(iconRes)
            b.iconTint = ColorStateList.valueOf(textColor)
            b.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            b.iconPadding = dp(8)
            b.iconSize = dp(22)
        }
        b.setOnClickListener(l)
        return b
    }

    /** 真实耳机已连接时禁用模拟按钮（与主页"连接后不可用"逻辑一致） */
    private fun updateSimState() {
        val real = HeadsetGate.getConnectedMac(this) != null
        simConnBtn?.let { setSimEnabled(it, !real) }
        simDiscBtn?.let { setSimEnabled(it, !real) }
    }

    private fun setSimEnabled(b: com.google.android.material.button.MaterialButton, en: Boolean) {
        b.isEnabled = en
        b.alpha = if (en) 1f else 0.45f
        b.backgroundTintList = ColorStateList.valueOf(if (en) pal.container else if (pal.dark) 0x14FFFFFF else 0x0A000000)
        b.setTextColor(if (en) pal.onContainer else pal.onVariant)
    }

    override fun onResume() {
        super.onResume()
        updateSimState()
    }

    private fun makeMaterialTextButton(text: String, color: Int, l: View.OnClickListener): TextView {
        val b = TextView(this)
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
        b.setOnClickListener(l)
        return b
    }

    /** 通用导航行（alpha1.36: M3Ui 卡片行——圆形容器图标 + chevron） */
    private fun makeNavRow(iconRes: Int, title: String, sub: String, onNav: Runnable): LinearLayout =
            M3Ui.navRow(this, pal, iconRes, title, sub, onNav)

    /** 通用开关行（alpha1.36: M3Ui 卡片行） */
    private fun makeSwitchRow(title: String, sub: String,
                              sw: com.google.android.material.materialswitch.MaterialSwitch): LinearLayout =
            M3Ui.switchRow(this, pal, title, sub, sw)

    /** alpha2.8: 外观卡行间分隔线（与 groupCard 同款淡线） */
    private fun makeAppearDivider(): View {
        val d = View(this)
        d.setBackgroundColor((pal.outline and 0x00FFFFFF) or 0x2E000000)
        return d
    }

    /** alpha2.8: 分隔线布局参数（1dp 高，左右 16dp 边距，与 groupCard 对齐） */
    private fun appearDividerLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(-1, dp(1))
        lp.marginStart = dp(16)
        lp.marginEnd = dp(16)
        return lp
    }

    /** 官方主题设置开关（MaterialSwitch），改动即存 SP + 重建 */
    private fun makeThemeSwitch(key: String, def: Boolean, label: String): com.google.android.material.materialswitch.MaterialSwitch {
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this)
        sw.isChecked = getSP().getBoolean(key, def)
        sw.contentDescription = label
        val trackOn = pal.primary
        val trackOff = if (pal.dark) 0x33FFFFFF else 0x22000000
        val trackTint = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(trackOn, trackOff))
        sw.trackTintList = trackTint
        sw.thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.onPrimary, pal.onSurface))
        sw.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean(key, checked).commit()
            // alpha2.8: 开启动态取色 -> 先播种子颜色行消失动画，再重建（Material fade+slide）
            if (key == "dynamic_color" && checked
                    && seedRow != null && seedRow!!.parent != null) {
                seedRow!!.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(200).start()
                android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ recreate() }, 550)
            } else {
                // alpha2.7: 等 MaterialSwitch 动画播完再重建（立即 recreate 会吞掉开关动画）
                android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ recreate() }, 350)
            }
        }
        return sw
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ── Root 强力保活（与 alpha1.34 行为一致）──
    private fun hasRoot(): Boolean {
        val out = runRoot("id")
        return out != null && out.contains("uid=0")
    }

    private fun runRoot(cmd: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val br = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (br.readLine().also { line = it } != null) sb.append(line).append('\n')
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun showRootWarnDialog(sw: com.google.android.material.materialswitch.MaterialSwitch) {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(28), dp(28), dp(28), dp(20))
        val title = TextView(this)
        title.text = "⚠️  权限风险警告"
        title.textSize = 22f
        title.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        title.setTextColor(pal.onSurface)
        box.addView(title, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(14)))
        val msg = TextView(this)
        msg.text = "开启后将使用 Root 权限执行系统命令：\n" +
                "• 将本应用加入系统电池优化白名单（防 Doze 杀后台）\n" +
                "• 允许后台运行，写入 Magisk 开机脚本实现开机自启\n\n" +
                "请确认：\n" +
                "• 设备已获取 Root 权限\n" +
                "• 你了解 Root 操作的风险\n" +
                "• 本应用来源可信"
        msg.textSize = 14f
        msg.setTextColor(pal.onVariant)
        msg.setLineSpacing(dp(3).toFloat(), 1.3f)
        box.addView(msg, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(24)))
        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.gravity = Gravity.END
        btnRow.addView(makeMaterialTextButton("取消", pal.onVariant) {
            sw.isChecked = false
            d.dismiss()
        })
        btnRow.addView(spacer(dp(6)))
        btnRow.addView(makeMaterialTextButton("继续开启", pal.primary) {
            d.dismiss()
            applyRootProtect(true)
        })
        box.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(box)
        d.window?.let {
            val dbg = GradientDrawable()
            dbg.setColor(pal.card)
            dbg.cornerRadius = dp(28).toFloat()
            dbg.setStroke(dp(1), 0x14000000)
            it.setBackgroundDrawable(dbg)
            it.setLayout((resources.displayMetrics.widthPixels * 0.84).toInt(), -2)
        }
        d.setCancelable(false)
        d.show()
    }

    private fun applyRootProtect(enable: Boolean) {
        if (enable) {
            if (!hasRoot()) {
                showSimpleDialog("未检测到 Root",
                        "未检测到 Root 权限，无法启用强力保活。请确认设备已 root 且允许本应用使用 su。")
                getSP().edit().putBoolean("root_protect", false).commit()
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
            getSP().edit().putBoolean("root_protect", true).commit()
            toast(if (out != null && out.contains("DONE")) "✅ 已启用：电池白名单 + 开机脚本" else "已写入配置，请重启后生效")
        } else {
            runRoot("rm -f /data/adb/service.d/50-moondrop-keepalive.sh; " +
                    "dumpsys deviceidle whitelist -com.fxxkmoondrop.secret; " +
                    "appops set com.fxxkmoondrop.secret RUN_IN_BACKGROUND default; " +
                    "appops set com.fxxkmoondrop.secret RUN_ANY_IN_BACKGROUND default; " +
                    "appops set com.fxxkmoondrop.secret START_FOREGROUND default; echo DONE")
            getSP().edit().putBoolean("root_protect", false).commit()
            toast("已关闭 Root 强力保活")
        }
    }

    // ── 日志抓取（alpha1.37）：Material 隐私声明弹窗 → 后台收集 → 显示路径 ──
    private fun showLogDialog() {
        showMaterialConfirm("日志抓取 · 隐私声明",
                LogCollector.PRIVACY_NOTICE,
                "同意并抓取", {
                    toast("⏳ 正在收集日志…")
                    Thread {
                        val path = LogCollector.collect(this@SettingsActivity)
                        runOnUiThread {
                            showSimpleDialog("日志已保存",
                                    "已打包为 ZIP（含 5 条分类日志）。\n\n路径：\n" + path +
                                            "\n\n您可自行将文件分享给开发者进行设备适配分析。")
                        }
                    }.start()
                })
    }

    /** Material 风格确认弹窗（深浅色自适应，pal 色板），ok 回调在主线程。 */
    private fun showMaterialConfirm(t: String, m: String, okText: String, onOk: Runnable) {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(28), dp(30), dp(28), dp(22))
        val title = TextView(this)
        title.text = t
        title.textSize = 22f
        title.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        title.setTextColor(pal.onSurface)
        box.addView(title, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(12)))
        val msg = TextView(this)
        msg.text = m
        msg.textSize = 14f
        msg.setTextColor(pal.onVariant)
        msg.setLineSpacing(dp(3).toFloat(), 1.3f)
        box.addView(msg, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(20)))
        val btnRow = LinearLayout(this)
        btnRow.gravity = Gravity.END
        btnRow.addView(makeMaterialTextButton("取消", pal.onVariant) { d.dismiss() })
        btnRow.addView(spacer(dp(10)))
        btnRow.addView(makeMaterialTextButton(okText, pal.primary) {
            d.dismiss()
            onOk.run()
        })
        box.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(box)
        d.window?.let {
            val dbg = GradientDrawable()
            dbg.setColor(pal.card)
            dbg.cornerRadius = dp(28).toFloat()
            it.setBackgroundDrawable(dbg)
            it.setLayout((resources.displayMetrics.widthPixels * 0.84).toInt(), -2)
        }
        d.show()
    }

    private fun showSimpleDialog(t: String, m: String) {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(28), dp(30), dp(28), dp(22))
        val title = TextView(this)
        title.text = t
        title.textSize = 22f
        title.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        title.setTextColor(pal.onSurface)
        box.addView(title, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(12)))
        val msg = TextView(this)
        msg.text = m
        msg.textSize = 14f
        msg.setTextColor(pal.onVariant)
        msg.setLineSpacing(dp(3).toFloat(), 1.3f)
        box.addView(msg, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(20)))
        val btnRow = LinearLayout(this)
        btnRow.gravity = Gravity.END
        btnRow.addView(makeMaterialTextButton("知道了", pal.primary) { d.dismiss() })
        box.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(box)
        d.window?.let {
            val dbg = GradientDrawable()
            dbg.setColor(pal.card)
            dbg.cornerRadius = dp(28).toFloat()
            it.setBackgroundDrawable(dbg)
            it.setLayout((resources.displayMetrics.widthPixels * 0.84).toInt(), -2)
        }
        d.show()
    }

    // ── 弹窗图标（与 alpha1.34 行为一致）──
    private fun iconCustomExists(): Boolean {
        val r = runRoot("test -f " + GMS_ICON_PATH + " && echo CUSTOM")
        return r != null && r.contains("CUSTOM")
    }

    private fun showIconDialog(custom: Boolean) {
        val dlg = android.app.Dialog(this)
        dlg.window?.let {
            it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            it.setDimAmount(0.5f)
        }
        val accent = pal.primary
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val cardBg = GradientDrawable()
        cardBg.setColor(pal.card)
        cardBg.cornerRadius = dp(28).toFloat()
        cardBg.setStroke(dp(1), (accent and 0x00FFFFFF) or 0x33000000)
        card.background = cardBg
        card.elevation = dp(16).toFloat()
        card.setPadding(dp(24), dp(22), dp(24), dp(24))

        val head = TextView(this)
        head.text = "弹窗图标（当前：" + (if (custom) "已自定义" else "默认") + "）"
        head.textSize = 22f
        head.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        head.setTextColor(accent)
        head.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(head, LinearLayout.LayoutParams(-1, -2))
        card.addView(spacer(dp(10)))

        val items = if (custom) arrayOf("从相册选择", "恢复默认图标") else arrayOf("从相册选择")
        val subs = if (custom) arrayOf("选择一张图片，替换 Google 弹窗显示的耳机图标", "删除自定义图标，恢复软件自带默认图")
        else arrayOf("选择一张图片，替换 Google 弹窗显示的耳机图标")
        for (i in items.indices) {
            val which = i
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            val rowBg = GradientDrawable()
            rowBg.setColor(if (pal.dark) 0x14FFFFFF else 0x0A000000)
            rowBg.cornerRadius = dp(14).toFloat()
            row.background = RippleDrawable(
                    ColorStateList.valueOf(if (pal.dark) 0x33FFFFFF else 0x22000000), rowBg, null)
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val t1 = TextView(this)
            t1.text = items[i]
            t1.textSize = 15f
            t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            t1.setTextColor(pal.onSurface)
            col.addView(t1, LinearLayout.LayoutParams(-2, -2))
            val t2 = TextView(this)
            t2.text = subs[i]
            t2.textSize = 12f
            t2.setTextColor(pal.onVariant)
            t2.alpha = 0.7f
            col.addView(t2, LinearLayout.LayoutParams(-2, -2))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(M3Ui.chevron(this, accent), LinearLayout.LayoutParams(-2, -2))
            row.setOnClickListener {
                dlg.dismiss()
                if (which == 0) {
                    val pick = Intent(Intent.ACTION_GET_CONTENT)
                    pick.type = "image/*"
                    pick.addCategory(Intent.CATEGORY_OPENABLE)
                    try {
                        startActivityForResult(Intent.createChooser(pick, "选择耳机图标"), REQ_PICK_ICON)
                    } catch (t: Throwable) {
                        toast("无法打开选择器: " + t.message)
                    }
                } else {
                    resetCustomIcon()
                }
            }
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            card.addView(spacer(dp(8)))
        }
        val btnRow = LinearLayout(this)
        btnRow.gravity = Gravity.END
        btnRow.addView(makeMaterialTextButton("取消", accent) { dlg.dismiss() })
        card.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        dlg.setContentView(card)
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.85).toInt(), -2)
        dlg.show()
    }

    private fun resetCustomIcon() {
        Thread {
            val r = runRoot("rm -f " + GMS_ICON_PATH + " && echo OK")
            val ok = r != null && r.contains("OK")
            runOnUiThread { toast(if (ok) "✅ 已恢复默认图标（下次连接生效）" else "恢复失败，请检查 Root") }
        }.start()
    }

    private fun saveIconFromUri(uri: Uri) {
        Thread {
            try {
                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    runOnUiThread { toast("无法读取所选图片") }
                    return@Thread
                }
                val bmp = BitmapFactory.decodeStream(input)
                try { input.close() } catch (_: Exception) { }
                if (bmp == null) {
                    runOnUiThread { toast("图片解码失败") }
                    return@Thread
                }
                val w = bmp.width
                val h = bmp.height
                val longSide = Math.max(w, h)
                var target = if (longSide > 512) 512 else longSide
                val out = File(cacheDir, "moondrop_custom_icon.png")
                var scaled = bmp
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
                    runOnUiThread { toast("图片仍超 1MB，请换小图") }
                    return@Thread
                }
                val uid = runRoot("stat -c %u:%g /data/user/0/com.google.android.gms")?.trim()
                if (uid == null || !uid.contains(":")) {
                    runOnUiThread { toast("读取 GMS 属主失败") }
                    return@Thread
                }
                val cmd = "cp '" + out.absolutePath + "' " + GMS_ICON_PATH +
                        " && chown " + uid + " " + GMS_ICON_PATH +
                        " && chmod 644 " + GMS_ICON_PATH + " && echo OK"
                val r = runRoot(cmd)
                val ok = r != null && r.contains("OK")
                runOnUiThread { toast(if (ok) "✅ 弹窗图标已更新（下次连接生效）" else "写入图标失败，请检查 Root") }
                if (scaled !== bmp && !scaled.isRecycled) scaled.recycle()
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
            } catch (t: Throwable) {
                runOnUiThread { toast("图标处理失败: " + t.message) }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_ICON && resultCode == RESULT_OK && data != null && data.data != null) {
            saveIconFromUri(data.data!!)
        }
    }

    companion object {
        private const val REQ_PICK_ICON = 0xE16
        private const val GMS_ICON_PATH = "/data/user/0/com.google.android.gms/files/moondrop_icon.png"
        private const val SIM_MAC = "AA:BB:CC:DD:EE:FF"
        private const val SIM_NAME = "Moondrop Golden Ages 2"
    }
}
