package com.fxxkmoondrop.secret

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * v30: 自定义胶囊开关，替代原生 Switch。
 * 圆角轨道 + 圆形滑块，开启时滑块平滑滑向右侧，颜色跟随主题色。
 */
class PillSwitch(c: Context) : View(c) {
    interface OnCheckedChangeListener {
        fun onCheckedChanged(buttonView: PillSwitch, isChecked: Boolean)
    }

    private var checked = false
    private var progress = 0f // 0..1，滑块位置比例
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var trackOn = 0xFF6750A4.toInt() // overridden by theme in init
    private var trackOff = 0x33000000.toInt() // 半透明黑，深浅色背景都协调
    private var thumbOn = 0xFFFFFFFF.toInt()
    private var thumbOff = 0xFFFFFFFF.toInt()
    private var listener: OnCheckedChangeListener? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { setChecked(!checked, true) }
    }

    fun setChecked(c: Boolean) {
        setChecked(c, false)
    }

    private fun setChecked(c: Boolean, animate: Boolean) {
        if (checked == c) return
        checked = c
        if (animate) {
            val anim = ValueAnimator.ofFloat(progress, if (checked) 1f else 0f)
            anim.duration = 180
            anim.interpolator = DecelerateInterpolator()
            anim.addUpdateListener { a ->
                progress = a.animatedValue as Float
                invalidate()
            }
            anim.start()
        } else {
            progress = if (checked) 1f else 0f
            invalidate()
        }
        listener?.onCheckedChanged(this, checked)
    }

    fun isChecked(): Boolean = checked

    fun setOnCheckedChangeListener(l: OnCheckedChangeListener?) {
        listener = l
    }

    /** 设置轨道颜色：on=开启色，off=关闭色 */
    fun setColors(on: Int, off: Int) {
        trackOn = on
        trackOff = off
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(dp(52f).toInt(), dp(32f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // 轨道
        trackPaint.color = lerpColor(trackOff, trackOn, progress)
        c.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, trackPaint)
        // 滑块
        val pad = dp(2f)
        val thumbD = h - pad * 2f
        val cx = pad + thumbD / 2f + (w - thumbD - pad * 2f) * progress
        thumbPaint.color = lerpColor(thumbOff, thumbOn, progress)
        c.drawCircle(cx, h / 2f, thumbD / 2f, thumbPaint)
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val aa = (a shr 24) and 0xFF
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val ba = (b shr 24) and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        return ((aa + (ba - aa) * t).toInt() shl 24) or
                ((ar + (br - ar) * t).toInt() shl 16) or
                ((ag + (bg - ag) * t).toInt() shl 8) or
                (ab + (bb - ab) * t).toInt()
    }
}
