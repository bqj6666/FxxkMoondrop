package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 3.1.0: 使用引导（横向分页，每页一个功能分区）。
 *
 * 页面顺序与内容：
 *   1. 关于      —— 应用定位、系统集成前置条件与使用说明。
 *   2. 权限申请  —— 复用 [PermissionChecker]（与权限检测页同一份判定），点击条目直接授权。
 *   3. 连接管理  —— 后台监听、电量通知、后台隐藏。
 *   4. 系统集成  —— 官方降噪面板、蓝牙详情页面板、通知内降噪控制、抗风噪按钮。
 *   5. 耳机控制  —— 概览页提供的各项耳机能力（只读说明）。
 *   6. 适配与诊断—— 自定义映射、弹窗图标、权限检测、日志抓取（只读说明）。
 *
 * 退出规则：第一次启动时仅「跳过」可提前退出；「返回」与系统返回键只回退到上一页，
 * 在第一页不退出（避免误触直接跳过整段引导）。末页「开始使用」为正常完成。
 *
 * 页面内的开关与设置页共用同一份偏好（cfg SP）与同一套副作用，改完立即生效，
 * 不存在第二套状态。不新增任何链路。
 */
class OnboardingActivity : Activity() {

    private lateinit var pal: ThemeUtil.Palette
    private lateinit var pager: ViewPager2
    private lateinit var dots: DotsView
    private lateinit var backBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var permBox: LinearLayout
    private var statusBarH = 0
    private var curPage = 0
    private var permLoading = false
    private var welcomeLoading = false
    private lateinit var welcomeBox: LinearLayout

