package com.fxxkmoondrop.secret

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 主题设置（二级页）。
 *
 * 对齐 LSPosed「主题设置」的信息层级：主题 / 强调色 / 调色规范 / 调色板风格 / 界面缩放
 * 全部收在这一页，设置页只留一个入口 —— 避免外观分组被七八行下拉撑爆。
 */
class ThemeSettingsActivity : BaseActivity() {

    private lateinit var pal: ThemeUtil.Palette
    private lateinit var body: LinearLayout

    /** 首次构建走分层入场，之后的 rebirth 只做整体淡入。 */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = ThemeUtil.Palette(this)
        val page = M3Ui.largeHeaderPageWithBack(this, pal,
                Lang.t("主题设置", "Theme settings")) { finish() }
        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        // 与设置页同一套版心：左右 12dp、上下 16/24dp。
        // 不设的话卡片会直接贴到屏幕边缘，浅色下看不出、深色下像被切掉一条。
        body.setPadding(dp(12), dp(16), dp(12), dp(24))
        rebuild()
        page.sv.addView(body, LinearLayout.LayoutParams(-1, -2))
        setContentView(page.container)
        M3Ui.fitSystemBars(this, pal)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun sp() = getSharedPreferences("cfg", MODE_PRIVATE)

    private fun rebuild() {
        body.removeAllViews()

        // ── 主题 ─────────────────────────────────────────────
        body.addView(M3Ui.sectionTitle(this, pal, Lang.t("主题", "Theme")))
        val modeRow = M3Ui.dropdownRow(this, pal,
                Lang.t("主题", "Theme"),
                "",
                arrayOf(Lang.t("跟随系统", "System"), Lang.t("浅色", "Light"), Lang.t("深色", "Dark")),
                ThemeUtil.themeMode(this)) { mi ->
            sp().edit().putInt("theme_mode", mi).commit()
            recreate()
        }

        // 强调色（种子色）：只在跟固定主色时才显示。
        // 始终建出来、用 visibility 控制，才能在开关切换时走 reveal 的淡入/淡出动画
        // （条件创建 = 只能"啪"地出现或消失，没有过渡）。
        val dynOn = sp().getBoolean("dynamic_color", true)
        val seedRow = buildSeedRow()
        seedRow.visibility = if (dynOn) View.GONE else View.VISIBLE

        body.addView(M3Ui.groupCard(this, pal, modeRow, seedRow), LinearLayout.LayoutParams(-1, -2))

        // ── 配色 ─────────────────────────────────────────────
        body.addView(M3Ui.sectionTitle(this, pal, Lang.t("配色", "Colors")))
        val dynRow = M3Ui.switchRow(this, pal,
                Lang.t("动态取色", "Dynamic color"),
                "",
                themeSwitch("dynamic_color", true, Lang.t("动态取色", "Dynamic color")) { on ->
                    // 立刻播强调色行的显隐动画；palette 重建延后到开关动画结束
                    M3Ui.reveal(seedRow, !on)
                })
        val amoledRow = M3Ui.switchRow(this, pal,
                Lang.t("AMOLED 纯黑", "AMOLED pure black"),
                "",
                themeSwitch("amoled", false, "AMOLED"))
        // 色彩规范：只换 M3 语义角色的 tone 表（颜色），不影响形状与动效。
        val stdRow = M3Ui.dropdownRow(this, pal,
                Lang.t("色彩规范", "Color spec"),
                "",
                ColorSpec.entries.map { Lang.t(it.zh, it.en) }.toTypedArray(),
                ColorSpec.current(this).ordinal) { i ->
            ColorSpec.set(this, ColorSpec.entries[i])
            recreate()
        }
        val variantRow = M3Ui.dropdownRow(this, pal,
                Lang.t("调色板风格", "Palette style"),
                "",
                M3SchemeVariant.entries.map { Lang.t(it.zh, it.en) }.toTypedArray(),
                M3SchemeVariant.current(this).ordinal) { i ->
            M3SchemeVariant.set(this, M3SchemeVariant.entries[i])
            recreate()
        }
        body.addView(M3Ui.groupCard(this, pal, dynRow, amoledRow, stdRow, variantRow),
                LinearLayout.LayoutParams(-1, -2))

        // ── 界面缩放 ─────────────────────────────────────────
        body.addView(M3Ui.sectionTitle(this, pal, Lang.t("界面缩放", "UI scale")))
        val scale = UiStyle.clampScale(sp().getFloat("ui_scale", 1f))
        val txt = TextView(this)
        txt.text = Lang.t("当前 ", "Current ") + (scale * 100).toInt() + "%"
        txt.textSize = 14f
        txt.setTextColor(pal.onSurface)
        txt.setPadding(dp(16), dp(12), dp(16), dp(4))
        val slider = com.google.android.material.slider.Slider(this)
        slider.valueFrom = 0.8f
        slider.valueTo = 1.2f
        slider.stepSize = 0.05f
        slider.value = scale
        slider.setLabelFormatter { v -> (v * 100).toInt().toString() + "%" }
        slider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            sp().edit().putFloat("ui_scale", v).commit()
            txt.text = Lang.t("当前 ", "Current ") + (v * 100).toInt() + "%"
        }
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(txt, LinearLayout.LayoutParams(-1, -2))
        wrap.addView(slider, LinearLayout.LayoutParams(-1, -2).also {
            it.leftMargin = dp(12); it.rightMargin = dp(12); it.bottomMargin = dp(8)
        })
        body.addView(M3Ui.groupCard(this, pal, wrap), LinearLayout.LayoutParams(-1, -2))


    }

    /** 强调色：五个官方种子色点，选一个即关闭动态取色（与 Material 轨行为一致）。 */
    private fun buildSeedRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(12), dp(16), dp(12))
        row.minimumHeight = dp(56)
        val label = TextView(this)
        label.text = Lang.t("强调色", "Accent")
        label.textSize = 14f
        label.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        label.setTextColor(pal.onSurface)
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        val cur = ThemeUtil.seed(this)
        ThemeUtil.SEEDS.forEachIndexed { i, color ->
            val dot = View(this)
            val dg = GradientDrawable()
            dg.shape = GradientDrawable.OVAL
            dg.setColor(color)
            if (i == cur) dg.setStroke(dp(3), pal.onSurface)
            // 点选反馈：圆点蒙版 + 主题涟漪，与列表行一致的按下观感
            val mask = GradientDrawable()
            mask.shape = GradientDrawable.OVAL
            mask.setColor(0xFFFFFFFF.toInt())
            dot.background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(
                            (pal.onSurface and 0x00FFFFFF) or 0x33000000),
                    dg, mask)
            val lp = LinearLayout.LayoutParams(dp(26), dp(26))
            lp.marginStart = dp(6)
            dot.setOnClickListener {
                sp().edit().putInt("seed", i).putBoolean("dynamic_color", false).commit()
                recreate()
            }
            row.addView(dot, lp)
        }
        return row
    }

    /**
     * 主题相关开关：改即写 SP。
     *
     * ⚠️ 不要立刻 [rebirth]：整页重建会当场销毁这个 MaterialSwitch，
     * 滑块动画（约 200ms）根本没机会播完，用户只觉得「点了没有任何反应」。
     * 所以先让开关自己的动画跑完，再用 postDelayed 重建配色。
     */
    private fun themeSwitch(key: String, def: Boolean, label: String,
                            afterChange: ((Boolean) -> Unit)? = null):
            com.google.android.material.materialswitch.MaterialSwitch {
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this)
        sw.isChecked = sp().getBoolean(key, def)
        sw.contentDescription = label
        M3Ui.standardSwitch(sw, pal)
        sw.setOnCheckedChangeListener { _, c ->
            sp().edit().putBoolean(key, c).commit()
            afterChange?.invoke(c)
            body.postDelayed({ if (!isFinishing) recreate() }, 260)
        }
        return sw
    }

}
