package com.fxxkmoondrop.secret

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.slider.Slider

/**
 * 均衡器（正式入口）：经典蓝牙 SPP，0A 05 读 / 0A 06 写，0A 01+0A 02 读预设、0A 03 切档。
 *
 * 不绑定型号：
 *  · 设备只挑已配对的水月雨设备（多台可选），绝不拿别家耳机试协议；
 *  · 段数由回包长度反推（MoondropEq），不写死 5 段；
 *  · 读完做「解码→回编码」自洽校验，不成立整页只读，避免拿不确定的布局写坏别人；
 *  · 写入以「回读逐字节一致」为唯一成功判据（设备对 0A 06 不回 ACK）。
 *
 * 界面按 Material 3（Expressive 取向）重排：sectionTitle + 分组卡、M3 Slider（带数值气泡）、
 * 药丸按钮、圆角 chip 选预设；间距/字号沿用本应用 M3Ui 那套规范值。
 */
class EqEditorActivity : Activity() {

    private lateinit var pal: ThemeUtil.Palette
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var retryBtn: TextView
    private lateinit var errorBox: LinearLayout
    private lateinit var loading: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var applyBtn: TextView
    private lateinit var curveView: EqCurveView
    private lateinit var paramsBox: LinearLayout
    private lateinit var presetBox: LinearLayout

    private val infoV = ArrayList<TextView>()
    private val gainV = ArrayList<TextView>()
    private val sliders = ArrayList<Slider>()

    private var profile: MoondropEq.Profile? = null
    private var editable = false
    private var gMin = -12f
    private var gMax = 3f

    private var presetIds: IntArray = intArrayOf(0, 1, 2)
    private var currentPreset = -1
    private var presetKnown = false

    @Volatile private var alive = false
    private var addr: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = ThemeUtil.Palette(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        val sbh = resources.getIdentifier("status_bar_height", "dimen", "android")
                .let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
        root.setPadding(dp(12), sbh + dp(8), dp(12), dp(16))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(M3Ui.arrowBack(this, pal) { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = TextView(this)
        title.text = Lang.t("均衡器", "Equalizer")
        title.textSize = 20f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setTextColor(pal.onSurface)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(8) })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL

        // 加载态：整页显示进度圈 + 一行说明（读不到参数时不展示半成品界面）
        loading = LinearLayout(this)
        loading.orientation = LinearLayout.VERTICAL
        loading.gravity = Gravity.CENTER_HORIZONTAL
        loading.setPadding(0, dp(72), 0, dp(72))
        // 官方 M3 加载指示器：直径 48dp、轨道 4dp（Material3 规范值）
        val ind = com.google.android.material.loadingindicator.LoadingIndicator(this)
        ind.indicatorSize = dp(48)
        ind.setIndicatorColor(pal.primary)
        loading.addView(ind)
        val lt = TextView(this)
        lt.text = Lang.t("正在读取耳机参数…", "Reading from headset…")
        lt.textSize = 13f
        lt.setTextColor(pal.onVariant)
        lt.gravity = Gravity.CENTER
        lt.setPadding(0, dp(16), 0, 0)
        loading.addView(lt)
        body.addView(loading, LinearLayout.LayoutParams(-1, -2))

        // 出错时才用的一行说明（成功不再显示任何“已读取”字样）
        status = TextView(this)
        status.textSize = 12f
        status.setTextColor(pal.onVariant)
        status.setPadding(dp(12), dp(2), dp(12), dp(8))
        errorBox = LinearLayout(this)
        errorBox.orientation = LinearLayout.VERTICAL
        errorBox.gravity = Gravity.CENTER_HORIZONTAL
        errorBox.setPadding(dp(24), dp(48), dp(24), dp(24))
        errorBox.visibility = View.GONE
        body.addView(errorBox, LinearLayout.LayoutParams(-1, -2))

        status = status.apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        errorBox.addView(status, LinearLayout.LayoutParams(-1, -2))

        retryBtn = m3Text(Lang.t("重试", "Retry")) { doRead(null) }
        errorBox.addView(retryBtn, LinearLayout.LayoutParams(-2, dp(40)).also {
            it.topMargin = dp(8); it.gravity = Gravity.CENTER_HORIZONTAL
        })

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.visibility = View.GONE
        body.addView(content, LinearLayout.LayoutParams(-1, -2))

        // 频响预览卡
        curveView = EqCurveView()
        val curveHolder = LinearLayout(this)
        curveHolder.setPadding(dp(8), dp(8), dp(8), dp(8))
        curveHolder.addView(curveView, LinearLayout.LayoutParams(-1, dp(132)))
        content.addView(M3Ui.groupCard(this, pal, curveHolder), LinearLayout.LayoutParams(-1, -2))

        contentOf(content)

        val sv = ScrollView(this)
        sv.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        M3Ui.fitSystemBars(this, pal)

        alive = true
        doRead(null)
    }

