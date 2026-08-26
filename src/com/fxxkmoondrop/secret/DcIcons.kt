package com.fxxkmoondrop.secret

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.content.Context

object DcIcons {
    fun build(context: Context, feature: Int, mode: Int, px: Int, color: Int): BitmapDrawable {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        val density = context.resources.displayMetrics.density
        p.strokeWidth = 2f * density
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = color
        val cx = px / 2f
        val cy = px / 2f
        val ir = px * 0.36f
        when (feature) {
            0 -> {
                when (mode) {
                    0 -> {
                        val rect = RectF(cx - ir * 0.6f, cy - ir * 0.8f, cx + ir * 0.6f, cy + ir * 0.8f)
                        c.drawRoundRect(rect, ir * 0.3f, ir * 0.3f, p)
                        c.drawLine(cx - ir * 1.1f, cy - ir * 1.1f, cx + ir * 1.1f, cy + ir * 1.1f, p)
                    }
                    1 -> {
                        c.drawCircle(cx, cy, ir * 0.5f, p)
                        val arcRect = RectF(cx - ir, cy - ir, cx + ir, cy + ir)
                        c.drawArc(arcRect, -75f, -30f, false, p)
                        val rad = -75f * Math.PI / 180f
                        c.drawLine(cx, cy, (cx + ir * Math.cos(rad)).toFloat(), (cy + ir * Math.sin(rad)).toFloat(), p)
                        val rad2 = -105f * Math.PI / 180f
                        c.drawLine(cx, cy, (cx + ir * Math.cos(rad2)).toFloat(), (cy + ir * Math.sin(rad2)).toFloat(), p)
                    }
                    2 -> {
                        c.drawCircle(cx, cy, ir * 0.45f, p)
                        c.drawCircle(cx, cy, ir * 0.8f, p)
                        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
                        dot.style = Paint.Style.FILL
                        dot.color = color
                        for (a in 0..3) {
                            val ang = (a * 90 - 90) * Math.PI / 180f
                            c.drawCircle((cx + ir * Math.cos(ang)).toFloat(), (cy + ir * Math.sin(ang)).toFloat(), px * 0.04f, dot)
                        }
                    }
                }
            }
            1 -> {
                val barW = px * 0.10f
                val gap = px * 0.06f
                val baseH = px * 0.16f
                val step = px * 0.12f
                for (i in 0..mode) {
                    val h = baseH + i * step
                    val x = cx - (barW * (mode + 1) + gap * mode) / 2f + i * (barW + gap)
                    val rect = RectF(x, cy + ir * 0.6f - h, x + barW, cy + ir * 0.6f)
                    val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
                    fillP.style = Paint.Style.FILL
                    fillP.color = color
                    c.drawRoundRect(rect, barW / 2f, barW / 2f, fillP)
                }
            }
            2 -> {
                when (mode) {
                    0 -> {
                        val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
                        fillP.style = Paint.Style.FILL
                        fillP.color = color
                        c.drawCircle(cx, cy, ir * 0.55f, fillP)
                        for (a in 0..7) {
                            val ang = (a * 45) * Math.PI / 180f
                            val r1 = ir * 0.75f
                            val r2 = ir * 1.05f
                            c.drawLine(
                                (cx + r1 * Math.cos(ang)).toFloat(), (cy + r1 * Math.sin(ang)).toFloat(),
                                (cx + r2 * Math.cos(ang)).toFloat(), (cy + r2 * Math.sin(ang)).toFloat(), p)
                        }
                    }
                    1 -> {
                        c.drawCircle(cx, cy, ir * 0.55f, p)
                    }
                }
            }
        }
        return BitmapDrawable(context.resources, bmp)
    }
}
