package com.xxss0903.trainalaram.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.xxss0903.trainalaram.R

/**
 * 极简环形进度条：底环 + 从 12 点钟方向顺时针增长的高亮弧。
 * 自绘实现，避免依赖 Material 组件的版本差异。
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arcBounds = RectF()
    private var progress = 0f

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.ProgressRingView)
        trackPaint.color = a.getColor(R.styleable.ProgressRingView_ringTrackColor, Color.DKGRAY)
        progressPaint.color = a.getColor(R.styleable.ProgressRingView_ringColor, Color.WHITE)
        val stroke = a.getDimension(R.styleable.ProgressRingView_ringStrokeWidth, dp(14f))
        a.recycle()
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
    }

    fun setProgress(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped == progress) return
        progress = clamped
        invalidate()
    }

    fun setRingColor(color: Int) {
        if (progressPaint.color == color) return
        progressPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val inset = progressPaint.strokeWidth / 2f
        arcBounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(arcBounds, START_ANGLE, SWEEP_FULL * progress, false, progressPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val START_ANGLE = -90f
        const val SWEEP_FULL = 360f
    }
}
