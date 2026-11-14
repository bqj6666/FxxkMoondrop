package com.fxxkmoondrop.secret

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/**
 * 取应用图标的「代表色」。
 *
 * 做法：把图标画进 64×64 位图，按 HSV 统计有彩色（饱和度够高、亮度不接近黑/白）的像素，
 * 取出现最多的色相族的平均颜色。图标是单色图形时也成立 —— 返回那个图形色。
 * 取不到（图标全灰/全透明）时退回主题主色，保证调用方永远拿得到可用颜色。
 */
object AppIconColor {

    @JvmStatic
    fun of(ctx: Context, fallback: Int): Int {
        val bmp = runCatching {
            val d = ctx.packageManager.getApplicationIcon(ctx.packageName)
            val size = 64
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            d.setBounds(0, 0, size, size)
            d.draw(cv)
            b
        }.getOrNull() ?: return fallback

        val bins = HashMap<Int, IntArray>()   // 色相桶 -> [r,g,b,count]
        val hsv = FloatArray(3)
        for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
                val c = bmp.getPixel(x, y)
                if (Color.alpha(c) < 200) continue
                Color.colorToHSV(c, hsv)
                if (hsv[1] < 0.18f || hsv[2] < 0.12f || hsv[2] > 0.96f) continue
                val bin = (hsv[0] / 15f).toInt()
                val acc = bins.getOrPut(bin) { IntArray(4) }
                acc[0] += Color.red(c)
                acc[1] += Color.green(c)
                acc[2] += Color.blue(c)
                acc[3] += 1
            }
        }
        val best = bins.values.maxByOrNull { it[3] } ?: return fallback
        val n = best[3].coerceAtLeast(1)
        return Color.rgb(best[0] / n, best[1] / n, best[2] / n)
    }
}
