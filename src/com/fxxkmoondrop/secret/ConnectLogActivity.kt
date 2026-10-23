package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 三级页面：连接过程日志。
 *
 * 手动连接是有过程的操作，这里按 logcat 的样子逐步打印（时间 / 级别 / 内容），
 * 每一步都来自真实状态（isConnected / isGaiaReady），失败也给出可判断的原因。
 */
class ConnectLogActivity : Activity() {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_ADDR = "addr"
    }

    private lateinit var pal: ThemeUtil.Palette
    private lateinit var logBox: LinearLayout
    private lateinit var scroll: ScrollView
    private val ui = Handler(Looper.getMainLooper())
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var targetName = ""
    private var targetAddr = ""
    private var startedAt = 0L
    private var finished = false
    private var lastState = ""

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ThemeUtil.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.applyTheme(this)
        super.onCreate(savedInstanceState)
        pal = ThemeUtil.Palette(this)
        targetName = intent.getStringExtra(EXTRA_NAME) ?: "?"
        targetAddr = intent.getStringExtra(EXTRA_ADDR) ?: "?"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        val sbh = resources.getIdentifier("status_bar_height", "dimen", "android")
                .let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
        root.setPadding(dp(12), sbh + dp(8), dp(12), dp(16))

        // 顶栏：返回按钮单独占 48dp，标题左对齐留 8dp 间距（上一版贴在一起看着怪）
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val back = M3Ui.backButton(this, pal) { finish() }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = TextView(this)
        title.text = Lang.t("连接过程", "Connection log")
        title.textSize = 28f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setTextColor(pal.onSurface)
        val tlp = LinearLayout.LayoutParams(0, -2, 1f)
        tlp.marginStart = dp(8)
        header.addView(title, tlp)
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val sub = TextView(this)
        sub.text = targetName + "  ·  " + targetAddr
        sub.textSize = 12f
        sub.setTextColor(pal.onVariant)
        sub.setPadding(dp(56), 0, 0, dp(8))
        root.addView(sub, LinearLayout.LayoutParams(-1, -2))

        logBox = LinearLayout(this)
        logBox.orientation = LinearLayout.VERTICAL
        logBox.setPadding(dp(12), dp(12), dp(12), dp(12))
        val card = M3Ui.groupCard(this, pal, logBox)
        scroll = ScrollView(this)
        scroll.addView(card, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        M3Ui.fitSystemBars(this, pal)

        startedAt = System.currentTimeMillis()
        log("I", Lang.t("开始连接 ", "Connecting to ") + targetAddr)
        try {
            startService(Intent(this, HeadsetDetectService::class.java)
                    .setAction(HeadsetDetectService.ACTION_MANUAL_CONTROL))
            log("I", Lang.t("已拉起监听服务", "Listener service started"))
        } catch (t: Throwable) {
            log("W", Lang.t("拉起监听服务失败：", "Failed to start service: ") + (t.message ?: ""))
        }
        val started = try { GaiaBleClient.getInstance().connect(this, targetAddr) }
        catch (t: Throwable) { log("E", "connect() " + (t.message ?: "")); false }
        if (!started) {
            log("E", Lang.t("无法发起连接：蓝牙未开启 / 缺少权限 / 设备不在范围内",
                    "Cannot start: Bluetooth off, missing permission, or out of range"))
            finishFail()
            return
        }
        log("I", Lang.t("已发起 GAIA BLE 连接，等待回调", "GAIA BLE connect issued, waiting"))
        poll()
    }

    private fun poll() {
        if (finished) return
        val g = GaiaBleClient.getInstance()
        val connected = try { g.isConnected() } catch (_: Throwable) { false }
        val ready = try { g.isGaiaReady() } catch (_: Throwable) { false }
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000
        val state = "c=$connected,r=$ready"
        if (state != lastState) {
            lastState = state
            if (connected) log("I", Lang.t("BLE 链路已建立", "BLE link established"))
            if (ready) log("I", Lang.t("GAIA 服务发现完成", "GAIA service discovered"))
        }
        if (ready) {
            val n = try { g.getConnectedDeviceName() } catch (_: Throwable) { null } ?: targetName
            log("OK", Lang.t("连接成功：", "Connected: ") + n)
            finished = true
            return
        }
        if (elapsed >= 20) {
            if (connected) log("E", Lang.t("链路已建立，但 GAIA 未就绪：可能不是水月雨设备，或固件未开放 GAIA 服务",
                    "Link up but GAIA not ready: not a Moondrop device, or GAIA disabled in firmware"))
            else log("E", Lang.t("超时未建立链路：设备可能已连接其它主机、在充电仓中，或距离过远",
                    "Timed out: device may be connected elsewhere, in the case, or too far"))
            finishFail()
            return
        }
        if (elapsed in listOf(3L, 8L, 15L)) log("W", Lang.t("仍在等待… ", "Still waiting… ") + elapsed + "s")
        ui.postDelayed({ poll() }, 500)
    }

    private fun finishFail() {
        finished = true
        ui.postDelayed({ /* 允许用户返回 */ }, 0)
    }

    /** 一行日志：时间  级别  内容（等宽字体，级别着色） */
    private fun log(level: String, msg: String) {
        val color = when (level) {
            "OK" -> pal.primary
            "E" -> pal.red
            "W" -> pal.warning
            else -> pal.onVariant
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(4), 0, dp(4))
        val t = TextView(this)
        t.text = ts.format(Date())
        t.textSize = 11f
        t.typeface = Typeface.MONOSPACE
        t.setTextColor(pal.outline)
        row.addView(t, LinearLayout.LayoutParams(-2, -2))
        val lv = TextView(this)
        lv.text = level.padEnd(3)
        lv.textSize = 11f
        lv.typeface = Typeface.MONOSPACE
        lv.setTextColor(color)
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.marginStart = dp(8)
        row.addView(lv, lp)
        val m = TextView(this)
        m.text = msg
        m.textSize = 12f
        m.typeface = Typeface.MONOSPACE
        m.setTextColor(if (level == "OK" || level == "E") color else pal.onSurface)
        val mlp = LinearLayout.LayoutParams(0, -2, 1f)
        mlp.marginStart = dp(8)
        row.addView(m, mlp)
        logBox.addView(row, LinearLayout.LayoutParams(-1, -2))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        finished = true
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
