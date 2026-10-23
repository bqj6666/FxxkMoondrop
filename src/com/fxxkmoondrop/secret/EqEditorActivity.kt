package com.fxxkmoondrop.secret

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * 均衡器（正式入口）：走经典蓝牙 SPP 的 0A 05 读 / 0A 06 写。
 *
 * 面向「水月雨 SPP 均衡器」这个协议族，不绑定型号：
 *  · 设备：只挑已配对的水月雨设备（DeviceMatcher 判定），有多台就让你选，绝不拿非水月雨设备试；
 *  · 段数：由设备回包长度反推（见 MoondropEq），不写死 5 段；
 *  · 写入判定：读完先做「解码→回编码」自洽检验，只有逐字节还原成功才允许写，
 *    否则整页转只读并说明原因 —— 宁可少功能，也不拿不确定的布局去写别人的耳机；
 *  · 写入结果：设备对 0A 06 不回 ACK，所以写完立刻回读逐字节比对，以回读为准。
 */
class EqEditorActivity : Activity() {

    private lateinit var pal: ThemeUtil.Palette
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var preview: EqCurveView
    private lateinit var rowsBox: LinearLayout
    private lateinit var preampLabel: TextView
    private lateinit var writeBtn: TextView

    private val infoV = ArrayList<TextView>()
    private val gainV = ArrayList<TextView>()
    private val bars = ArrayList<SeekBar>()

    private var profile: MoondropEq.Profile? = null
    private var editable = false
    private var gMin = -12f
    private var gMax = 3f

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

