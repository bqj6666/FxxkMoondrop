package com.fxxkmoondrop.secret

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 能力探测页（调试模式）。
 *
 * 逐条发只读命令，看哪些功能真的会回包 —— 用于给新耳机测绘能力。
 * 手动触发、只读、不参与连接流程。
 */
class FeatureProbeActivity : BaseActivity() {

    private lateinit var pal: ThemeUtil.Palette
    private lateinit var rowsBox: LinearLayout
    private lateinit var startBtn: android.view.View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = ThemeUtil.Palette(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        val statusBarH = resources.getIdentifier("status_bar_height", "dimen", "android")
                .let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
        root.setPadding(dp(16), statusBarH + dp(8), dp(16), dp(24))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(M3Ui.backButton(this, pal) { finish() })
        val title = TextView(this)
        title.text = Lang.t("能力探测", "Capability probe")
        title.textSize = 22f
        title.setTextColor(pal.onSurface)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        val hint = TextView(this)
        hint.text = Lang.t("只读：逐条读各功能状态，有回包即视为支持。不影响连接。",
                "Read-only: asks each feature state; a reply means supported.")
        hint.textSize = 13f
        hint.setTextColor(pal.onVariant)
        hint.setPadding(0, dp(8), 0, dp(12))
        root.addView(hint)

        rowsBox = LinearLayout(this)
        rowsBox.orientation = LinearLayout.VERTICAL
        root.addView(rowsBox, LinearLayout.LayoutParams(-1, -2))

        startBtn = M3Ui.actionButton(this, Lang.t("开始探测", "Start probe"),
                R.drawable.ic_refresh, pal.primary, pal.onPrimary, android.view.View.OnClickListener {
                    if (FeatureProbe.isRunning()) FeatureProbe.stop() else FeatureProbe.start()
                    renderRows()
                }, medium = true)
        root.addView(startBtn, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16)
        })

        FeatureProbe.listener = {
            runOnUiThread { renderRows() }
        }
        renderRows()
        setContentView(root)
    }

    private fun renderRows() {
        rowsBox.removeAllViews()
        for (item in FeatureProbe.list()) {
            val st = FeatureProbe.stateOf(item.feature)
            val text = when (st) {
                FeatureProbe.OK -> Lang.t("✔ 支持", "✔ supported")
                FeatureProbe.WAITING -> Lang.t("… 等待回包", "… waiting")
                FeatureProbe.TIMEOUT -> Lang.t("✘ 无响应", "✘ no reply")
                else -> Lang.t("未探测", "not probed")
            }
            val name = FeatureProbe.label(item) + if (item.experimental)
                Lang.t("（实验）", " (experimental)") else ""
            rowsBox.addView(M3Ui.listRow(this, pal, R.drawable.ic_bug_report,
                    name, text, null, null))
        }
        (startBtn as? com.google.android.material.button.MaterialButton)?.text =
                if (FeatureProbe.isRunning()) Lang.t("停止", "Stop")
                else Lang.t("开始探测", "Start probe")
    }

    override fun onDestroy() {
        FeatureProbe.listener = null
        FeatureProbe.stop()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
