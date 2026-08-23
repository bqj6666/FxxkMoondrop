package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * alpha2.4: "关于"页按官方 M3 样式重构：
 * 头部应用信息卡（图标 / 名称 / 版本）+ "项目"分组卡（GitHub / 作者 / 协助者）+ "说明"卡。
 */
class AboutActivity : Activity() {

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    private fun spacer(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    /** 版本号：alpha2.3 -> V2.3Alpha（与主页一致） */
    private fun verText(): String {
        var vn = "alpha2.3"
        try {
            vn = packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { }
        val core = if (vn.lowercase().startsWith("alpha")) vn.substring(5) else vn
        return "V" + core + "Alpha"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pal = ThemeUtil.Palette(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        root.addView(M3Ui.topBarTitle(this, pal, "关于"),
                LinearLayout.LayoutParams(-1, -2))
        root.addView(spacer(dp(4)))

        val sv = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(4), dp(16), dp(24))

        // ── 头部应用信息卡（官方 AboutHeader：图标容器 + 名称 + 版本）──
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.gravity = Gravity.CENTER_HORIZONTAL
        header.setPadding(dp(24), dp(30), dp(24), dp(26))
        val hbg = GradientDrawable()
        hbg.setColor(pal.card)
        hbg.setCornerRadius(dp(28).toFloat())
        header.background = hbg

        val iconWrap = LinearLayout(this)
        iconWrap.gravity = Gravity.CENTER
        val iwBg = GradientDrawable()
        iwBg.setColor(pal.container)
        iwBg.setCornerRadius(dp(26).toFloat())
        iconWrap.background = iwBg
        val iconView = ImageView(this)
        try {
            iconView.setImageDrawable(packageManager.getApplicationIcon(packageName))
        } catch (_: Exception) { }
        iconWrap.addView(iconView, LinearLayout.LayoutParams(dp(72), dp(72)))
        header.addView(iconWrap, LinearLayout.LayoutParams(dp(96), dp(96)))

        header.addView(spacer(dp(14)))
        val appTitle = TextView(this)
        appTitle.text = "FxxkMoondrop"
        appTitle.textSize = 26f
        appTitle.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        appTitle.setTextColor(pal.onSurface)
        header.addView(appTitle, LinearLayout.LayoutParams(-2, -2))

        header.addView(spacer(dp(6)))
        val ver = TextView(this)
        ver.text = verText()
        ver.textSize = 14f
        ver.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ver.setTextColor(pal.primary)
        header.addView(ver, LinearLayout.LayoutParams(-2, -2))

        header.addView(spacer(dp(8)))
        val tagline = TextView(this)
        tagline.text = "Moondrop 蓝牙耳机助手 · GAIA 直连"
        tagline.textSize = 12f
        tagline.setTextColor(pal.onVariant)
        tagline.alpha = 0.85f
        header.addView(tagline, LinearLayout.LayoutParams(-2, -2))
        box.addView(header, LinearLayout.LayoutParams(-1, -2))

        box.addView(spacer(dp(16)))
        box.addView(M3Ui.sectionTitle(this, pal, "项目"))

        // ── 项目分组卡：GitHub / 作者 / 协助者（官方 listRow 无描边组卡）──
        val rowGithub = M3Ui.listRow(this, pal, R.drawable.ic_log, "GitHub 仓库",
                "github.com/bqj6666/FxxkMoondrop",
                M3Ui.chevron(this, pal.onVariant)) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/bqj6666/FxxkMoondrop")))
            } catch (_: Exception) { }
        }
        val rowAuthor = M3Ui.listRow(this, pal, R.drawable.ic_info, "作者",
                "bqj6666", null, null)
        val rowHelper = M3Ui.listRow(this, pal, R.drawable.ic_headphones, "协助者",
                "Deepseek · Qwen · ChatGPT · Kimi", null, null)
        box.addView(M3Ui.groupCard(this, pal, rowGithub, rowAuthor, rowHelper),
                LinearLayout.LayoutParams(-1, -2))

        box.addView(spacer(dp(16)))
        box.addView(M3Ui.sectionTitle(this, pal, "说明"))

        // ── 说明卡 ──
        val desc = LinearLayout(this)
        desc.orientation = LinearLayout.VERTICAL
        desc.setPadding(dp(18), dp(16), dp(18), dp(16))
        val dg = GradientDrawable()
        dg.setColor(pal.card)
        dg.setCornerRadius(dp(20).toFloat())
        desc.background = dg
        val d1 = TextView(this)
        d1.text = "耳机连接时自动弹出 FastPair 卡片"
        d1.textSize = 15f
        d1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        d1.setTextColor(pal.onSurface)
        desc.addView(d1, LinearLayout.LayoutParams(-2, -2))
        val d2 = TextView(this)
        d2.text = "显示名称 / 电量 / MAC；通过 GAIA BLE 协议直连耳机读取状态" +
                "（电量、ANC、模式等），地址全动态发现、零硬编码。"
        d2.textSize = 13f
        d2.setTextColor(pal.onVariant)
        d2.setLineSpacing(dp(2).toFloat(), 1.2f)
        d2.setPadding(0, dp(8), 0, 0)
        desc.addView(d2, LinearLayout.LayoutParams(-2, -2))
        box.addView(desc, LinearLayout.LayoutParams(-1, -2))

        sv.addView(box)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(M3Ui.navBar(this, pal, 3) { id ->
                    if (id == 1) M3Ui.goPage(this, MainActivity::class.java)
                    else if (id == 2) M3Ui.goPage(this, SettingsActivity::class.java)
                },
                LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        // alpha2.11: 内容入场动画（底栏静止，仅内容区淡入上滑）
        M3Ui.contentEnter(this, sv)
        M3Ui.fitSystemBars(this, pal)
    }
}
