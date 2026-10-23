package com.fxxkmoondrop.secret

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Fast Pair 半屏卡片里的自绘降噪面板。
 *
 * 为什么要自绘：半屏原生内容来自 GMS 的 **Slice（RemoteViews）**，那里只有 Slice Action，
 * 不存在 Material 组件，所以「按下圆角形变 / 涟漪」在 Slice 通道里做不到。
 * 这个面板由我们注入成真实 View 树，按钮用 M3Ui.actionButton（官方 MaterialButton），
 * 形变、配色、字数都能与 App 内、蓝牙详情面板保持一致。
 */
object FastPairPanel {

    const val TAG = "fxxk_fp_panel"

    /** 与 App 内一致的四档：降噪 / 关闭 / 透传 / 自适应 */
    fun buildView(ctx: Context, onPick: (Int) -> Unit): View {
        val themed = ThemeUtil.moduleUiContext(ctx)
        val pal = ThemeUtil.Palette(themed)

        val card = LinearLayout(themed)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(themed, 16), dp(themed, 12), dp(themed, 16), dp(themed, 12))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(themed, 24).toFloat()
        bg.setColor(pal.container)
        card.background = bg

        val title = TextView(themed)
        title.text = Lang.t(themed, "降噪控制", "Noise control")
        title.textSize = 14f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setTextColor(pal.onContainer)
        card.addView(title, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(themed)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(themed, 8), 0, 0)
        card.addView(row, LinearLayout.LayoutParams(-1, -2))

        for ((mode, label) in listOf(
                1 to Lang.t(themed, "降噪", "ANC"),
                0 to Lang.t(themed, "关闭", "Off"),
                2 to Lang.t(themed, "透传", "Pass"),
                4 to Lang.t(themed, "自适应", "Auto"))) {
            val btn = M3Ui.actionButton(themed, label, 0,
                    pal.surfaceContainerHighest, pal.onSurface,
                    android.view.View.OnClickListener { onPick(mode) })
            btn.tag = "fp_btn_$mode"
            btn.isCheckable = true
            btn.isToggleCheckedStateOnClick = false
            row.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).also {
                it.marginEnd = dp(themed, 6)
            })
        }
        return card
    }

    // AncBridge retains its cached mode after disconnect; it is not current until GAIA is ready.
    internal fun selectedMode(mode: Int, connected: Boolean, gaiaReady: Boolean): Int =
        if (connected && gaiaReady) mode else -1

    /** 按当前档位刷新选中态（选中用 primary 填充） */
    fun refresh(panel: View, mode: Int) {
        val root = panel as? android.view.ViewGroup ?: return
        val row = root.getChildAt(1) as? android.view.ViewGroup ?: return
        for (i in 0 until row.childCount) {
            val b = row.getChildAt(i) as? com.google.android.material.button.MaterialButton ?: continue
            val m = (b.tag as? String)?.removePrefix("fp_btn_")?.toIntOrNull() ?: continue
            val pal = ThemeUtil.Palette(b.context)
            b.isChecked = m == mode
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (m == mode) pal.primary else pal.surfaceContainerHighest)
            b.setTextColor(if (m == mode) pal.onPrimary else pal.onSurface)
        }
    }

    private fun dp(c: Context, v: Int) = Math.round(v * c.resources.displayMetrics.density)
}
