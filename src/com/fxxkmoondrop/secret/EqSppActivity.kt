package com.fxxkmoondrop.secret

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** EQ（经典蓝牙 SPP 通道）测试页：读预设 / 切预设 / 读曲线 / 回写曲线 */
class EqSppActivity : Activity() {

    private lateinit var pal: ThemeUtil.Palette
    private lateinit var logBox: LinearLayout
    private lateinit var sv: ScrollView
    private val ui = Handler(Looper.getMainLooper())
    private var lastCurve: ByteArray? = null
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
        val t = TextView(this)
        t.text = Lang.t("EQ 通道（SPP）", "EQ over SPP")
        t.textSize = 20f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(pal.onSurface)
        val lp = LinearLayout.LayoutParams(0, -2, 1f); lp.marginStart = dp(8)
        header.addView(t, lp)
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(8), 0, dp(8))
        addBtn(row, Lang.t("连接", "Connect")) { pickDevice() }
        addBtn(row, Lang.t("读预设", "Read preset"), onLong = { selfTest() }) { act { MoondropSpp.readPreset() } }
        addBtn(row, Lang.t("读曲线", "Read curve")) { act { MoondropSpp.readCurve() } }
        root.addView(row, LinearLayout.LayoutParams(-1, -2))

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.setPadding(0, 0, 0, dp(8))
        addBtn(row2, Lang.t("流行", "Pop")) { act { MoondropSpp.setPreset(0) } }
        addBtn(row2, Lang.t("标准", "Standard")) { act { MoondropSpp.setPreset(1) } }
        addBtn(row2, Lang.t("监听", "Monitor")) { act { MoondropSpp.setPreset(2) } }
        addBtn(row2, Lang.t("预设4", "Preset 4")) { act { MoondropSpp.setPreset(3) } }
        addBtn(row2, Lang.t("探测", "Probe")) { probe() }
        addBtn(row2, Lang.t("回写曲线", "Write curve"), onLong = { restoreOfficial() }) { writeBack() }
        root.addView(row2, LinearLayout.LayoutParams(-1, -2))

        logBox = LinearLayout(this)
        logBox.orientation = LinearLayout.VERTICAL
        logBox.setPadding(dp(12), dp(12), dp(12), dp(12))
        sv = ScrollView(this)
        sv.addView(M3Ui.groupCard(this, pal, logBox), LinearLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        M3Ui.fitSystemBars(this, pal)

        line("I", Lang.t("SPP 通道已就绪，点「连接」建立链路", "Ready. Tap Connect to open the SPP link"))
    }

    private fun addBtn(parent: LinearLayout, text: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        val b = TextView(this)
        b.text = text
        b.textSize = 13f
        b.gravity = Gravity.CENTER
        b.setTextColor(pal.primary)
        b.setPadding(dp(10), dp(10), dp(10), dp(10))
        b.background = M3Ui.pressRipple(this, null)
        b.setOnClickListener { onClick() }
        if (onLong != null) b.setOnLongClickListener { onLong(); true }
        val lp = LinearLayout.LayoutParams(0, dp(44), 1f); lp.marginEnd = dp(6)
        parent.addView(b, lp)
    }

    /** 通用：列出所有已配对的水月雨设备供选择（不绑定具体型号） */
    private fun pickDevice() {
        val bonded = try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
        } catch (_: Throwable) { emptyList() }
        val md = bonded.filter { DeviceMatcher.isMoondrop(try { it.name } catch (_: Throwable) { null }) }
        val list = if (md.isNotEmpty()) md else bonded
        if (list.isEmpty()) { line("E", Lang.t("没有已配对设备", "No bonded devices")); return }
        val names = list.map { d ->
            (try { d.name } catch (_: Throwable) { null } ?: "?") +
                    (if (md.isNotEmpty() && md.contains(d)) "  ✓" else "")
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t("选择水月雨设备", "Choose a Moondrop device"))
                .setItems(names) { _, which ->
                    addr = try { list[which].address } catch (_: Throwable) { null }
                    line("I", Lang.t("目标设备：", "Target: ") + addr)
                    connect()
                }
                .setNegativeButton(Lang.t("取消", "Cancel"), null)
                .show()
    }

    private fun connect() {
        val addr = addr ?: run { pickDevice(); return }
        Thread {
            val ok = MoondropSpp.connect(addr)
            ui.post {
                line(if (ok) "OK" else "E",
                        if (ok) Lang.t("SPP 连接成功", "SPP connected") else Lang.t("SPP 连接失败", "SPP connect failed"))
                if (ok) {
                    // 能力探测：不问型号，直接问协议
                    val p = MoondropSpp.readPreset()
                    line("I", Lang.t("预设查询：", "preset query: ") +
                            (p?.joinToString("") { String.format("%02x", it) } ?: "-"))
                    val c = MoondropSpp.readCurve()
                    if (c != null) {
                        lastCurve = c
                        line("I", Lang.t("曲线读取：", "curve read: ") + c.size + Lang.t(" 字节", " bytes"))
                    } else line("W", Lang.t("读曲线无回包（该型号可能不支持）", "No curve reply (model may not support)"))
                }
            }
        }.start()
    }

    /**
     * 协议探测：不猜型号，直接逐条试候选命令，把**有回包**的列出来。
     * 不同水月雨型号的子命令表可能不同，这一步用来找出「读曲线」到底是哪条。
     */
    private fun probe() {
        Thread {
            // 只探 0A 组（均衡器子命令），不再乱扫 0x10/0x20/0x24/0x28/0x2C：
            // 那些在别的型号上可能是**写**命令，诊断工具不该对未知设备发写操作。
            val cmds = mutableListOf<Pair<Int, IntArray>>()
            for (a in 0..9) cmds.add(0x0A to intArrayOf(a))
            for ((c, a) in cmds) {
                val r = MoondropSpp.raw(c, a[0])
                if (r != null && r.size > 4) {
                    val hex = r.joinToString("") { String.format("%02x", it) }
                    ui.post { line("OK", String.format("cmd %02x %02x -> ", c, a[0]) + hex) }
                }
                Thread.sleep(120)
            }
            ui.post { line("I", Lang.t("探测结束", "Probe done")) }
        }.start()
    }

    /** 回写并回读校验（设备实测原样存储，所以「写→读→逐字节比对」就是可信判据） */
    private fun writeBack() {
        val c = lastCurve
        if (c == null) { line("E", Lang.t("先读一次曲线", "Read a curve first")); return }
        Thread {
            val sent = c.joinToString("") { String.format("%02x", it) }
            ui.post { line("I", Lang.t("下发：", "TX: ") + sent) }
            val ok = MoondropSpp.writeCurveVerified(c)
            ui.post {
                if (ok) line("OK", Lang.t("写入成功，回读逐字节一致", "Written, read-back identical"))
                else line("E", Lang.t("写入失败或回读不一致", "Write failed or read-back mismatch"))
            }
        }.start()
    }

    private fun act(block: () -> ByteArray?) {
        Thread {
            val r = block()
            ui.post {
                if (r == null) line("W", Lang.t("无回包（超时）", "No reply (timeout)"))
                else {
                    val hex = r.joinToString("") { String.format("%02x", it) }
                    line("OK", hex)
                    if (r.size >= 39) lastCurve = r
                }
            }
        }.start()
    }

    /**
     * 诊断用（长按「回写曲线」）：写回官方 App 抓到的标准档曲线，用来还原被旧版错帧污染的设备槽位。
     * 来源：官方 write 帧 `ff040027001d0a060004ff8e…ffc4`（FxxkMoondropSpp 抓包），
     * 不是我们编的数据，写完照例回读校验。
     */
    private fun restoreOfficial() {
        val hex = "0004ff8e0018080000fefe0082899900005a01904000000078145004cc00ff221d4c14cc00ffc4"
        val bytes = ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        Thread {
            ui.post { line("I", Lang.t("写入官方抓包曲线：", "Restoring official curve: ") + bytes.size + Lang.t(" 字节", " bytes")) }
            val ok = MoondropSpp.writeCurveVerified(bytes)
            ui.post {
                if (ok) {
                    lastCurve = bytes
                    line("OK", Lang.t("官方曲线已写回，回读一致", "Official curve restored, read-back identical"))
                } else line("E", Lang.t("官方曲线写回失败", "Restore failed"))
            }
        }.start()
    }

    /**
     * 编解码自检（长按「读预设」）：拿两条**官方 App 真实下发帧**做 解码 -> 编码 -> 逐字节比对。
     * 不需要耳机，也不依赖设备，只要这里 PASS，就说明 39 字节布局与单位在实现里是对的。
     */
    private fun selfTest() {
        val frames = listOf(
                "0004ff46006413330000b401f40ccc00ffa609c4100000003c271014cc0000783a980e6600006c",
                "0004ff8e0018080000fefe0082899900005a01904000000078145004cc00ff221d4c14cc00ffc4"
        )
        var pass = 0
        for (h in frames) {
            val raw = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val pr = MoondropEq.decode(raw)
            if (pr == null) { line("E", Lang.t("自检：解码失败", "self-test: decode failed")); continue }
            val back = MoondropEq.encode(pr)
            val ok = back.contentEquals(raw)
            val desc = pr.bands.joinToString(" ") { String.format("%dHz%+.2f/%.2f", it.freq, it.gainDb, it.q) }
            line(if (ok) "OK" else "E",
                    (if (ok) Lang.t("自检通过", "pass") else Lang.t("自检失败", "FAIL")) +
                            String.format(" pre%+.2f | ", pr.preampDb) + desc)
            if (ok) pass++
        }
        // 通用性自检：段数不写死 5 —— 造一份 7 段数据，编码->解码->再编码必须完全复原
        val synth = MoondropEq.Profile(-2.0f, (0 until 7).map {
            MoondropEq.Band(60 * (1 shl it), (it - 3) * 0.7f, 0.5f + it * 0.25f)
        }.toMutableList())
        val enc = MoondropEq.encode(synth)
        val dec = MoondropEq.decode(enc)
        val generic = dec != null && MoondropEq.encode(dec).contentEquals(enc) &&
                dec.bands.size == 7 && Math.abs(dec.bands[6].freq - 3840) < 1
        line(if (generic) "OK" else "E",
                Lang.t("任意段数自检：", "arbitrary band count: ") + enc.size + Lang.t(" 字节 / ", " bytes / ") +
                        (dec?.bands?.size ?: -1) + Lang.t(" 段", " bands") +
                        if (generic) " ✓" else " ✗")
        if (generic) pass++
        // 反证：布局不符时必须拒绝解释（对应「别的型号 → 只读」的降级路径）
        val good = ByteArray(39).also { it[1] = 0x04; it[8] = 0x00 }
        val padBad = good.copyOf().also { it[8] = 0x7F }            // 段尾标记不为 0
        val lenBad = ByteArray(38)                                   // 长度反推不出整数段
        val rPad = MoondropEq.decode(padBad) == null
        val rLen = MoondropEq.decode(lenBad) == null
        line(if (rPad && rLen) "OK" else "E",
                Lang.t("布局不符必须拒绝：段尾标记", "reject bad layout: pad ") + (if (rPad) "✓" else "✗") +
                        Lang.t("、长度 ", ", length ") + (if (rLen) "✓" else "✗"))
        if (rPad && rLen) pass++
        val total = frames.size + 2
        line(if (pass == total) "OK" else "E",
                Lang.t("编解码自检 ", "codec self-test ") + pass + "/" + total)
    }

    private fun line(level: String, msg: String) {
        val color = when (level) {
            "OK" -> pal.primary
            "E" -> pal.red
            "W" -> 0xFFFFA000.toInt()
            else -> pal.onSurface
        }
        val tv = TextView(this)
        tv.text = "[$level] $msg"
        tv.textSize = 11f
        tv.typeface = Typeface.MONOSPACE
        tv.setTextColor(color)
        tv.setPadding(0, dp(3), 0, dp(3))
        logBox.addView(tv, LinearLayout.LayoutParams(-1, -2))
        sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }   // 新日志自动滚到底（不然写了啥根本看不见）
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        MoondropSpp.disconnect()
        super.onDestroy()
    }
}
