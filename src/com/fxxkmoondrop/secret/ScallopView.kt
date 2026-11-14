package com.fxxkmoondrop.secret

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Material 3 Expressive 风格的「形变圆形」背景（loading indicator 的形态语言）。
 *
 * 形状是 12 瓣的波浪圆（r(θ) = R·(1 + a·cos(Nθ))）：整体缓慢自转，
 * 同时让瓣深 a 在 0.03~0.07 之间往复，看起来像在缓缓"呼吸/形变"。
 * 只做背景装饰，不拦截触摸；页面不可见时停止动画。
 */
class ScallopView(ctx: Context, private val fillColor: Int) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val path = Path()

    private var phase = 0f
    private var amp = 0.05f

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 9000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }
    private val breathe = ValueAnimator.ofFloat(0.03f, 0.075f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            amp = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        val scale = android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (scale == 0f) {
            spin.duration = 1
            breathe.duration = 1
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!spin.isStarted) spin.start()
        if (!breathe.isStarted) breathe.start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        breathe.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(cx, cy) - 2f * resources.displayMetrics.density
        val lobes = 12
        val steps = 240
        path.reset()
        for (i in 0..steps) {
            val t = i.toFloat() / steps * 360f
            val rad = Math.toRadians((t + phase).toDouble())
            val r = base * (1f + amp * Math.cos(lobes * rad).toFloat())
            val x = cx + (r * Math.cos(rad)).toFloat()
            val y = cy + (r * Math.sin(rad)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    /** 设置填充色（跟随图标主色），带一点透明度让上层图标清晰可见。 */
    fun setFill(color: Int, alpha: Int = 255) {
        paint.color = (color and 0x00FFFFFF) or (alpha shl 24)
        invalidate()
    }

    companion object {
        @JvmStatic
        fun tint(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24) or
                (Color.BLACK and 0xFF000000.toInt())
    }
}
