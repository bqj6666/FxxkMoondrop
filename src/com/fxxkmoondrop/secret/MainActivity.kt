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
class MainActivity : FragmentActivity(), NavigationEventDispatcherOwner,
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
    private var navBar: com.google.android.material.bottomnavigation.BottomNavigationView? = null

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
        setContentView(root)
        // 状态栏/导航栏 insets：必须等 DecorView 创建后再设置（否则 getInsetsController NPE）
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val appr = if (pal.dark) 0 else (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            window.insetsController?.setSystemBarsAppearance(appr,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }

        if (getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("auto_service", true)
                && getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("enable", true)
                && !HeadsetDetectService.RUNNING) {
            HeadsetDetectService.RUNNING = true
            try { startService(Intent(this, HeadsetDetectService::class.java)) } catch (_: Exception) { }
        }

        if (savedInstanceState == null) showTab(curTab)

        // 3.1.0: 首次启动展示使用引导。只在全新启动时判一次（savedInstanceState == null，
        // 旋转 / 重建不会重弹）；引导里「跳过」「开始使用」和左上返回都记为已读，
        // 之后不再自动弹，设置页最底部随时可重看。
        if (savedInstanceState == null &&
                !getSharedPreferences("cfg", Context.MODE_PRIVATE)
                        .getBoolean(OnboardingActivity.KEY_DONE, false)) {
            try { startActivity(Intent(this, OnboardingActivity::class.java)) } catch (_: Exception) { }
        }

        // 3.0.5: 保活默认常开（不再有开关、不再需要 Root）。
        // 无 root 路径 = 开机自启 + 30s 看门狗 + 系统电池优化白名单弹窗（只主动问一次）；
        // 有 root 时 KeepAlive 内部再静默做一次 deviceidle/appops 增强。
        try { KeepAlive.ensure(this) } catch (_: Throwable) { }
        try { KeepAlive.requestWhitelistOnce(this) } catch (_: Throwable) { }
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
}
