package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
        /** 下拉行右侧「当前值」TextView 的 tag（setDropdownValue 用） */
        private const val TAG_DROPDOWN_VALUE = "m3_dropdown_value"

        // M3 LargeTopAppBar 规范：展开 152dp / 收起 64dp；标题 headlineMedium 28sp -> titleLarge 22sp
        private const val HEADER_EXPANDED_DP = 152
        private const val HEADER_COLLAPSED_DP = 64
        private const val HEADER_EXPANDED_SP = 28f
        private const val HEADER_COLLAPSED_SP = 22f

        /**
         * alpha2.53: M3 indeterminate 圆形加载指示（跨进程/异步取数时用）。
         * 直接用系统 ProgressBar，动画由框架负责，零自绘。
         */
        @JvmStatic
        fun circularLoader(c: Context, sizeDp: Int, color: Int): android.widget.ProgressBar {
            val pb = android.widget.ProgressBar(c)
            pb.isIndeterminate = true
            val lp = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
            pb.layoutParams = lp
            pb.indeterminateTintList = ColorStateList.valueOf(color)
            return pb
        }

        /** alpha2.53: M3 indeterminate 进度条（页面刷新中）。 */
        @JvmStatic
        fun linearLoader(c: Context, color: Int): android.widget.ProgressBar {
            val pb = android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal)
            pb.isIndeterminate = true
            pb.layoutParams = LinearLayout.LayoutParams(-1, dp(c, 3))
            pb.indeterminateTintList = ColorStateList.valueOf(color)
            return pb
        }

        /**
         * alpha2.53: M3 LargeTopAppBar 的「大标题随滚动收缩」。
         *
         * 结构（与官方一致：内容从标题下方穿过，标题钉在上层）：
         *   FrameLayout[ ScrollView(paddingTop = 展开高度) , Header(TOP 对齐, 高度随滚动变化) ]
         * 滚动只改 Header 自身高度，不动 ScrollView 的布局，因此不会每帧触发内容重新测量。
         */
        class LargeHeaderPage(val container: android.widget.FrameLayout,
                              val sv: android.widget.ScrollView,
                              val header: CollapseHeader)

        class CollapseHeader(val view: LinearLayout, private val title: TextView,
                             private val expandedPx: Int, private val collapsedPx: Int) {
            private var lastT = -1f

            /** t: 0 = 完全展开，1 = 完全收起 */
            fun apply(t: Float) {
                if (t == lastT) return
                lastT = t
                val h = (expandedPx + (collapsedPx - expandedPx) * t).toInt()
                val lp = view.layoutParams
                if (lp != null && lp.height != h) {
                    lp.height = h
                    view.layoutParams = lp
                }
                val sp = HEADER_EXPANDED_SP + (HEADER_COLLAPSED_SP - HEADER_EXPANDED_SP) * t
                title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
            }

            fun onScroll(scrollY: Int) {
                val span = (expandedPx - collapsedPx).coerceAtLeast(1)
                apply((scrollY.toFloat() / span).coerceIn(0f, 1f))
            }
        }

        /** alpha2.53: 用大标题页骨架替换普通 ScrollView（三页统一入口）。 */
        @JvmStatic
        fun largeHeaderPage(act: Activity, pal: ThemeUtil.Palette, title: String): LargeHeaderPage {
            val expanded = dp(act, HEADER_EXPANDED_DP)
            val collapsed = dp(act, HEADER_COLLAPSED_DP)
            val header = collapsingHeader(act, pal, title, expanded)

            val sv = android.widget.ScrollView(act)
            sv.setBackgroundColor(pal.surface)
            sv.setPadding(0, expanded, 0, 0)
            // 关键：默认 clipToPadding=true 会把顶部内边距区当成裁剪区，
            // 内容滚上去就画不出来，标题栏收缩后中间空出一大片。
            sv.clipToPadding = false

            val container = android.widget.FrameLayout(act)
            container.setBackgroundColor(pal.surface)
            container.addView(sv, android.widget.FrameLayout.LayoutParams(-1, -1))
            val hlp = android.widget.FrameLayout.LayoutParams(-1, expanded)
            hlp.gravity = Gravity.TOP
            container.addView(header.view, hlp)

            sv.setOnScrollChangeListener { _, _, sy, _, _ -> header.onScroll(sy) }
            header.apply(0f)
            return LargeHeaderPage(container, sv, header)
        }

        /** alpha2.53: 大标题栏本体 —— 底部对齐，随容器高度收缩自然上移。 */
        @JvmStatic
        fun collapsingHeader(act: Activity, pal: ThemeUtil.Palette, title: String, heightPx: Int): CollapseHeader {
            val bar = LinearLayout(act)
            bar.orientation = LinearLayout.VERTICAL
            bar.gravity = Gravity.BOTTOM
            bar.setBackgroundColor(pal.surface)
            bar.setPadding(dp(act, 16), 0, dp(act, 16), dp(act, 16))
            val tv = TextView(act)
            tv.text = title
            tv.isSingleLine = true
            tv.setTextColor(pal.onSurface)
            tv.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, HEADER_EXPANDED_SP)
            bar.addView(tv, LinearLayout.LayoutParams(-1, -2))
            val lp = LinearLayout.LayoutParams(-1, heightPx)
            bar.layoutParams = lp
            return CollapseHeader(bar, tv, dp(act, HEADER_EXPANDED_DP), dp(act, HEADER_COLLAPSED_DP))
        }

        @JvmStatic
        fun dp(c: Context, v: Int): Int = Math.round(v * c.resources.displayMetrics.density)

        /** 发丝线宽（AMOLED 纯黑下卡片描边用，任意 dpi 下都保持 1px 级别，不显得笨重） */
        @JvmStatic
        fun hairline(c: Context): Int =
                Math.max(1, Math.round(c.resources.displayMetrics.density * 0.75f))

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
            tb.setNavigationContentDescription(Lang.t(act, "返回", "Back"))
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
            b.contentDescription = Lang.t(c, "返回", "Back")
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
                            resolveSize(dp(c, 24), widthMeasureSpec),
                            resolveSize(dp(c, 24), heightMeasureSpec))
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
            // alpha2.52: AMOLED 下 card 与 surface 同为纯黑，靠发丝描边区分层级
            if (pal.cardStroke != 0) g.setStroke(hairline(c), pal.cardStroke)
            return g
        }

        /** 统一的卡片外观（MaterialCardView 版）：AMOLED 下同样带描边，避免卡片"消失" */
        @JvmStatic
        fun applyCardLook(card: MaterialCardView, c: Context, pal: ThemeUtil.Palette, radiusDp: Int) {
            card.radius = dp(c, radiusDp).toFloat()
            card.setCardBackgroundColor(pal.card)
            card.cardElevation = 0f
            if (pal.cardStroke != 0) {
                card.strokeWidth = hairline(c)
                card.strokeColor = pal.cardStroke
            } else {
                card.strokeWidth = 0
            }
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
            // alpha2.52: 弹窗描边弱化（0x33 -> 0x1F、1.5dp -> 1dp）。
            // 浮层已有 0.5f scrim 与背景分离，原先的强调色描边在纯黑下过于抢眼。
            card.setStrokeColor((accent and 0x00FFFFFF) or 0x1F000000)
            card.setStrokeWidth(Math.max(1, (1f * density).toInt()))
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
            applyCardLook(card, act, pal, 20)
            card.setRippleColor(ColorStateList.valueOf(if (pal.dark) 0x33FFFFFF else 0x22000000))
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16))
            if (iconRes != 0) {
                val ic = android.widget.ImageView(act)
                ic.setImageResource(iconRes)
                ic.imageTintList = ColorStateList.valueOf(pal.onVariant)
                val ilp = LinearLayout.LayoutParams(dp(act, 24), dp(act, 24))
                ilp.marginEnd = dp(act, 16)
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
                t2.textSize = 14f
                t2.setTextColor(pal.onVariant)
                labels.addView(t2, LinearLayout.LayoutParams(-2, -2))
            }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            val ch = chevron(act, pal.onVariant)
            val clp = LinearLayout.LayoutParams(dp(act, 24), dp(act, 24))
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
            applyCardLook(card, act, pal, 20)
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16))
            row.minimumHeight = dp(act, 56)
            val labels = LinearLayout(act)
            labels.orientation = LinearLayout.VERTICAL
            val t1 = TextView(act)
            t1.text = title
            t1.textSize = 16f
            t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            t1.setTextColor(pal.onSurface)
            labels.addView(t1, LinearLayout.LayoutParams(-2, -2))
            if (sub.isNotEmpty()) {
                val t2 = TextView(act)
                t2.text = sub
                t2.textSize = 14f
                t2.setTextColor(pal.onVariant)
                labels.addView(t2, LinearLayout.LayoutParams(-2, -2))
            }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(sw, LinearLayout.LayoutParams(-2, -2))
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
            return wrap
        }

        /**
         * ANC 模式按钮图标几何（Canvas 绘制）—— 主界面与 GMS 弹窗共用同一份，
         * 避免两处各画一套导致语义漂移。
         *
         * 与 Material Symbols 语义对齐（UI 模式顺序见 AncProfileLib：0=关闭 1=降噪 2=透传 3=抗风）：
         *  0=电源符号（关闭）   1=同心弧（声波被逐层屏蔽 = 降噪）
         *  2=耳道（声音进入 = 透传）  3=波浪线（与 Material `air` 同形 = 抗风）
         */
        /**
         * alpha2.53: 取模块自身 Resources 里的 Drawable。
         *
         * Settings / GMS 等 hook 进程里，宿主 Context 解析不了模块的 R.drawable：
         * 两边资源表都是 0x7f 包 ID，同一个数值在宿主体内被解释成宿主自己的资源
         * （实测 ic_anc_off 命中 com.android.settings:dimen/animation_max_size，
         * 抛 Resources$NotFoundException，整个设备详情面板构建失败，退化成单行条目）。
         * 模块进程内直接用传入的 Context，行为与改动前逐字一致。
         * 做法与 FastPairHookEntry 拿 sModCtx 同源。
         */
        @Volatile private var sSelfCtx: Context? = null

        @JvmStatic
        fun moduleDrawable(c: Context, resId: Int): Drawable? {
            val ctx = try {
                if (c.packageName == "com.fxxkmoondrop.secret") c
                else sSelfCtx ?: c.createPackageContext("com.fxxkmoondrop.secret",
                        Context.CONTEXT_IGNORE_SECURITY).also { sSelfCtx = it }
            } catch (t: Throwable) { c }
            return try { ctx.getDrawable(resId) } catch (t: Throwable) { null }
        }

        @JvmStatic
