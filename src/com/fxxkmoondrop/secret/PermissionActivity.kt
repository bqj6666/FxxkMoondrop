package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

/**
 * alpha2.1: 权限检测 —— 官方 M3 二级页（TopBar + tonal 状态头 + 官方分组卡列表）。
 * 检查蓝牙/通知/悬浮窗/电池白名单/Root/FastPairHook/GAIA 直连，缺失项可点击跳转修复。
 */
class PermissionActivity : Activity() {

    private lateinit var pal: ThemeUtil.Palette
    private var statusBarH = 0
    private lateinit var listBox: LinearLayout
    private lateinit var headIcon: ImageView
    private lateinit var headTitle: TextView
    private lateinit var headSub: TextView
    private lateinit var progressRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        pal = ThemeUtil.Palette(this)

        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(pal.surface))
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) statusBarH = resources.getDimensionPixelSize(resId)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        root.setPadding(dp(16), statusBarH + dp(10), dp(16), dp(24))

        // ── 官方 Top Bar（64dp MaterialToolbar + 返回箭头）──
        root.addView(M3Ui.topBar(this, pal, Lang.t(this, "权限检测", "Permission Check")) { finish() }, LinearLayout.LayoutParams(-1, -2))
        root.addView(spacer(dp(8)))

        // ── 官方 tonal 状态头：图标容器(44dp 圆角方) + 标题 + 副标题 ──
        val headCard = LinearLayout(this)
        headCard.orientation = LinearLayout.HORIZONTAL
        headCard.gravity = Gravity.CENTER_VERTICAL
        val headBg = GradientDrawable()
        headBg.setColor(pal.container)
        headBg.setCornerRadius(dp(20).toFloat())
        headCard.background = headBg
        headCard.setPadding(dp(16), dp(14), dp(16), dp(14))

        val iconBox = FrameLayout(this)
        val ibg = GradientDrawable()
        ibg.setCornerRadius(dp(16).toFloat())
        ibg.setColor((pal.onContainer and 0x00FFFFFF) or 0x14000000)
        iconBox.background = ibg
        headIcon = ImageView(this)
        headIcon.setImageResource(R.drawable.ic_refresh)
        headIcon.imageTintList = ColorStateList.valueOf(pal.onContainer)
        val ilp = FrameLayout.LayoutParams(dp(24), dp(24))
        ilp.gravity = Gravity.CENTER
        iconBox.addView(headIcon, ilp)
        val iBlp = LinearLayout.LayoutParams(dp(44), dp(44))
        iBlp.marginEnd = dp(14)
        headCard.addView(iconBox, iBlp)

        val hTxt = LinearLayout(this)
        hTxt.orientation = LinearLayout.VERTICAL
        headTitle = TextView(this)
        headTitle.textSize = 16f
        headTitle.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        headTitle.setTextColor(pal.onSurface)
        hTxt.addView(headTitle, LinearLayout.LayoutParams(-2, -2))
        headSub = TextView(this)
        headSub.textSize = 12f
        headSub.setTextColor((pal.onSurface and 0x00FFFFFF) or 0x99000000.toInt())
        headSub.setPadding(0, dp(2), 0, 0)
        hTxt.addView(headSub, LinearLayout.LayoutParams(-2, -2))
        headCard.addView(hTxt, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(headCard, LinearLayout.LayoutParams(-1, -2))
        root.addView(spacer(dp(8)))

        // ── 检查中提示 ──
        progressRow = LinearLayout(this)
        progressRow.gravity = Gravity.CENTER_HORIZONTAL
        val prog = TextView(this)
        prog.text = Lang.t(this, "正在检查…", "Checking…")
        prog.textSize = 13f
        prog.setTextColor(pal.onVariant)
        progressRow.addView(prog, LinearLayout.LayoutParams(-2, -2))
        root.addView(progressRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(spacer(dp(8)))

        // ── 权限列表（官方分组卡）──
        val sv = ScrollView(this)
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        sv.addView(listBox, FrameLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        // ── 底部：全宽重新检查（官方 Filled 按钮）──
        val btnWrap = LinearLayout(this)
        val refresh = M3Ui.filledButton(this, pal, Lang.t(this, "重新检查", "Re-check")) { startCheck() }
        btnWrap.addView(refresh, LinearLayout.LayoutParams(-1, -2))
        root.addView(btnWrap, LinearLayout.LayoutParams(-1, -2))
        root.addView(spacer(dp(6)))

        setContentView(root)

        window.statusBarColor = pal.surface
        window.navigationBarColor = pal.surface
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController
            c?.setSystemBarsAppearance(
                    if (pal.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        }

        startCheck()
    }

    /** 后台检查权限，完成后刷新 UI（不阻塞主线程） */
    private fun startCheck() {
        headTitle.text = Lang.t(this, "正在检查…", "Checking…")
        headSub.text = Lang.t(this, "设备权限与运行环境", "Device permissions & runtime")
        headIcon.setImageResource(R.drawable.ic_refresh)
        headIcon.imageTintList = ColorStateList.valueOf(pal.onContainer)
        progressRow.visibility = View.VISIBLE
        listBox.removeAllViews()
        val act = this
        Thread {
            val items = PermissionChecker.checkAll(act)
            act.runOnUiThread {
                progressRow.visibility = View.GONE
                applyResults(items)
            }
        }.start()
    }

    private fun applyResults(items: List<PermissionChecker.Item>) {
        val missing = PermissionChecker.countMissing(items)
        if (missing == 0) {
            headTitle.text = Lang.t(this, "所有必要权限均已就绪", "All required permissions ready")
            headSub.text = Lang.t(this, "系统运行环境正常", "System environment OK")
            headIcon.setImageResource(R.drawable.ic_check)
            headIcon.imageTintList = ColorStateList.valueOf(pal.green)
        } else {
            headTitle.text = Lang.tf("发现 %d 项权限缺失", "%d missing permission(s)", missing)
            headSub.text = Lang.t(this, "点击缺失项可直接修复", "Tap missing items to fix")
            headIcon.setImageResource(R.drawable.ic_warning)
            headIcon.imageTintList = ColorStateList.valueOf(pal.primary)
        }
        listBox.removeAllViews()

        val rows = ArrayList<View>()
        for (it in items) rows.add(makePermRow(it))
        listBox.addView(M3Ui.groupCard(this, pal, *rows.toTypedArray()),
                LinearLayout.LayoutParams(-1, -2))
    }

    /** 官方权限行：40dp 状态圆 + 名称/详情 + 缺失可修复 chevron + 官方涟漪 */
    private fun makePermRow(item: PermissionChecker.Item): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(12), dp(14), dp(12))

        // 状态圆点（40dp OVAL：ok=绿 14% 底 + 勾，缺失=红 14% 底 + 叉）
        val dotBox = FrameLayout(this)
        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        dot.setColor(if (item.ok) 0x1422B573 else 0x14E53935)
        dotBox.background = dot
        val dotIc = ImageView(this)
        dotIc.setImageResource(if (item.ok) R.drawable.ic_check else R.drawable.ic_close)
        dotIc.imageTintList = ColorStateList.valueOf(if (item.ok) pal.green else pal.red)
        val dilp = FrameLayout.LayoutParams(dp(20), dp(20))
        dilp.gravity = Gravity.CENTER
        dotBox.addView(dotIc, dilp)
        val dBlp = LinearLayout.LayoutParams(dp(40), dp(40))
        dBlp.marginEnd = dp(14)
        row.addView(dotBox, dBlp)

        // 名称 + 详情
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = if (item.ok) item.name
        else (if (item.action == PermissionChecker.ACTION_NONE) item.name + Lang.t(this, "（需手动处理）", " (manual)") else item.name)
        name.textSize = 16f
        name.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        name.setTextColor(pal.onSurface)
        col.addView(name, LinearLayout.LayoutParams(-2, -2))
        if (item.detail.isNotEmpty()) {
            val detail = TextView(this)
            detail.text = item.detail
            detail.textSize = 12f
            detail.setTextColor(pal.onVariant)
            detail.setPadding(0, dp(2), 0, 0)
            col.addView(detail, LinearLayout.LayoutParams(-2, -2))
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        // 缺失且可修复 → chevron + 涟漪 + 点击跳转
        if (!item.ok && item.action != PermissionChecker.ACTION_NONE) {
            val ch = M3Ui.chevron(this, pal.onVariant)
            val clp = LinearLayout.LayoutParams(dp(16), dp(16))
            clp.marginStart = dp(10)
            row.addView(ch, clp)
            val bg = GradientDrawable()
            bg.setColor(android.graphics.Color.TRANSPARENT)
            row.background = RippleDrawable(
                    ColorStateList.valueOf(if (pal.dark) 0x33FFFFFF else 0x22000000), bg, null)
            row.setOnClickListener { fixPermission(item) }
        }
        return row
    }

    /** 缺失项跳转修复（与 alpha1.34 行为一致） */
    private fun fixPermission(it: PermissionChecker.Item) {
        try {
            when (it.action) {
                PermissionChecker.ACTION_RUNTIME -> {
                    if (it.requestCode == 1) {
                        requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 1)
                    } else if (it.requestCode == 2) {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
                    }
                }
                PermissionChecker.ACTION_OVERLAY -> startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")))
                PermissionChecker.ACTION_BATTERY -> startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                else -> toast(it.detail)
            }
        } catch (e: Exception) {
            toast(Lang.t(this, "跳转失败: ", "Open failed: ") + e.message)
        }
    }

    /** 从设置页返回后自动重新检查 */
    override fun onResume() {
        super.onResume()
        if (::listBox.isInitialized) startCheck()
    }

    // ── UI 辅助 ──
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun spacer(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
