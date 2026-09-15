package com.fxxkmoondrop.secret

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * alpha2.52: 设备控制图标（追踪 / 增益 / 指示灯）。
 *
 * 旧实现用 Canvas 手绘位图，三套图标几何各不相同、线宽与网格不统一。
 * 现改为 Material Symbols 矢量资源（24dp 网格、统一描边观感），
 * 颜色仍由调用方传入 tint，签名与调用点保持不变。
 *
 * feature / mode 语义（与 AncProfileLib、DeviceControlBridge 一致）：
 *  0 = 追踪模式：0 关闭追踪 / 1 30° / 2 全方位
 *  1 = 增益档位：0 低 / 1 中 / 2 高
 *  2 = 指示灯：  0 开 / 1 关
 */
object DcIcons {

    /** @return 已按 [px] 设定边界并 tint [color] 的图标；资源缺失时返回 null */
    fun build(context: Context, feature: Int, mode: Int, px: Int, color: Int): Drawable? {
        val res = when (feature) {
            0 -> when (mode) {
                0 -> R.drawable.ic_track_off
                1 -> R.drawable.ic_track_dir
                else -> R.drawable.ic_track_full
            }
            1 -> when (mode) {
                0 -> R.drawable.ic_gain_1
                1 -> R.drawable.ic_gain_2
                else -> R.drawable.ic_gain_3
            }
            else -> if (mode == 0) R.drawable.ic_led_on else R.drawable.ic_led_off
        }
        val d = M3Ui.moduleDrawable(context, res) ?: return null
        d.setBounds(0, 0, px, px)
        d.setTint(color)
        return d
    }
}
