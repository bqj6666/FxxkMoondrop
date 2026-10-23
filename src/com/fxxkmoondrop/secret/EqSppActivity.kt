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
    private val ui = Handler(Looper.getMainLooper())
    private var lastCurve: ByteArray? = null

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
        addBtn(row, Lang.t("连接", "Connect")) { connect() }
        addBtn(row, Lang.t("读预设", "Read preset")) { act { MoondropSpp.readPreset() } }
        addBtn(row, Lang.t("读曲线", "Read curve")) { act { MoondropSpp.readCurve() } }
        root.addView(row, LinearLayout.LayoutParams(-1, -2))

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.setPadding(0, 0, 0, dp(8))
        addBtn(row2, Lang.t("流行", "Pop")) { act { MoondropSpp.setPreset(0) } }
        addBtn(row2, Lang.t("标准", "Standard")) { act { MoondropSpp.setPreset(1) } }
        addBtn(row2, Lang.t("监听", "Monitor")) { act { MoondropSpp.setPreset(2) } }
        addBtn(row2, Lang.t("回写曲线", "Write curve")) {
            val c = lastCurve
            if (c == null || c.size < 39) line("E", Lang.t("先读一次曲线", "Read a curve first"))
            else act { MoondropSpp.writeCurve(c.copyOfRange(c.size - 39, c.size)) }
        }
        root.addView(row2, LinearLayout.LayoutParams(-1, -2))

        logBox = LinearLayout(this)
        logBox.orientation = LinearLayout.VERTICAL
        logBox.setPadding(dp(12), dp(12), dp(12), dp(12))
        val sv = ScrollView(this)
        sv.addView(M3Ui.groupCard(this, pal, logBox), LinearLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        M3Ui.fitSystemBars(this, pal)

        line("I", Lang.t("SPP 通道已就绪，点「连接」建立链路", "Ready. Tap Connect to open the SPP link"))
    }

    private fun addBtn(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val b = TextView(this)
        b.text = text
        b.textSize = 13f
        b.gravity = Gravity.CENTER
        b.setTextColor(pal.primary)
        b.setPadding(dp(10), dp(10), dp(10), dp(10))
        b.background = M3Ui.pressRipple(this, null)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(0, dp(44), 1f); lp.marginEnd = dp(6)
        parent.addView(b, lp)
    }

    private fun connect() {
        val addr = GaiaBleClient.getInstance().deviceAddress
        if (addr.isNullOrEmpty()) { line("E", Lang.t("没有已连接设备地址", "No device address")); return }
        Thread {
            val ok = MoondropSpp.connect(addr)
            ui.post {
                line(if (ok) "OK" else "E",
                        if (ok) Lang.t("SPP 连接成功", "SPP connected") else Lang.t("SPP 连接失败", "SPP connect failed"))
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
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        MoondropSpp.disconnect()
        super.onDestroy()
    }
}
