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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            simRestoreHandler.removeCallbacks(this)
            if (!GaiaBleClient.isSimConnected()) return
            GaiaBleClient.setSimConnected(false)
            BatteryStore.clearGaia(SIM_MAC)
            PopupGate.clear(SIM_MAC, SIM_NAME)
        }
    }
    /**
     * alpha2.54: 主题重建统一入口。
     *
     * 原实现是在 OnCheckedChangeListener 里直接
     *   Handler().postDelayed({ requireActivity().recreate() }, 350/550)
     * 有三个问题叠加，反复开关动态取色 / AMOLED 时必崩：
     *   1. 没有任何取消机制 —— 连点会排出多个 recreate，多次全量重建叠加；
     *   2. 回调里用 requireActivity()，而延迟期间 Fragment 可能已 detach，
     *      抛 IllegalStateException: Fragment not attached to an activity；
     *   3. 没有生命周期清理，视图销毁后回调照跑。
     *
     * 这里用「先 removeCallbacks 再 post」把连续请求合并成一次，
     * 并在真正执行前用 isAdded / activity 双检，任一不满足就静默跳过。
     */
    private val rebuildHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rebuildRunnable: Runnable = object : Runnable {
        override fun run() {
            rebuildHandler.removeCallbacks(this)
            val act = activity
            if (isAdded && act != null && !act.isFinishing) act.recreate()
        }
    }

    private fun scheduleRebuild(delayMs: Long) {
        rebuildHandler.removeCallbacks(rebuildRunnable)
        rebuildHandler.postDelayed(rebuildRunnable, delayMs)
    }

    private var runModeNameTv: TextView? = null    // 运行模式行的模式名（模块探测落定后就地刷新）
    private var runModeDetailTv: TextView? = null  // 运行模式行的说明

    private var seedRow: LinearLayout? = null // alpha2.8: 种子颜色行（动态取色关闭时显示；出现/消失动画）

    override fun onCreateView(inflater: LayoutInflater, containerView: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, containerView, savedInstanceState)
        Lang.refresh(requireContext())
        pal = ThemeUtil.Palette(requireContext())

        // alpha1.36: 窗口背景=surface + 系统栏透明（顶部完全铺满，无空白带；深浅色自适应）
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) statusBarH = resources.getDimensionPixelSize(resId)

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        root.setPadding(0, statusBarH, 0, 0)

        // alpha2.53: M3 LargeTopAppBar —— 大标题居上不动，内容从下方穿过，下翻时标题收缩（对齐官方）
        val page = M3Ui.largeHeaderPage(requireActivity(), pal, Lang.t("设置", "Settings"))

        // ── 内容（可滚动）──
        val sv = page.sv
        val box = LinearLayout(requireContext())
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), 0, dp(16), dp(24))

        // 本页这几项（官方集成 / 弹窗图标）由 GMS 进程里的 Hook 承担，
        // 与 App 自身有没有 root **无关**：没 root 但 LSPosed 模块已启用 = 照常可用。
        // 因此只在 Hook **确定**未激活时才置灰；尚未探测（null）不置灰，
        // 否则一进设置页就没 root 把功能整片禁掉（3.0.5 的问题）。
        val hookOff = !EnvProbe.hookUsable()

        // ── 运行模式（单独一块，只展示当前环境，不掺别的设置项）──
        //   三态由「有无可用 root 入口」×「FastPairHook 模块是否已激活」决定：
        //   Root 模式 / 模块模式（无 root 但模块激活）/ 无 Root 模式。
        //   模块未探测时显示「检测中…」，探测落定后（见本函数末尾）就地刷新这一行。
        box.addView(M3Ui.sectionTitle(requireActivity(), pal, Lang.t("运行模式", "Run mode")))
        val rowRunMode = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_info,
                EnvProbe.runModeName(requireContext()), EnvProbe.runModeDetail(requireContext()),
                null, null)
        listRowLabels(rowRunMode)?.let { (nameTv, detailTv) ->
            runModeNameTv = nameTv
            runModeDetailTv = detailTv
        }
        box.addView(M3Ui.groupCard(requireActivity(), pal, rowRunMode))
        box.addView(spacer(dp(14)))

        box.addView(M3Ui.sectionTitle(requireActivity(), pal, Lang.t("外观", "Appearance")))

        // ── 外观（对齐 org.lsposed.manager：每项独立卡片 + 12dp 卡间距）──
        val appear = LinearLayout(requireContext())
        appear.orientation = LinearLayout.VERTICAL
        // 主题模式：跟随系统 / 浅色 / 深色（3 段 pill）
        // alpha2.52: 统一走 M3 分段控件（高 40dp / 圆角 20dp / labelLarge 14sp）
        // alpha2.53: 对齐 org.lsposed.manager —— 主题模式改为「行 + 当前值 + 下拉菜单」
        val modeRow = M3Ui.dropdownRow(requireActivity(), pal,
                Lang.t("主题", "Theme"), Lang.t("选择应用的主题模式", "Choose the app theme mode"),
                arrayOf(Lang.t("跟随系统", "System"), Lang.t("浅色", "Light"), Lang.t("深色", "Dark")),
                ThemeUtil.themeMode(requireContext())) { mi ->
            getSP().edit().putInt("theme_mode", mi).commit()
            scheduleRebuild(0L)
        }
        appear.addView(M3Ui.groupCard(requireActivity(), pal, modeRow), LinearLayout.LayoutParams(-1, -2))

        // 动态取色 / AMOLED 开关（makeSwitchRow 自身即卡片，无需再包）
        val swDyn = makeSwitchRow(Lang.t("动态取色", "Dynamic color"),
                Lang.t("跟随壁纸调色；关闭后使用下方种子颜色", "Follow wallpaper; uses seed color below when off"), makeThemeSwitch("dynamic_color", true, Lang.t("动态取色", "Dynamic color")))
        appear.addView(spacer(dp(12)))
        appear.addView(swDyn, LinearLayout.LayoutParams(-1, -2))
        appear.addView(spacer(dp(12)))
        // alpha2.52: 副标题只说明开关作用，不反映当前状态（状态由开关自身表达）
        val swAmoled = makeSwitchRow(Lang.t("AMOLED 纯黑", "AMOLED pure black"),
                Lang.t("深色模式下背景使用纯黑", "Pure black background in dark mode"),
                makeThemeSwitch("amoled", false, "AMOLED"))
        appear.addView(swAmoled, LinearLayout.LayoutParams(-1, -2))

        // 种子颜色（仅动态取色关闭时显示）：5 个官方种子色点
        seedRow = LinearLayout(requireContext())
        seedRow!!.orientation = LinearLayout.HORIZONTAL
        seedRow!!.gravity = Gravity.CENTER_VERTICAL
        val seedLabel = TextView(requireContext())
        seedLabel.text = Lang.t("种子颜色", "Seed color")
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
                scheduleRebuild(0L)
            }
            seedRow!!.addView(dot, dlp)
        }
        if (!ThemeUtil.dynColor(requireContext())) {
            appear.addView(spacer(dp(12)))
            appear.addView(wrapCard(seedRow!!), LinearLayout.LayoutParams(-1, -2))
            // alpha2.8: 种子颜色行入场动画（fade + slide，Material emphasized）
            seedRow!!.alpha = 0f
            seedRow!!.translationY = dp(8).toFloat()
            // alpha2.54: 必须捕获局部引用 —— onDestroyView 会把 seedRow 置空，
            // 延迟回调里再解引用（seedRow!!）会抛 KotlinNullPointerException。
            // 视图已分离时跳过动画即可，不影响正确性。
            val seedAnim = seedRow
            if (seedAnim != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (seedAnim.parent != null) {
                        seedAnim.animate().alpha(1f).translationY(0f).setDuration(250).start()
                    }
                }, 120)
            }
        }

        box.addView(appear, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(12)))

        // 语言：归入外观（区域设置与主题同类），不再单列一个「通用」段
        // alpha2.53: 对齐 org.lsposed.manager —— 语言改为「行 + 当前值 + 下拉菜单」（0=跟随系统 1=中文 2=English）
        val langRow = M3Ui.dropdownRow(requireActivity(), pal, "语言 / Language", null,
                arrayOf("跟随系统", "中文", "English"), Lang.mode(requireContext())) { mi ->
            getSP().edit().putInt("lang", mi).commit()
            scheduleRebuild(0L)
        }
        box.addView(M3Ui.groupCard(requireActivity(), pal, langRow), LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(14)))

        // ── 检查权限 / 日志抓取 / 弹窗图标：官方分组卡片 ──
        val rowPerm = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_shield, Lang.t("检查权限", "Check permissions"),
                Lang.t("蓝牙、通知、电池白名单、运行模式与模块环境",
                        "Bluetooth, notifications, battery whitelist, run mode & module env"),
                M3Ui.chevron(requireActivity(), pal.onVariant)) {
            requireActivity().startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }
        val rowLog = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_description, Lang.t("日志抓取（设备适配）", "Log capture (device adaptation)"),
                Lang.t("收集设备信息与运行日志，导出 ZIP（含隐私声明）", "Collect device info and logs, export ZIP (incl. privacy notice)"),
                M3Ui.chevron(requireActivity(), pal.onVariant)) { showLogDialog() }
        val iconState = TextView(requireContext())
        iconState.textSize = 14f
        iconState.setTextColor(pal.primary)
        iconState.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // alpha2.53: 检查是异步的（读私有目录），等待期间显示圆形加载指示，避免行上先空一截再突然出字
        val iconSlot = FrameLayout(requireContext())
        val iconSpin = M3Ui.circularLoader(requireContext(), 18, pal.primary)
        iconState.visibility = View.GONE
        iconSlot.addView(iconState, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        iconSlot.addView(iconSpin, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val showIconState: (Boolean) -> Unit = { exists ->
            iconSpin.visibility = View.GONE
            iconState.text = if (exists) Lang.t("已自定义", "Custom") else Lang.t("默认", "Default")
            iconState.visibility = View.VISIBLE
        }
        iconCustomExistsAsync(showIconState)
        // 自定义图标由 GMS 进程内的 Hook 读取（readIconBytes，经 PrefsProvider）：
        // 只要 LSPosed 模块激活就能用，不需要 App 有 root —— 仅模块未激活时置灰并说明。
        val iconRow = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_image, Lang.t("弹窗图标", "Popup icon"),
                if (hookOff)
                    Lang.t("需要 FastPairHook 模块（在 LSPosed 中启用后可用）",
                            "Requires the FastPairHook module (enable it in LSPosed)")
                else
                    Lang.t("Google 弹窗显示的耳机图标（从相册选择，或恢复默认）",
                            "Earbud icon shown in the Google popup (choose from gallery, or restore default)"),
                iconSlot) {
            iconCustomExistsAsync { exists ->
                showIconState(exists)
                showIconDialog(exists)
            }
        }
        if (hookOff) {
            iconRow.isEnabled = false
            iconRow.alpha = 0.4f
            iconRow.setOnClickListener(null)
            iconSlot.visibility = android.view.View.GONE
        }
        val rowIcon = iconRow
        box.addView(spacer(dp(10)))

        // 「行为」这个筐太杂（官方集成 / 通知 / 保活 / 后台 / 界面元素混在一起），
        // 改名「功能」，只留真正影响耳机功能与界面呈现的开关。
        box.addView(M3Ui.sectionTitle(requireActivity(), pal, Lang.t("功能", "Features")))

        // ── 第 3 项：分类功能开关 ──
        //  每个开关对应一条真实生效的链路：关掉后相应 hook / 通知即停用（跨进程读同一个 SP）。
        //  默认全开，保持既有行为不变。
        if (hookOff) {
            box.addView(makeSubLabel(Lang.t(
                "FastPairHook 模块未激活：以下官方集成项不可用（在 LSPosed 中启用后恢复）",
                "FastPairHook inactive: official integration unavailable (enable the module in LSPosed to restore)")))
        }
        box.addView(makeSubLabel(Lang.t("官方集成", "Official integration")))
        val swOfficial = makeTintedSwitch()
        swOfficial.isChecked = if (hookOff) false else getSP().getBoolean("feat_official_panel", true)
        if (hookOff) {
            swOfficial.isEnabled = false
            swOfficial.isClickable = false
            swOfficial.alpha = 0.4f
        }
        val rowOfficial = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_headphones,
                Lang.t("官方降噪面板", "Official noise-control panel"),
                Lang.t("把耳机状态接进 Google 的官方降噪面板（音量面板 / 提示音和振动）",
                        "Feed state into Google's official ANC panel (volume & sound panels)"),
                swOfficial, null)
        if (!hookOff) {
            swOfficial.setOnCheckedChangeListener { _, checked ->
                getSP().edit().putBoolean("feat_official_panel", checked).commit()
            }
        }

        val swDetail = makeTintedSwitch()
        swDetail.isChecked = if (hookOff) false else getSP().getBoolean("feat_detail_panel", true)
        if (hookOff) {
            swDetail.isEnabled = false
            swDetail.isClickable = false
            swDetail.alpha = 0.4f
        }
        val rowDetail = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_tune,
                Lang.t("蓝牙详情页面板", "Bluetooth details panel"),
                Lang.t("在系统蓝牙设备详情页注入降噪与功能控制卡片；未连接时整块收起，连上但耳机未就绪时用官方加载行原位占位",
                        "Inject the control card into the system device-details page; hidden while disconnected, replaced by the official loading row until the headset is ready"),
                swDetail, null)
        if (!hookOff) {
            swDetail.setOnCheckedChangeListener { _, checked ->
                getSP().edit().putBoolean("feat_detail_panel", checked).commit()
            }
        }
        box.addView(M3Ui.groupCard(requireActivity(), pal, rowOfficial, rowDetail))
        box.addView(spacer(dp(14)))

        box.addView(makeSubLabel(Lang.t("通知", "Notifications")))
        val swNotifBatt = makeTintedSwitch()
        swNotifBatt.isChecked = getSP().getBoolean("feat_notif_battery", true)
        val rowNotifBatt = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_battery_full,
                Lang.t("电量通知", "Battery notification"),
                Lang.t("耳机连接后常驻通知，显示左右耳电量（不含充电盒）",
                        "Persistent notification with left/right battery (no case)"),
                swNotifBatt, null)
        swNotifBatt.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean("feat_notif_battery", checked).commit()
            // 即时生效：refreshBattery 内部会按开关取消/重建（GAIA 未连时什么都不做）
            DeviceNotif.refreshBattery(requireContext())
        }

        val swNotifAnc = makeTintedSwitch()
        swNotifAnc.isChecked = getSP().getBoolean("feat_notif_anc", true)
        val rowNotifAnc = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_anc_on,
                Lang.t("通知内降噪控制", "ANC control in notification"),
                Lang.t("通知栏提供降噪档位按钮，按键按本设备支持的档位生成",
                        "Shade buttons to switch ANC modes, built from the device's supported modes"),
                swNotifAnc, null)
        swNotifAnc.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean("feat_notif_anc", checked).commit()
            DeviceNotif.refreshAnc(requireContext())
        }
        box.addView(M3Ui.groupCard(requireActivity(), pal, rowNotifBatt, rowNotifAnc))
        box.addView(spacer(dp(14)))


        // （3.0.5：原「Root 强力保活」开关已移除 —— 保活改为默认常开且不再需要 Root，
        //   无 root 走开机自启 + 看门狗 + 系统电池优化白名单，有 root 时静默做额外增强。）

        // ── 后台隐藏 ──
        val swBg = makeTintedSwitch()
        swBg.isChecked = getSP().getBoolean("bg_hide", false)
        val rowBg = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_visibility_off, Lang.t("后台隐藏", "Hide in background"),
                Lang.t("用户切到后台时隐藏主界面（不驻留最近任务）；应用内跳转与授权流程不受影响",
                        "Hide main UI only when you send the app to background (no recents task); in-app navigation & authorization are unaffected"), swBg, null)
        swBg.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean("bg_hide", checked).commit()
        }

        // ── 后台监听总开关（alpha2.41.10: 合并原主界面「开始/停止后台监听」按钮）──
        val swAuto = makeTintedSwitch()
        // 总开关状态 = enable && auto_service，两键始终同步写入
        swAuto.isChecked = getSP().getBoolean("enable", true) && getSP().getBoolean("auto_service", true)
        val rowAuto = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_sensors, Lang.t("后台监听", "Background monitor"),
                Lang.t("监听总开关：开启后立即开始监听，并在启动应用／开机时自动恢复与后台防杀；连接耳机自动直连 GAIA 读取电量与控制降噪",
                        "Master switch: starts monitoring immediately, auto-resumes on launch/boot and keeps alive while on; auto-connect GAIA to read battery & ANC on connect"), swAuto, null)
        swAuto.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean("auto_service", checked).putBoolean("enable", checked).commit()
            if (checked) {
                HeadsetDetectService.RUNNING = true
                try { requireContext().startService(Intent(requireContext(), HeadsetDetectService::class.java)) } catch (_: Exception) { }
            } else {
                AliveReceiver.cancel(requireContext())
                HeadsetDetectService.RUNNING = false
                try { requireContext().stopService(Intent(requireContext(), HeadsetDetectService::class.java)) } catch (_: Exception) { }
            }
        }

        // ── 显示抗风噪按钮（alpha2.26.2 起可选隐藏；3.1.0 起详情页开关同样受它约束）──
        val swWind = makeTintedSwitch()
        swWind.isChecked = getSP().getBoolean("show_wind", true)
        val rowWind = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_air, Lang.t("显示抗风噪按钮", "Show wind-noise button"),
                Lang.t("抗风是降噪的加强档：蓝牙详情页的快捷开关只在耳机处于「降噪／抗风」档时出现，切到通透或关闭会自动收起；关掉本项后弹窗、主界面与详情页都不再提供抗风",
                        "Wind is the boosted ANC level: the quick switch on the Bluetooth details page appears only while the headset is on ANC or Wind, and folds away on Transparency/Off; turning this off removes Wind everywhere"), swWind, null)
        swWind.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean("show_wind", checked).commit()
        }

        // 界面元素：抗风噪按钮决定弹窗/主界面呈现哪些档位，属于「功能」
        box.addView(M3Ui.groupCard(requireActivity(), pal, rowWind))
        box.addView(spacer(dp(14)))

                // ── 自定义映射（alpha2.52：降噪 / 增益 / 追踪标签 三块合并为一组，
        //    小标题分级，避免三个同级 section 把设置页切得太碎）──
        box.addView(M3Ui.sectionTitle(requireActivity(), pal, Lang.t("自定义映射", "Custom Mapping")))
        box.addView(makeSubLabel(Lang.t("降噪按钮", "Noise-control buttons")))
        val ancProfileDevice = GaiaBleClient.getInstance().getConnectedDeviceName()
        val ancMapProfile = AncProfileLib.matchedProfileName(ancProfileDevice)
        val ancMapState = mapStateLine("")

        val ancMapHint = TextView(requireContext())
        // alpha2.53: 文案对齐下拉交互（原来写的是「手动修改」的输入框说法）
        ancMapHint.text = Lang.t("选择每个降噪档位发给耳机的设备码（0-5）。改动任意一档即成为自定义映射，优先于型号档案。",
                "Device code (0-5) each noise-control level sends. Changing any level makes it a custom mapping that overrides the profile.")
        ancMapHint.textSize = 12f
        ancMapHint.setTextColor(pal.onVariant)
        ancMapHint.setPadding(dp(4), 0, dp(4), dp(6))
        box.addView(ancMapHint, LinearLayout.LayoutParams(-1, -2))
        ancMapState.text = customLabel(getSP().getInt("anc_map_custom", 0) == 1)
        box.addView(ancMapState, LinearLayout.LayoutParams(-1, -2))
        // 「当前型号档案」与「当前：…」同规格（12sp medium / primary），视觉上成对
        box.addView(mapStateLine(Lang.t("当前型号档案：", "Active profile: ") + ancMapProfile +
                Lang.t("（未改动的档位一律按档案发送）", " (untouched levels follow the profile)")),
                LinearLayout.LayoutParams(-1, -2))
        val ancMapNames = AncProfileLib.modeNames(requireContext())
        val ancMapDefaults = GaiaBleClient.getInstance().getEffectiveAncMap()
        // 编辑任意一格时，把全部 4 格按「当前生效映射」一起落库。
        // 旧实现只写被编辑的那一格——未写过的格子会在读取时回退到名义默认映射
        // （GaiaBleClient.readAncMap 的老逻辑），于是在 GA2 上只要碰一个档位，
        // 透传/抗风就被悄悄换位（实测 anc_map_2=3 / anc_map_3=4 两格残留）。
        // alpha2.53: 改成下拉选择（对齐官方）。原先每档一个裸输入框有三个问题：
        //  · 可选范围只写在提示里，输入框本身看不出能填几
        //  · 边打字边落库，把半截输入（想把 3 改成 12 的中间态 1）写进配置
        //  · 越界输入被静默丢弃，界面显示与存储不一致
        // 下拉把可选值收敛成固定集合，越界与中间态都不可能出现。
        val ancMapCur = ancMapDefaults.copyOf()
        val ancMapRows = ArrayList<View>()
        for (i in 0..3) {
            val opts = Array(6) { it.toString() }
            lateinit var row: LinearLayout
            row = M3Ui.dropdownRow(requireActivity(), pal, ancMapNames[i],
                    Lang.t("发送的设备码（0-5）", "Device code sent (0-5)"),
                    opts, ancMapCur[i].coerceIn(0, 5)) { pick ->
                ancMapCur[i] = pick
                // 一次落库全部 4 档：只写被编辑的那一格会让未写过的格子
                // 回退到名义默认映射，于是碰一下就把透传/抗风惄惄换位
                val ed = getSP().edit()
                for (j in 0..3) ed.putInt("anc_map_" + j, ancMapCur[j])
                ed.putInt("anc_map_custom", 1).commit()
                M3Ui.setDropdownValue(row, opts[pick])
                ancMapState.text = customLabel(true)
            }
            ancMapRows.add(row)
        }
        box.addView(M3Ui.groupCard(requireActivity(), pal, *ancMapRows.toTypedArray()))
        box.addView(spacer(dp(10)))

        // ── 增益映射（alpha2.37：用户自定义增益设备码，同 ANC 映射逻辑）──
        box.addView(makeSubLabel(Lang.t("增益按钮", "Gain buttons")))
        val gainMapHint = TextView(requireContext())
        gainMapHint.text = Lang.t("选择每个增益档位发给耳机的设备码（0-9）；选「隐藏该档位」则不显示该档。",
                "Device code (0-9) each gain level sends; pick \"Hide this level\" to hide it.")
        gainMapHint.textSize = 12f
        gainMapHint.setTextColor(pal.onVariant)
        gainMapHint.setPadding(dp(4), 0, dp(4), dp(6))
        box.addView(gainMapHint, LinearLayout.LayoutParams(-1, -2))
        val gainLabels = DeviceControlBridge.gainLabels()
        val gainCount = DeviceControlBridge.gainCount()
        val gainDefaults = AncProfileLib.resolveDc(GaiaBleClient.getInstance().getConnectedDeviceName()).gainMap
        // alpha2.53: 同 ANC 映射，改下拉。第 0 项是「隐藏该档位」（对应设备码 -1）。
        val gainMapRows = ArrayList<View>()
        for (i in 0 until gainCount) {
            val opts = Array(11) { if (it == 0) Lang.t("隐藏该档位", "Hide this level") else (it - 1).toString() }
            val saved = getSP().getInt("gain_map_" + i, gainDefaults.getOrElse(i) { i })
            val cur = if (saved < 0) 0 else (saved + 1).coerceIn(0, 10)
            lateinit var row: LinearLayout
            row = M3Ui.dropdownRow(requireActivity(), pal,
                    gainLabels.getOrElse(i) { Lang.t("档位 ", "Level ") + i },
                    Lang.t("发送的设备码（0-9），或隐藏", "Device code (0-9), or hide"),
                    opts, cur) { pick ->
                getSP().edit().putInt("gain_map_" + i, if (pick == 0) -1 else pick - 1).commit()
                M3Ui.setDropdownValue(row, opts[pick])
            }
            gainMapRows.add(row)
        }
        box.addView(M3Ui.groupCard(requireActivity(), pal, *gainMapRows.toTypedArray()))
        box.addView(spacer(dp(10)))

        // ── 空间音频追踪模式（alpha2.37：用户自定义标签）──
        box.addView(makeSubLabel(Lang.t("空间音频追踪标签", "Spatial audio tracking labels")))
        val trackHint = TextView(requireContext())
        trackHint.text = Lang.t("自定义空间音频各追踪模式显示名称。", "Customize display names for each spatial audio tracking mode.")
        trackHint.textSize = 12f
        trackHint.setTextColor(pal.onVariant)
        trackHint.setPadding(dp(4), 0, dp(4), dp(6))
        box.addView(trackHint, LinearLayout.LayoutParams(-1, -2))
        val trackLabels = DeviceControlBridge.trackingLabels()
        val trackRows = ArrayList<View>()
        for (i in trackLabels.indices) {
            val et = EditText(requireContext())
            et.setText(getSP().getString("track_label_" + i, trackLabels[i]))
            et.textSize = 16f
            et.setSingleLine(true)
            et.setPadding(dp(8), dp(4), dp(8), dp(4))
            val etBg = GradientDrawable()
            etBg.shape = GradientDrawable.RECTANGLE
            etBg.setStroke(dp(1), pal.outline)
            etBg.setColor(pal.surface)
            etBg.setCornerRadius(dp(8).toFloat())
            et.background = etBg
            // alpha2.53: 改成失焦/回车时才保存。原先每次按键都写库，
            // 一个名字没敲完就落了半截；空值视为恢复默认。
            val labelIdx = i
            val defLabel = trackLabels[i]
            et.hint = defLabel
            et.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            et.setOnFocusChangeListener { _, has ->
                if (!has) {
                    val v = et.text?.toString()?.trim()
                    if (v.isNullOrEmpty()) {
                        getSP().edit().remove("track_label_" + labelIdx).commit()
                        et.setText(defLabel)
                    } else {
                        getSP().edit().putString("track_label_" + labelIdx, v).commit()
                    }
                }
            }
            et.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    et.clearFocus()
                    true
                } else false
            }
            trackRows.add(M3Ui.listRow(requireActivity(), pal, 0,
                    Lang.t("模式 ", "Mode ") + i, Lang.t("默认名称：", "Default name: ") + trackLabels[i], et, null))
        }
        box.addView(M3Ui.groupCard(requireActivity(), pal, *trackRows.toTypedArray()))
        box.addView(spacer(dp(10)))

        // ── 重置自定义映射（alpha2.37）──
        val rowReset = M3Ui.listRow(requireActivity(), pal, R.drawable.ic_restart_alt,
                Lang.t("重置所有自定义映射", "Reset all custom mappings"),
                Lang.t("恢复降噪 / 增益 / 追踪标签为型号档案默认值",
                        "Restore ANC / gain / tracking labels to profile defaults"),
                M3Ui.chevron(requireActivity(), pal.onVariant)) {
            val editor = getSP().edit()
            // 清除 ANC 映射
            for (i in 0..3) editor.remove("anc_map_" + i)
            editor.remove("anc_map_custom")
            // 清除增益映射
            for (i in 0 until gainCount) editor.remove("gain_map_" + i)
            // 清除追踪标签
            for (i in trackLabels.indices) editor.remove("track_label_" + i)
            editor.commit()
            android.widget.Toast.makeText(requireContext(), Lang.t("已重置所有自定义映射", "All custom mappings reset"), android.widget.Toast.LENGTH_SHORT).show()
            // 刷新当前页面
            scheduleRebuild(0L)
        }
        box.addView(M3Ui.groupCard(requireActivity(), pal, rowReset))
        box.addView(spacer(dp(14)))

        // ── 后台：监听 / 隐藏 / 保活 —— 三项都在管「应用怎么活着」，与耳机功能无关 ──
        box.addView(M3Ui.sectionTitle(requireActivity(), pal, Lang.t("后台", "Background")))
        box.addView(M3Ui.groupCard(requireActivity(), pal, rowAuto, rowBg))
        box.addView(spacer(dp(14)))

        // ── 诊断与测试：权限检查 / 日志抓取 / 弹窗图标 / 模拟测试，
        //    全是「排查与验证」用途，原来分散在「通用」与独立段，现在收拢一处 ──
        box.addView(M3Ui.sectionTitle(requireActivity(), pal, Lang.t("诊断", "Diagnostics")))
        box.addView(M3Ui.groupCard(requireActivity(), pal, rowPerm, rowLog, rowIcon))
        box.addView(spacer(dp(10)))
        box.addView(makeSubLabel(Lang.t("模拟测试", "Simulation Test")))
        val simBox = LinearLayout(requireContext())
        simBox.orientation = LinearLayout.VERTICAL
        simBox.setPadding(dp(14), dp(12), dp(14), dp(12))
        simBox.background = M3Ui.cardBg(requireContext(), pal, 24)
        simConnBtn = makeM3Button(Lang.t("模拟连接 耳机", "Simulate connect earbuds"), R.drawable.ic_bluetooth, pal.container, pal.onContainer) {
            // 模拟连接：GAIA 模拟态 + 左右耳模拟电量 + 默认降噪模式 + 弹窗（可重复点击）
            GaiaBleClient.setSimConnected(true)
            BatteryStore.setGaiaLevel(SIM_MAC, 1, 86)
            BatteryStore.setGaiaLevel(SIM_MAC, 2, 72)
            AncBridge.notifyAncMode(1)
            PopupGate.clear(SIM_MAC, SIM_NAME)
            PopupGate.tryShowConnected(requireContext(), SIM_MAC, SIM_NAME)
            // alpha2.7: 模拟弹窗消失后自动恢复（自带弹窗用 hide 钩子，GMS 弹窗用 30s 兜底）
            simRestoreHandler.removeCallbacks(simRestoreRunnable)
            simRestoreHandler.postDelayed(simRestoreRunnable, 30000)
        }
        simBox.addView(simConnBtn, LinearLayout.LayoutParams(-1, -2))
        simBox.addView(spacer(dp(10)))
        // alpha2.38: 模拟断开按钮已移除（不再有断开弹窗，30s 自动恢复即可）
        box.addView(simBox, LinearLayout.LayoutParams(-1, -2))
        box.addView(spacer(dp(14)))

        // ── 帮助：使用引导入口（3.1.0）—— 放在全页最底部，
        //    想重看功能概览、权限申请与各项开关时不必卸载重装 ──
        box.addView(M3Ui.sectionTitle(requireActivity(), pal, Lang.t("帮助", "Help")))
        val rowOnboard = makeNavRow(R.drawable.ic_info,
                Lang.t("使用引导", "Getting started"),
                Lang.t("重新查看功能概览、权限申请与各项开关",
                        "Replay the feature tour, permission requests and switches")) {
            startActivity(Intent(requireContext(), OnboardingActivity::class.java))
        }
        box.addView(M3Ui.groupCard(requireActivity(), pal, rowOnboard))

        sv.addView(box, FrameLayout.LayoutParams(-1, -2))
        root.addView(page.container, LinearLayout.LayoutParams(-1, 0, 1f))

        // Hook 状态首次可能是「尚未探测」（null）：上面按「可用」渲染（不置灰），
        // 这里后台补探一次（PING 阻塞 ~2s，必须离开主线程）。已探测过则不重复探。
        //   未激活 -> 重刷本页（要置灰那几项），走 scheduleRebuild 现成的生命周期防护；
        //   已激活 -> 不必重画整页，只把运行模式行的文案换成实测结果（免得丢滚动位置）。
        if (EnvProbe.hookActiveCached() == null) {
            val appCtx = requireContext().applicationContext
            Thread {
                if (!EnvProbe.isFastPairHookActive(appCtx)) {
                    scheduleRebuild(0)
                } else {
                    activity?.runOnUiThread {
                        if (isAdded) {
                            runModeNameTv?.text = EnvProbe.runModeName(appCtx)
                            runModeDetailTv?.text = EnvProbe.runModeDetail(appCtx)
                        }
                    }
                }
            }.start()
        }
        return root
    }

    /**
     * 取 [M3Ui.listRow] 里「标题 / 副标题」两个 TextView。
     * 运行模式行要用：模块状态是子线程探测出来的，落定后就地改文本，
     * 比整页重建更轻（重建会丢滚动位置）。结构不符时返回 null —— 只影响刷新，不影响渲染。
     */
    private fun listRowLabels(row: View): Pair<TextView, TextView>? {
        val labels = runCatching {
            val outer = row as LinearLayout
            outer.getChildAt(outer.childCount - 1) as LinearLayout
        }.getOrNull() ?: return null
        if (labels.childCount < 2) return null
        val title = runCatching { labels.getChildAt(0) as TextView }.getOrNull() ?: return null
        val sub = runCatching { labels.getChildAt(1) as TextView }.getOrNull() ?: return null
        return title to sub
    }

    // ── UI 辅助 ──
    /** alpha2.53: 自定义映射的状态行（当前值 / 当前型号档案），统一规格：12sp medium + primary */
    private fun mapStateLine(text: String): TextView {
        val t = TextView(requireContext())
        t.text = text
        t.textSize = 12f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(pal.primary)
        t.setPadding(dp(4), 0, dp(4), dp(6))
        return t
    }

    /** alpha2.53: 自定义映射当前状态描述（对齐官方的「当前值就在行上」） */
    private fun customLabel(custom: Boolean): String =
            if (custom) Lang.t("当前：自定义映射生效", "Current: custom mapping active")
            else Lang.t("当前：跟随型号档案", "Current: following device profile")

    /** alpha2.52: 自定义映射分组内的小标题（比 sectionTitle 轻一级，用于同组内分块） */
    private fun makeSubLabel(text: String): TextView {
        val t = TextView(requireContext())
        t.text = text
        t.textSize = 14f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(pal.onSurface)
        t.setPadding(dp(4), dp(8), dp(4), dp(2))
        return t
    }

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
        b.textSize = 14f
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.setTextColor(textColor)
        b.isAllCaps = false
        b.gravity = Gravity.CENTER
        b.insetTop = 0
        b.insetBottom = 0
        // M3 规范：按钮高 40dp、圆角 20dp（触摸目标由 MaterialButton 自动扩到 48dp）
        b.minHeight = dp(40)
        b.setMinimumHeight(dp(40))
        b.setCornerRadius(dp(20))
        b.backgroundTintList = ColorStateList.valueOf(bgColor)
        if (iconRes != 0) {
            b.setIconResource(iconRes)
            b.setIconTint(ColorStateList.valueOf(textColor))
            b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START)
            b.setIconPadding(dp(8))
            b.setIconSize(dp(18))
        }
        b.setOnClickListener(l)
        return b
    }

    private fun makeTintedSwitch(): MaterialSwitch {
        val sw = MaterialSwitch(requireContext())
        M3Ui.standardSwitch(sw, pal)
        return sw
    }

    /** 真实耳机已连接时禁用模拟按钮（与主页"连接后不可用"逻辑一致） */
    private fun updateSimState() {
        val real = HeadsetGate.getConnectedMac(requireContext()) != null
        simConnBtn?.let { setSimEnabled(it, !real) }
    }

    private fun setSimEnabled(b: MaterialButton, en: Boolean) {
        b.isEnabled = en
        b.alpha = if (en) 1f else 0.45f
        b.backgroundTintList = ColorStateList.valueOf(if (en) pal.container
        else if (pal.dark) 0x14FFFFFF else 0x0A000000)
        b.setTextColor(if (en) pal.onContainer else pal.onVariant)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // alpha2.54: 视图销毁即取消挂起的重建，并断开对旧视图的引用
        rebuildHandler.removeCallbacks(rebuildRunnable)
        seedRow = null
    }

    override fun onResume() {
        super.onResume()
        updateSimState()
    }

    /** M3 文本按钮：统一走 M3Ui.textButton（去掉旧版 0x14000000 药丸底，对齐 LSPosed 纯文本按钮） */
    private fun makeMaterialTextButton(text: String, color: Int, l: View.OnClickListener): TextView {
        val b = M3Ui.textButton(requireContext(), pal, text, l)
        b.setTextColor(color)
        return b
    }

    /** 通用导航行（alpha1.36: M3Ui 卡片行——圆形容器图标 + chevron） */
    private fun makeNavRow(iconRes: Int, title: String, sub: String, onNav: Runnable): LinearLayout =
            M3Ui.navRow(requireActivity(), pal, iconRes, title, sub, onNav)

    /** 通用开关行（alpha1.36: M3Ui 卡片行） */
    private fun makeSwitchRow(title: String, sub: String, sw: MaterialSwitch): LinearLayout =
            M3Ui.switchRow(requireActivity(), pal, title, sub, sw)

    /** alpha2.52: 把裸行包成一张独立卡片（对齐 LSPosed：一行一卡 + 12dp 卡间距） */
    private fun wrapCard(v: View): LinearLayout {
        val card = LinearLayout(requireContext())
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(12), dp(16), dp(12))
        card.background = M3Ui.cardBg(requireContext(), pal, 20)
        card.addView(v, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    /** 官方主题设置开关（MaterialSwitch），改动即存 SP + 重建 */
    private fun makeThemeSwitch(key: String, def: Boolean, label: String): MaterialSwitch {
        val sw = MaterialSwitch(requireContext())
        sw.isChecked = getSP().getBoolean(key, def)
        sw.contentDescription = label
        M3Ui.standardSwitch(sw, pal)
        sw.setOnCheckedChangeListener { _, checked ->
            getSP().edit().putBoolean(key, checked).commit()
            // alpha2.54: 取局部引用，避免 !! 断言与 parent 检查之间的 TOCTOU
            val sr = seedRow
            if (key == "dynamic_color" && checked && sr != null && sr.parent != null) {
                // 开启动态取色 -> 先播种子颜色行消失动画，再重建（Material fade+slide）
                sr.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(200).start()
                scheduleRebuild(550L)
            } else {
                // 等 MaterialSwitch 动画播完再重建（立即 recreate 会吞掉开关动画）
                scheduleRebuild(350L)
            }
        }
        return sw
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }


    /**
     * 3.3.0: 改用 M3 官方 MaterialAlertDialogBuilder。
     * 标题/正文/按钮排版与无障碍语义全部交给官方组件（此前是自绘 Dialog + 手搓按钮行），
     * 颜色取自主题 M3 token（见 res/values 与 res/values-night 下的 colors.xml），与 ThemeUtil.Palette 同源。
     * ok 回调在主线程。
     */
    private fun showMaterialConfirm(t: String, m: String, okText: String, onOk: Runnable) {
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(t)
                .setMessage(m)
                .setNegativeButton(Lang.t("取消", "Cancel"), null)
                .setPositiveButton(okText) { _, _ -> onOk.run() }
                .show()
    }

    /** 3.3.0: M3 官方 MaterialAlertDialogBuilder（同 showMaterialConfirm）。 */
    private fun showSimpleDialog(t: String, m: String) {
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(t)
                .setMessage(m)
                .setPositiveButton(Lang.t("知道了", "Got it"), null)
                .show()
    }

    // ── 弹窗图标（与 alpha1.34 行为一致）──
    /** 3.0.5: 自定义图标存在性改为查本应用 filesDir（无需 Root，可主线程直调）。 */
    private fun iconCustomExists(): Boolean = IconStore.exists(requireContext().applicationContext)

    private fun iconCustomExistsAsync(callback: (Boolean) -> Unit) {
        // 本地文件判断，毫秒级，无需线程切换
        callback(iconCustomExists())
    }

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

    /**
     * 3.3.1: 改用 M3 官方 MaterialAlertDialogBuilder + M3Ui.listRow。
     *
     * 此前是自绘外壳（M3Ui.materialDialog + GradientDrawable 圆角行 + 手搓按钮行），
     * 与已改官方组件的其它弹窗风格不统一：标题排版、按钮顺序、圆角、无障碍语义都要自己维护。
     * 现在标题/按钮交给官方 AlertDialog，行内容用 M3 列表项规范（56dp / 16dp 内边距 /
     * 24dp 图标 / 16sp-14sp 双行文字 / 尾随 chevron）。
     */
    private fun showIconDialog(custom: Boolean) {
        val act = requireActivity()
        val ctx = requireContext()
        var dlg: androidx.appcompat.app.AlertDialog? = null

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(8), 0, dp(8))

        box.addView(M3Ui.listRow(act, pal, R.drawable.ic_image,
                Lang.t("从相册选择", "Choose from gallery"),
                Lang.t("替换 Google 弹窗显示的耳机图标", "Replace the earbud icon shown in the Google popup"),
                M3Ui.chevron(ctx, pal.onVariant),
                Runnable {
                    dlg?.dismiss()
                    try {
                        iconPicker.launch("image/*")
                    } catch (t: Throwable) {
                        toast("无法打开选择器: ${t.message}")
                    }
                }), LinearLayout.LayoutParams(-1, -2))

        if (custom) {
            box.addView(M3Ui.listRow(act, pal, R.drawable.ic_restart_alt,
                    Lang.t("恢复默认图标", "Restore default icon"),
                    Lang.t("删除自定义图标，恢复软件自带默认图", "Remove the custom icon and restore the default"),
                    M3Ui.chevron(ctx, pal.onVariant),
                    Runnable {
                        dlg?.dismiss()
                        resetCustomIcon()
                    }), LinearLayout.LayoutParams(-1, -2))
        }

        val title = Lang.t("弹窗图标（当前：", "Popup icon (current: ") +
                (if (custom) Lang.t("已自定义", "Custom") else Lang.t("默认", "Default")) + "）"

        dlg = MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(box)
                .setNegativeButton(Lang.t("取消", "Cancel"), null)
                .show()
    }

    private fun showLogDialog() {
        showMaterialConfirm(Lang.t("日志抓取 · 隐私声明", "Log capture · Privacy notice"),
                LogCollector.privacyNotice(requireContext()),
                Lang.t("同意并抓取", "Agree and capture")) {
            toast(Lang.t("⏳ 正在收集日志…", "⏳ Collecting logs…"))
            Thread {
                val path = LogCollector.collect(requireContext())
                requireActivity().runOnUiThread {
                    showSimpleDialog(Lang.t("日志已保存", "Log saved"),
                            Lang.t("已打包为 ZIP（含 6 条分类日志：系统/应用/蓝牙/环境/logcat/运行日志）。\n\n路径：\n", "Packaged as ZIP (6 log categories: system/app/bluetooth/env/logcat/run).\n\nPath:\n") + path +
                                    Lang.t("\n\n您可自行将文件分享给开发者进行设备适配分析。", "\n\nYou can share it with the developer for device adaptation."))
                }
            }.start()
        }
    }

    private fun resetCustomIcon() {
        // 3.0.5: 只删本应用内的图标文件（无需 Root）
        val ok = IconStore.clear(requireContext().applicationContext)
        toast(if (ok) "✅ 已恢复默认图标（下次连接生效）" else "恢复失败")
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
                // 3.0.5: 写本应用 filesDir（无需 Root），GMS 侧 Hook 经 ContentProvider 读取；
                // 有 Root 时 IconStore 内部额外写一份 GMS 老路径（纯增强，失败不影响）。
                val ok = IconStore.save(requireContext().applicationContext, out.readBytes())
                requireActivity().runOnUiThread {
                    toast(if (ok) "✅ 弹窗图标已更新（下次连接生效）" else "写入图标失败")
                }
                if (scaled !== bmp && !scaled.isRecycled) scaled.recycle()
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
            } catch (t: Throwable) {
                requireActivity().runOnUiThread { toast("图标处理失败: ${t.message}") }
            }
        }.start()
    }

    companion object {
        private const val SIM_MAC = "AA:BB:CC:DD:EE:FF"
        private const val SIM_NAME = "Moondrop Golden Ages 2"
    }
}
