package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * alpha2.0: 官方 Material 3 组件化（替换 alpha1.36 手搓实现）。
 * API 全部保持不变（返回类型/参数签名），调用方零修改；
 * 色板仍用 ThemeUtil 动态 Material You（pal），与官方组件叠加显示。
 * 组件：MaterialToolbar / MaterialButton / MaterialCardView。
 */
@Suppress("DEPRECATION")
class M3Ui {
    companion object {
        @JvmStatic
        fun dp(c: Context, v: Int): Int = Math.round(v * c.resources.displayMetrics.density)

        /** 沉浸式系统栏：随色板亮暗（浅色深色自适应） */
        @JvmStatic
        fun fitSystemBars(act: Activity, pal: ThemeUtil.Palette) {
            act.window.statusBarColor = pal.surface
            act.window.navigationBarColor = pal.surface
            if (Build.VERSION.SDK_INT >= 30) {
                val c = act.window.insetsController
                c?.setSystemBarsAppearance(
                        if (pal.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            }
        }

        /** M3 Top App Bar：并行一级页标题栏（无返回箭头，与主页平行，官方样式） */
        @JvmStatic
        fun topBarTitle(act: Activity, pal: ThemeUtil.Palette, title: String): LinearLayout {
            val bar = LinearLayout(act)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.setBackgroundColor(pal.surface)
            val tb = MaterialToolbar(act)
            tb.minimumHeight = dp(act, 64)
            tb.setBackgroundColor(pal.surface)
            tb.title = title
            tb.setTitleTextColor(pal.onSurface)
            bar.addView(tb, LinearLayout.LayoutParams(-1, -2))
            return bar
        }

        /** M3 Top App Bar（64dp）：官方 MaterialToolbar，导航箭头 + titleLarge 标题 */
        @JvmStatic
        fun topBar(act: Activity, pal: ThemeUtil.Palette, title: String,
                   onBack: Runnable?): LinearLayout {
            val bar = LinearLayout(act)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.setBackgroundColor(pal.surface)
            val tb = MaterialToolbar(act)
            tb.minimumHeight = dp(act, 64)
            tb.setBackgroundColor(pal.surface)
            tb.title = title
            tb.setTitleTextColor(pal.onSurface)
            tb.setNavigationIcon(com.fxxkmoondrop.secret.R.drawable.abc_ic_ab_back_material)
            tb.setNavigationIconTint(pal.onSurface)
            tb.setNavigationContentDescription("返回")
            if (onBack != null) tb.setNavigationOnClickListener { onBack.run() }
            bar.addView(tb, LinearLayout.LayoutParams(-1, -2))
            return bar
        }

        /** M3 返回箭头按钮：MaterialButton（IconButton 式），48dp 点击区 + 官方涟漪 */
        @JvmStatic
        fun arrowBack(c: Context, pal: ThemeUtil.Palette, onBack: Runnable?): View {
            val b = MaterialButton(c, null,
                    com.google.android.material.R.attr.materialIconButtonStyle)
            b.setIconResource(com.fxxkmoondrop.secret.R.drawable.abc_ic_ab_back_material)
            b.iconTint = ColorStateList.valueOf(pal.onSurface)
            b.contentDescription = "返回"
            b.layoutParams = LinearLayout.LayoutParams(dp(c, 48), dp(c, 48))
            if (onBack != null) b.setOnClickListener { onBack.run() }
            return b
        }

        /** 行尾 chevron（12dp 矢量，M3 navigation 指示） */
        @JvmStatic
        fun chevron(c: Context, color: Int): View {
            return object : View(c) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                private val path = Path()

                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    setMeasuredDimension(
                            resolveSize(dp(c, 16), widthMeasureSpec),
                            resolveSize(dp(c, 16), heightMeasureSpec))
                }

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val u = Math.min(width, height) / 24f
                    path.reset()
                    path.moveTo(8.59f * u, 16.59f * u)
                    path.lineTo(13.17f * u, 12f * u)
                    path.lineTo(8.59f * u, 7.41f * u)
                    path.lineTo(10f * u, 6f * u)
                    path.lineTo(16f * u, 12f * u)
                    path.lineTo(10f * u, 18f * u)
                    path.close()
                    paint.color = color
                    canvas.drawPath(path, paint)
                }
            }
        }