        status = TextView(this)
        status.textSize = 12f
        status.setTextColor(pal.onVariant)
        status.setPadding(dp(12), dp(6), dp(12), dp(6))
        status.text = Lang.t("正在连接耳机…", "Connecting…")
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        preview = EqCurveView()
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(150)).also { it.topMargin = dp(4) })

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL

        rowsBox = LinearLayout(this)
        rowsBox.orientation = LinearLayout.VERTICAL
        body.addView(rowsBox, LinearLayout.LayoutParams(-1, -2))

        preampLabel = TextView(this)
        preampLabel.textSize = 12f
        preampLabel.setTextColor(pal.onVariant)
        preampLabel.setPadding(dp(12), dp(4), dp(12), dp(8))
        body.addView(preampLabel, LinearLayout.LayoutParams(-1, -2))

        val ops = LinearLayout(this)
        ops.orientation = LinearLayout.HORIZONTAL
        ops.setPadding(dp(4), dp(4), dp(4), dp(4))
        writeBtn = addBtn(ops, Lang.t("写入耳机", "Write")) { doWrite() }
        addBtn(ops, Lang.t("重新读取", "Reload")) { doRead(null) }
        addBtn(ops, Lang.t("自动预增益", "Auto preamp")) {
            val p = profile ?: return@addBtn
            profile = MoondropEq.withAutoPreamp(p)
            renderAll()
        }
        addBtn(ops, Lang.t("全部归零", "Flatten")) {
            val p = profile ?: return@addBtn
            p.bands.forEach { it.gainDb = 0f }
            renderAll()
            setStatus(Lang.t("已归零，点「写入耳机」生效", "Flattened — tap Write"), false)
        }
        body.addView(ops, LinearLayout.LayoutParams(-1, -2))

        val presets = LinearLayout(this)
        presets.orientation = LinearLayout.HORIZONTAL
        presets.setPadding(dp(4), 0, dp(4), dp(8))
        addBtn(presets, Lang.t("流行", "Pop")) { setPreset(0) }
        addBtn(presets, Lang.t("标准", "Standard")) { setPreset(1) }
        addBtn(presets, Lang.t("监听", "Monitor")) { setPreset(2) }
        body.addView(presets, LinearLayout.LayoutParams(-1, -2))

        val hint = TextView(this)
        hint.textSize = 11f
        hint.setTextColor(pal.onVariant)
        hint.setPadding(dp(16), dp(4), dp(16), dp(8))
        hint.text = Lang.t(
                "读写的是耳机里那组参数均衡（预增益 + 每段频点/增益/Q）。切换预设会覆盖当前调音。",
                "This edits the parametric EQ stored in the headset (preamp + per-band frequency/gain/Q).")
        body.addView(hint, LinearLayout.LayoutParams(-1, -2))

        val sv = ScrollView(this)
        sv.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        M3Ui.fitSystemBars(this, pal)
        writeBtn.isEnabled = false

        alive = true
        doRead(null)
    }

    // ───────────────────────── UI ─────────────────────────

    private fun addBtn(parent: LinearLayout, text: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = text
        b.textSize = 13f
        b.gravity = Gravity.CENTER
        b.setTextColor(pal.primary)
        b.setPadding(dp(8), dp(12), dp(8), dp(12))
        b.background = M3Ui.pressRipple(this, null)
        b.setOnClickListener { onClick() }
        parent.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        return b
    }

    private fun setStatus(msg: String, error: Boolean = false) {
        status.text = msg
        status.setTextColor(if (error) pal.red else pal.onVariant)
    }

    /** 段数由设备回包决定，所以行是解码之后才建 */
    private fun rebuildRows(p: MoondropEq.Profile) {
        rowsBox.removeAllViews()
        infoV.clear(); gainV.clear(); bars.clear()
        // 滑块范围跟着数据走：默认 -12..+3 dB（官方文档范围），设备里若本来就有更极端的值就扩出去，
        // 免得一进页面就把人家的参数截断。
        gMin = Math.min(-12f, p.bands.minOf { it.gainDb }.let { Math.floor(it.toDouble()).toFloat() })
        gMax = Math.max(3f, p.bands.maxOf { it.gainDb }.let { Math.ceil(it.toDouble()).toFloat() })
        for (i in p.bands.indices) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(12), dp(8), dp(12), dp(4))

            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            val info = TextView(this)
            info.textSize = 13f
            info.setTextColor(pal.onSurface)
            top.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val gain = TextView(this)
            gain.textSize = 13f
            gain.typeface = Typeface.MONOSPACE
            gain.setTextColor(pal.primary)
            top.addView(gain, LinearLayout.LayoutParams(-2, -2))
            row.addView(top, LinearLayout.LayoutParams(-1, -2))

            val bar = SeekBar(this)
            bar.max = Math.round((gMax - gMin) * 10)
            bar.isEnabled = editable
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, pr: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val pf = profile ?: return
                    pf.bands.getOrNull(i) ?: return
                    pf.bands[i].gainDb = Math.round((gMin + pr / 10f) * 10) / 10f
                    renderBand(i); paint()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            row.addView(bar, LinearLayout.LayoutParams(-1, dp(36)))
            row.setOnClickListener { editBand(i) }
            info.setOnClickListener { editBand(i) }

            rowsBox.addView(row, LinearLayout.LayoutParams(-1, -2))
            infoV.add(info); gainV.add(gain); bars.add(bar)
        }
    }

    private fun renderBand(i: Int) {
        val b = profile?.bands?.getOrNull(i) ?: return
        infoV.getOrNull(i)?.text = Lang.t("频点 ", "Freq ") + b.freq + " Hz   Q " + String.format("%.2f", b.q)
        gainV.getOrNull(i)?.text = String.format("%+.1f dB", b.gainDb)
        val bar = bars.getOrNull(i) ?: return
        val want = Math.round((b.gainDb - gMin) * 10).coerceIn(0, bar.max)
        if (bar.progress != want) bar.progress = want
    }

    private fun renderAll() {
        val p = profile ?: return
        for (i in p.bands.indices) renderBand(i)
        preampLabel.text = Lang.t("预增益 ", "Preamp ") + String.format("%+.1f dB", p.preampDb) +
                Lang.t("（防叠加削波）", " (anti-clipping)") +
                Lang.t("　共 ", "　") + p.bands.size + Lang.t(" 段", " bands")
        paint()
    }

    private fun paint() { profile?.let { preview.setProfile(it) } }

    private fun editBand(i: Int) {
        val b = profile?.bands?.getOrNull(i) ?: return
        if (!editable) { setStatus(Lang.t("这台设备的载荷尚未通过自洽校验，只能看不能改", "Read-only: payload layout not verified"), true); return }
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
                    val nf = f.text.toString().trim().toIntOrNull()
                    val nq = q.text.toString().trim().toFloatOrNull()
                    if (nf != null && nf in 20..20000) b.freq = nf
                    if (nq != null && nq in 0.1f..12f) b.q = nq
                    renderBand(i); paint()
                }
                .setNegativeButton(Lang.t("取消", "Cancel"), null)
                .show()
    }

    // ───────────────────────── 设备与协议 ─────────────────────────

    /** 只挑已配对的水月雨设备；有多台让用户选；一台都没有就明确报错（不拿别家耳机试协议） */
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
        setStatus(Lang.t("正在读取均衡参数…", "Reading…"))
        resolveDevice { target ->
            if (target == null) {
                setStatus(Lang.t("没有已配对的水月雨设备", "No bonded Moondrop device"), true); return@resolveDevice
            }
            Thread {
                if (!MoondropSpp.isConnected() && !MoondropSpp.connect(target)) {
                    ui.post { if (alive) setStatus(Lang.t("SPP 连接失败（耳机被官方 App 占用或已休眠？）", "SPP connect failed"), true) }
                    return@Thread
                }
                val raw = MoondropSpp.readCurve()
                if (raw == null) {
                    MoondropSpp.disconnect()
                    ui.post { if (alive) setStatus(Lang.t("读不到曲线：这台设备可能不支持该协议", "No curve reply — protocol not supported"), true) }
                    return@Thread
                }
                if (afterWrite != null && !afterWrite.contentEquals(raw)) {
                    ui.post { if (alive) setStatus(Lang.t("写入后回读不一致（设备没接受这组参数）", "Read-back mismatch after write"), true) }
                    return@Thread
                }
                MoondropSpp.disconnect()   // 读完就放链路，不长期占着耳机（实测长时间挂住的 RFCOMM 并不友好）
                val pr = MoondropEq.decode(raw)
                val ok = pr != null && MoondropEq.roundTrips(raw)
                ui.post {
                    if (!alive) return@post
                    if (pr == null) {
                        setStatus(Lang.t("载荷布局不认识（", "Unrecognised payload (") + raw.size +
                                Lang.t(" 字节，段数不是整数或段尾标记不符）", " bytes)"), true)
                        editable = false; writeBtn.isEnabled = false
                    } else {
                        profile = pr
                        editable = ok
                        writeBtn.isEnabled = ok
                        rebuildRows(pr)
                        renderAll()
                        setStatus(
                                when {
                                    afterWrite != null && ok -> Lang.t("已写入并回读一致 ✓", "Written, read-back identical")
                                    ok -> Lang.t("已读取耳机当前参数 ✓", "Loaded from headset")
                                    else -> Lang.t("这台设备的载荷没能自洽还原，已转只读（避免写坏）", "Layout not self-consistent — read-only")
                                },
                                !ok)
                    }
                }
            }.start()
        }
    }

    private fun doWrite() {
        val p = profile ?: return
        if (!editable) { setStatus(Lang.t("当前为只读模式", "Read-only mode"), true); return }
        val raw = MoondropEq.encode(p)
        writeBtn.isEnabled = false
        setStatus(Lang.t("正在写入…", "Writing…"))
        Thread {
            val ok = MoondropSpp.writeCurve(raw)
            ui.post {
                if (!alive) return@post
                writeBtn.isEnabled = editable
                if (!ok) setStatus(Lang.t("写入失败（链路断开？）", "Write failed"), true)
            }
            if (ok) {
                Thread.sleep(500)
                doRead(raw)
            }
        }.start()
    }

    private fun setPreset(idx: Int) {
        Thread {
            val r = MoondropSpp.setPreset(idx)
            ui.post {
                if (!alive) return@post
                if (r == null) setStatus(Lang.t("切换预设失败", "Preset switch failed"), true)
                else setStatus(Lang.t("已切换预设", "Preset switched"))
            }
            if (r != null) { Thread.sleep(400); doRead(null) }
        }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        alive = false
        MoondropSpp.disconnect()
        super.onDestroy()
    }

    /** 频响预览：对数频率轴 + 自动纵向缩放 */
    private inner class EqCurveView : View(this@EqEditorActivity) {
        private val curve = Path()
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.outline; style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); alpha = 80
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.primary; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.primary; style = Paint.Style.FILL; alpha = 28
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
