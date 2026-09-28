package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 均衡器（3.2.12）。
 *
 * 走 Moondrop 私有 BleSourceSwitch 协议（9ECA），只有蓝讯 / 中科系主控的型号才有这个服务；
 * 高通 GAIA 系没有。设备没这个服务时整页显示「本设备不支持」而不是空表格。
 *
 * 本版范围（B 档，与用户确认过）：
 *  - **预设可切换**：读出耳机内置预设数量，点选即下发。
 *  - **PEQ 只读**：读 32 段参量均衡的参数并画频响曲线，不提供编辑与提交。
 *
 * 为什么不做 PEQ 编辑：写参数需要真实耳机反复试听才能确认「频率 / 增益 / Q / 滤波器类型」
 * 四组字节的解释没搞错，而开发环境没有耳机可测。协议解析可能的偏差在只读展示时表现为
 * 「数字看着不对」，在编辑时却会直接把参数写坏 —— 先把读这条链路验证过再谈写。
 *
 * 预设**不显示名字**：设备只回一个下标，不回报名称，名称表是各厂商自定义的。
 * 官方 App 的图标名暗示了一组顺序（off / pop / rock / classical / ...），但那个顺序
 * 无法从协议或资源里证实，猜错会让用户以为选了「流行」实际听到别的。
 * 所以这里只显示「预设 N」，宁可少信息也不给错信息。
 */
/**
 * 增益单位：协议里 preGain 明确标注「单位 0.01 dB」，PEQ 点的 gainRaw 与它同族，
 * 按同一单位解释。**这是推断，未在真机确证** —— 若实测发现曲线纵向比例差 100 倍，
 * 改这一个常量即可，不必翻别的代码。
 */
private const val GAIN_UNIT_DB = 0.01

class EqActivity : Activity() {

    private companion object {
        const val TAG = "FxxkMoondrop"

        /** 读不到回包时的兜底：耳机不回就一直是「读取中」，必须有出口。 */
        const val READ_TIMEOUT_MS = 6000L
    }

    private lateinit var pal: ThemeUtil.Palette
    private lateinit var content: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    /** 覆盖全部异步回调：设备断开 / 页面退出后到达的回包一律丢弃。 */
    @Volatile private var alive = false
    private var timeoutToken = 0