        /** 圆角卡片背景（M3 surfaceContainer 色） */
        @JvmStatic
        fun cardBg(c: Context, pal: ThemeUtil.Palette, radiusDp: Int): GradientDrawable {
            val g = GradientDrawable()
            g.setColor(pal.card)
            g.setCornerRadius(dp(c, radiusDp).toFloat())
            return g
        }

        /** alpha2.28: 统一 MaterialCardView 弹窗构建器（与 Google 弹窗同风格）
         *  @return Pair<Dialog, LinearLayout(body)> — body 用于添加内容 */
        @JvmStatic
        fun materialDialog(c: Context, accent: Int, cardColor: Int): Pair<android.app.Dialog, LinearLayout> {
            val dlg = android.app.Dialog(c)
            val win = dlg.window
            if (win != null) {
                win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
                win.setDimAmount(0.5f)
            }
            val density = c.resources.displayMetrics.density
            val card = MaterialCardView(c)
            card.setRadius(28 * density)
            card.setCardBackgroundColor(cardColor)
            card.setStrokeColor((accent and 0x00FFFFFF) or 0x33000000)
            card.setStrokeWidth((1.5f * density).toInt())
            card.setCardElevation(16 * density)
            val body = LinearLayout(c)
            body.orientation = LinearLayout.VERTICAL
            body.setPadding(dp(c, 24), dp(c, 22), dp(c, 24), dp(c, 24))
            card.addView(body, android.widget.FrameLayout.LayoutParams(-1, -2))
            dlg.setContentView(card)
            win?.setLayout((c.resources.displayMetrics.widthPixels * 0.84).toInt(), -2)
            return Pair(dlg, body)
        }

        /** alpha2.28: 弹窗标题 TextView（统一字号/字重/颜色） */
        @JvmStatic
        fun dialogTitle(c: Context, text: String, color: Int): TextView {
            val tv = TextView(c)
            tv.text = text
            tv.textSize = 22f
            tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            tv.setTextColor(color)
            tv.gravity = Gravity.CENTER_HORIZONTAL
            return tv
        }