fun ancModeDrawable(c: Context, mode: Int, px: Int, color: Int): Drawable? {
            // alpha2.52: 改用 Material Symbols 规范图标，替代原先手绘 Canvas 几何。
            // 语义（UI 模式顺序见 AncProfileLib：0=关闭 1=降噪 2=透传 3=抗风）：
            //   noise_control_off（ANC 关闭）/ noise_control_on（降噪）
            //   hearing（透传）/ air（抗风）
            val res = when (mode) {
                0 -> R.drawable.ic_anc_off
                1 -> R.drawable.ic_anc_on
                2 -> R.drawable.ic_anc_passthrough
                3 -> R.drawable.ic_air
                // 3.0.3: 自适应(4)。仓库暂无 Material Symbols 的 noise_aware 矢量，
                // 复用降噪图标而非自造 path；资源到位后只改这一行。
                4 -> R.drawable.ic_anc_on
                else -> R.drawable.ic_air
            }
            val d = moduleDrawable(c, res) ?: return null
            d.setBounds(0, 0, px, px)
            d.setTint(color)
            return d
        }

        /**
         * M3 标准开关（对齐 org.lsposed.manager）：开 = primary 轨道 + onPrimary 拇指 + ✓；
         * 关 = surfaceContainerHighest 轨道 + outline 拇指 + ✗。
         * 原先三处各写一份 tint，样式不一，统一收敛到这里。
         */
        @JvmStatic
        fun standardSwitch(sw: com.google.android.material.materialswitch.MaterialSwitch,
                           pal: ThemeUtil.Palette) {
            sw.setTrackTintList(ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(pal.primary, pal.surfaceContainerHighest)))
            sw.setThumbTintList(ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(pal.onPrimary, pal.outline)))
            // 拇指图标：✓ / ✗（StateListDrawable 按选中态切换）
            val c = sw.context
            val on = moduleDrawable(c, R.drawable.ic_check)
            val off = moduleDrawable(c, R.drawable.ic_close)
            if (on != null && off != null) {
                val sl = android.graphics.drawable.StateListDrawable()
                on.setTint(pal.primary)
                off.setTint(pal.surfaceContainerHighest)
                sl.addState(intArrayOf(android.R.attr.state_checked), on)
                sl.addState(intArrayOf(), off)
                sw.thumbIconDrawable = sl
            }
        }

        /**
         * M3 Text Button（弹窗动作按钮）：无底色、仅涟漪，primary 文字，高 40dp。
         * 旧实现有一层 0x14000000 药丸底，与 LSPosed 的纯文本按钮不一致。
         */
        @JvmStatic
        fun textButton(c: Context, pal: ThemeUtil.Palette, text: String,
                       onClick: android.view.View.OnClickListener): TextView {
            val b = TextView(c)
            b.text = text
            b.textSize = 14f
            b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            b.gravity = Gravity.CENTER
            b.isSingleLine = true
            b.minimumHeight = dp(c, 48)
            b.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
            b.setTextColor(pal.primary)
            val mask = GradientDrawable()
            mask.cornerRadius = dp(c, 24).toFloat()
            b.background = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf((pal.primary and 0x00FFFFFF) or 0x1F000000),
                    null, mask)
            b.setOnClickListener(onClick)
            return b
        }

        /**
         * M3 分段控件（Segmented Button）：主题模式 / 语言等单选。
         * 规范：高 48dp（M3 最小可点尺寸）、圆角 24dp、labelLarge 14sp；
         * 选中 = secondaryContainer/onSecondaryContainer，未选中 = 透明底 + 1dp outline 描边。
         * 原实现两处各写一份（主题模式、语言），样式与规范不符，统一收敛到这里。
         */
        /**
         * alpha2.53: 对齐 org.lsposed.manager 的「行 + 当前值 + 下拉菜单」选择器。
         *
         * LSPosed 的主题/语言都不做成排按钮，而是一整行（标题 + 右侧当前值），点开圆角菜单、
         * 选中项填 primary 并带 ✓。此处按同一形态实现，供「主题」「语言」等三选一项使用。
         */
        @JvmStatic
        fun dropdownRow(act: Activity, pal: ThemeUtil.Palette, title: String, sub: String?,
                        items: Array<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
            val value = TextView(act)
            value.tag = TAG_DROPDOWN_VALUE
            value.text = if (selected in items.indices) items[selected] else ""
            value.textSize = 14f
            value.setTextColor(pal.onVariant)
            value.isSingleLine = true
            val row = listRow(act, pal, 0, title, sub, value, null)
            // alpha2.53: 菜单跟随手指位置（官方行为）。
            // 触摸点用 OnTouchListener 记录但返回 false，保留行自身的点击/涟漪；
            // 无障碍与键盘触发的 click 没有触摸点，退化为锚到行中心。
            val at = intArrayOf(0, 0)
            var hasAt = false
            // 当前选中态必须可变：否则选完再打开菜单，高亮还停在初始值上
            var cur = selected
            row.setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    at[0] = e.rawX.toInt(); at[1] = e.rawY.toInt(); hasAt = true
                }
                false
            }
            row.setOnClickListener { v ->
                if (!hasAt || v.width == 0) {
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    at[0] = loc[0] + v.width / 2
                    at[1] = loc[1] + v.height / 2
                }
                showMenuAt(v, pal, items, cur, { pick ->
                    cur = pick
                    onPick(pick)
                }, at[0], at[1])
            }
            return row
        }

        /** alpha2.53: 就地更新下拉行右侧的当前值（不重建页面）。 */
        @JvmStatic
        fun setDropdownValue(row: View, text: String) {
            row.findViewWithTag<TextView>(TAG_DROPDOWN_VALUE)?.text = text
        }

        /**
         * alpha2.53: LSPosed 同款下拉菜单 —— 圆角 24dp 卡片；选中项 primary 填充 + ✓，未选中透明。
         *
         * [x]/[y] 是屏幕绝对坐标：菜单就出现在用户手指落下的地方（官方行为），
         * 贴边时向屏幕内收，避免被裁掉。
         */
        @JvmStatic
        fun showMenuAt(anchor: View, pal: ThemeUtil.Palette, items: Array<String>,
                       selected: Int, onPick: (Int) -> Unit, x: Int, y: Int) {
            val c = anchor.context
            val menu = LinearLayout(c)
            menu.orientation = LinearLayout.VERTICAL
            menu.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8))
            val bg = GradientDrawable()
            bg.cornerRadius = dp(c, 24).toFloat()
            bg.setColor(pal.surfaceContainerHighest)
            bg.setStroke(hairline(c), pal.outline)
            menu.background = bg
            menu.elevation = dp(c, 6).toFloat()
            val pop = android.widget.PopupWindow(menu,
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
            pop.isOutsideTouchable = true
            pop.elevation = dp(c, 6).toFloat()
            for (i in items.indices) {
                val on = i == selected
                val item = LinearLayout(c)
                item.orientation = LinearLayout.HORIZONTAL
                item.gravity = Gravity.CENTER_VERTICAL
                item.setPadding(dp(c, 16), 0, dp(c, 20), 0)
                val ib = GradientDrawable()
                ib.cornerRadius = dp(c, 20).toFloat()
                ib.setColor(if (on) pal.primary else 0x00000000)
                item.background = ib
                val cb = android.widget.ImageView(c)
                cb.setImageDrawable(moduleDrawable(c, R.drawable.ic_check))
                cb.imageTintList = ColorStateList.valueOf(if (on) pal.onPrimary else 0x00000000)
                cb.visibility = if (on) View.VISIBLE else View.INVISIBLE
                val clp = LinearLayout.LayoutParams(dp(c, 18), dp(c, 18))
                clp.marginEnd = dp(c, 8)
                item.addView(cb, clp)
                val tv = TextView(c)
                tv.text = items[i]
                tv.textSize = 14f
                tv.setTextColor(if (on) pal.onPrimary else pal.onSurface)
                item.addView(tv, LinearLayout.LayoutParams(-2, -2))
                item.setOnClickListener {
                    // M3 退场：缩回 0.9 + 淡出，120ms 后再真正 dismiss
                    menu.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                            .setDuration(120L)
                            .withEndAction {
                                try { pop.dismiss() } catch (_: Throwable) { }
                            }
                            .start()
                    onPick(i)
                }
                menu.addView(item, LinearLayout.LayoutParams(-1, dp(c, 48)))
            }
            // 先量一次拿到菜单实际尺寸，再夹到屏幕内（贴边不裁切）
            menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val dm = c.resources.displayMetrics
            val mw = menu.measuredWidth
            val mh = menu.measuredHeight
            val m = dp(c, 8)
            val px = x.coerceIn(m, (dm.widthPixels - mw - m).coerceAtLeast(m))
            val py = y.coerceIn(m, (dm.heightPixels - mh - m).coerceAtLeast(m))
            pop.width = mw
            pop.height = mh

            // alpha2.53: M3 菜单入场动画 —— 从「离手指最近的那个角」长出来。
            // 官方 DropdownMenu 就是 fadeIn + scaleIn(0.8)，约 140ms、emphasized decelerate。
            // 用动画前的状态起手：PopupWindow 已经按内容量好尺寸，缩放只是视觉效果，不影响布局。
            menu.pivotX = (x - px).coerceIn(0, mw).toFloat()
            menu.pivotY = (y - py).coerceIn(0, mh).toFloat()
            menu.alpha = 0f
            menu.scaleX = 0.8f
            menu.scaleY = 0.8f

            android.util.Log.d("FxxkMoondrop", "menu place: want=" + x + "," + y +
                    " got=" + px + "," + py + " size=" + mw + "x" + mh +
                    " screen=" + dm.widthPixels + "x" + dm.heightPixels)
            pop.showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, px, py)

            menu.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(140L)
                    .setInterpolator(android.view.animation.PathInterpolator(
                            0.05f, 0.7f, 0.1f, 1f))
                    .start()
        }

        /**
         * M3 官方加载行：LoadingIndicator + 文字（material 1.14 新增组件）。
         * 用于真正耗时的后台任务占位（root 探测、模块 PING、日志打包等）——
         * 取代原先只有一行「正在检查…」文字、没有任何进度反馈的写法。
         */
        @JvmStatic
        fun loadingRow(c: Context, pal: ThemeUtil.Palette, text: String): LinearLayout {
            val row = LinearLayout(c)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            val ind = com.google.android.material.loadingindicator.LoadingIndicator(c)
            ind.indicatorSize = dp(c, 20)
            ind.setIndicatorColor(pal.primary)
            row.addView(ind, LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)))
            row.addView(View(c), LinearLayout.LayoutParams(dp(c, 10), 1))
            val t = TextView(c)
            t.text = text
            t.textSize = 13f
            t.setTextColor(pal.onVariant)
            row.addView(t, LinearLayout.LayoutParams(-2, -2))
            return row
        }

        /**
         * M3 Filled 按钮：官方 MaterialButton，尺寸/形状/内边距全部交给主题里的
         * Widget.Material3Expressive.Button（material 1.14 起生效）。
         *
         * 不再手写 padding / 圆角：expressive 用 materialSizeOverlay 决定尺寸档
         * （当前 Small：视觉 40dp + insetTop/Bottom 各 4dp = 48dp 触摸区），并用
         * m3expressive_button_shape_state_list 做按下形变；手写这些值会把它覆盖掉。
         * 另注：MaterialButton 不实现 minHeight，写 View.setMinimumHeight 是空操作。
         */
        @JvmStatic
        fun filledButton(act: Activity, pal: ThemeUtil.Palette, text: String,
                         l: View.OnClickListener): TextView {
            val b = MaterialButton(act)
            b.text = text
            b.textSize = 14f
            b.setTextColor(pal.onPrimary)
            b.backgroundTintList = ColorStateList.valueOf(pal.primary)
            b.gravity = Gravity.CENTER
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
            Lang.refresh(act)
            val nm = nav.menu
            nm.add(0, 1, 0, Lang.t("概览", "Overview")).setIcon(R.drawable.ic_home)
            nm.add(0, 2, 0, Lang.t("设置", "Settings")).setIcon(R.drawable.ic_settings)
            nm.add(0, 3, 0, Lang.t("关于", "About")).setIcon(R.drawable.ic_info)
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

        // ── 官方设置页组件：分区标题 / 分组卡片 / 图标容器列表行 ──
        @JvmStatic
        fun sectionTitle(act: Activity, pal: ThemeUtil.Palette, text: String): TextView {
            val t = TextView(act)
            t.text = text
            t.textSize = 14f
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
                // alpha2.52: 对齐 org.lsposed.manager —— 每行独立卡片 + 12dp 间距，
                // 取代原先"一张大卡 + 行间分隔线"的合并式布局。
                if (i > 0) {
                    val gap = View(act)
                    wrap.addView(gap, LinearLayout.LayoutParams(1, dp(act, 12)))
                }
                val card = LinearLayout(act)
                card.orientation = LinearLayout.VERTICAL
                card.background = cardBg(act, pal, 20)
                card.addView(rows[i], LinearLayout.LayoutParams(-1, -2))
                wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
            }
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
            row.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16))
            row.minimumHeight = dp(act, 56)
            if (iconRes != 0) {
                val ic = android.widget.ImageView(act)
                ic.setImageResource(iconRes)
                ic.imageTintList = ColorStateList.valueOf(pal.onVariant)
                val ilp = LinearLayout.LayoutParams(dp(act, 24), dp(act, 24))
                ilp.marginEnd = dp(act, 16)
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
                t2.textSize = 14f
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
