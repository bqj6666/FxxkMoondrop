package com.fxxkmoondrop.secret

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

/**
 * alpha2.12: 官方单 Activity 架构 —— 三页 Fragment 切换，底部导航常驻（M3 官方 fade 仅作用于内容区）。
 */
class MainActivity : FragmentActivity() {

    private var curTab = 1 // 1=概览 2=设置 3=关于
    // 固定 container id：recreate 后 FragmentManager 按保存的 containerId 恢复 fragment；
    // 若用 View.generateViewId() 每次重建 ID 都变，恢复时找不到容器导致页面消失
    private val containerId = 0x00F0F1
    private var navBar: com.google.android.material.bottomnavigation.BottomNavigationView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    /** 官方 M3 切换：Fragment fade 过渡（内容动，底栏静止） */
    private fun showTab(id: Int) {
        curTab = id
        val f: Fragment = when (id) {
            2 -> SettingsFragment()
            3 -> AboutFragment()
            else -> OverviewFragment()
        }
        val ft: FragmentTransaction = supportFragmentManager.beginTransaction()
        ft.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
        ft.replace(containerId, f)
        ft.commit()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, curTab)
    }

    override fun onStop() {
        super.onStop()
        if (getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("bg_hide", false)) {
            finishAndRemoveTask()
        }
    }

    companion object {
        private const val KEY_TAB = "fxxk_tab"
    }
}
