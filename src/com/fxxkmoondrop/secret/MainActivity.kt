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
    private var palRef: ThemeUtil.Palette? = null
    /** tab -> 抽屉行，用于刷新选中态（tag 1/2/3）。 */
    private val drawerRows = HashMap<Int, LinearLayout>()
    /** 抽屉 banner：用户自选图片存 filesDir/drawer_banner，缺省用主题渐变。 */
    private var bannerView: android.widget.ImageView? = null
    private var bannerScrim: View? = null
    private var bannerTitle: TextView? = null
    /** 抽屉里的当前状态卡（连接状态 / 降噪模式）。 */
    private var statusRow: LinearLayout? = null
    private var heroStatus: TextView? = null
    private var heroSub: TextView? = null
    /** 英雄卡右侧 API 徽章。 */
    private var heroBadge: TextView? = null
    /** 抽屉底部的「发现新版本」胶囊。 */
    private var updBadge: TextView? = null
    private var bannerSub: TextView? = null

    private val bannerPicker = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.File(filesDir, "drawer_banner").outputStream().use { out ->
                        input.copyTo(out)
                    }
                }
            }
            bannerView?.let { applyBanner(palRef ?: ThemeUtil.Palette(this)) }
        }
    }
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

        // 菜单键覆盖层：抽屉入口必须所有 tab 都能点到。
        // 之前只加在概览页，切到设置/关于就找不到入口 —— 这是"唤不出来"的直接原因。
        val contentWrap = FrameLayout(this)
        contentWrap.addView(content, FrameLayout.LayoutParams(-1, -1))
        val menuBtn = android.widget.ImageButton(this)
        menuBtn.setImageResource(R.drawable.ic_menu)
        menuBtn.imageTintList = android.content.res.ColorStateList.valueOf(pal.onSurface)
        menuBtn.background = M3Ui.pressRipple(this, null)
        menuBtn.contentDescription = Lang.t("打开菜单", "Open menu")
        menuBtn.setOnClickListener { openDrawer() }
        val sbhPx = resources.getIdentifier("status_bar_height", "dimen", "android")
                .let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
        val mlp = FrameLayout.LayoutParams(
                (48 * resources.displayMetrics.density).toInt(),
                (48 * resources.displayMetrics.density).toInt())
        mlp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        mlp.topMargin = sbhPx + (4 * resources.displayMetrics.density).toInt()
        mlp.leftMargin = (4 * resources.displayMetrics.density).toInt()
        contentWrap.addView(menuBtn, mlp)
        contentHost = contentWrap
        menuButton = menuBtn
        root.addView(contentWrap, LinearLayout.LayoutParams(-1, 0, 1f))
        navBar = M3Ui.navBar(this, pal, curTab) { showTab(it) }
        // 3.3.x: 底部导航栏移除 —— 导航统一走侧滑菜单（M3 抽屉式导航）。
        // 底部导航与抽屉同时存在属于两套导航并存，且底栏高度/指示器并不符合 Expressive 规范。
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
        drawer.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                refreshStatusCard()
            }
        })
        palRef = pal
        installBackHandler()
        maybeAutoCheckUpdate()
        applyNavMode(pal)

        // 系统手势导航把左边缘划动占为「返回」，DrawerLayout 收不到边缘滑动。
        // 用官方 systemGestureExclusionRects 把左侧 40dp 让给应用，抽屉边缘手势才生效。
        if (Build.VERSION.SDK_INT >= 29) {
            window.decorView.post {
                val excl = (40 * resources.displayMetrics.density).toInt()
                val h = resources.displayMetrics.heightPixels
                window.decorView.systemGestureExclusionRects =
                        listOf(android.graphics.Rect(0, 0, excl, h))
            }
        }
        // 便于无手势环境（如自动化）核对抽屉：am start ... --ez open_drawer true
        if (intent?.getBooleanExtra("open_drawer", false) == true) {
            // 读完立刻清掉：否则 Activity 因切语言/换主题重建时这个 intent 还在，
            // 抽屉会自己再弹一次（实测切语言后必现）。
            intent?.removeExtra("open_drawer")
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
    /** 抽屉状态卡：胶囊 chip 显示连接状态与降噪模式，点一下回概览。 */
    private fun buildStatusCard(pal: ThemeUtil.Palette): LinearLayout {
        val d = resources.displayMetrics.density
        fun p(v: Int) = (v * d).toInt()

        // 结构照搬概览页英雄卡：圆形图标 chip（44dp）+ 状态词 + 版本 + 右侧 API 徽章。
        // 底色与文字色全部取 Palette（primary / onPrimary），动态取色、AMOLED、深浅色都会跟着变。
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = android.view.Gravity.CENTER_VERTICAL
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = p(24).toFloat()
        bg.setColor(pal.primary)
        card.background = bg
        card.setPadding(p(16), p(14), p(16), p(14))

        val chip = android.widget.FrameLayout(this)
        val chipBg = android.graphics.drawable.GradientDrawable()
        chipBg.shape = android.graphics.drawable.GradientDrawable.OVAL
        chipBg.setColor((pal.onPrimary and 0x00FFFFFF) or 0x26000000)
        chip.background = chipBg
        val icon = android.widget.ImageView(this)
        icon.setImageResource(R.drawable.ic_check)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(pal.onPrimary)
        val ilp = android.widget.FrameLayout.LayoutParams(p(20), p(20))
        ilp.gravity = android.view.Gravity.CENTER
        chip.addView(icon, ilp)
        val clp = LinearLayout.LayoutParams(p(44), p(44))
        clp.marginEnd = p(14)
        card.addView(chip, clp)

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        heroStatus = TextView(this)
        heroStatus!!.textSize = 16f
        heroStatus!!.isSingleLine = true
        heroStatus!!.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        heroStatus!!.setTextColor(pal.onPrimary)
        texts.addView(heroStatus, LinearLayout.LayoutParams(-2, -2))
        heroSub = TextView(this)
        heroSub!!.textSize = 14f
        heroSub!!.isSingleLine = true
        heroSub!!.setTextColor((pal.onPrimary and 0x00FFFFFF) or 0xB3000000.toInt())
        texts.addView(heroSub, LinearLayout.LayoutParams(-2, -2))
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        heroBadge = TextView(this)
        heroBadge!!.textSize = 12f
        heroBadge!!.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        heroBadge!!.setTextColor(pal.primary)
        heroBadge!!.gravity = android.view.Gravity.CENTER
        heroBadge!!.setPadding(p(10), p(5), p(10), p(5))
        val bdg = android.graphics.drawable.GradientDrawable()
        bdg.cornerRadius = p(20).toFloat()
        bdg.setColor(pal.onPrimary)
        heroBadge!!.background = bdg
        card.addView(heroBadge, LinearLayout.LayoutParams(-2, -2))

        card.setOnClickListener {
            drawerRef?.closeDrawers()
            showTab(1)
        }
        return card
    }

    /** 刷新英雄卡文案（连接状态 / 版本 / API 级别）。 */
    private fun refreshStatusCard() {
        val conn = runCatching { GaiaBleClient.getInstance().isConnected() }.getOrDefault(false)
        heroStatus?.text = if (conn) Lang.t("已连接", "Connected") else Lang.t("未连接", "Disconnected")
        heroSub?.text = runCatching {
            "V" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "").removePrefix("alpha")
        }.getOrDefault("")
        // 徽章显示的是**当前链路/编解码器**（与概览页一致），不是系统 API 级别。
        // 未连接时隐藏：否则和左侧状态词「未连接」重复两遍。
        val link = linkTypeLabel()
        if (link == null) {
            heroBadge?.visibility = View.GONE
        } else {
            heroBadge?.visibility = View.VISIBLE
            heroBadge?.text = badgeText()
            refreshCodecBadge()
        }
    }

    /** 编解码器缓存（dumpsys 查询是百毫秒级，5 秒内不重复查）。 */
    private var codecLabel: String? = null
    private var codecQuerying = false
    private var lastCodecQueryMs = 0L

    /** 徽章文案：编解码器 > 链路类型 > 未连接。 */
    private fun badgeText(): String {
        val link = linkTypeLabel() ?: return Lang.t("未连接", "Not connected")
        codecLabel?.let { if (it.isNotEmpty()) return it }
        return link
    }

    /** 链路类型：LE Audio / ACL，无链路返回 null。 */
    private fun linkTypeLabel(): String? {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val types = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
            when {
                Build.VERSION.SDK_INT >= 31 &&
                        (android.media.AudioDeviceInfo.TYPE_BLE_HEADSET in types ||
                                android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER in types) -> "LE Audio"
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types ||
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types -> "ACL"
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 经 Root 通道读蓝牙栈的当前编解码器（系统 API 是 @SystemApi，普通应用拿不到）。 */
    private fun refreshCodecBadge() {
        if (linkTypeLabel() == null) {
            codecLabel = null
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (codecQuerying || now - lastCodecQueryMs < 5000L) return
        codecQuerying = true
        lastCodecQueryMs = now
        Thread {
            val out = runCatching { RootShell.exec("dumpsys bluetooth_manager | grep -m1 'mCodecConfig:'") }
                    .getOrNull()
            val name = Regex("codecName:([A-Za-z0-9 ]+)").find(out ?: "")
                    ?.groupValues?.get(1)?.trim()
            runOnUiThread {
                codecQuerying = false
                if (!name.isNullOrEmpty()) {
                    codecLabel = name
                    heroBadge?.text = badgeText()
                }
            }
        }.start()
    }

    /** banner：有自定义图片就显示（裁成胶囊圆角），没有就保持透明 —— 不预设任何图片。 */
    private fun applyBanner(pal: ThemeUtil.Palette) {
        val v = bannerView ?: return
        val f = java.io.File(filesDir, "drawer_banner")
        val bmp = if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
        if (bmp != null) {
            v.colorFilter = null
            v.alpha = 1f
            if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(null)
            v.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            v.setImageBitmap(bmp)
            bannerScrim?.visibility = View.VISIBLE
            // 按图片平均亮度自动取文字色：暗图用白字、亮图用近黑，保证对比度
            val lum = averageLuminance(bmp)
            val fg = if (lum < 140) android.graphics.Color.WHITE else 0xFF1B1B1F.toInt()
            bannerTitle?.setTextColor(fg)
            bannerSub?.setTextColor((fg and 0x00FFFFFF) or 0xCC000000.toInt())
        } else {
            // 默认完全透明：不加任何图片/滤镜/描边，标题用正常前景色
            v.colorFilter = null
            v.alpha = 1f
            if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(null)
            v.setImageDrawable(null)
            bannerScrim?.visibility = View.GONE
            bannerTitle?.setTextColor(pal.onSurface)
            bannerSub?.setTextColor(pal.onVariant)
        }
    }


    /** 采样 5×5 网格的平均亮度（0-255），决定 banner 文字用白还是近黑。 */
    private fun averageLuminance(bmp: android.graphics.Bitmap): Double {
        var sum = 0.0
        var n = 0
        for (i in 1..5) for (j in 1..5) {
            val x = ((bmp.width - 1) * i / 6f).toInt().coerceIn(0, bmp.width - 1)
            val y = ((bmp.height - 1) * j / 6f).toInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(x, y)
            sum += 0.299 * android.graphics.Color.red(c) +
                    0.587 * android.graphics.Color.green(c) +
                    0.114 * android.graphics.Color.blue(c)
            n++
        }
        return if (n == 0) 128.0 else sum / n
    }

    /** banner 换图／恢复默认：走全应用统一的 M3 弹窗。 */
    private fun showBannerDialog() {
        val pal = palRef ?: ThemeUtil.Palette(this)
        val (dlg, body) = M3Ui.materialDialog(this, pal.primary, pal.card)
        val title = TextView(this)
        title.text = Lang.t("抽屉背景", "Drawer banner")
        title.textSize = 20f
        title.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        title.setTextColor(pal.onSurface)
        body.addView(title, LinearLayout.LayoutParams(-1, -2))
        val hint = TextView(this)
        hint.text = Lang.t("选择一张图片作为抽屉顶部的背景", "Pick an image for the drawer header")
        hint.textSize = 13f
        hint.setTextColor(pal.onVariant)
        val hlp = LinearLayout.LayoutParams(-1, -2)
        hlp.topMargin = (4 * resources.displayMetrics.density).toInt()
        hlp.bottomMargin = (12 * resources.displayMetrics.density).toInt()
        body.addView(hint, hlp)

        fun action(label: String, onClick: () -> Unit) {
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 16f
            tv.setTextColor(pal.primary)
            val d = resources.displayMetrics.density
            tv.setPadding((12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt())
            tv.background = M3Ui.pressRipple(this, null)
            tv.setOnClickListener { dlg.dismiss(); onClick() }
            body.addView(tv, LinearLayout.LayoutParams(-1, -2))
        }
        action(Lang.t("选择图片", "Choose image")) {
            runCatching { bannerPicker.launch("image/*") }
        }
        action(Lang.t("恢复默认", "Use default")) {
            runCatching { java.io.File(filesDir, "drawer_banner").delete() }
            bannerView?.let { applyBanner(palRef ?: ThemeUtil.Palette(this)) }
        }
        dlg.show()
    }

    // ── 更新检查 ─────────────────────────────────────────────
    // 轻量策略：只在应用被打开时（MainActivity 创建）按频率静默查一次，
    // 不注册后台任务、不常驻服务；联网总开关沿用 UpdateChecker.isEnabled（默认关）。

    private fun updPrefs() = getSharedPreferences("cfg", MODE_PRIVATE)

    /** 抽屉底部胶囊：有新版才显示版本号。 */
    private fun refreshUpdateBadge() {
        val b = updBadge ?: return
        val tag = updPrefs().getString("update_latest_tag", "") ?: ""
        if (tag.isNotEmpty() && UpdateChecker.isEnabled(this)) {
            b.text = Lang.t("发现新版本 ", "New version ") + tag
            b.visibility = View.VISIBLE
        } else {
            b.visibility = View.GONE
        }
    }

    /** 启动时按频率静默检查一次（距上次不足间隔则只刷新胶囊）。 */
    private fun maybeAutoCheckUpdate() {
        if (!UpdateChecker.isEnabled(this)) return
        val sp = updPrefs()
        if (!sp.getBoolean("update_auto", true)) return
        val days = sp.getInt("update_interval_days", 1).coerceAtLeast(1)
        val last = sp.getLong("update_last_at", 0L)
        val now = System.currentTimeMillis()
        if (now - last < days * 24L * 60L * 60L * 1000L) {
            refreshUpdateBadge()
            return
        }
        sp.edit().putLong("update_last_at", now).apply()
        Thread {
            val res = runCatching { UpdateChecker.check(applicationContext, false) }.getOrNull()
            val tag = (res as? UpdateChecker.Result.Available)?.info?.tag ?: ""
            updPrefs().edit().putString("update_latest_tag", tag).apply()
            runOnUiThread { refreshUpdateBadge() }
        }.start()
    }

    /** 打开发布页。 */
    private fun openUpdatePage() {
        runCatching {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/bqj6666/FxxkMoondrop/releases")))
        }
    }

    // ── 导航方式：抽屉 / 悬浮导航栏 ──────────────────────────────
    // nav_mode = 0 抽屉（默认），1 悬浮胶囊导航栏；二选一在设置页「外观」里切换。
    private var contentHost: android.widget.FrameLayout? = null
    private var menuButton: View? = null
    private var floatNav: View? = null

    private fun navMode(): Int = getSharedPreferences("cfg", MODE_PRIVATE).getInt("nav_mode", 0)

    /** 按当前导航方式切换：抽屉模式下显示菜单键并解锁抽屉，悬浮模式下反之。 */
    private fun applyNavMode(pal: ThemeUtil.Palette) {
        val floating = navMode() == 1
        menuButton?.visibility = if (floating) View.GONE else View.VISIBLE
        drawerRef?.setDrawerLockMode(if (floating)
            androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        else androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
        floatNav?.let { contentHost?.removeView(it) }
        floatNav = null
        if (!floating) return
        val bar = buildFloatingNav(pal)
        floatNav = bar
        val lp = android.widget.FrameLayout.LayoutParams(-2, -2)
        lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        lp.bottomMargin = (24 * resources.displayMetrics.density).toInt()
        contentHost?.addView(bar, lp)
    }

    /** 悬浮胶囊导航栏：三个入口，选中项用容器色 pill。 */
    private fun buildFloatingNav(pal: ThemeUtil.Palette): View {
        val d = resources.displayMetrics.density
        fun p(v: Int) = (v * d).toInt()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER
        row.setPadding(p(8), p(8), p(8), p(8))
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = p(28).toFloat()
        bg.setColor(pal.card)
        bg.setStroke(p(1), pal.cardStroke)
        row.background = bg

        fun addItem(tab: Int, icon: Int, label: String) {
            val sel = tab == curTab
            val cell = android.widget.FrameLayout(this)
            val cellBg = android.graphics.drawable.GradientDrawable()
            cellBg.cornerRadius = p(20).toFloat()
            cellBg.setColor(if (sel) pal.secondaryContainer else android.graphics.Color.TRANSPARENT)
            cell.background = cellBg
            val ic = android.widget.ImageView(this)
            ic.setImageResource(icon)
            ic.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (sel) pal.onSecondaryContainer else pal.onVariant)
            val ilp = android.widget.FrameLayout.LayoutParams(p(24), p(24))
            ilp.gravity = android.view.Gravity.CENTER
            cell.addView(ic, ilp)
            cell.contentDescription = label
            cell.setOnClickListener {
                if (navMode() == 1) {
                    showTab(tab)
                    applyNavMode(palRef ?: ThemeUtil.Palette(this))
                }
            }
            val clp = LinearLayout.LayoutParams(p(64), p(48))
            clp.leftMargin = p(2); clp.rightMargin = p(2)
            row.addView(cell, clp)
        }

        addItem(1, R.drawable.ic_home, Lang.t("概览", "Overview"))
        addItem(2, R.drawable.ic_settings, Lang.t("设置", "Settings"))
        addItem(3, R.drawable.ic_info, Lang.t("关于", "About"))
        return row
    }

    /** 左边缘手势排除：DrawerLayout 的边缘滑动要能用，必须让系统返回手势让位。 */

    private fun applyGestureExclusion() {
        if (Build.VERSION.SDK_INT < 29) return
        val dv = window?.decorView ?: return
        val d = resources.displayMetrics.density
        val excl = (40 * d).toInt()
        val h = resources.displayMetrics.heightPixels
        // 系统限制：每条屏幕边缘最多排除 200dp，且必须贴着边缘。
        // 之前给的是整屏高（≈880dp），会被系统直接忽略 —— 抽屉边缘手势因此一直不生效。
        val half = (100 * d).toInt()
        val top = (h / 2 - half).coerceAtLeast(0)
        val bottom = (h / 2 + half).coerceAtMost(h)
        dv.systemGestureExclusionRects = listOf(android.graphics.Rect(0, top, excl, bottom))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyGestureExclusion()
    }

    /** 全屏「从左往右划」即可唤出抽屉：不要求必须从边缘起手。 */
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeArmed = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val d = drawerRef
        val pn = panelRef
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX
                swipeDownY = ev.rawY
                swipeArmed = true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (swipeArmed && d != null && pn != null && !d.isDrawerOpen(pn)) {
                    val dx = ev.rawX - swipeDownX
                    val dy = ev.rawY - swipeDownY
                    val threshold = 64 * resources.displayMetrics.density
                    if (dx > threshold && dx > 2 * Math.abs(dy)) {
                        swipeArmed = false
                        openDrawer()
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> swipeArmed = false
        }
        return super.dispatchTouchEvent(ev)
    }

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
        runCatching { refreshDrawerSelection() }
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

        drawerRows.clear()
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        // Expressive modal drawer：面板右缘 16dp 圆角（左缘贴屏幕边不加圆角）
        val panelBg = android.graphics.drawable.GradientDrawable()
        panelBg.setColor(pal.surface)
        val cr = px(16).toFloat()
        panelBg.cornerRadii = floatArrayOf(0f, 0f, cr, cr, cr, cr, 0f, 0f)
        panel.background = panelBg
        // 显式让出状态栏：banner 顶到屏幕外会被状态栏／通知横幅压掉一截
        val sbhDrawer = resources.getIdentifier("status_bar_height", "dimen", "android")
                .let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
        panel.setPadding(0, sbhDrawer + px(8), 0, px(12))

        // 品牌区：内缩的独立胶囊卡（四角全圆），默认透明；有图时裁圆角并按图取对比色文字
        val bannerH = px(120)
        val header = android.widget.FrameLayout(this)
        val round = android.graphics.drawable.GradientDrawable()
        round.cornerRadius = px(28).toFloat()
        round.setColor(android.graphics.Color.TRANSPARENT)
        header.background = round
        header.clipToOutline = true

        val bannerImg = android.widget.ImageView(this)
        bannerImg.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        header.addView(bannerImg, android.widget.FrameLayout.LayoutParams(-1, -1))
        bannerView = bannerImg

        // 只在有图时压暗，保证文字可读；没图时保持透明
        val scrim = View(this)
        scrim.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xB3000000.toInt(), 0x00000000))
        scrim.visibility = View.GONE
        header.addView(scrim, android.widget.FrameLayout.LayoutParams(-1, -1))
        bannerScrim = scrim

        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        titleBox.gravity = android.view.Gravity.BOTTOM
        titleBox.setPadding(px(20), 0, px(20), px(16))
        val name = TextView(this)
        name.text = "MoonEar"
        name.textSize = 22f
        name.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        titleBox.addView(name, LinearLayout.LayoutParams(-2, -2))
        val sub = TextView(this)
        sub.text = Lang.t("耳机控制助手", "Earbud control assistant")
        sub.textSize = 12f
        titleBox.addView(sub, LinearLayout.LayoutParams(-2, -2))
        header.addView(titleBox, android.widget.FrameLayout.LayoutParams(-1, -1,
                android.view.Gravity.BOTTOM))
        bannerTitle = name
        bannerSub = sub

        header.setOnClickListener { showBannerDialog() }
        applyBanner(pal)
        val hLp = LinearLayout.LayoutParams(-1, bannerH)
        hLp.leftMargin = px(12); hLp.rightMargin = px(12)
        panel.addView(header, hLp)

        // 当前状态卡（原概览页英雄卡的位置）：banner 与导航之间
        statusRow = buildStatusCard(pal)
        val scLp = LinearLayout.LayoutParams(-1, -2)
        scLp.leftMargin = px(12); scLp.rightMargin = px(12)
        scLp.topMargin = px(8); scLp.bottomMargin = px(4)
        panel.addView(statusRow, scLp)
        refreshStatusCard()

        // ── 导航 ──
        panel.addView(drawerSection(pal, Lang.t("导航", "Navigation")))

        fun addItem(tab: Int, icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.minimumHeight = px(56)
            row.setPadding(px(16), 0, px(16), 0)
            row.background = drawerRowBg(pal, selected)
            val ic = android.widget.ImageView(this)
            ic.setImageResource(icon)
            ic.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (selected) pal.onSecondaryContainer else pal.onVariant)
            row.addView(ic, LinearLayout.LayoutParams(px(24), px(24)))
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 14f
            tv.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            tv.setTextColor(if (selected) pal.onSecondaryContainer else pal.onSurface)
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.marginStart = px(12)
            row.addView(tv, lp)
            row.setOnClickListener { onClick() }
            val rlp = LinearLayout.LayoutParams(-1, -2)
            rlp.leftMargin = px(12); rlp.rightMargin = px(12); rlp.bottomMargin = px(2)
            panel.addView(row, rlp)
            drawerRows[tab] = row
        }

        addItem(1, R.drawable.ic_home, Lang.t("概览", "Overview"), curTab == 1) {
            drawer.closeDrawers(); showTab(1)
        }
        addItem(2, R.drawable.ic_settings, Lang.t("设置", "Settings"), curTab == 2) {
            drawer.closeDrawers(); showTab(2)
        }
        addItem(3, R.drawable.ic_info, Lang.t("关于", "About"), curTab == 3) {
            drawer.closeDrawers(); showTab(3)
        }

        // 抽屉只做导航：不再重复「设置」页已有的入口（原「通用设置」与「设置」同指一页）
        panel.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        // ── 底部：更新入口 + 有新版时的胶囊提示 ──
        updBadge = TextView(this)
        updBadge!!.textSize = 13f
        updBadge!!.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        updBadge!!.setTextColor(pal.onSecondaryContainer)
        updBadge!!.gravity = android.view.Gravity.CENTER
        updBadge!!.setPadding(px(14), px(8), px(14), px(8))
        val ubg = android.graphics.drawable.GradientDrawable()
        ubg.cornerRadius = px(18).toFloat()
        ubg.setColor(pal.secondaryContainer)
        updBadge!!.background = ubg
        updBadge!!.visibility = View.GONE
        updBadge!!.setOnClickListener { openUpdatePage() }
        val ubLp = LinearLayout.LayoutParams(-2, -2)
        ubLp.leftMargin = px(28); ubLp.rightMargin = px(28); ubLp.bottomMargin = px(8)
        ubLp.gravity = android.view.Gravity.START
        panel.addView(updBadge, ubLp)

        // 抽屉里只放「有新版」提示，具体设置项在设置页的更新栏
        refreshUpdateBadge()
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
        shape.setColor(if (selected) pal.secondaryContainer else android.graphics.Color.TRANSPARENT)
        val mask = android.graphics.drawable.GradientDrawable()
        mask.cornerRadius = r
        mask.setColor(android.graphics.Color.WHITE)
        return android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((pal.onSurface and 0x00FFFFFF) or 0x1F000000),
                shape, mask)
    }

    /**
     * 只更新抽屉里各行的选中样式，**不重建 panel**。
     * 重建会把 DrawerLayout 正在跟踪的抽屉视图换掉，导致切换页面后抽屉关不干净。
     */
    private fun refreshDrawerSelection() {
        val p = palRef ?: return
        for ((tab, row) in drawerRows) {
            val sel = tab == curTab
            row.background = drawerRowBg(p, sel)
            (row.getChildAt(0) as? android.widget.ImageView)?.imageTintList =
                    android.content.res.ColorStateList.valueOf(
                            if (sel) p.onSecondaryContainer else p.onVariant)
            (row.getChildAt(1) as? TextView)?.setTextColor(
                    if (sel) p.onSecondaryContainer else p.onSurface)
        }
    }

    /** 供概览页标题栏的菜单键调用（系统手势导航会占用左边缘，单靠边缘滑动唤不出）。 */
    fun openDrawer() {
        val d = drawerRef ?: return
        val p = panelRef ?: return
        d.openDrawer(p)
    }
}