        /** M3 列表导航行：官方 MaterialCardView 卡片 + Material Icons 图标（无底）+ 标题/副标题 + chevron（LSPosed 同款） */
        @JvmStatic
        fun navRow(act: Activity, pal: ThemeUtil.Palette,
                   iconRes: Int, title: String, sub: String?,
                   onClick: Runnable?): LinearLayout {
            val wrap = LinearLayout(act)
            val card = MaterialCardView(act)
            card.radius = dp(act, 20).toFloat()
            card.setCardBackgroundColor(pal.card)
            card.cardElevation = 0f
            card.strokeWidth = 0 // alpha2.4: 去默认描边，与其他分组卡统一
            card.setRippleColor(ColorStateList.valueOf(if (pal.dark) 0x33FFFFFF else 0x22000000))
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12))
            if (iconRes != 0) {
                val ic = android.widget.ImageView(act)
                ic.setImageResource(iconRes)
                ic.imageTintList = ColorStateList.valueOf(pal.onVariant)
                val ilp = LinearLayout.LayoutParams(dp(act, 24), dp(act, 24))
                ilp.marginEnd = dp(act, 14)
                row.addView(ic, ilp)
            }
            val labels = LinearLayout(act)
            labels.orientation = LinearLayout.VERTICAL
            val t1 = TextView(act)
            t1.text = title
            t1.textSize = 16f
            t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            t1.setTextColor(pal.onSurface)
            labels.addView(t1, LinearLayout.LayoutParams(-2, -2))
            if (!sub.isNullOrEmpty()) {
                val t2 = TextView(act)
                t2.text = sub
                t2.textSize = 12f
                t2.setTextColor(pal.onVariant)
                labels.addView(t2, LinearLayout.LayoutParams(-2, -2))
            }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            val ch = chevron(act, pal.onVariant)
            val clp = LinearLayout.LayoutParams(dp(act, 16), dp(act, 16))
            clp.marginStart = dp(act, 10)
            row.addView(ch, clp)
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (onClick != null) card.setOnClickListener { onClick.run() }
            wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
            return wrap
        }

        /** M3 开关行：官方 MaterialCardView 卡片 + 标题/副标题 + 开关 */
        @JvmStatic
        fun switchRow(act: Activity, pal: ThemeUtil.Palette, title: String, sub: String,
                      sw: com.google.android.material.materialswitch.MaterialSwitch): LinearLayout {
            val wrap = LinearLayout(act)
            val card = MaterialCardView(act)
            card.radius = dp(act, 20).toFloat()
            card.setCardBackgroundColor(pal.card)
            card.cardElevation = 0f
            card.strokeWidth = 0 // alpha2.4: 去默认描边，与其他分组卡统一
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(act, 16), dp(act, 10), dp(act, 16), dp(act, 10))
            val labels = LinearLayout(act)
            labels.orientation = LinearLayout.VERTICAL
            val t1 = TextView(act)
            t1.text = title
            t1.textSize = 16f
            t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            t1.setTextColor(pal.onSurface)
            labels.addView(t1, LinearLayout.LayoutParams(-2, -2))
            val t2 = TextView(act)
            t2.text = sub
            t2.textSize = 12f
            t2.setTextColor(pal.onVariant)
            labels.addView(t2, LinearLayout.LayoutParams(-2, -2))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(sw, LinearLayout.LayoutParams(-2, -2))
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
            return wrap
        }

        /** M3 Filled 按钮：官方 MaterialButton（primary 底 onPrimary 字，圆角 20） */
        @JvmStatic
        fun filledButton(act: Activity, pal: ThemeUtil.Palette, text: String,
                         l: View.OnClickListener): TextView {
            val b = MaterialButton(act)
            b.text = text
            b.textSize = 15f
            b.setTextColor(pal.onPrimary)
            b.backgroundTintList = ColorStateList.valueOf(pal.primary)
            b.setCornerRadius(dp(act, 20))
            b.gravity = Gravity.CENTER
            b.setPadding(dp(act, 26), dp(act, 12), dp(act, 26), dp(act, 12))
            b.setOnClickListener(l)
            return b
        }

        /** 底部导航选中 tab：1=概览 / 2=设置 / 3=关于 */
        fun interface OnNavTab {
            fun onTab(id: Int)
        }

        /** M3 底部导航：官方 BottomNavigationView（LSPosed 同款 pill 指示器 + 动态色） */
        @JvmStatic
        fun navBar(act: Activity, pal: ThemeUtil.Palette, selectedTab: Int,
                   onTab: OnNavTab): BottomNavigationView {
            val nav = BottomNavigationView(act)
            nav.setBackgroundColor(pal.surface)
            val nm = nav.menu
            nm.add(0, 1, 0, "概览").setIcon(R.drawable.ic_home)
            nm.add(0, 2, 0, "设置").setIcon(R.drawable.ic_settings)
            nm.add(0, 3, 0, "关于").setIcon(R.drawable.ic_info)
            nav.selectedItemId = selectedTab
            val navTint = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(pal.primary, pal.onVariant))
            nav.setItemIconTintList(navTint)
            nav.setItemTextColor(navTint)
            nav.setItemActiveIndicatorColor(ColorStateList.valueOf(pal.container))
            // 始终回调：BottomNavigationView 点击当前已选中项不会触发 listener，
            // 无需拦截；否则初始 tab 被闭包写死，切回主页时被误吞（alpha2.12 bug 修复）
            nav.setOnItemSelectedListener { item ->
                onTab.onTab(item.itemId)
                true
            }
            return nav
        }

        // ── 官方水平过渡动画（M3 风格）──
        @JvmStatic
        fun openPage(act: Activity, it: Intent) {
            act.startActivity(it)
            act.overridePendingTransition(R.anim.m3_fade_in, R.anim.m3_fade_out)
        }

        @JvmStatic
        fun finishPage(act: Activity) {
            act.finish()
            act.overridePendingTransition(R.anim.m3_fade_in, R.anim.m3_fade_out)
        }

        /** 前进跳转并关闭当前页（兼容：旧 SettingsActivity/AboutActivity 保留使用） */
        @JvmStatic
        fun goPage(act: Activity, cls: Class<*>) {
            act.startActivity(Intent(act, cls))
            act.overridePendingTransition(R.anim.m3_fade_in, R.anim.m3_fade_out)
            act.finish()
        }

        /** 页面内容入场动画（兼容：旧整页 Activity 使用） */
        @JvmStatic
        fun contentEnter(act: Activity, content: View) {
            content.alpha = 0f
            content.translationY = dp(act, 8).toFloat()
            content.animate().alpha(1f).translationY(0f).setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
        }

        // ── 官方设置页组件：分区标题 / 分组卡片 / 图标容器列表行 ──
        @JvmStatic
        fun sectionTitle(act: Activity, pal: ThemeUtil.Palette, text: String): TextView {
            val t = TextView(act)
            t.text = text
            t.textSize = 13f
            t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            t.setTextColor(pal.onVariant)
            t.setPadding(dp(act, 20), dp(act, 2), dp(act, 20), dp(act, 6))
            return t
        }

        /** 官方分组卡片：GradientDrawable 圆角 LinearLayout（与外观卡同款已验证方案），行间淡分隔线 */
        @JvmStatic
        fun groupCard(act: Activity, pal: ThemeUtil.Palette, vararg rows: View): View {
            val wrap = LinearLayout(act)
            wrap.orientation = LinearLayout.VERTICAL
            for (i in rows.indices) {
                wrap.addView(rows[i], LinearLayout.LayoutParams(-1, -2))
                if (i < rows.size - 1) {
                    val d = View(act)
                    d.setBackgroundColor((pal.outline and 0x00FFFFFF) or 0x2E000000.toInt())
                    val dlp = LinearLayout.LayoutParams(-1, dp(act, 1))
                    dlp.marginStart = dp(act, 16)
                    dlp.marginEnd = dp(act, 16)
                    wrap.addView(d, dlp)
                }
            }
            val bg = GradientDrawable()
            bg.setColor(pal.card)
            bg.setCornerRadius(dp(act, 20).toFloat())
            wrap.background = bg
            return wrap
        }

        /** 官方列表行：图标 + 标题/副标题 + 尾随控件（switchRow 同款已验证结构） */
        @JvmStatic
        fun listRow(act: Activity, pal: ThemeUtil.Palette,
                    iconRes: Int, title: String, sub: String?,
                    trailing: View?, onClick: Runnable?): LinearLayout {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(act, 16), dp(act, 10), dp(act, 16), dp(act, 10))
            if (iconRes != 0) {
                val ic = android.widget.ImageView(act)
                ic.setImageResource(iconRes)
                ic.imageTintList = ColorStateList.valueOf(pal.onContainer)
                val ilp = LinearLayout.LayoutParams(dp(act, 24), dp(act, 24))
                ilp.marginEnd = dp(act, 14)
                row.addView(ic, ilp)
            }
            val labels = LinearLayout(act)
            labels.orientation = LinearLayout.VERTICAL
            val t1 = TextView(act)
            t1.text = title
            t1.textSize = 16f
            t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            t1.setTextColor(pal.onSurface)
            labels.addView(t1, LinearLayout.LayoutParams(-2, -2))
            if (!sub.isNullOrEmpty()) {
                val t2 = TextView(act)
                t2.text = sub
                t2.textSize = 12f
                t2.setTextColor(pal.onVariant)
                labels.addView(t2, LinearLayout.LayoutParams(-2, -2))
            }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            if (trailing != null) {
                val tlp = LinearLayout.LayoutParams(-2, -2)
                tlp.marginStart = dp(act, 10)
                row.addView(trailing, tlp)
            }
            if (onClick != null) row.setOnClickListener { onClick.run() }
            return row
        }
    }
}
