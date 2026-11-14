package com.fxxkmoondrop.secret

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 图标外圈的旋转弧（Material 加载条手感）。
 *
 * 画两层：底层是完整描边轨道（outlineVariant），上层是一段 90° 圆头弧（primary），
 * 用 ValueAnimator 匀速 360° 旋转。只在 attach 时启动、detach 时取消 —— 页面不可见时不空转。
 */
class RingView(ctx: Context, private val accent: Int, private val track: Int) : View(ctx) {

    private val density = resources.displayMetrics.density
    private var angle = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = track
        alpha = 90
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = accent
    }

    private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1300
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        // 系统关闭动画时不要转：跟着 animator scale 走
        if (android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) {
            anim.duration = 1
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!anim.isStarted) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = (2.5f * density) + 2f * density
        val r = (minOf(width, height) / 2f) - inset
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, r, trackPaint)
        val box = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(box, angle, 90f, false, arcPaint)
    }
}
