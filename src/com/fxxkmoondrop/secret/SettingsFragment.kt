package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * alpha1.35: 设置&关于 —— 整页二级界面（替代原 Dialog 浮窗）。
 * Material You 动态取色 + 深浅色自适应（ThemeUtil 与主界面同色板）。
 */
@Suppress("DEPRECATION")
class SettingsFragment : Fragment() {

    private lateinit var pal: ThemeUtil.Palette
    private var statusBarH = 0
    private var simConnBtn: MaterialButton? = null
    private val simRestoreHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val simRestoreRunnable = object : Runnable {
        override fun run() {
            PopupOverlay.clearSimDismissHook()
            simRestoreHandler.removeCallbacks(this)
            if (!GaiaBleClient.isSimConnected()) return
            GaiaBleClient.setSimConnected(false)
            BatteryStore.clearGaia(SIM_MAC)
            PopupGate.clear(SIM_MAC, SIM_NAME)
        }
    }
    private var simDiscBtn: MaterialButton? = null
    private var seedRow: LinearLayout? = null // alpha2.8: 种子颜色行（动态取色关闭时显示；出现/消失动画）

    override fun onCreateView(inflater: LayoutInflater, containerView: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, containerView, savedInstanceState)
        pal = ThemeUtil.Palette(requireContext())

