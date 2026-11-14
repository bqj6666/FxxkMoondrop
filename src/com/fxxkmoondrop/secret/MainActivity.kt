package com.fxxkmoondrop.secret

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import com.fxxkmoondrop.secret.ui.miuix.MiuixHostFragment

/**
 * alpha2.12: 官方单 Activity 架构 —— 三页 Fragment 切换，底部导航常驻（M3 官方 fade 仅作用于内容区）。
 */
// 3.2.13: 实现 `NavigationEventDispatcherOwner`。
//
// Miuix 的所有弹层（下拉 `WindowDropdownMenu` / `OverlayListPopup` /
// `Scaffold` 内的 popupHost）都要读 Compose 的 `LocalWindowInfo.current`，
// 它由 androidx.navigationevent 提供，要求宿主实现本接口。
// 缺实现时一点弹层就崩：
//   IllegalStateException: No NavigationEventDispatcher was provided
//   via LocalNavigationEventDispatcherOwner
//
// `ComponentActivity` 只在 androidx.navigation 存在时才实现它，
// 而我们只引了 navigation-runtime（不要 NavHost/NavController），
// 所以这里显式实现：一个无参 NavigationEventDispatcher 即可。
//
// ⚠️ 与导航功能无关，不影响任何现有页面架构与行为。
class MainActivity : BaseFragmentActivity(), NavigationEventDispatcherOwner,
    com.fxxkmoondrop.secret.ui.miuix.MiuixNavHost {

    // ⚠️ 必须写成 Kotlin 的 `override val`（不是 Java 风格的 override fun
    // getNavigationEventDispatcher()）—— 该接口是用 Kotlin 声明的
    // `val navigationEventDispatcher: NavigationEventDispatcher`，
    // 编译器会明确要求这一点（报 "not implement abstract member:
    // val navigationEventDispatcher"）。
    override val navigationEventDispatcher: NavigationEventDispatcher by lazy {
        NavigationEventDispatcher()
    }

    private var curTab = 1 // 1=概览 2=设置 3=关于
    // 固定 container id：recreate 后 FragmentManager 按保存的 containerId 恢复 fragment；
    // 若用 View.generateViewId() 每次重建 ID 都变，恢复时找不到容器导致页面消失
    private val containerId = 0x00F0F1
    /** 侧滑菜单引用：返回键要先关抽屉，再退出页面。 */
    private var drawerRef: androidx.drawerlayout.widget.DrawerLayout? = null
    private var panelRef: View? = null
    private var navBar: com.google.android.material.bottomnavigation.BottomNavigationView? = null

    /** 主题设置里的「界面缩放」：改 densityDpi 实现整体缩放（dp 全部跟着走）。 */
    override fun attachBaseContext(newBase: android.content.Context) {
        val scale = try {
            newBase.getSharedPreferences("cfg", MODE_PRIVATE).getFloat("ui_scale", 1f)
        } catch (_: Throwable) {
            1f
        }
        if (scale.isFinite() && scale != 1f && scale in 0.5f..2f) {
            val conf = android.content.res.Configuration(newBase.resources.configuration)
            conf.densityDpi = (conf.densityDpi * scale).toInt().coerceIn(96, 640)
            super.attachBaseContext(newBase.createConfigurationContext(conf))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this) // alpha2.16: 运行日志（无 Root 可收集）
        installVisibilityTracking(application) // alpha2.41.9: 后台隐藏判定需要全局可见界面计数
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        if (savedInstanceState != null) curTab = savedInstanceState.getInt(KEY_TAB, 1)

        val pal = ThemeUtil.Palette(this)
        window.setStatusBarColor(pal.surface)
        window.setNavigationBarColor(pal.surface)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        val content = FrameLayout(this)
        content.id = containerId
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        navBar = M3Ui.navBar(this, pal, curTab) { showTab(it) }
        root.addView(navBar, LinearLayout.LayoutParams(-1, -2))
        // 3.2.13 (D1): Miuix 轨自带 Compose 导航栏，Material 的这根要藏起来，
        // 否则页面里会出现两根导航栏。
        syncNavBarVisibility()
        // ── 侧滑菜单：品牌区 / 导航 / 开关 / 通用设置（DrawerLayout，边缘滑动唤出）──
        val drawer = androidx.drawerlayout.widget.DrawerLayout(this)
        drawer.addView(root, androidx.drawerlayout.widget.DrawerLayout.LayoutParams(-1, -1))
        // M3 modal drawer 规范宽度 360dp；窄屏取 85%，避免盖满整个屏幕
        val dm = resources.displayMetrics
        val panelW = minOf((360 * dm.density).toInt(), (dm.widthPixels * 0.85f).toInt())
        val panelParams = androidx.drawerlayout.widget.DrawerLayout.LayoutParams(panelW, -1)
        panelParams.gravity = android.view.Gravity.START
        val drawerPanel = buildDrawer(pal, drawer)
        drawer.addView(drawerPanel, panelParams)
        setContentView(drawer)
        drawerRef = drawer
        panelRef = drawerPanel
        installBackHandler()
        // 便于无手势环境（如自动化）核对抽屉：am start ... --ez open_drawer true
        if (intent?.getBooleanExtra("open_drawer", false) == true) {
            drawer.postDelayed({ drawer.openDrawer(drawerPanel) }, 400)
        }
        // 状态栏/导航栏 insets：必须等 DecorView 创建后再设置（否则 getInsetsController NPE）
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val appr = if (pal.dark) 0 else (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            window.insetsController?.setSystemBarsAppearance(appr,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }

        recoverMonitoring()

        if (savedInstanceState == null) showTab(curTab)

        // 3.1.0: 首次启动展示使用引导。只在全新启动时判一次（savedInstanceState == null，
        // 旋转 / 重建不会重弹）；引导里「跳过」「开始使用」和左上返回都记为已读，
        // 之后不再自动弹，设置页最底部随时可重看。
        if (savedInstanceState == null &&
                !getSharedPreferences("cfg", Context.MODE_PRIVATE)
                        .getBoolean(OnboardingActivity.KEY_DONE, false)) {
            try { startActivity(Intent(this, OnboardingActivity::class.java)) } catch (_: Exception) { }
        }

        // Non-waking recovery only; battery exemptions require the user's consent.
        try { KeepAlive.ensure(this) } catch (_: Throwable) { }
        try { KeepAlive.requestWhitelistOnce(this) } catch (_: Throwable) { }
    }

    /** 从「主题设置」回来时若缩放改过，重建一次让新比例生效。 */
    private var appliedScale = -1f

    override fun onResume() {
        super.onResume()
        // 从「主题设置」回来时若缩放改过，重建一次让新 density 生效
        val cur = try {
            getSharedPreferences("cfg", MODE_PRIVATE).getFloat("ui_scale", 1f)
        } catch (_: Throwable) {
            1f
        }
        if (appliedScale < 0f) {
            appliedScale = cur
        } else if (cur != appliedScale) {
            appliedScale = cur
            recreate()
            return
        }
        recoverMonitoring()
    }

    private fun recoverMonitoring() {
        if (KeepAlive.enabled(this) && !HeadsetDetectService.RUNNING) {
            KeepAlive.wake(this)
            try { startService(Intent(this, HeadsetDetectService::class.java)) } catch (_: Exception) { }
        }
    }

    /** 官方 M3 切换：Fragment fade 过渡（内容动，底栏静止） */
    /**
     * 3.2.13: 切换界面风格后立即重建当前页。
     *
     * ## 为什么不能用 recreate()
     *
     * 用户实测反馈：「点击切换之后不会立即生效，要来回切换一下页面才会生效」。
     *
     * 根因在 onCreate 里这一行：
     *
     * ```kotlin
     * if (savedInstanceState == null) showTab(curTab)
     * ```
     *
     * `recreate()` 之后 `savedInstanceState != null`，于是 **showTab 根本不会被调用**，
     * 而是由 FragmentManager 依据保存的 state 恢复**旧主题的 Fragment**
     * （此刻仍是 MiuixHostFragment）。SP 里虽然已写成 MATERIAL，界面却不变
     * —— 必须手动切一次 tab 才会触发 showTab 重新分派。
     *
     * 顺带一提，3.2.13 那次「切回 Material 闪退」也出在 recreate 路径上
     * （FragmentManager 用 `getConstructor()` 重建，要求 Fragment 必须有无参构造）。
     *
     * ## 修法
     *
     * 直接 `showTab(curTab)`：内部 `ft.replace(containerId, f)` 会按**当前 SP**
     * 重新分派出对应主题的 Fragment 并替换现有 Fragment。
     * Activity 本身不重建 —— 底栏、状态栏配色都不受影响，切换更轻。
     */
    fun applyStyleSwitch() {
        // 3.2.13 (D1): 切主题时导航栏要跟着换实现 ——
        // Material 的 View 导航栏与 Miuix 的 Compose 导航栏二选一。
        syncNavBarVisibility()
        showTab(curTab)
    }

    /**
     * Material 导航栏只在 Material 轨显示。
     *
     * Miuix 轨的导航栏由 `MiuixNavBar` 画在页面的 Scaffold 里，
     * 这根 View 导航栏必须 `GONE`，否则一屏两根。
     */
    /**
     * 返回键：抽屉打开时先关抽屉。
     *
     * 必须走 OnBackPressedDispatcher —— Android 13+ 启用预测性返回后，
     * 覆盖 onBackPressed() 不再被调用（实测直接退出到桌面）。
     */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this,
                object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        val d = drawerRef
                        val pn = panelRef
                        if (d != null && pn != null && d.isDrawerOpen(pn)) {
                            d.closeDrawer(pn)
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                })
    }

    private fun syncNavBarVisibility() {
        navBar?.visibility = if (MiuixSurface.enabled(this)) View.GONE else View.VISIBLE
    }

    override fun onMiuixTab(id: Int) {
        if (id == curTab) return   // 重复点当前项不做无谓重建
        showTab(id)
    }

    private fun showTab(id: Int) {
        curTab = id
        syncNavBarVisibility()
        // 3.2.13: 双主题分派点（**全项目唯一**）。
        //
        // Material 轨一行未改，只是这里多一个 if：选 Miuix 时走 ui/miuix 下的
        // Compose 实现，否则走原 Fragment。两套并存，用户在设置页随时切换。
        // 删掉整个 ui/miuix 目录 + 这个 if 即可回到纯 Material。
        val miuix = MiuixSurface.enabled(this)
        val f: Fragment = when (id) {
            2 -> if (miuix) MiuixHostFragment.newInstance(MiuixHostFragment.Screen.SETTINGS)
                 else SettingsFragment()
            3 -> if (miuix) MiuixHostFragment.newInstance(MiuixHostFragment.Screen.ABOUT)
                 else AboutFragment()
            else -> if (miuix) MiuixHostFragment.newInstance(MiuixHostFragment.Screen.OVERVIEW)
                    else OverviewFragment()
        }
        val ft: FragmentTransaction = supportFragmentManager.beginTransaction()
        // alpha2.53: 对齐 org.lsposed.manager 的切页动效 ——
        // 进入 fadeIn + scaleIn(0.985)，320ms；退出快速淡出，由进入动画主导。
        ft.setCustomAnimations(R.anim.m3_page_in, R.anim.m3_page_out)
        ft.replace(containerId, f)
        ft.commit()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, curTab)
    }

    /**
     * alpha2.41.9: 后台隐藏语义修正。
     *
     * 旧实现在 onStop() 里无条件 finishAndRemoveTask()：只要离开主界面（进入「权限检测」
     * 二级页、跳系统授权页）就会把整个任务销毁，用户回来时应用「自己退出了」。
     * 现在只有 onUserLeaveHint（Home / 最近任务等用户主动离开）才隐藏，并且离开前确认
     * 本应用没有其他界面仍在前台，应用内跳转与授权流程完全不受影响。
     */
    private var userLeftHint = false

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        userLeftHint = true
    }

    override fun onRestart() {
        super.onRestart()
        userLeftHint = false
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) {
            userLeftHint = false
            return
        }
        if (!userLeftHint) return // 应用内跳转 / 打开系统页面：不隐藏
        userLeftHint = false
        if (visibleActivityCount > 1) return // 还有其他本应用界面可见：不隐藏
        if (!getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("bg_hide", false)) return
        window.decorView.postDelayed({
            if (visibleActivityCount == 0 && !isFinishing && !isChangingConfigurations) {
                finishAndRemoveTask()
            }
        }, BG_HIDE_RECHECK_MS)
    }

    companion object {
        private const val KEY_TAB = "fxxk_tab"
        private const val BG_HIDE_RECHECK_MS = 500L

        /** alpha2.41.9: 本应用当前可见（started 且未 stopped）的 Activity 数量 */
        @Volatile private var visibleActivityCount = 0
        @Volatile private var visibilityTrackingInstalled = false

        private fun installVisibilityTracking(app: Application) {
            if (visibilityTrackingInstalled) return
            visibilityTrackingInstalled = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    visibleActivityCount++
                }

                override fun onActivityStopped(activity: Activity) {
                    if (visibleActivityCount > 0) visibleActivityCount--
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
    }

    /**
     * 侧滑菜单内容：品牌区 / 导航 / 快捷开关 / 通用设置。
     *
     * 行沿用 M3Ui 的列表行（48dp 图标容器 + 标题），开关用官方 MaterialSwitch，
     * 与设置页同一套控件与配色，避免抽屉里出现另一套「长得不一样」的组件。
     */
    /**
     * 侧滑菜单（M3 modal navigation drawer）。
     *
     * 规范要点：宽度 360dp（窄屏取 85%）、行高 56dp、图标 24dp 无彩色底、
     * 选中态是 pill（secondaryContainer 语义），分组之间用小标题分隔。
     * 不再放主题开关 —— 那属于「设置 → 主题」，抽屉只做导航。
     */
    private fun buildDrawer(pal: ThemeUtil.Palette,
                            drawer: androidx.drawerlayout.widget.DrawerLayout): View {
        fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(pal.surface)
        panel.fitsSystemWindows = true
        panel.setPadding(0, px(12), 0, px(12))

        // 品牌区
        val name = TextView(this)
        name.text = "MoonEar"
        name.textSize = 22f
        name.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        name.setTextColor(pal.onSurface)
        name.setPadding(px(28), px(20), px(28), 0)
        panel.addView(name, LinearLayout.LayoutParams(-1, -2))

        val sub = TextView(this)
        sub.text = Lang.t("耳机控制助手", "Earbud control assistant")
        sub.textSize = 13f
        sub.setTextColor(pal.onVariant)
        sub.setPadding(px(28), px(2), px(28), px(16))
        panel.addView(sub, LinearLayout.LayoutParams(-1, -2))

        // ── 导航 ──
        panel.addView(drawerSection(pal, Lang.t("导航", "Navigation")))

        fun addItem(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.minimumHeight = px(56)
            row.setPadding(px(16), 0, px(16), 0)
            row.background = drawerRowBg(pal, selected)
            val ic = android.widget.ImageView(this)
            ic.setImageResource(icon)
            ic.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (selected) pal.onContainer else pal.onVariant)
            row.addView(ic, LinearLayout.LayoutParams(px(24), px(24)))
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 14f
            tv.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            tv.setTextColor(if (selected) pal.onContainer else pal.onSurface)
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.marginStart = px(12)
            row.addView(tv, lp)
            row.setOnClickListener { onClick() }
            val rlp = LinearLayout.LayoutParams(-1, -2)
            rlp.leftMargin = px(12); rlp.rightMargin = px(12); rlp.bottomMargin = px(2)
            panel.addView(row, rlp)
        }

        addItem(R.drawable.ic_home, Lang.t("概览", "Overview"), curTab == 1) {
            drawer.closeDrawers(); showTab(1)
        }
        addItem(R.drawable.ic_settings, Lang.t("设置", "Settings"), curTab == 2) {
            drawer.closeDrawers(); showTab(2)
        }
        addItem(R.drawable.ic_info, Lang.t("关于", "About"), curTab == 3) {
            drawer.closeDrawers(); showTab(3)
        }

        // 抽屉只做导航：不再重复「设置」页已有的入口（原「通用设置」与「设置」同指一页）
        panel.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }

    /** 抽屉分组小标题：14sp、onSurfaceVariant、左内边距与行文字对齐。 */
    private fun drawerSection(pal: ThemeUtil.Palette, text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        tv.setTextColor(pal.onVariant)
        val d = resources.displayMetrics.density
        tv.setPadding((28 * d).toInt(), (14 * d).toInt(), (28 * d).toInt(), (6 * d).toInt())
        return tv
    }

    /** 抽屉行背景：选中为 pill（容器色），未选中为透明 + 圆角涟漪。 */
    private fun drawerRowBg(pal: ThemeUtil.Palette, selected: Boolean):
            android.graphics.drawable.Drawable {
        val d = resources.displayMetrics.density
        val r = 28 * d
        val shape = android.graphics.drawable.GradientDrawable()
        shape.cornerRadius = r
        shape.setColor(if (selected) pal.container else android.graphics.Color.TRANSPARENT)
        val mask = android.graphics.drawable.GradientDrawable()
        mask.cornerRadius = r
        mask.setColor(android.graphics.Color.WHITE)
        return android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((pal.onSurface and 0x00FFFFFF) or 0x1F000000),
                shape, mask)
    }

    /** 供概览页标题栏的菜单键调用（系统手势导航会占用左边缘，单靠边缘滑动唤不出）。 */
    fun openDrawer() {
        val d = drawerRef ?: return
        val p = panelRef ?: return
        d.openDrawer(p)
    }
}
