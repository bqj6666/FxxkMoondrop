package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 调试日志（只在「设置 → 诊断 → 调试模式」打开时可用）。
 *
 * 目的：出问题时能直接看到**软件侧详细日志**，不用连着电脑抓 logcat。
 * 内容两段：
 *  1. 应用内日志 AppLog 快照（本应用自己记录的关键路径）
 *  2. logcat 里与本应用/协议相关的行（MoondropSpp 的 tx/rx 帧、崩溃堆栈等），走 RootShell 读取
 *
 * 未打开调试模式时，界面入口不显示，本页也会直接退出——避免普通用户误入。
 */
class DebugLogActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var pal: ThemeUtil.Palette
    private lateinit var box: LinearLayout
    private lateinit var sv: ScrollView
    @Volatile private var alive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 门禁：调试模式没开就不进这个页面
        val on = getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("debug_mode", false)
        if (!on) {
            android.widget.Toast.makeText(this, Lang.t("请先在设置 → 诊断 打开调试模式", "Enable Debug mode first"),
                    android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        pal = ThemeUtil.Palette(this)

        // 与均衡器等二级页统一：官方大标题 + 内置返回键
        val page = M3Ui.largeHeaderPageWithBack(this, pal, Lang.t("调试日志", "Debug log")) { finish() }

        val ops = LinearLayout(this)
        ops.orientation = LinearLayout.HORIZONTAL
        ops.setPadding(dp(4), dp(4), dp(4), dp(4))
        ops.addView(btn(Lang.t("刷新", "Refresh")) { load() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        ops.addView(btn(Lang.t("复制全部", "Copy all")) { copyAll() }, LinearLayout.LayoutParams(0, dp(40), 1f))

        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(8), dp(12), dp(8))
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(12), dp(8), dp(12), dp(16))
        content.addView(ops, LinearLayout.LayoutParams(-1, -2))
        sv = ScrollView(this)
        sv.addView(box, LinearLayout.LayoutParams(-1, -2))
        content.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        page.sv.addView(content, LinearLayout.LayoutParams(-1, -2))
        setContentView(page.container)
        M3Ui.fitSystemBars(this, pal)

        alive = true
        load()
    }

    private fun btn(text: String, onClick: () -> Unit): TextView {
        val b = M3Ui.textButton(this, pal, text) { onClick() }
        return b
    }

    /** 拉一次日志：应用内 AppLog 快照 + 相关 logcat（走 RootShell） */
    private fun load() {
        box.removeAllViews()
        addSection(Lang.t("正在读取…", "Loading…"), false)
        Thread {
            val appLog = try { AppLog.snapshot() } catch (_: Throwable) { "" }
            val cmd = "logcat -d -v time -t 800 | grep -E " +
                    "'MoondropSpp|FxxkMoondrop|EqEditor|DebugLog|AndroidRuntime|fxxkmoondrop' | tail -n 400"
            val lc = try { RootShell.exec(cmd) } catch (_: Throwable) { null }
            ui.post {
                if (!alive) return@post
                box.removeAllViews()
                addSection(Lang.t("应用内日志（AppLog）", "In-app log (AppLog)"),
                        appLog.isBlank(), appLog.ifBlank { Lang.t("（暂无记录）", "(empty)") })
                addSection(Lang.t("系统日志（logcat，需 Root）", "logcat (requires root)"),
                        lc.isNullOrBlank(),
                        lc?.ifBlank { Lang.t("（无匹配行）", "(no matching lines)") }
                                ?: Lang.t("（读取失败：可能没有 Root 授权）", "(failed: root not available)"))
            }
        }.start()
    }

    private fun addSection(title: String, empty: Boolean, body: String = "") {
        val t = TextView(this)
        t.text = title
        t.textSize = 13f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(pal.primary)
        t.setPadding(0, dp(10), 0, dp(4))
        box.addView(t, LinearLayout.LayoutParams(-1, -2))
        if (empty) return
        val c = TextView(this)
        c.text = body
        c.textSize = 10f
        c.typeface = Typeface.MONOSPACE
        c.setTextColor(pal.onSurface)
        c.setTextIsSelectable(true)
        box.addView(c, LinearLayout.LayoutParams(-1, -2))
    }

    private fun copyAll() {
        val sb = StringBuilder()
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i) as? TextView ?: continue
            sb.append(v.text).append('\n')
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("log", sb.toString()))
        android.widget.Toast.makeText(this, Lang.t("已复制", "Copied"), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }
}