        // alpha1.36: 窗口背景=surface + 系统栏透明（顶部完全铺满，无空白带；深浅色自适应）
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) statusBarH = resources.getDimensionPixelSize(resId)

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        root.setPadding(0, statusBarH, 0, 0)

        // ── M3 Top App Bar：矢量返回按钮（alpha1.36 修复变形/错位）──
        root.addView(M3Ui.topBarTitle(requireActivity(), pal, "设置"), LinearLayout.LayoutParams(-1, -2))
        root.addView(spacer(dp(8)))

        // ── 内容（可滚动）──
        val sv = ScrollView(requireContext())
        val box = LinearLayout(requireContext())
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), 0, dp(16), dp(24))

        box.addView(M3Ui.sectionTitle(requireActivity(), pal, "外观"))

        // ── 外观（官方 HomeAppearanceSheet 对位：主题模式 / 动态取色 / AMOLED / 种子色）──
        val appear = LinearLayout(requireContext())
        appear.orientation = LinearLayout.VERTICAL
        appear.setPadding(dp(14), dp(12), dp(14), dp(12))
        val appearBg = GradientDrawable()
        appearBg.setColor(pal.card)
        appearBg.setCornerRadius(dp(24).toFloat())
        appear.background = appearBg
        // 主题模式：跟随系统 / 浅色 / 深色（3 段 pill）
        val modeRow = LinearLayout(requireContext())
        modeRow.orientation = LinearLayout.HORIZONTAL
        modeRow.gravity = Gravity.CENTER
        val modeNames = arrayOf("跟随系统", "浅色", "深色")
        val curMode = ThemeUtil.themeMode(requireContext())
        for (mi in 0 until 3) {
            val mb = TextView(requireContext())
            mb.text = modeNames[mi]
            mb.textSize = 12f
            mb.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            mb.gravity = Gravity.CENTER
            mb.setPadding(dp(8), dp(8), dp(8), dp(8))
            val mg = GradientDrawable()
            mg.setCornerRadius(dp(20).toFloat())
            mg.setColor(if (mi == curMode) pal.primary
            else if (pal.dark) 0x14FFFFFF else 0x0A000000)
            mb.background = mg
            mb.setTextColor(if (mi == curMode) pal.onPrimary else pal.onVariant)
            mb.setOnClickListener {
                getSP().edit().putInt("theme_mode", mi).commit()
                requireActivity().recreate()
            }
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
        seedRow = LinearLayout(requireContext())
        seedRow!!.orientation = LinearLayout.HORIZONTAL
        seedRow!!.gravity = Gravity.CENTER_VERTICAL
        val seedLabel = TextView(requireContext())
        seedLabel.text = "种子颜色"
        seedLabel.textSize = 14f
        seedLabel.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        seedLabel.setTextColor(pal.onSurface)
        seedLabel.setPadding(0, 0, 0, 0)
        seedRow!!.addView(seedLabel, LinearLayout.LayoutParams(0, -2, 1f))
        val curSeed = ThemeUtil.seed(requireContext())
        for (si in ThemeUtil.SEEDS.indices) {
            val seedCol = ThemeUtil.SEEDS[si]
            val dot = View(requireContext())
            val dg = GradientDrawable()
            dg.shape = GradientDrawable.OVAL
            dg.setColor(seedCol)
            if (si == curSeed) dg.setStroke(dp(3), pal.onSurface)
            dot.background = dg
            val dlp = LinearLayout.LayoutParams(dp(26), dp(26))
            dlp.marginStart = dp(6)
            dot.setOnClickListener {
                // 选种子色 = 官方互斥：自动关闭动态取色，储存并即时重建
                getSP().edit().putInt("seed", si).putBoolean("dynamic_color", false).commit()
                requireActivity().recreate()
            }
            seedRow!!.addView(dot, dlp)
        }
        if (!ThemeUtil.dynColor(requireContext())) {
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

        box.addView(M3Ui.sectionTitle(requireActivity(), pal, "通用"))

        // ── 检查权限 / 日志抓取 / 弹窗图标：官方分组卡片 ──
        val rowPerm = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_search, "检查权限",
                "蓝牙、通知、悬浮窗、Root/模块环境",
                M3Ui.chevron(requireActivity(), pal.onVariant)) {
            requireActivity().startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }
        val rowLog = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_log, "日志抓取（设备适配）",
                "收集设备信息与运行日志，导出 ZIP（含隐私声明）",
                M3Ui.chevron(requireActivity(), pal.onVariant)) { showLogDialog() }
        val iconState = TextView(requireContext())
        iconState.text = if (iconCustomExists()) "已自定义" else "默认"
        iconState.textSize = 13f
        iconState.setTextColor(pal.primary)
        iconState.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val rowIcon = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_image, "弹窗图标",
                "Google 弹窗显示的耳机图标（从相册选择，或恢复默认）", iconState) {
            iconState.text = if (iconCustomExists()) "已自定义" else "默认"
            showIconDialog(iconCustomExists())
        }
        box.addView(M3Ui.groupCard(requireActivity(), pal, rowPerm, rowLog, rowIcon))

        box.addView(spacer(dp(10)))

        box.addView(M3Ui.sectionTitle(requireActivity(), pal, "行为"))

        // ── Root 强力保活 ──
        val swRoot = makeTintedSwitch()
        swRoot.isChecked = getSP().getBoolean("root_protect", false)
        val rowRoot = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_power, "Root 强力保活",
                "开机自启 + 后台防杀（需 Root）", swRoot, null)
        swRoot.setOnCheckedChangeListener { _, checked ->
            if (checked) showRootWarnDialog(swRoot)
            else applyRootProtect(false)
        }

        // ── 后台隐藏 ──
        val swBg = makeTintedSwitch()
        swBg.isChecked = getSP().getBoolean("bg_hide", false)
        val rowBg = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_block, "后台隐藏",
                "切到后台自动隐藏主界面（不驻留最近任务）", swBg, null)
        swBg.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean("bg_hide", checked).commit()
        }

        // ── 启动自动监听 ──
        val swAuto = makeTintedSwitch()
        swAuto.isChecked = getSP().getBoolean("auto_service", true)
        val rowAuto = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_play, "启动自动监听",
                "启动应用时自动监听；连接耳机自动直连 GAIA 读取电量与控制降噪", swAuto, null)
        swAuto.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean("auto_service", checked).commit()
        }

        // ── Google 弹窗（Fast Pair）──
        val swGp = makeTintedSwitch()
        swGp.isChecked = getSP().getBoolean(PopupGate.CFG_FASTPAIR_POPUP, true)
        val rowGp = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_bluetooth, "Google 弹窗（Fast Pair）",
                "连接时使用谷歌半屏配对弹窗（含电量与降噪控制）；关闭则用应用自带弹窗", swGp, null)
        swGp.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean(PopupGate.CFG_FASTPAIR_POPUP, checked).commit()
        }

        // ── 显示抗风噪按钮（alpha2.26.2：可选隐藏，弹窗与主界面同步生效）──
        val swWind = makeTintedSwitch()
        swWind.isChecked = getSP().getBoolean("show_wind", true)
        val rowWind = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_ac_unit, "显示抗风噪按钮",
                "在弹窗和主界面显示抗风噪模式；关闭后仅显示 关闭/降噪/透传", swWind, null)
        swWind.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean("show_wind", checked).commit()
        }

        // 行为区分组卡片
        box.addView(M3Ui.groupCard(requireActivity(), pal, rowRoot, rowBg, rowAuto, rowGp, rowWind))
        box.addView(spacer(dp(14)))

                // ── ANC 按钮映射（alpha2.26.2：用户自定义，不硬编码）──
        box.addView(M3Ui.sectionTitle(requireActivity(), pal, "ANC 按钮映射"))
        val ancMapHint = TextView(requireContext())
        ancMapHint.text = "自定义降噪按钮发送的设备码（0-5）。GA2（梦回二）实测 1=关/2=降噪/3=抗风/4=透传，连接该型号自动套用档案；其他型号走默认映射。手动修改即自定义并优先生效。"
        ancMapHint.textSize = 12f
        ancMapHint.setTextColor(pal.onVariant)
        ancMapHint.setPadding(dp(4), 0, dp(4), dp(6))
        box.addView(ancMapHint, LinearLayout.LayoutParams(-1, -2))
        val ancMapNames = arrayOf("关闭", "降噪", "透传", "抗风")
        val ancMapDefaults = GaiaBleClient.getInstance().getEffectiveAncMap()
        val ancMapRows = ArrayList<View>()
        for (i in 0..3) {
            val et = EditText(requireContext())
            et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            et.setText(getSP().getInt("anc_map_" + i, ancMapDefaults[i]).toString())
            et.textSize = 15f
            et.gravity = Gravity.CENTER
            et.setPadding(dp(8), dp(4), dp(8), dp(4))
            val etBg = GradientDrawable()
            etBg.shape = GradientDrawable.RECTANGLE
            etBg.setStroke(dp(1), pal.outline)
            etBg.setColor(pal.surface)
            etBg.setCornerRadius(dp(8).toFloat())
            et.background = etBg
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val v = s?.toString()?.trim()?.toIntOrNull()
                    if (v != null && v in 0..5) {
                        getSP().edit().putInt("anc_map_" + i, v)
                                .putInt("anc_map_custom", 1).commit()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            ancMapRows.add(M3Ui.listRow(requireActivity(), pal, 0, ancMapNames[i],
                    "发送的设备码（当前生效 " + ancMapDefaults[i] + "）", et, null))
        }
        box.addView(M3Ui.groupCard(requireActivity(), pal, *ancMapRows.toTypedArray()))
        box.addView(spacer(dp(10)))


        // ── 模拟测试（alpha2.3 从主页迁入；真实耳机连接时禁用）──
        box.addView(M3Ui.sectionTitle(requireActivity(), pal, "模拟测试"))
        val simBox = LinearLayout(requireContext())
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
            PopupGate.tryShowConnected(requireContext(), SIM_MAC, SIM_NAME)
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
            PopupGate.tryShowDisconnected(requireContext(), SIM_MAC, SIM_NAME)
        }
        simBox.addView(simDiscBtn, LinearLayout.LayoutParams(-1, -2))
        box.addView(simBox, LinearLayout.LayoutParams(-1, -2))

        sv.addView(box, FrameLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    // ── UI 辅助 ──
    private fun getSP(): android.content.SharedPreferences =
            requireContext().getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun spacer(h: Int): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun makeM3Button(text: String, iconRes: Int, bgColor: Int, textColor: Int,
                             l: View.OnClickListener): MaterialButton {
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
        b.setMinimumHeight(dp(52))
        b.setCornerRadius(dp(28))
        b.backgroundTintList = ColorStateList.valueOf(bgColor)
        if (iconRes != 0) {
            b.setIconResource(iconRes)
            b.setIconTint(ColorStateList.valueOf(textColor))
            b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START)
            b.setIconPadding(dp(8))
            b.setIconSize(dp(22))
        }
        b.setOnClickListener(l)
        return b
    }

    private fun makeTintedSwitch(): MaterialSwitch {
        val sw = MaterialSwitch(requireContext())
        sw.setTrackTintList(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.primary, if (pal.dark) 0x33FFFFFF else 0x22000000)))
        sw.setThumbTintList(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.onPrimary, pal.onSurface)))
        return sw
    }

    /** 真实耳机已连接时禁用模拟按钮（与主页"连接后不可用"逻辑一致） */
    private fun updateSimState() {
        val real = HeadsetGate.getConnectedMac(requireContext()) != null
        simConnBtn?.let { setSimEnabled(it, !real) }
        simDiscBtn?.let { setSimEnabled(it, !real) }
    }

    private fun setSimEnabled(b: MaterialButton, en: Boolean) {
        b.isEnabled = en
        b.alpha = if (en) 1f else 0.45f
        b.backgroundTintList = ColorStateList.valueOf(if (en) pal.container
        else if (pal.dark) 0x14FFFFFF else 0x0A000000)
        b.setTextColor(if (en) pal.onContainer else pal.onVariant)
    }

    override fun onResume() {
        super.onResume()
        updateSimState()
    }

    private fun makeMaterialTextButton(text: String, color: Int, l: View.OnClickListener): TextView {
        val b = TextView(requireContext())
        b.text = text
        b.textSize = 14f
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(22), dp(11), dp(22), dp(11))
        val g = GradientDrawable()
        g.setColor(0x14000000)
        g.setCornerRadius(dp(22).toFloat())
        val rd = RippleDrawable(
                ColorStateList.valueOf(0x1F000000), g, null)
        b.background = rd
        b.setTextColor(color)
        b.setOnClickListener(l)
        return b
    }

    /** 通用导航行（alpha1.36: M3Ui 卡片行——圆形容器图标 + chevron） */
    private fun makeNavRow(iconRes: Int, title: String, sub: String, onNav: Runnable): LinearLayout =
            M3Ui.navRow(requireActivity(), pal, iconRes, title, sub, onNav)

    /** 通用开关行（alpha1.36: M3Ui 卡片行） */
    private fun makeSwitchRow(title: String, sub: String, sw: MaterialSwitch): LinearLayout =
            M3Ui.switchRow(requireActivity(), pal, title, sub, sw)

    /** alpha2.8: 外观卡行间分隔线（与 groupCard 同款淡线） */
    private fun makeAppearDivider(): View {
        val d = View(requireContext())
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
    private fun makeThemeSwitch(key: String, def: Boolean, label: String): MaterialSwitch {
        val sw = MaterialSwitch(requireContext())
        sw.isChecked = getSP().getBoolean(key, def)
        sw.contentDescription = label
        sw.setTrackTintList(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.primary, if (pal.dark) 0x33FFFFFF else 0x22000000)))
        sw.setThumbTintList(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.onPrimary, pal.onSurface)))
        sw.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean(key, checked).commit()
            // alpha2.8: 开启动态取色 -> 先播种子颜色行消失动画，再重建（Material fade+slide）
            if (key == "dynamic_color" && checked
                    && seedRow != null && seedRow!!.parent != null) {
                seedRow!!.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(200).start()
                android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ requireActivity().recreate() }, 550)
            } else {
                // alpha2.7: 等 MaterialSwitch 动画播完再重建（立即 recreate 会吞掉开关动画）
                android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ requireActivity().recreate() }, 350)
            }
        }
        return sw
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
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
            while (true) {
                val line = br.readLine() ?: break
                sb.append(line).append('\n')
            }
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun showRootWarnDialog(sw: MaterialSwitch) {
        val d = android.app.Dialog(requireContext())
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(requireContext())
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(28), dp(28), dp(28), dp(20))
        val title = TextView(requireContext())
        title.text = "⚠️  权限风险警告"
        title.textSize = 22f
        title.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        title.setTextColor(pal.onSurface)
        box.addView(title, LinearLayout.LayoutParams(-1, -2))
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
        msg.setTextColor(pal.onVariant)
        msg.setLineSpacing(dp(3).toFloat(), 1.3f)
        box.addView(msg, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(24)))
        val btnRow = LinearLayout(requireContext())
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
        if (d.window != null) {
            val dbg = GradientDrawable()
            dbg.setColor(pal.card)
            dbg.setCornerRadius(dp(28).toFloat())
            dbg.setStroke(dp(1), 0x14000000)
            d.window!!.setBackgroundDrawable(dbg)
            d.window!!.setLayout((resources.displayMetrics.widthPixels * 0.84f).toInt(), -2)
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
            runRoot("mkdir -p /data/adb/service.d && echo '" + script +
                    "' > /data/adb/service.d/50-moondrop-keepalive.sh && chmod 755 " +
                    "/data/adb/service.d/50-moondrop-keepalive.sh")
            val out = runRoot("dumpsys deviceidle whitelist +com.fxxkmoondrop.secret; " +
                    "appops set com.fxxkmoondrop.secret RUN_IN_BACKGROUND allow; " +
                    "appops set com.fxxkmoondrop.secret RUN_ANY_IN_BACKGROUND allow; " +
                    "appops set com.fxxkmoondrop.secret START_FOREGROUND allow; echo DONE")
            getSP().edit().putBoolean("root_protect", true).commit()
            toast(if (out != null && out.contains("DONE")) "✅ 已启用：电池白名单 + 开机脚本"
            else "已写入配置，请重启后生效")
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
                "同意并抓取") {
            toast("⏳ 正在收集日志…")
            Thread {
                val path = LogCollector.collect(requireContext())
                requireActivity().runOnUiThread {
                    showSimpleDialog("日志已保存",
                            "已打包为 ZIP（含 6 条分类日志：系统/应用/蓝牙/环境/logcat/运行日志）。\n\n路径：\n$path" +
                                    "\n\n您可自行将文件分享给开发者进行设备适配分析。")
                }
            }.start()
        }
    }

    /** Material 风格确认弹窗（深浅色自适应，pal 色板），ok 回调在主线程。 */
    private fun showMaterialConfirm(t: String, m: String, okText: String, onOk: Runnable) {
        val d = android.app.Dialog(requireContext())
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(requireContext())
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(28), dp(30), dp(28), dp(22))
        val title = TextView(requireContext())
        title.text = t
        title.textSize = 22f
        title.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        title.setTextColor(pal.onSurface)
        box.addView(title, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(12)))
        val msg = TextView(requireContext())
        msg.text = m
        msg.textSize = 14f
        msg.setTextColor(pal.onVariant)
        msg.setLineSpacing(dp(3).toFloat(), 1.3f)
        box.addView(msg, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(20)))
        val btnRow = LinearLayout(requireContext())
        btnRow.gravity = Gravity.END
        btnRow.addView(makeMaterialTextButton("取消", pal.onVariant) { d.dismiss() })
        btnRow.addView(spacer(dp(10)))
        btnRow.addView(makeMaterialTextButton(okText, pal.primary) { d.dismiss(); onOk.run() })
        box.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(box)
        if (d.window != null) {
            val dbg = GradientDrawable()
            dbg.setColor(pal.card)
            dbg.setCornerRadius(dp(28).toFloat())
            d.window!!.setBackgroundDrawable(dbg)
            d.window!!.setLayout((resources.displayMetrics.widthPixels * 0.84f).toInt(), -2)
        }
        d.show()
    }

    private fun showSimpleDialog(t: String, m: String) {
        val d = android.app.Dialog(requireContext())
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(requireContext())
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(28), dp(30), dp(28), dp(22))
        val title = TextView(requireContext())
        title.text = t
        title.textSize = 22f
        title.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        title.setTextColor(pal.onSurface)
        box.addView(title, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(12)))
        val msg = TextView(requireContext())
        msg.text = m
        msg.textSize = 14f
        msg.setTextColor(pal.onVariant)
        msg.setLineSpacing(dp(3).toFloat(), 1.3f)
        box.addView(msg, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(20)))
        val btnRow = LinearLayout(requireContext())
        btnRow.gravity = Gravity.END
        btnRow.addView(makeMaterialTextButton("知道了", pal.primary) { d.dismiss() })
        box.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(box)
        if (d.window != null) {
            val dbg = GradientDrawable()
            dbg.setColor(pal.card)
            dbg.setCornerRadius(dp(28).toFloat())
            d.window!!.setBackgroundDrawable(dbg)
            d.window!!.setLayout((resources.displayMetrics.widthPixels * 0.84f).toInt(), -2)
        }
        d.show()
    }

    // ── 弹窗图标（与 alpha1.34 行为一致）──
    private fun iconCustomExists(): Boolean {
        val r = runRoot("test -f $GMS_ICON_PATH && echo CUSTOM")
        return r != null && r.contains("CUSTOM")
    }

    private fun showIconDialog(custom: Boolean) {
        val dlg = android.app.Dialog(requireContext())
        val win = dlg.window
        if (win != null) {
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            win.setDimAmount(0.5f)
        }
        val accent = pal.primary
        val card = LinearLayout(requireContext())
        card.orientation = LinearLayout.VERTICAL
        val cardBg = GradientDrawable()
        cardBg.setColor(pal.card)
        cardBg.setCornerRadius(dp(28).toFloat())
        cardBg.setStroke(dp(1), (accent and 0x00FFFFFF) or 0x33000000)
        card.background = cardBg
        card.elevation = dp(16).toFloat()
        card.setPadding(dp(24), dp(22), dp(24), dp(24))

        val head = TextView(requireContext())
        head.text = "弹窗图标（当前：" + (if (custom) "已自定义" else "默认") + "）"
        head.textSize = 22f
        head.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        head.setTextColor(accent)
        head.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(head, LinearLayout.LayoutParams(-1, -2))
        card.addView(spacer(dp(10)))

        val items = if (custom) arrayOf("从相册选择", "恢复默认图标") else arrayOf("从相册选择")
        val subs = if (custom) arrayOf(
                "选择一张图片，替换 Google 弹窗显示的耳机图标",
                "删除自定义图标，恢复软件自带默认图")
        else arrayOf("选择一张图片，替换 Google 弹窗显示的耳机图标")
        for (i in items.indices) {
            val which = i
            val row = LinearLayout(requireContext())
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            val rowBg = GradientDrawable()
            rowBg.setColor(if (pal.dark) 0x14FFFFFF else 0x0A000000)
            rowBg.setCornerRadius(dp(14).toFloat())
            row.background = RippleDrawable(
                    ColorStateList.valueOf(if (pal.dark) 0x33FFFFFF else 0x22000000), rowBg, null)
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            val t1 = TextView(requireContext())
            t1.text = items[i]
            t1.textSize = 15f
            t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            t1.setTextColor(pal.onSurface)
            col.addView(t1, LinearLayout.LayoutParams(-2, -2))
            val t2 = TextView(requireContext())
            t2.text = subs[i]
            t2.textSize = 12f
            t2.setTextColor(pal.onVariant)
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
                        toast("无法打开选择器: ${t.message}")
                    }
                } else {
                    resetCustomIcon()
                }
            }
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            card.addView(spacer(dp(8)))
        }
        val btnRow = LinearLayout(requireContext())
        btnRow.gravity = Gravity.END
        btnRow.addView(makeMaterialTextButton("取消", accent) { dlg.dismiss() })
        card.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        dlg.setContentView(card)
        val w2 = dlg.window
        if (w2 != null) w2.setLayout((resources.displayMetrics.widthPixels * 0.85f).toInt(), -2)
        dlg.show()
    }

    private fun resetCustomIcon() {
        Thread {
            val r = runRoot("rm -f $GMS_ICON_PATH && echo OK")
            val ok = r != null && r.contains("OK")
            requireActivity().runOnUiThread {
                toast(if (ok) "✅ 已恢复默认图标（下次连接生效）" else "恢复失败，请检查 Root")
            }
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
                var bmp = BitmapFactory.decodeStream(input)
                try { input.close() } catch (_: Exception) { }
                if (bmp == null) {
                    requireActivity().runOnUiThread { toast("图片解码失败") }
                    return@Thread
                }
                val w = bmp.width
                val h = bmp.height
                var longSide = Math.max(w, h)
                var target = if (longSide > 512) 512 else longSide
                val out = File(requireContext().cacheDir, "moondrop_custom_icon.png")
                var scaled = bmp
                for (i in 0 until 4) {
                    if (target < 128) break
                    val sc = target / Math.max(longSide, 1).toFloat()
                    if (sc < 1f) {
                        val s2 = Bitmap.createScaledBitmap(bmp,
                                Math.max(1, (w * sc).toInt()), Math.max(1, (h * sc).toInt()), true)
                        if (s2 !== scaled && scaled !== bmp) scaled.recycle()
                        scaled = s2
                        longSide = Math.max(scaled.width, scaled.height)
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
                val cmd = "cp '${out.absolutePath}' $GMS_ICON_PATH" +
                        " && chown $uid $GMS_ICON_PATH" +
                        " && chmod 644 $GMS_ICON_PATH && echo OK"
                val r = runRoot(cmd)
                val ok = r != null && r.contains("OK")
                requireActivity().runOnUiThread {
                    toast(if (ok) "✅ 弹窗图标已更新（下次连接生效）" else "写入图标失败，请检查 Root")
                }
                if (scaled !== bmp && !scaled.isRecycled) scaled.recycle()
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
            } catch (t: Throwable) {
                requireActivity().runOnUiThread { toast("图标处理失败: ${t.message}") }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PICK_ICON && resultCode == Activity.RESULT_OK &&
                data != null && data.data != null) {
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
