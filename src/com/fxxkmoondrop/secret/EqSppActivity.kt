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
        addBtn(row, Lang.t("读预设", "Read preset")) { act { MoondropSpp.readPreset() } }
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
            val cmds = mutableListOf<Pair<Int, IntArray>>()
            for (c in 0x0A..0x0A) for (a in 0..9) cmds.add(c to intArrayOf(a))
            for (c in intArrayOf(0x10, 0x20, 0x24, 0x28, 0x2C)) for (a in 0..5) cmds.add(c to intArrayOf(a))
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
