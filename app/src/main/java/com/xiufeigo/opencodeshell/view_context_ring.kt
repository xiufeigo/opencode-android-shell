package com.xiufeigo.opencodeshell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 16dp self-drawn session context ring (header). Track ring is grey, progress
 * arc uses the accent color. M1 renders a static 0%; the progress property is
 * already wired for M2.
 */
class ContextRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 0f..1f consumed context. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.oc_layer_03)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.oc_accent)
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBounds = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val stroke = (w * STROKE_RATIO).coerceAtLeast(1f)
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        val half = stroke / 2f
        arcBounds.set(half, half, w - half, h - half)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(arcBounds, -90f, 360f * progress, false, progressPaint)
        }
    }

    private companion object {
        const val STROKE_RATIO = 0.18f
    }
}