    /** 全应用唯一的偏好表（与设置页同源）。 */
    private fun sp() = getSharedPreferences("cfg", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        pal = ThemeUtil.Palette(this)
        window.setBackgroundDrawable(ColorDrawable(pal.surface))
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val rid = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (rid > 0) statusBarH = resources.getDimensionPixelSize(rid)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        root.setPadding(dp(20), statusBarH, dp(20), dp(16))

        // 顶部只有「跳过」：第一次启动时它是唯一的提前退出入口，不放返回箭头。
        root.addView(buildTopRow(), LinearLayout.LayoutParams(-1, -2))

        pager = ViewPager2(this)
        pager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        pager.adapter = PagesAdapter(buildPages())
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                curPage = position
                syncChrome()
                if (isPermPage(position)) refreshPerms()
                if (position == PAGE_COUNT - 1) refreshWelcome()
            }
        })
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))

        // 指示器与上方正文、下方按钮各留出间距，避免贴着正文底边。
        dots = DotsView(this)
        val dotsWrap = LinearLayout(this)
        dotsWrap.orientation = LinearLayout.VERTICAL
        dotsWrap.setPadding(0, dp(22), 0, dp(14))
        dotsWrap.addView(dots, LinearLayout.LayoutParams(-1, dp(24)))
        root.addView(dotsWrap, LinearLayout.LayoutParams(-1, -2))

        root.addView(buildBottomBar(), LinearLayout.LayoutParams(-1, -2))
        // 目标 SDK 35+ 强制全屏铺设：底部按钮必须自己让开系统手势条，否则贴着屏幕下沿。
        root.setOnApplyWindowInsetsListener { v, insets ->
            val nav = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            else
                insets.systemWindowInsetBottom
            v.setPadding(dp(20), statusBarH, dp(20), dp(16) + nav)
            insets
        }
        setContentView(root)
        syncChrome()
        Log.d(TAG, "onboarding shown")
    }

    /**
     * 任何退出路径都记为已读：跳过、开始使用、以及万一被系统回收，
     * 都不会在下次启动时重复弹出。
     */
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) sp().edit().putBoolean(KEY_DONE, true).apply()
    }

    /**
     * 系统返回键只回退一页，不在第一页退出 —— 第一次使用时提前退出只能走「跳过」。
     */
    @Deprecated("系统返回键按页回退，不退出引导")
    override fun onBackPressed() {
        if (curPage > 0) goTo(curPage - 1) else Unit
    }

    /** 从系统授权页返回后重新读取权限状态。 */
    override fun onResume() {
        super.onResume()
        if (isPermPage(curPage)) refreshPerms()
        if (curPage == PAGE_COUNT - 1) refreshWelcome()
    }

    /** 看完或跳过：记录已读并结束。 */
    private fun done() {
        Log.d(TAG, "onboarding done")
        sp().edit().putBoolean(KEY_DONE, true).apply()
        // 显式把主界面提到前台：首次启动时主界面在引导期间一直处于后台，
        // 若已被系统回收，单靠 finish() 会让整个任务变空、直接退到桌面。
        try {
            startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) { }
        // 若主界面就在本页下面，CLEAR_TOP 已经把它带回前台（本页随之结束）；
        // 只有在引导是任务根（例如主界面已被系统回收）时才需要自己收尾。
        finish()
    }

    private fun isPermPage(p: Int) = p == PAGE_PERMS

    private fun goTo(p: Int) {
        if (p in 0 until PAGE_COUNT) pager.setCurrentItem(p, true)
    }

    private fun onNext() {
        if (curPage >= PAGE_COUNT - 1) done() else goTo(curPage + 1)
    }

    /**
     * 页码指示器与底部按钮随当前页同步。
     *
     * 指示器只重画、不增删子视图：ViewPager2 会在自己的布局阶段回调 onPageSelected，
     * 此刻改动视图树会因 requestLayout 被吞掉而留下 0 尺寸的子视图（点就画不出来）。
     * 自绘 + invalidate 没有这个问题。
     */
    private fun syncChrome() {
        dots.pages = PAGE_COUNT
        dots.current = curPage
        dots.invalidate()
        // 第一页没有上一页，也没有可退出入口：返回键隐藏，避免被当成「跳过」。
        backBtn.visibility = if (curPage > 0) View.VISIBLE else View.INVISIBLE
        nextBtn.text = if (curPage >= PAGE_COUNT - 1)
            Lang.t(this, "开始使用", "Get started")
        else
            Lang.t(this, "下一步", "Next")
    }

    // ── 页面骨架 ───────────────────────────────────────────────

    private fun buildTopRow(): View {
        val row = FrameLayout(this)
        val skip = M3Ui.textButton(this, pal, Lang.t(this, "跳过", "Skip")) { done() }
        skip.contentDescription = Lang.t(this, "跳过使用引导", "Skip the tour")
        row.addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.END))
        return row
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(0, dp(4), 0, 0)
        backBtn = M3Ui.textButton(this, pal, Lang.t(this, "返回", "Back")) {
            if (curPage > 0) goTo(curPage - 1)
        }
        bar.addView(backBtn, LinearLayout.LayoutParams(-2, -2))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        nextBtn = M3Ui.filledButton(this, pal, Lang.t(this, "下一步", "Next")) { onNext() }
        bar.addView(nextBtn, LinearLayout.LayoutParams(-2, -2))
        return bar
    }

    /**
     * 单页骨架：圆形图标 + 标题 + 副标题 + 内容区。
     *
     * 内容区可滚动（页内条目较多时超出屏幕），图标保持居中，
     * 与其它页面的视觉重心一致。
     */
    private fun buildPage(title: String, sub: String, iconRes: Int, body: View?,
                          appIcon: Boolean = false): View {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.layoutParams = ViewGroup.LayoutParams(-1, -1)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(0, dp(28), 0, dp(20))

        if (appIcon) {
            // 首页放应用自身图标：无需外加色底，图标本身已带背景与形状。
            val logo = ImageView(this)
            logo.setImageResource(iconRes)
            logo.scaleType = ImageView.ScaleType.FIT_CENTER
            col.addView(logo, LinearLayout.LayoutParams(dp(104), dp(104)))
        } else {
            val circle = FrameLayout(this)
            val cd = GradientDrawable()
            cd.shape = GradientDrawable.OVAL
            cd.setColor(pal.container)
            circle.background = cd
            val ic = ImageView(this)
            ic.setImageResource(iconRes)
            ic.imageTintList = ColorStateList.valueOf(pal.onContainer)
            circle.addView(ic, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
            col.addView(circle, LinearLayout.LayoutParams(dp(112), dp(112)))
        }

        col.addView(spacer(dp(26)))

        val t = TextView(this)
        t.text = title
        t.textSize = 28f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(pal.onSurface)
        t.gravity = Gravity.CENTER
        col.addView(t, LinearLayout.LayoutParams(-1, -2))

        col.addView(spacer(dp(10)))

        val s = TextView(this)
        s.text = sub
        s.textSize = 16f
        s.setTextColor(pal.onVariant)
        s.gravity = Gravity.CENTER
        // M3 typescale bodyLarge = 16sp/24sp（行高比 1.5）。此前是 16sp+4dp ≈ 20sp，
        // 明显紧于规范；引导页副标题是成段文字，按规范行高读起来才不挤。
        s.setLineSpacing(0f, 1.5f)
        col.addView(s, LinearLayout.LayoutParams(-1, -2))

        if (body != null) {
            col.addView(spacer(dp(26)))
            col.addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        sv.addView(col, FrameLayout.LayoutParams(-1, -2))
        return sv
    }

    private fun buildPages(): List<View> {
        val pages = ArrayList<View>()
        pages.add(pageAbout())
        pages.add(pagePerms())
        pages.add(pageConnection())
        pages.add(pageInjection())
        pages.add(pageHeadset())
        pages.add(pageTools())
        pages.add(pageWelcome())
        if (pages.size != PAGE_COUNT) Log.w(TAG, "page count mismatch: " + pages.size)
        return pages
    }

    // ── 1. 关于 ───────────────────────────────────────────────

    private fun pageAbout(): View {
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = M3Ui.cardBg(this, pal, 20)
        card.setPadding(dp(18), dp(16), dp(18), dp(16))
        card.addView(body(Lang.t(this,
                "本应用为 Moondrop 系列蓝牙耳机提供系统级控制能力，将耳机状态接入 Android 系统界面。",
                "A system-level console for Moondrop Bluetooth earbuds that brings headset state into the Android UI.")))
        card.addView(spacer(dp(12)))
        card.addView(body(Lang.t(this,
                "降噪档位、空间音频与头部跟踪、增益、指示灯与电量信息，可直接在系统蓝牙设备详情页、通知栏及 Google 快速配对弹窗中查看与调整，无需启动官方应用。",
                "Noise-control levels, spatial audio with head tracking, gain, indicator light and battery can be viewed and adjusted from the system Bluetooth details page, the notification shade and the Google Fast Pair popup, without opening the official app.")))
        card.addView(spacer(dp(12)))
        card.addView(body(Lang.t(this,
                "系统集成能力由 LSPosed 模块 FastPairHook 提供，需在 LSPosed 中启用该模块，并勾选「系统界面」与「Google Play 服务」作用域。模块未激活时，应用主体功能不受影响，仅系统集成项不可用。",
                "System integration is provided by the LSPosed module FastPairHook, which must be enabled with the System UI and Google Play services scopes. While it is inactive the app itself keeps working; only the injected features are unavailable.")))
        body.addView(card, LinearLayout.LayoutParams(-1, -2))

        return buildPage("FxxkMoondrop",
                Lang.t(this, "Moondrop 蓝牙耳机助手 · 版本 V" + versionName(),
                        "Moondrop earbud assistant · Version V" + versionName()),
                R.mipmap.ic_launcher, body, appIcon = true)
    }

    // ── 2. 权限申请 ─────────────────────────────────────────────

    private fun pagePerms(): View {
        permBox = LinearLayout(this)
        permBox.orientation = LinearLayout.VERTICAL
        return buildPage(Lang.t(this, "权限申请", "Permissions"),
                Lang.t(this, "以下为功能运行所需的权限与环境检查项，缺失时点击相应条目完成授权；必要项缺失会直接影响使用，可选项仅影响增强功能。",
                        "Permissions and environment checks the app relies on. Tap a pending item to grant it; missing required items directly affect usage, optional ones only affect enhancements."),
                R.drawable.ic_shield, permBox)
    }

    /**
     * 重读权限状态。
     *
     * [PermissionChecker.checkAll] 含一次模块 PING 与一次 root 探测，必须离开主线程执行
     * （与权限检测页 [PermissionActivity] 同一套做法）。
     */
    private fun refreshPerms() {
        if (!::permBox.isInitialized || permLoading) return
        permLoading = true
        permBox.removeAllViews()
        permBox.addView(M3Ui.loadingRow(this, pal, Lang.t(this, "正在检查…", "Checking…")))
        val act = this
        Thread {
            val items = try {
                PermissionChecker.checkAll(act)
            } catch (t: Throwable) {
                emptyList<PermissionChecker.Item>()
            }
            act.runOnUiThread {
                permLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                permBox.removeAllViews()
                // 必要在前、可选在后，各成一组；组内为空则不出标题。
                addPermGroup(act, Lang.t(act, "必要权限", "Required"), items.filter { it.required })
                addPermGroup(act, Lang.t(act, "可选权限", "Optional"), items.filter { !it.required })
                // 就绪时不在这里下结论：整体状态由末页「欢迎使用」统一呈现。
                val missReq = PermissionChecker.countMissingRequired(items)
                if (missReq > 0) {
                    permBox.addView(hint(Lang.tf("尚有 %d 项必要权限未就绪，点击上方条目可前往授权。",
                            "%d required item(s) pending — tap a row above to grant.", missReq)))
                }
            }
        }.start()
    }

    /** 一组权限：组标题 + 每行一张卡片。 */
    private fun addPermGroup(act: Activity, title: String, items: List<PermissionChecker.Item>) {
        if (items.isEmpty()) return
        permBox.addView(groupLabel(title))
        val rows = ArrayList<View>()
        for (it in items) rows.add(permRow(it))
        permBox.addView(M3Ui.groupCard(act, pal, *rows.toTypedArray()))
    }

    /** 分组标题。 */
    private fun groupLabel(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(pal.primary)
        t.setPadding(dp(6), dp(10), dp(6), dp(4))
        return t
    }

    private fun permRow(it: PermissionChecker.Item): View {
        // 就绪 = 绿色圆形背景勾；未就绪保持红色文字，读起来就是个待办。
        val trailing: View = if (it.ok) {
            readyBadge()
        } else {
            val status = TextView(this)
            status.text = Lang.t(this, "待处理", "Pending")
            status.textSize = 14f
            status.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            status.setTextColor(pal.red)
            status
        }
        return M3Ui.listRow(this, pal, 0, it.name, it.detail, trailing, Runnable { fixPerm(it) })
    }

    /**
     * 就绪徽标：绿色圆底 + 白色勾。
     *
     * 尺寸必须落在**内层**圆形上：M3Ui.listRow 会给尾随视图套上它自己的 LayoutParams，
     * 外层再设 layoutParams 会被覆盖，结果被压成图标本身那点大小。
     */
    private fun readyBadge(): View {
        val outer = LinearLayout(this)
        val circle = FrameLayout(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(pal.green)
        circle.background = bg
        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_check)
        ic.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        circle.addView(ic, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
        outer.addView(circle, LinearLayout.LayoutParams(dp(30), dp(30)))
        return outer
    }

    /** 缺失项跳转修复：与权限检测页同一套动作（运行时权限 / 电池白名单 / root 重探）。 */
    private fun fixPerm(it: PermissionChecker.Item) {
        try {
            when (it.action) {
                PermissionChecker.ACTION_RUNTIME ->
                    if (it.requestCode == 1) {
                        requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT,
                                android.Manifest.permission.BLUETOOTH_SCAN), 1)
                    } else if (it.requestCode == 2) {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
                    }
                PermissionChecker.ACTION_BATTERY -> KeepAlive.requestWhitelist(this)
                PermissionChecker.ACTION_ROOT_RECHECK -> {
                    // root 与模块的负结果可能是「未授权时整体隐藏」，必须给出重试入口；
                    // 探测会 exec（可能弹授权框），绝不能在主线程执行。
                    RootShell.retryNow()
                    EnvProbe.retryHookProbe()
                    permLoading = false
                    refreshPerms()
                }
                else -> toast(it.detail)
            }
        } catch (e: Exception) {
            toast(Lang.t(this, "无法打开授权页面：", "Cannot open the permission page: ") + e.message)
        }
    }

    // ── 3. 连接管理 ─────────────────────────────────────────────

    private fun pageConnection(): View {
        val rows = ArrayList<View>()

        // 后台监听总开关：开启即开始监听，启动 / 开机自动恢复；连上耳机直连 GAIA。
        val swAuto = m3Switch()
        swAuto.isChecked = sp().getBoolean("enable", true) && sp().getBoolean("auto_service", true)
        rows.add(M3Ui.listRow(this, pal, R.drawable.ic_sensors,
                Lang.t(this, "后台监听（总开关）", "Background monitor (master)"),
                Lang.t(this,
                        "开启后立即开始监听，并在启动应用与开机时自动恢复；耳机连接后直连 GAIA，读取电量并控制降噪。",
                        "Starts monitoring immediately and resumes automatically on launch and boot; connects to GAIA on earbud connect for battery and noise control."),
                swAuto, null))
        swAuto.setOnCheckedChangeListener { _, checked ->
            // 两个偏好键始终同步写入（与设置页一致）；关闭时真正停止服务并取消看门狗。
            sp().edit().putBoolean("auto_service", checked).putBoolean("enable", checked).apply()
            if (checked) {
                HeadsetDetectService.RUNNING = true
                try { startService(Intent(this, HeadsetDetectService::class.java)) } catch (_: Exception) { }
            } else {
                AliveReceiver.cancel(this)
                HeadsetDetectService.RUNNING = false
                try { stopService(Intent(this, HeadsetDetectService::class.java)) } catch (_: Exception) { }
            }
        }

        val swBatt = m3Switch()
        swBatt.isChecked = sp().getBoolean("feat_notif_battery", true)
        rows.add(M3Ui.listRow(this, pal, R.drawable.ic_battery_full,
                Lang.t(this, "电量通知", "Battery notification"),
                Lang.t(this, "耳机连接后显示常驻通知，呈现左右耳电量（不含充电盒）。",
                        "Shows a persistent notification with left and right battery level after connection (charging case excluded)."),
                swBatt, null))
        swBatt.setOnCheckedChangeListener { _, checked ->
            sp().edit().putBoolean("feat_notif_battery", checked).apply()
            // 即时生效：refreshBattery 内部按开关取消或重建（GAIA 未连接时不执行任何操作）。
            DeviceNotif.refreshBattery(this)
        }

        val swBg = m3Switch()
        swBg.isChecked = sp().getBoolean("bg_hide", false)
        rows.add(M3Ui.listRow(this, pal, R.drawable.ic_visibility_off,
                Lang.t(this, "后台隐藏", "Hide in background"),
                Lang.t(this, "切到后台时隐藏主界面，不在最近任务中保留；应用内跳转与授权流程不受影响。",
                        "Hides the main UI when moved to the background and keeps it out of recents; in-app navigation and permission flows are unaffected."),
                swBg, null))
        swBg.setOnCheckedChangeListener { _, checked ->
            sp().edit().putBoolean("bg_hide", checked).apply()
        }

        return buildPage(Lang.t(this, "连接管理", "Connection"),
                Lang.t(this, "控制应用的后台运行方式与通知行为。",
                        "Controls how the app runs in the background and how it notifies you."),
                R.drawable.ic_sensors, M3Ui.groupCard(this, pal, *rows.toTypedArray()))
    }

    // ── 4. 系统集成 ─────────────────────────────────────────────

    private fun pageInjection(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val hookOff = !EnvProbe.hookUsable()
        if (hookOff) {
            wrap.addView(hint(Lang.t(this,
                    "FastPairHook 模块当前未激活，以下功能不可用。请在 LSPosed 中启用该模块后重试。",
                    "The FastPairHook module is inactive, so the features below are unavailable. Enable it in LSPosed and try again.")))
            wrap.addView(spacer(dp(10)))
        }

        val rows = ArrayList<View>()

        val swOfficial = m3Switch()
        swOfficial.isChecked = !hookOff && sp().getBoolean("feat_official_panel", true)
        rows.add(M3Ui.listRow(this, pal, R.drawable.ic_headphones,
                Lang.t(this, "官方降噪面板", "Official noise-control panel"),
                Lang.t(this, "将耳机状态接入 Google 官方降噪面板（音量面板与提示音和振动面板）。",
                        "Feeds headset state into Google's official noise-control panel (volume and sound panels)."),
                swOfficial, null))
        if (!hookOff) swOfficial.setOnCheckedChangeListener { _, c ->
            sp().edit().putBoolean("feat_official_panel", c).apply()
        }

        val swDetail = m3Switch()
        swDetail.isChecked = !hookOff && sp().getBoolean("feat_detail_panel", true)
        rows.add(M3Ui.listRow(this, pal, R.drawable.ic_tune,
                Lang.t(this, "蓝牙详情页面板", "Bluetooth details panel"),
                Lang.t(this, "在系统蓝牙设备详情页注入降噪与功能控制卡片；未连接时整块收起，耳机未就绪时以官方加载行占位。",
                        "Injects the control card into the system device-details page; it collapses while disconnected and holds place with the official loading row until the headset is ready."),
                swDetail, null))
        if (!hookOff) swDetail.setOnCheckedChangeListener { _, c ->
            sp().edit().putBoolean("feat_detail_panel", c).apply()
        }

        val swNotifAnc = m3Switch()
        swNotifAnc.isChecked = sp().getBoolean("feat_notif_anc", true)
        rows.add(M3Ui.listRow(this, pal, R.drawable.ic_anc_on,
                Lang.t(this, "通知内降噪控制", "Noise control in notification"),
                Lang.t(this, "在通知栏提供降噪档位按钮，按钮按本机支持的档位生成。",
                        "Adds noise-control buttons to the notification shade, built from the levels this device supports."),
                swNotifAnc, null))
        swNotifAnc.setOnCheckedChangeListener { _, c ->
            sp().edit().putBoolean("feat_notif_anc", c).apply()
            DeviceNotif.refreshAnc(this)
        }

        val swWind = m3Switch()
        swWind.isChecked = sp().getBoolean("show_wind", true)
        rows.add(M3Ui.listRow(this, pal, R.drawable.ic_air,
                Lang.t(this, "抗风噪按钮", "Wind-noise button"),
                Lang.t(this, "抗风为降噪的加强档。详情页快捷开关仅在耳机处于降噪或抗风档时出现；关闭本项后弹窗、主界面与详情页均不再提供抗风。",
                        "Wind is the boosted noise-control level. Its quick switch appears only while the earbuds are on ANC or Wind; turning this off removes Wind from the popup, the main screen and the details page."),
                swWind, null))
        swWind.setOnCheckedChangeListener { _, c -> sp().edit().putBoolean("show_wind", c).apply() }

        wrap.addView(M3Ui.groupCard(this, pal, *rows.toTypedArray()))

        if (hookOff) {
            // 置灰而非隐藏：用户需要知道存在该功能，以及当前为何不可用。
            for (r in rows) r.alpha = 0.45f
            for (sw in listOf(swOfficial, swDetail)) {
                sw.isEnabled = false
                sw.isClickable = false
            }
        }

        return buildPage(Lang.t(this, "系统集成", "System integration"),
                Lang.t(this, "将耳机控制能力接入 Android 系统界面。",
                        "Brings headset control into the Android system UI."),
                R.drawable.ic_tune, wrap)
    }

    // ── 5. 耳机控制 ─────────────────────────────────────────────

    private fun pageHeadset(): View {
        val rows = arrayOf(
                M3Ui.listRow(this, pal, R.drawable.ic_anc_on,
                        Lang.t(this, "降噪档位", "Noise-control levels"),
                        Lang.t(this, "降噪、关闭、透传、自适应与抗风等档位可在概览页、通知栏及系统界面之间切换。",
                                "ANC, Off, Transparency, Adaptive and Wind can be switched from the overview page, the notification shade and the system UI."),
                        null, null),
                M3Ui.listRow(this, pal, R.drawable.ic_track_full,
                        Lang.t(this, "空间音频与头部跟踪", "Spatial audio and head tracking"),
                        Lang.t(this, "使用系统蓝牙设备详情页中的官方开关，勾选状态与耳机端保持同步。",
                                "Uses the official switches on the system Bluetooth details page, kept in sync with the earbuds."),
                        null, null),
                M3Ui.listRow(this, pal, R.drawable.ic_gain_2,
                        Lang.t(this, "增益", "Gain"),
                        Lang.t(this, "按档位调整耳机输出增益，改动立即下发至耳机。",
                                "Adjusts the output gain by level; changes are sent to the earbuds immediately."),
                        null, null),
                M3Ui.listRow(this, pal, R.drawable.ic_led_on,
                        Lang.t(this, "指示灯", "Indicator light"),
                        Lang.t(this, "控制耳机指示灯的开与关。",
                                "Turns the earbud indicator light on or off."),
                        null, null),
                M3Ui.listRow(this, pal, R.drawable.ic_battery_full,
                        Lang.t(this, "电量显示", "Battery"),
                        Lang.t(this, "连接后显示左右耳电量，并在概览页集中呈现设备名称与地址。",
                                "Shows left and right battery after connection, together with device name and address on the overview page."),
                        null, null))
        return buildPage(Lang.t(this, "耳机控制", "Headset control"),
                Lang.t(this, "概览页提供耳机全部功能的集中控制。",
                        "The overview page is the single place for every headset function."),
                R.drawable.ic_headphones, M3Ui.groupCard(this, pal, *rows))
    }

    // ── 6. 适配与诊断 ───────────────────────────────────────────

    private fun pageTools(): View {
        val rows = arrayOf(
                M3Ui.listRow(this, pal, R.drawable.ic_tune,
                        Lang.t(this, "自定义映射", "Custom mapping"),
                        Lang.t(this, "设置各降噪档位与增益档位发送的设备码，以及三档追踪模式的显示名称。",
                                "Sets the device code each noise-control and gain level sends, plus the display names of the three tracking modes."),
                        null, null),
                M3Ui.listRow(this, pal, R.drawable.ic_image,
                        Lang.t(this, "弹窗图标", "Popup icon"),
                        Lang.t(this, "替换 Google 快速配对弹窗中显示的耳机图标，可从相册选择或恢复默认。",
                                "Replaces the earbud icon shown in the Google Fast Pair popup; pick one from the gallery or restore the default."),
                        null, null),
                M3Ui.listRow(this, pal, R.drawable.ic_shield,
                        Lang.t(this, "权限检测", "Permission check"),
                        Lang.t(this, "检查蓝牙、通知、电池白名单、Root 与模块环境等各项状态。",
                                "Checks Bluetooth, notifications, battery whitelist, root and module environment status."),
                        null, null),
                M3Ui.listRow(this, pal, R.drawable.ic_description,
                        Lang.t(this, "日志抓取", "Log capture"),
                        Lang.t(this, "收集设备信息与运行日志并导出压缩包，用于机型适配分析。",
                                "Collects device information and runtime logs into a ZIP for device-adaptation analysis."),
                        null, null))
        return buildPage(Lang.t(this, "适配与诊断", "Adaptation and diagnostics"),
                Lang.t(this, "面向机型适配与问题排查的工具。",
                        "Tools for device adaptation and troubleshooting."),
                R.drawable.ic_description, M3Ui.groupCard(this, pal, *rows))
    }

    // ── 7. 欢迎使用 ─────────────────────────────────────────────

    private fun pageWelcome(): View {
        welcomeBox = LinearLayout(this)
        welcomeBox.orientation = LinearLayout.VERTICAL
        return buildPage(Lang.t(this, "欢迎使用", "Welcome"),
                Lang.t(this, "引导到此结束，以下为当前环境状态，可以开始使用了。",
                        "That is the whole tour. Below is the current environment status — you are good to go."),
                R.drawable.ic_check, welcomeBox)
    }

    /**
     * 刷新末页总览。
     *
     * 与权限页同源取数（[PermissionChecker] 含一次模块 PING 与一次 root 探测，必须离开主线程），
     * 只呈现结论，不重复列出每一项权限。
     */
    private fun refreshWelcome() {
        if (!::welcomeBox.isInitialized || welcomeLoading) return
        welcomeLoading = true
        welcomeBox.removeAllViews()
        welcomeBox.addView(M3Ui.loadingRow(this, pal, Lang.t(this, "正在检查…", "Checking…")))
        val act = this
        Thread {
            val items = try {
                PermissionChecker.checkAll(act)
            } catch (t: Throwable) {
                emptyList<PermissionChecker.Item>()
            }
            val hookOk = try {
                EnvProbe.isFastPairHookActive(act)
            } catch (t: Throwable) {
                false
            }
            act.runOnUiThread {
                welcomeLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                welcomeBox.removeAllViews()
                val missReq = PermissionChecker.countMissingRequired(items)
                val missOpt = PermissionChecker.countMissing(items) - missReq
                val reqTotal = items.count { it.required }
                val optTotal = items.size - reqTotal
                val rows = arrayOf(
                        summaryRow(Lang.t(act, "必要权限", "Required permissions"), missReq == 0,
                                if (missReq == 0)
                                    Lang.tf("已全部就绪（%d 项）", "All %d granted", reqTotal)
                                else
                                    Lang.tf("尚有 %d 项未就绪，可在上一页处理",
                                            "%d pending — handle them on the previous page", missReq)),
                        summaryRow(Lang.t(act, "可选权限", "Optional permissions"), missOpt == 0,
                                if (missOpt == 0)
                                    Lang.tf("已全部就绪（%d 项）", "All %d granted", optTotal)
                                else
                                    Lang.tf("%d 项未就绪，仅影响后台留存与系统集成",
                                            "%d pending — affects background retention and system integration only", missOpt)),
                        summaryRow(Lang.t(act, "FastPairHook 模块", "FastPairHook module"), hookOk,
                                if (hookOk)
                                    Lang.t(act, "已激活，系统集成功能可用", "Active, system integration available")
                                else
                                    Lang.t(act, "未激活，系统集成功能不可用（可在 LSPosed 中启用）",
                                            "Inactive, system integration unavailable (enable it in LSPosed)")),
                        summaryRow(Lang.t(act, "使用引导", "Getting started"), true,
                                Lang.t(act, "已完成，可在设置页最底部重新查看",
                                        "Completed — replay it from the bottom of Settings")))
                welcomeBox.addView(M3Ui.groupCard(act, pal, *rows))
            }
        }.start()
    }

    private fun summaryRow(title: String, ok: Boolean, detail: String): View =
            M3Ui.listRow(this, pal, 0, title, detail, if (ok) readyBadge() else null, null)

    // ── 页码指示器（自绘） ──────────────────────────────────────

    /**
     * 页码指示器：当前页为长条，其余为圆点，整体水平居中。
     *
     * 用单个自绘 View 而不是一排子 View：既不依赖子视图测量时机，
     * 也不会因为页数变化引起额外的布局。
     */
    private inner class DotsView(ctx: Context) : View(ctx) {

        var pages = 0
        var current = 0

        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(24))
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            if (pages <= 0) return
            val dot = dp(8).toFloat()
            val active = dp(28).toFloat()
            val gap = dp(10).toFloat()
            val r = dot / 2f
            var x = (width - (active + (pages - 1) * (dot + gap))) / 2f
            val cy = height / 2f
            for (i in 0 until pages) {
                val w = if (i == current) active else dot
                paint.color = if (i == current) pal.primary else pal.onVariant
                paint.alpha = if (i == current) 255 else 102
                canvas.drawRoundRect(x, cy - r, x + w, cy + r, r, r, paint)
                x += w + gap
            }
        }
    }

    // ── 分页适配器 ─────────────────────────────────────────────

    /**
     * 横向分页。每页的 itemViewType 即页序号，保证一页只对应一个视图实例，
     * 视图被复用时不会串页。
     */
    private inner class PagesAdapter(private val pages: List<View>) :
            RecyclerView.Adapter<PagesAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = pages[viewType]
            v.layoutParams = ViewGroup.LayoutParams(-1, -1)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = Unit

        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = position
    }

    // ── UI 小工具 ──────────────────────────────────────────────

    /** 当前应用版本名，取自包信息（不再硬编码，免得每次发版漏改这里）。 */
    private fun versionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (t: Throwable) {
        ""
    }

    private fun m3Switch(): MaterialSwitch {
        val sw = MaterialSwitch(this)
        M3Ui.standardSwitch(sw, pal)
        return sw
    }

    private fun body(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTextColor(pal.onVariant)
        t.setLineSpacing(dp(5).toFloat(), 1f)
        return t
    }

    private fun hint(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTextColor(pal.onVariant)
        t.setLineSpacing(dp(4).toFloat(), 1f)
        t.setPadding(dp(4), dp(4), dp(4), dp(10))
        return t
    }

    private fun spacer(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "Onboarding"

        /** 页数与各页序号（序号即内容分区，见类注释）。 */
        private const val PAGE_ABOUT = 0
        private const val PAGE_PERMS = 1
        private const val PAGE_COUNT = 7

        /** 已看过引导（设置页底部的入口不受它限制，随时可重看）。 */
        const val KEY_DONE = "onboard_seen_v310"
    }
}