    /**
     * 内容区结构（顺序按设计：参数 → 应用 → 预设 → 操作）。
     * 尺寸全部走 Material 3 规范值：按钮高 40dp / 圆角 20dp / 14sp，chip 高 32dp / 圆角 8dp / 14sp。
     */
    private fun contentOf(body: LinearLayout) {
        body.addView(M3Ui.sectionTitle(this, pal, Lang.t("参数", "Bands")))
        paramsBox = LinearLayout(this)
        paramsBox.orientation = LinearLayout.VERTICAL
        body.addView(paramsBox, LinearLayout.LayoutParams(-1, -2))

        // 应用：M3 Filled Button（40dp 高、20dp 圆角、14sp），位置在参数卡下方、预设上方
        applyBtn = m3Filled(Lang.t("应用", "Apply")) { doWrite() }
        applyBtn.isEnabled = false
        applyBtn.alpha = 0.38f
        body.addView(applyBtn, LinearLayout.LayoutParams(-2, -2).also {
            it.topMargin = dp(12); it.bottomMargin = dp(8)
            it.gravity = Gravity.CENTER_HORIZONTAL
        })

        body.addView(M3Ui.sectionTitle(this, pal, Lang.t("预设", "Presets")))
        val presetScroll = android.widget.HorizontalScrollView(this)
        presetScroll.isHorizontalScrollBarEnabled = false
        presetBox = LinearLayout(this)
        presetBox.orientation = LinearLayout.HORIZONTAL
        presetBox.setPadding(dp(20), dp(2), dp(20), dp(6))
        presetScroll.addView(presetBox, LinearLayout.LayoutParams(-2, -2))
        body.addView(presetScroll, LinearLayout.LayoutParams(-1, -2))

        body.addView(M3Ui.sectionTitle(this, pal, Lang.t("操作", "Actions")))
        val ops = LinearLayout(this)
        ops.orientation = LinearLayout.HORIZONTAL
        ops.gravity = Gravity.CENTER_HORIZONTAL
        ops.setPadding(dp(12), dp(2), dp(12), dp(6))
        val opLp = { LinearLayout.LayoutParams(-2, -2) }
        ops.addView(m3Text(Lang.t("重新读取", "Reload")) { doRead(null) },
                opLp().also { it.marginEnd = dp(8) })
        ops.addView(m3Text(Lang.t("自动预增益", "Auto preamp")) {
            profile?.let { profile = MoondropEq.withAutoPreamp(it); renderAll() }
        }, opLp().also { it.marginEnd = dp(8) })
        ops.addView(m3Text(Lang.t("全部归零", "Flatten")) {
            profile?.let { p ->
                p.bands.forEach { it.gainDb = 0f }
                renderAll()
            }
        }, opLp())
        body.addView(ops, LinearLayout.LayoutParams(-1, -2))

        val hint = TextView(this)
        hint.textSize = 12f
        hint.setTextColor(pal.onVariant)
        hint.setPadding(dp(20), dp(10), dp(20), dp(8))
        hint.text = Lang.t(
                "读写耳机内的参数均衡（预增益 + 每段频点/增益/Q）。切换预设会覆盖当前调音，应用前先确认。",
                "Edits the parametric EQ stored in the headset. Switching a preset replaces the current tuning.")
        body.addView(hint, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * 官方 M3 Filled Button：直接套 ?attr/materialButtonStyle（Material3 规范自带
     * 40dp 高、20dp 圆角、14sp、横向 24dp 内边距、inset 为 0），这里只负责内容和点击。
     */
    private fun m3Filled(text: String, onClick: () -> Unit): TextView {
        val b = com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonStyle)
        b.text = text
        b.isAllCaps = false
        // Material3 规范：高 40dp；官方样式默认 minHeight 48dp + 6dp inset，这里按规范收紧
        b.insetTop = 0
        b.insetBottom = 0
        b.minHeight = dp(40)
        b.minimumHeight = dp(40)
        b.setPadding(dp(24), 0, dp(24), 0)
        b.setOnClickListener { onClick() }
        return b
    }

    /**
     * 官方 M3 Text Button：MaterialButton + 透明底 + primary 文字，
     * 尺寸按规范：高 40dp、圆角 20dp、14sp、横向 12dp 内边距。
     */
    /** 官方 M3 Text Button：从 view_m3_text_button.xml 里 inflate（style=Widget.Material3.Button.TextButton） */
    private fun m3Text(text: String, onClick: () -> Unit): TextView {
        val b = android.view.LayoutInflater.from(this)
                .inflate(R.layout.view_m3_text_button, null, false) as com.google.android.material.button.MaterialButton
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    /** 成功状态不显示任何「已读取/已应用」字样（按设计要求） */
    private fun clearStatus() {
        errorBox.visibility = View.GONE
    }

    /** 出错：只显示一行说明 + 重试，内容区收起（不留空壳） */
    private fun showError(msg: String) {
        loading.visibility = View.GONE
        content.visibility = View.GONE
        status.text = msg
        status.setTextColor(pal.red)
        errorBox.visibility = View.VISIBLE
    }

    /** 加载态：整页转圈；只有拿到数据才露出内容 */
    private fun setLoading() {
        loading.visibility = View.VISIBLE
        content.visibility = View.GONE
        errorBox.visibility = View.GONE
    }

    private fun showContent() {
        loading.visibility = View.GONE
        content.visibility = View.VISIBLE
        clearStatus()
    }

    private fun setApplyEnabled(on: Boolean) {
        applyBtn.isEnabled = on
        applyBtn.alpha = if (on) 1f else 0.38f
    }

    // ───────────────────────── 参数行（M3 Slider） ─────────────────────────

    private fun rebuildParams(p: MoondropEq.Profile) {
        infoV.clear(); gainV.clear(); sliders.clear()
        // 范围跟着数据走：默认 -12..+3 dB（官方文档范围），设备里本来更极端就扩出去，别截断人家参数
        gMin = Math.min(-12f, Math.floor(p.bands.minOf { it.gainDb }.toDouble()).toFloat())
        gMax = Math.max(3f, Math.ceil(p.bands.maxOf { it.gainDb }.toDouble()).toFloat())

        val rows = ArrayList<View>()
        for (i in p.bands.indices) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(16), dp(10), dp(16), dp(2))

            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            val info = TextView(this)
            info.textSize = 14f
            info.setTextColor(pal.onSurface)
            top.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val gain = TextView(this)
            gain.textSize = 14f
            gain.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gain.setTextColor(pal.primary)
            top.addView(gain, LinearLayout.LayoutParams(-2, -2))
            row.addView(top, LinearLayout.LayoutParams(-1, -2))

            val sl = Slider(this)
            sl.valueFrom = gMin
            sl.valueTo = gMax
            // 不设 stepSize：M3 的刻度点只在有意义的步长时才该出现，
            // 0.1 dB 细分会让整条轨道变成一串圆点（真机截图复现过）。精度在回调里四舍五入。
            sl.value = (Math.round(p.bands[i].gainDb * 10) / 10f).coerceIn(gMin, gMax)
            sl.setLabelFormatter { v -> String.format("%+.1f dB", v) }
            // 颜色交给主题（动态取色的 primary/secondary），Slider 的 track/thumb 不公开可写
            sl.isEnabled = editable
            sl.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val pf = profile ?: return@addOnChangeListener
                pf.bands.getOrNull(i) ?: return@addOnChangeListener
                pf.bands[i].gainDb = Math.round(value * 10) / 10f
                renderBand(i); paint()
            }
            row.addView(sl, LinearLayout.LayoutParams(-1, -2))
            row.setOnClickListener { editBand(i) }
            info.setOnClickListener { editBand(i) }

            rows.add(row)
            infoV.add(info); gainV.add(gain); sliders.add(sl)
            if (i < p.bands.size - 1) rows.add(divider())
        }
        val foot = TextView(this)
        foot.textSize = 12f
        foot.setTextColor(pal.onVariant)
        foot.setPadding(dp(16), dp(6), dp(16), dp(12))
        rows.add(foot)

