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
 * 均衡器（正式入口，走经典蓝牙 SPP 的 0A 05 / 0A 06）。
 *
 * 数据链路（全部在真机上验过）：
 *   连接 SPP → 0A 05 读 39 字节载荷 → MoondropEq 解成 5 段 → 编辑 → MoondropEq 编码 → 0A 06 写入
 *   → 再读一次逐字节比对（设备对 0A 06 不回 ACK，所以「写完回读」才是唯一可信判据）
 *
 * 解码依据见 MoondropEq 的注释：两条官方真帧可以逐字节复现。
 */
class EqEditorActivity : Activity() {

    private companion object {
        const val TAG = "FxxkMoondrop"
        const val G_MIN = -12f
        const val G_MAX = 3f
    }

    private lateinit var pal: ThemeUtil.Palette
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var preview: EqCurveView
    private val bandRows = ArrayList<LinearLayout>(MoondropEq.BANDS)
    private val bandInfo = ArrayList<TextView>(MoondropEq.BANDS)
    private val bandGain = ArrayList<TextView>(MoondropEq.BANDS)
    private val bandBar = ArrayList<SeekBar>(MoondropEq.BANDS)
    private lateinit var preampLabel: TextView
    private lateinit var writeBtn: TextView

    private var profile: MoondropEq.Profile? = null
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
        for (i in 0 until MoondropEq.BANDS) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(12), dp(8), dp(12), dp(4))

            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            val info = TextView(this)
            info.textSize = 13f
            info.setTextColor(pal.onSurface)
            info.text = Lang.t("第 ", "Band ") + (i + 1) + Lang.t(" 段 · 未读取", " · —")
            top.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val gain = TextView(this)
            gain.textSize = 13f
            gain.typeface = Typeface.MONOSPACE
            gain.setTextColor(pal.primary)
            top.addView(gain, LinearLayout.LayoutParams(-2, -2))
            row.addView(top, LinearLayout.LayoutParams(-1, -2))

            val bar = SeekBar(this)
            bar.max = ((G_MAX - G_MIN) * 10).toInt()
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val pr = profile ?: return
                    pr.bands[i].gainDb = Math.round((G_MIN + p / 10f) * 10) / 10f
                    renderBand(i); paint()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            row.addView(bar, LinearLayout.LayoutParams(-1, dp(36)))

            row.setOnClickListener { editBand(i) }
            info.setOnClickListener { editBand(i) }

            body.addView(row, LinearLayout.LayoutParams(-1, -2))
            bandRows.add(row); bandInfo.add(info); bandGain.add(gain); bandBar.add(bar)
        }

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
            ui.post { profile?.let { renderAll(it) } }
        }
        addBtn(ops, Lang.t("全部归零", "Flatten")) {
            val p = profile ?: return@addBtn
            p.bands.forEach { it.gainDb = 0f }
            renderAll(p); setStatus(Lang.t("已归零，点「写入耳机」生效", "Flattened — tap Write"), false)
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
                "读写的是耳机里那组 5 段参数均衡。切换预设会覆盖当前调音，写回前请先确认。",
                "This edits the 5-band parametric EQ stored in the headset. Switching a preset replaces the current tuning.")
        body.addView(hint, LinearLayout.LayoutParams(-1, -2))

        val sv = ScrollView(this)
        sv.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        M3Ui.fitSystemBars(this, pal)

        alive = true
        doRead(null)
    }

    // ───────────────────────── UI 小件 ─────────────────────────

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

    private fun renderBand(i: Int) {
        val b = profile?.bands?.getOrNull(i) ?: return
        bandInfo[i].text = Lang.t("频点 ", "Freq ") + b.freq + " Hz   Q " + String.format("%.2f", b.q)
        bandGain[i].text = String.format("%+.1f dB", b.gainDb)
        if (bandBar[i].progress != Math.round((b.gainDb - G_MIN) * 10)) {
            bandBar[i].progress = Math.round((b.gainDb - G_MIN) * 10).coerceIn(0, bandBar[i].max)
        }
    }

    private fun renderAll(p: MoondropEq.Profile) {
        for (i in 0 until MoondropEq.BANDS) renderBand(i)
        preampLabel.text = Lang.t("预增益 ", "Preamp ") + String.format("%+.1f dB", p.preampDb) +
                Lang.t("（防叠加削波）", " (anti-clipping)")
        paint()
    }

    private fun paint() { profile?.let { preview.setProfile(it) } }

    private fun editBand(i: Int) {
        val b = profile?.bands?.getOrNull(i) ?: return
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

    // ───────────────────────── 协议操作 ─────────────────────────

    private fun findDevice(): String? {
        addr?.let { return it }
        val bonded = try {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
        } catch (_: Throwable) { emptyList() }
        val md = bonded.filter { DeviceMatcher.isMoondrop(try { it.name } catch (_: Throwable) { null }) }
        val pick = md.firstOrNull() ?: bonded.firstOrNull() ?: return null
        addr = try { pick.address } catch (_: Throwable) { null }
        return addr
    }

    private fun doRead(afterWrite: ByteArray?) {
        val target = findDevice()
        if (target == null) { setStatus(Lang.t("没有已配对的设备", "No bonded device"), true); return }
        setStatus(Lang.t("正在读取均衡参数…", "Reading…"))
        Thread {
            if (!MoondropSpp.isConnected() && !MoondropSpp.connect(target)) {
                ui.post { if (alive) setStatus(Lang.t("SPP 连接失败（耳机被官方 App 占用？）", "SPP connect failed"), true) }
                return@Thread
            }
            val raw = MoondropSpp.readCurve()
            if (raw == null) {
                ui.post { if (alive) setStatus(Lang.t("读不到曲线：这台设备可能不支持该协议", "No curve reply — protocol not supported"), true) }
                return@Thread
            }
            if (afterWrite != null && !afterWrite.contentEquals(raw)) {
                ui.post { if (alive) setStatus(Lang.t("写入后回读不一致（设备没接受这组参数）", "Read-back mismatch after write"), true) }
                return@Thread
            }
            val pr = MoondropEq.decode(raw)
            ui.post {
                if (!alive) return@post
                if (pr == null) {
                    setStatus(Lang.t("载荷布局不认识（", "Unrecognised payload (") + raw.size + Lang.t(" 字节）", " bytes)"), true)
                } else {
                    profile = pr
                    renderAll(pr)
                    setStatus(
                            if (afterWrite != null) Lang.t("已写入并回读一致 ✓", "Written, read-back identical")
                            else Lang.t("已读取耳机当前参数 ✓", "Loaded from headset"),
                            false)
                }
            }
        }.start()
    }

    private fun doWrite() {
        val p = profile ?: return
        val raw = MoondropEq.encode(p)
        writeBtn.isEnabled = false
        setStatus(Lang.t("正在写入…", "Writing…"))
        Thread {
            val ok = MoondropSpp.writeCurve(raw)
            ui.post {
                if (!alive) return@post
                writeBtn.isEnabled = true
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
            for (k in 1..3) {
                val y = h * k / 4f
                canvas.drawLine(0f, y, w, y, grid)
            }
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