    private var presetCount = 0
    private var currentPreset = -1
    private var presetRow: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        pal = ThemeUtil.Palette(this)
        alive = true

        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(pal.surface))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        var statusBarH = 0
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) statusBarH = resources.getDimensionPixelSize(resId)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        root.setPadding(dp(16), statusBarH + dp(10), dp(16), dp(24))
        root.addView(M3Ui.topBar(this, pal, Lang.t(this, "均衡器", "Equalizer")) { finish() },
                LinearLayout.LayoutParams(-1, -2))
        root.addView(spacer(dp(8)))

        val sv = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        sv.addView(content)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        load()
    }

    override fun onDestroy() {
        alive = false
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── 读取链路 ─────────────────────────────────────────────

    private fun load() {
        val client = srcClient()
        if (client == null) {
            renderUnsupported()
            return
        }
        renderLoading()
        armTimeout()
        try {
            client.getPresetEq(object : BleSourceSwitchClient.SrcCallback<BleSourceProtocol.SrcPresetEqInfo> {
                override fun onSuccess(value: BleSourceProtocol.SrcPresetEqInfo?) = post {
                    if (!alive) return@post
                    val v = value ?: run { renderFailed(); return@post }
                    presetCount = v.presetCount
                    currentPreset = v.currentPreset
                    renderPresets()
                    loadPeq()
                }
                override fun onError(message: String?) = post {
                    if (!alive) return@post
                    log("preset eq read failed: " + message)
                    renderFailed()
                }
            })
        } catch (t: Throwable) {
            log("getPresetEq threw: " + t)
            renderFailed()
        }
    }

    /** 读 PEQ 配置，再逐点读取。32 段逐点问是协议要求（一次只能取一个点）。 */
    private fun loadPeq() {
        val client = srcClient() ?: return
        try {
            client.getPeqConfig(object : BleSourceSwitchClient.SrcCallback<BleSourceProtocol.SrcPeqConfig> {
                override fun onSuccess(value: BleSourceProtocol.SrcPeqConfig?) = post {
                    if (!alive) return@post
                    val cfg = value ?: run { renderPeqUnavailable(); return@post }
                    readPoints(client, cfg.pointCount, 0, ArrayList(), cfg)
                }
                override fun onError(message: String?) = post {
                    if (!alive) return@post
                    log("peq config read failed: " + message)
                    renderPeqUnavailable()
                }
            })
        } catch (t: Throwable) {
            log("getPeqConfig threw: " + t)
            renderPeqUnavailable()
        }
    }

    private fun readPoints(client: BleSourceSwitchClient, count: Int, i: Int,
                           acc: ArrayList<BleSourceProtocol.SrcPeqPoint>,
                           cfg: BleSourceProtocol.SrcPeqConfig) {
        if (i >= count) {
            if (alive) renderPeq(acc, cfg)
            return
        }
        try {
            client.getPeqPoint(i, object : BleSourceSwitchClient.SrcCallback<BleSourceProtocol.SrcPeqPoint> {
                override fun onSuccess(value: BleSourceProtocol.SrcPeqPoint?) = post {
                    value?.let { acc.add(it) }
                    if (alive) readPoints(client, count, i + 1, acc, cfg)
                }
                override fun onError(message: String?) = post {
                    // 单点失败不放弃整条链路：能读多少画多少，标注缺口比整页失败有用
                    log("peq point " + i + " failed: " + message)
                    if (alive) readPoints(client, count, i + 1, acc, cfg)
                }
            })
        } catch (t: Throwable) {
            log("getPeqPoint " + i + " threw: " + t)
            if (alive) renderPeq(acc, cfg)
        }
    }

    /**
     * 回主线程执行。
     *
     * 回调重写用的是表达式体（`override fun onSuccess(...) = post { ... }`），要求返回 Unit，
     * 而 Handler.post 返回 Boolean —— 直接写就编译不过。包一层既解决类型，也顺手统一了
     * 「页面已退出就丢弃回包」这件事，省得每个回调各写一遍。
     */
    private fun post(block: () -> Unit) {
        ui.post { if (alive) block() }
    }

    private fun srcClient(): BleSourceSwitchClient? = try {
        val c = GaiaBleClient.getInstance().getSrcClient()
        if (c != null && c.isPresent()) c else null
    } catch (t: Throwable) {
        log("srcClient unavailable: " + t)
        null
    }

    private fun armTimeout() {
        timeoutToken++
        val token = timeoutToken
        ui.postDelayed({
            if (alive && token == timeoutToken && presetRow == null) renderFailed()
        }, READ_TIMEOUT_MS)
    }

    // ── 渲染 ─────────────────────────────────────────────────

    private fun renderLoading() {
        content.removeAllViews()
        content.addView(M3Ui.loadingRow(this, pal, Lang.t(this, "正在读取均衡器设置…",
                "Reading the equalizer settings…")), LinearLayout.LayoutParams(-1, -2))
    }

    private fun renderUnsupported() {
        content.removeAllViews()
        note(Lang.t(this, "本设备不支持均衡器",
                        "This device has no equalizer"),
                Lang.t(this,
                        "均衡器走的是 Moondrop 私有 9ECA 协议，只有蓝讯 / 中科系主控的型号才提供这个服务。" +
                                "当前连接的耳机（或尚未连接）没有这个服务，所以没有可调的项目。",
                        "The equalizer uses Moondrop's private 9ECA protocol, which is only present on " +
                                "Bluetrum / Zhongke-based models. The connected headset does not expose it."))
    }

    private fun renderFailed() {
        content.removeAllViews()
        note(Lang.t(this, "读不到均衡器设置", "Could not read the equalizer"),
                Lang.t(this, "耳机没有回应。请确认耳机已连接且处于可控制状态，然后重新进入本页。",
                        "The headset did not respond. Make sure it is connected, then reopen this page."))
    }

    private fun renderPresets() {
        content.removeAllViews()
        content.addView(M3Ui.sectionTitle(this, pal, Lang.t(this, "预设", "Preset")),
                LinearLayout.LayoutParams(-1, -2))

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        val perRow = 3
        var row: LinearLayout? = null
        for (i in 0 until presetCount) {
            if (i % perRow == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                grid.addView(row, LinearLayout.LayoutParams(-1, -2))
                if (i > 0) grid.addView(spacer(dp(8)))
            }
            val b = presetChip(i)
            row!!.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i % perRow != 0) marginStart = dp(8)
            })
        }
        content.addView(M3Ui.groupCard(this, pal, grid), LinearLayout.LayoutParams(-1, -2))
        // 记住这一行，超时兜底靠它判断「已经渲染出来了」
        presetRow = grid
    }

    private fun presetChip(index: Int): TextView {
        val selected = index == currentPreset
        val t = TextView(this)
        t.text = Lang.t(this, "预设 " + (index + 1), "Preset " + (index + 1))
        t.textSize = 14f
        t.gravity = Gravity.CENTER
        t.minimumHeight = dp(48)
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(if (selected) pal.onPrimary else pal.onSurface)
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = dp(24).toFloat()
        bg.setColor(if (selected) pal.primary else pal.surfaceContainerHighest)
        if (!selected) bg.setStroke(M3Ui.hairline(this), pal.outline)
        t.background = bg
        t.setOnClickListener {
            if (index == currentPreset) return@setOnClickListener
            applyPreset(index)
        }
        return t
    }

    private fun applyPreset(index: Int) {
        val client = srcClient() ?: run { renderUnsupported(); return }
        // 乐观高亮：设备回包前先让用户看到「点下去了」，失败再回滚（与降噪按钮同一套做法）
        val prev = currentPreset
        currentPreset = index
        renderPresets()
        try {
            client.setPresetEq(index, object : BleSourceSwitchClient.SrcCallback<BleSourceProtocol.SrcPresetEqChange> {
                override fun onSuccess(value: BleSourceProtocol.SrcPresetEqChange?) = post {
                    if (!alive) return@post
                    value?.let { if (it.currentPreset >= 0) currentPreset = it.currentPreset }
                    renderPresets()
                    // 换了预设，PEQ 的读数也跟着变（用户预设与出厂预设是两套数据）
                    loadPeq()
                }
                override fun onError(message: String?) = post {
                    if (!alive) return@post
                    log("setPresetEq failed: " + message)
                    currentPreset = prev
                    renderPresets()
                    toast(Lang.t(this@EqActivity, "切换失败：" + message,
                            "Could not switch: " + message))
                }
            })
        } catch (t: Throwable) {
            log("setPresetEq threw: " + t)
            currentPreset = prev
            renderPresets()
        }
    }

    private fun renderPeqUnavailable() {
        // 预设区已经在界面上了，这里只补一段说明，不整页替换
        if (presetRow == null) {
            note(Lang.t(this, "读不到均衡器设置", "Could not read the equalizer"),
                    Lang.t(this, "耳机没有回应。请确认耳机已连接后再试。",
                            "The headset did not respond. Make sure it is connected."))
        } else {
            content.addView(spacer(dp(16)))
            content.addView(M3Ui.sectionTitle(this, pal,
                    Lang.t(this, "参量均衡", "Parametric EQ")), LinearLayout.LayoutParams(-1, -2))
            val t = TextView(this)
            t.text = Lang.t(this, "这副耳机不提供参量均衡数据。",
                    "This headset does not provide parametric EQ data.")
            t.textSize = 13f
            t.setTextColor(pal.onVariant)
            content.addView(M3Ui.groupCard(this, pal, pad(t)), LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun renderPeq(points: List<BleSourceProtocol.SrcPeqPoint>,
                          cfg: BleSourceProtocol.SrcPeqConfig) {
        if (presetRow == null) return
        content.addView(spacer(dp(16)))
        content.addView(M3Ui.sectionTitle(this, pal,
                Lang.t(this, "参量均衡（只读）", "Parametric EQ (read-only)")),
                LinearLayout.LayoutParams(-1, -2))

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL

        // 曲线
        val curve = EqCurveView(this, pal)
        curve.setData(points)
        box.addView(curve, LinearLayout.LayoutParams(-1, dp(180)))

        // 前级增益（单位与增益同源）
        val pre = TextView(this)
        pre.text = Lang.t(this, "前级增益 " + fmtDb(cfg.preGainRaw * GAIN_UNIT_DB) + " dB",
                "Pre-gain " + fmtDb(cfg.preGainRaw * GAIN_UNIT_DB) + " dB")
        pre.textSize = 13f
        pre.setTextColor(pal.onVariant)
        pre.setPadding(0, dp(10), 0, 0)
        box.addView(pre)

        val cnt = TextView(this)
        cnt.text = Lang.t(this, "读到 " + points.size + " / " + cfg.pointCount + " 段",
                "Read " + points.size + " of " + cfg.pointCount + " bands")
        cnt.textSize = 13f
        cnt.setTextColor(pal.onVariant)
        cnt.setPadding(0, dp(2), 0, 0)
        box.addView(cnt)

        content.addView(M3Ui.groupCard(this, pal, pad(box)), LinearLayout.LayoutParams(-1, -2))

        // 频点明细：只列增益非 0 的段。32 段全列出来读不下去，而增益为 0 的段
        // 对听感没有贡献，列了只是噪音。
        val active = points.filter { it.gainRaw != 0 }
        content.addView(spacer(dp(16)))
        content.addView(M3Ui.sectionTitle(this, pal,
                Lang.t(this, "已启用频点", "Active bands")), LinearLayout.LayoutParams(-1, -2))
        val listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        if (active.isEmpty()) {
            val t = TextView(this)
            t.text = Lang.t(this, "所有频点增益都是 0（未做任何调整）。",
                    "Every band has 0 dB gain (no adjustment applied).")
            t.textSize = 13f
            t.setTextColor(pal.onVariant)
            listBox.addView(pad(t), LinearLayout.LayoutParams(-1, -2))
        } else {
            for (p in active) {
                // groupCard 会为每个子视图各包一张卡片并留出间距，这里不需要自己排空行
                listBox.addView(pad(detailRow(p)), LinearLayout.LayoutParams(-1, -2))
            }
        }
        content.addView(M3Ui.groupCard(this, pal, listBox), LinearLayout.LayoutParams(-1, -2))

        holdHint()
    }

    private fun detailRow(p: BleSourceProtocol.SrcPeqPoint): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        val a = TextView(this)
        a.text = fmtFreq(p.frequencyHz) + "  ·  " + fmtDb(p.gainRaw * GAIN_UNIT_DB) + " dB"
        a.textSize = 14f
        a.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        a.setTextColor(pal.onSurface)
        l.addView(a, LinearLayout.LayoutParams(-1, -2))
        val b = TextView(this)
        b.text = Lang.t(this, "Q " + fmtQ(p.qRaw) + "  ·  " + filterName(p.filter),
                "Q " + fmtQ(p.qRaw) + "  ·  " + filterName(p.filter))
        b.textSize = 12f
        b.setTextColor(pal.onVariant)
        l.addView(b, LinearLayout.LayoutParams(-1, -2))
        return l
    }

    /** 只读页里的一行小字：说清数据的可信度，避免用户把推断值当成实测值。 */
    private fun holdHint() {
        content.addView(spacer(dp(12)))
        val t = TextView(this)
        t.text = Lang.t(this,
                "本页只能查看，不能修改。增益单位按协议同族的 0.01 dB 解释，尚未在真机上逐档核对。",
                "This page is read-only. Gain is interpreted as 0.01 dB to match the protocol's " +
                        "pre-gain field; that unit has not been verified band by band on real hardware.")
        t.textSize = 12f
        t.setTextColor(pal.onVariant)
        t.alpha = 0.85f
        content.addView(t, LinearLayout.LayoutParams(-1, -2))
    }

    private fun note(title: String, body: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val a = TextView(this)
        a.text = title
        a.textSize = 16f
        a.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        a.setTextColor(pal.onSurface)
        box.addView(a, LinearLayout.LayoutParams(-1, -2))
        val b = TextView(this)
        b.text = body
        b.textSize = 13f
        b.setTextColor(pal.onVariant)
        b.setLineSpacing(dp(2).toFloat(), 1.25f)
        b.setPadding(0, dp(6), 0, 0)
        box.addView(b, LinearLayout.LayoutParams(-1, -2))
        content.addView(M3Ui.groupCard(this, pal, pad(box)), LinearLayout.LayoutParams(-1, -2))
    }

    // ── 小工具 ───────────────────────────────────────────────

    private fun pad(v: View): View {
        val w = LinearLayout(this)
        w.orientation = LinearLayout.VERTICAL
        w.setPadding(dp(16), dp(16), dp(16), dp(16))
        w.addView(v, LinearLayout.LayoutParams(-1, -2))
        return w
    }

    private fun fmtDb(db: Double): String {
        // 一位小数就够看，-0.0 也要正常显示成 0.0
        val v = Math.round(db * 10.0) / 10.0
        return (if (v == 0.0) "0.0" else v.toString())
    }

    private fun fmtQ(qRaw: Int): String = (Math.round(qRaw / 100.0) / 10.0).toString()

    private fun fmtFreq(hz: Int): String =
            if (hz >= 1000) (Math.round(hz / 100.0) / 10.0).toString() + " kHz" else hz.toString() + " Hz"

    private fun filterName(f: Int): String = when (f) {
        BleSourceProtocol.FILTER_PEAKING -> Lang.t(this, "峰值", "Peaking")
        BleSourceProtocol.FILTER_LOW_SHELF -> Lang.t(this, "低架", "Low shelf")
        BleSourceProtocol.FILTER_HIGH_SHELF -> Lang.t(this, "高架", "High shelf")
        BleSourceProtocol.FILTER_LOW_PASS -> Lang.t(this, "低通", "Low pass")
        BleSourceProtocol.FILTER_HIGH_PASS -> Lang.t(this, "高通", "High pass")
        BleSourceProtocol.FILTER_BAND_PASS -> Lang.t(this, "带通", "Band pass")
        BleSourceProtocol.FILTER_NOTCH -> Lang.t(this, "陷波", "Notch")
        BleSourceProtocol.FILTER_ALL_PASS -> Lang.t(this, "全通", "All pass")
        else -> "—"
    }

    private fun dp(v: Int): Int = Math.round(resources.displayMetrics.density * v)

    private fun spacer(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun log(msg: String) = AppLog.i(TAG, "[Eq] " + msg)

    private fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) { }
    }

    /**
     * 频响曲线（只读）。
     *
     * 横轴对数频率 20 Hz – 20 kHz，纵轴增益 dB。只把各频点连成折线并标出点位 ——
     * 不做双二阶滤波器响应卷积：那是为了「预看编辑效果」，而本页不能编辑；
     * 折线已经够看清「哪几段被抬起来、抬了多少」。
     */
    private class EqCurveView(c: Context, private val pal: ThemeUtil.Palette) : View(c) {

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = pal.outline
            alpha = 90
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = pal.primary
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = pal.primary
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f
            color = pal.onVariant
        }
        private val path = Path()

        private var pts: List<BleSourceProtocol.SrcPeqPoint> = emptyList()
        private var spanDb = 12.0

        fun setData(list: List<BleSourceProtocol.SrcPeqPoint>) {
            // 频点按频率排序后再连线，否则顺序就是 0..31 的索引，折线会来回横跳
            pts = list.sortedBy { it.frequencyHz }
            // 纵轴固定 ±12 dB 会出现「数据超出画面」；按最大值扩到整档，至少 12 dB
            val peak = pts.maxOfOrNull { Math.abs(it.gainRaw * GAIN_UNIT_DB) } ?: 0.0
            spanDb = Math.max(12.0, Math.ceil(peak / 6.0) * 6.0)
            invalidate()
        }

        private fun xOf(hz: Int, w: Float, padL: Float, padR: Float): Float {
            val f = hz.coerceIn(20, 20000).toDouble()
            val t = (Math.log10(f) - Math.log10(20.0)) / (Math.log10(20000.0) - Math.log10(20.0))
            return padL + (w - padL - padR) * t.toFloat()
        }

        private fun yOf(db: Double, h: Float, padT: Float, padB: Float): Float {
            val t = (db + spanDb) / (2 * spanDb)
            return padT + (h - padT - padB) * (1 - t).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            val padL = 46f
            val padR = 16f
            val padT = 14f
            val padB = 30f

            // 横向网格：每 6 dB 一条，0 dB 用主线色便于对齐
            var db = -spanDb
            while (db <= spanDb + 0.001) {
                val y = yOf(db, h, padT, padB)
                canvas.drawLine(padL, y, w - padR, y, gridPaint)
                canvas.drawText((if (db > 0) "+" else "") + db.toInt(), 6f, y + 7f, textPaint)
                db += 6.0
            }
            // 纵向网格：100 / 1k / 10k
            for (f in intArrayOf(100, 1000, 10000)) {
                val x = xOf(f, w, padL, padR)
                canvas.drawLine(x, padT, x, h - padB, gridPaint)
                val label = if (f >= 1000) (f / 1000).toString() + "k" else f.toString()
                canvas.drawText(label, x - 14f, h - 8f, textPaint)
            }

            if (pts.size < 2) return

            path.reset()
            pts.forEachIndexed { i, p ->
                val x = xOf(p.frequencyHz, w, padL, padR)
                val y = yOf(p.gainRaw * GAIN_UNIT_DB, h, padT, padB)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)

            for (p in pts) {
                val x = xOf(p.frequencyHz, w, padL, padR)
                val y = yOf(p.gainRaw * GAIN_UNIT_DB, h, padT, padB)
                // 增益为 0 的点画小一点：它们只是坐标，视觉上不该和真正生效的段抢注意力
                canvas.drawCircle(x, y, if (p.gainRaw == 0) 2.5f else 5f, dotPaint)
            }
        }
    }
}