        paramsBox.removeAllViews()
        paramsBox.addView(M3Ui.groupCard(this, pal, *rows.toTypedArray()), LinearLayout.LayoutParams(-1, -2))
        renderAll()
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(pal.divider)
        layoutParams = LinearLayout.LayoutParams(-1, Math.max(1, dp(1)))
    }

    private fun renderBand(i: Int) {
        val b = profile?.bands?.getOrNull(i) ?: return
        infoV.getOrNull(i)?.text = Lang.t("频点 ", "Freq ") + b.freq + " Hz   Q " + String.format("%.2f", b.q)
        gainV.getOrNull(i)?.text = String.format("%+.1f dB", b.gainDb)
        val sl = sliders.getOrNull(i) ?: return
        val want = (Math.round(b.gainDb * 10) / 10f).coerceIn(gMin, gMax)
        if (Math.abs(sl.value - want) > 0.05f) sl.value = want
    }

    private fun renderAll() {
        val p = profile ?: return
        for (i in p.bands.indices) renderBand(i)
        paint()
        // 卡片脚注：预增益 + 段数（设备自己告诉我们的段数）
        paramsBox.getChildAt(0)?.let { card ->
            val box = (card as? android.view.ViewGroup)?.getChildAt(0) as? android.view.ViewGroup
            val foot = box?.getChildAt(box.childCount - 1) as? TextView
            foot?.text = Lang.t("预增益 ", "Preamp ") + String.format("%+.1f dB", p.preampDb) +
                    Lang.t("（防叠加削波）", " (anti-clipping)") +
                    Lang.t("　共 ", "　") + p.bands.size + Lang.t(" 段", " bands")
        }
    }

    private fun paint() { profile?.let { curveView.setProfile(it) } }

    private fun editBand(i: Int) {
        val b = profile?.bands?.getOrNull(i) ?: return
        if (!editable) {
            showError(Lang.t("这台设备的载荷未通过自洽校验，只能查看", "Read-only: layout not verified"))
            return
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(4), dp(20), 0)
        val f = EditText(this)
        f.inputType = InputType.TYPE_CLASS_NUMBER
        f.hint = Lang.t("频点 (Hz)", "Frequency (Hz)")
        f.setText(b.freq.toString())
        val q = EditText(this)
        q.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        q.hint = "Q"
        q.setText(String.format("%.2f", b.q))
        box.addView(f); box.addView(q)
        AlertDialog.Builder(this)
                .setTitle(Lang.t("第 ", "Band ") + (i + 1) + Lang.t(" 段", ""))
                .setView(box)
                .setPositiveButton(Lang.t("确定", "OK")) { _, _ ->
                    f.text.toString().trim().toIntOrNull()?.let { if (it in 20..20000) b.freq = it }
                    q.text.toString().trim().toFloatOrNull()?.let { if (it in 0.1f..12f) b.q = it }
                    renderBand(i); paint()
                }
                .setNegativeButton(Lang.t("取消", "Cancel"), null)
                .show()
    }

    // ───────────────────────── 预设（0A 01 / 0A 02 / 0A 03） ─────────────────────────

    private fun rebuildPresets() {
        presetBox.removeAllViews()
        if (!presetKnown) {
            // 设备没回预设表：只提示，不编名字
            val t = TextView(this)
            t.text = Lang.t("读取不到预设列表", "Preset list unavailable")
            t.textSize = 12f
            t.setTextColor(pal.onVariant)
            t.setPadding(dp(12), dp(8), dp(12), dp(8))
            presetBox.addView(t)
            return
        }
        for (id in presetIds) {
            val chip = pill(presetLabel(id), id == currentPreset) { setPreset(id) }
            presetBox.addView(chip, LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(8) })
        }
    }

    /** 设备只回下标不回名字：0/1/2 是我们实测过的三档，其余只标编号，避免给错名字 */
    private fun presetLabel(id: Int): String = when (id) {
        0 -> Lang.t("流行", "Pop")
        1 -> Lang.t("标准", "Standard")
        2 -> Lang.t("监听", "Monitor")
        else -> Lang.t("预设 ", "Preset ") + (id + 1)
    }

    /** 布局不认识时的占位（只读提示） */
    private fun showPlaceholder() {
        paramsBox.removeAllViews()
        val t = TextView(this)
        t.text = Lang.t("这台设备的载荷布局与已知格式不同", "Payload layout doesn't match the known format")
        t.textSize = 13f
        t.setTextColor(pal.onVariant)
        t.setPadding(dp(20), dp(16), dp(20), dp(16))
        paramsBox.addView(M3Ui.groupCard(this, pal, t), LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * 官方 M3 Filter Chip：Material Chip + 规范尺寸（高 32dp、圆角 8dp、14sp、横向 16dp），
     * 未选中 1dp 描边、选中 secondaryContainer。
     */
    private fun pill(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val c = com.google.android.material.chip.Chip(this)
        c.text = text
        c.textSize = 14f
        c.isCheckable = true
        c.isChecked = selected
        c.isChipIconVisible = false
        c.isCheckedIconVisible = false
        c.chipMinHeight = dp(32).toFloat()
        c.setPadding(dp(16), dp(8), dp(16), dp(8))
        c.shapeAppearanceModel = c.shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(dp(8).toFloat()).build()
        c.chipStrokeWidth = dp(1).toFloat()
        c.chipStrokeColor = cs(intArrayOf() to pal.outline)
        c.chipBackgroundColor = cs(
                intArrayOf(android.R.attr.state_checked) to pal.container,
                intArrayOf() to android.graphics.Color.TRANSPARENT)
        c.setTextColor(cs(
                intArrayOf(android.R.attr.state_checked) to pal.onContainer,
                intArrayOf() to pal.onSurface))
        c.setOnClickListener { onClick() }
        return c
    }

    private fun cs(vararg pairs: Pair<IntArray, Int>): android.content.res.ColorStateList {
        val states = arrayOfNulls<IntArray>(pairs.size)
        val colors = IntArray(pairs.size)
        pairs.forEachIndexed { i, p -> states[i] = p.first; colors[i] = p.second }
        @Suppress("UNCHECKED_CAST")
        return android.content.res.ColorStateList(states as Array<IntArray>, colors)
    }

    // ───────────────────────── 设备与协议 ─────────────────────────

    private fun bondedMoondrop(): List<android.bluetooth.BluetoothDevice> {
        val bonded = try {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
        } catch (_: Throwable) { emptyList() }
        return bonded.filter { DeviceMatcher.isMoondrop(try { it.name } catch (_: Throwable) { null }) }
    }

    private fun resolveDevice(onReady: (String?) -> Unit) {
        addr?.let { onReady(it); return }
        val md = bondedMoondrop()
        if (md.isEmpty()) { onReady(null); return }
        if (md.size == 1) {
            addr = try { md[0].address } catch (_: Throwable) { null }
            onReady(addr); return
        }
        val names = md.map { (try { it.name } catch (_: Throwable) { null } ?: "?") }.toTypedArray()
        AlertDialog.Builder(this)
                .setTitle(Lang.t("选择水月雨设备", "Choose a Moondrop device"))
                .setItems(names) { _, which ->
                    addr = try { md[which].address } catch (_: Throwable) { null }
                    doRead(null)
                }
                .setNegativeButton(Lang.t("取消", "Cancel"), null)
                .show()
    }

    private fun doRead(afterWrite: ByteArray?) {
        // 关键：doWrite / setPreset 在后台线程完成后会回调这里，而 setLoading() 会动 View。
        // 不在主线程就先抛回主线程，避免 CalledFromWrongThreadException（真机崩溃堆栈见 260 行）。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            ui.post { if (alive) doRead(afterWrite) }
            return
        }
        setLoading()
        resolveDevice { target ->
            if (target == null) {
                showError(Lang.t("没有已配对的水月雨设备", "No bonded Moondrop device")); return@resolveDevice
            }
            Thread {
                if (!MoondropSpp.isConnected() && !MoondropSpp.connect(target)) {
                    ui.post { if (alive) showError(Lang.t("SPP 连接失败（耳机被官方 App 占用或已休眠？）", "SPP connect failed")) }
                    return@Thread
                }
                // 预设表/当前档位：和曲线同一次连接里一并读，读完一起断开
                val ids = MoondropSpp.readPresetIds()
                val cur = MoondropSpp.readCurrentPreset()
                val raw = MoondropSpp.readCurve()
                if (raw == null) {
                    ui.post {
                        if (!alive) return@post
                        showError(Lang.t("读不到曲线：这台设备可能不支持该协议", "No curve reply — protocol not supported"))
                    }
                    return@Thread
                }
                if (afterWrite != null && !afterWrite.contentEquals(raw)) {
                    ui.post { if (alive) showError(Lang.t("写入后回读不一致（设备没接受这组参数）", "Read-back mismatch")) }
                    return@Thread
                }
                val pr = MoondropEq.decode(raw)
                val ok = pr != null && MoondropEq.roundTrips(raw)
                ui.post {
                    if (!alive) return@post
                    presetKnown = ids != null
                    if (ids != null) presetIds = ids
                    currentPreset = cur
                    rebuildPresets()
                    if (pr == null) {
                        showError(Lang.t("载荷布局不认识（", "Unrecognised payload (") + raw.size +
                                Lang.t(" 字节）", " bytes)"))
                        editable = false; setApplyEnabled(false)
                        showContent()
                        showPlaceholder()
                    } else {
                        profile = pr
                        editable = ok
                        rebuildParams(pr)
                        showContent()
                        setApplyEnabled(ok)
                        if (!ok) showError(Lang.t("这台设备的载荷没能自洽还原，只能查看",
                                "Layout not self-consistent — read only"))
                    }
                }
            }.start()
        }
    }

    @Volatile private var busy = false

    private fun doWrite() {
        val p = profile ?: return
        if (!editable) { showError(Lang.t("当前为只读模式", "Read-only mode")); return }
        if (busy) return
        val target = addr
        if (target == null) { showError(Lang.t("没有已配对的水月雨设备", "No bonded Moondrop device")); return }
        // 写之前按官方文档范围钳制（频点 20–20000 Hz、增益 −12…+3 dB、Q 0.1…12、预增益 −24…0）。
        // 越界系数会让耳机 DSP 自己重启 —— 这是「写完之后耳机反复重连」最合理的软件诱因。
        val safe = p.duplicate()
        safe.preampDb = safe.preampDb.coerceIn(-24f, 0f)
        safe.bands.forEach { b ->
            b.freq = b.freq.coerceIn(20, 20000)
            b.gainDb = b.gainDb.coerceIn(-12f, 3f)
            b.q = b.q.coerceIn(0.1f, 12f)
        }
        val raw = MoondropEq.encode(safe)
        busy = true
        // 注意：这里不立刻 setEnabled(false)——那会吞掉 MaterialButton 的点击波纹与状态动画；
        // 用 busy 标记防重入，写完再决定按钮状态。
        Thread {
            // 读完就会断开（不长期占链路），写之前自己把链路建起来
            if (!MoondropSpp.isConnected() && !MoondropSpp.connect(target)) {
                ui.post { busy = false; if (alive) { setApplyEnabled(editable); showError(Lang.t("写入前建链失败", "Connect failed")) } }
                return@Thread
            }
            val ok = MoondropSpp.writeCurve(raw)
            ui.post {
                busy = false
                if (!alive) return@post
                setApplyEnabled(editable)
                if (!ok) showError(Lang.t("写入失败（链路断开？）", "Write failed"))
            }
            if (ok) { Thread.sleep(500); doRead(raw) }
        }.start()
    }

    private fun setPreset(idx: Int) {
        val target = addr ?: run {
            showError(Lang.t("没有已配对的水月雨设备", "No bonded Moondrop device")); return
        }
        Thread {
            if (!MoondropSpp.isConnected() && !MoondropSpp.connect(target)) {
                ui.post { if (alive) showError(Lang.t("切换预设前建链失败", "Connect failed")) }
                return@Thread
            }
            val r = MoondropSpp.setPreset(idx)
            ui.post {
                if (!alive) return@post
                if (r == null) showError(Lang.t("切换预设失败", "Preset switch failed"))
                else { currentPreset = idx; rebuildPresets() }
            }
            if (r != null) { Thread.sleep(300); doRead(null) }
        }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 页面可见期间保持 SPP（避免每次读写都重开 RFCOMM 导致耳机反复重连），离开就断开 */
    override fun onStop() {
        MoondropSpp.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        alive = false
        MoondropSpp.disconnect()
        super.onDestroy()
    }

    /** 频响预览：对数频率轴 + 自动纵向缩放 */
    private inner class EqCurveView : View(this@EqEditorActivity) {
        private val curve = Path()
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.outline; style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); alpha = 60
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.primary; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.primary; style = Paint.Style.FILL; alpha = 30
        }
        private var prof: MoondropEq.Profile? = null

        fun setProfile(p: MoondropEq.Profile) { prof = p; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            for (k in 1..3) canvas.drawLine(0f, h * k / 4f, w, h * k / 4f, grid)
            val p = prof ?: return
            val fMin = 20.0; val fMax = 20000.0
            val n = 160
            val vals = DoubleArray(n + 1)
            var lo = 0.0; var hi = 0.0
            for (i in 0..n) {
                val f = fMin * Math.pow(fMax / fMin, i.toDouble() / n)
                vals[i] = MoondropEq.magnitudeDb(p, f)
                if (i == 0 || vals[i] < lo) lo = vals[i]
                if (i == 0 || vals[i] > hi) hi = vals[i]
            }
            val span = Math.max(6.0, hi - lo)
            val mid = (hi + lo) / 2
            curve.reset()
            for (i in 0..n) {
                val x = w * i / n
                val y = (h / 2 - (vals[i] - mid) / span * h * 0.9).toFloat()
                if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
            }
            val area = Path(curve)
            area.lineTo(w, h); area.lineTo(0f, h); area.close()
            canvas.drawPath(area, fill)
            canvas.drawPath(curve, line)
        }
    }
}
