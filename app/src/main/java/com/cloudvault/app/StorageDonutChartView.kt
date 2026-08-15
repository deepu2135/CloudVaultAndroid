package com.cloudvault.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class StorageDonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Segment(
        val key: String,
        val sizeBytes: Long,
        val color: Int,
        val isSelected: Boolean = true
    )

    private val segments = mutableListOf<Segment>()
    private var centerMainText = "0"
    private var centerUnitText = "B"

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val bgRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#1E293B")
    }

    private val centerMainTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val centerUnitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val arcRect = RectF()

    fun setData(newSegments: List<Segment>, totalSizeFormatted: String) {
        segments.clear()
        segments.addAll(newSegments)

        val parts = totalSizeFormatted.trim().split(" ")
        if (parts.size >= 2) {
            centerMainText = parts[0]
            centerUnitText = parts[1]
        } else {
            centerMainText = totalSizeFormatted
            centerUnitText = ""
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredSize = (180 * resources.displayMetrics.density).toInt()
        val width = resolveSize(desiredSize, widthMeasureSpec)
        val height = resolveSize(desiredSize, heightMeasureSpec)
        val size = minOf(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val density = resources.displayMetrics.density
        val strokeWidth = 24f * density
        arcPaint.strokeWidth = strokeWidth
        bgRingPaint.strokeWidth = strokeWidth

        val pad = strokeWidth / 2f + 4f * density
        arcRect.set(pad, pad, width - pad, height - pad)

        // Draw background empty ring
        canvas.drawOval(arcRect, bgRingPaint)

        val totalSelectedBytes = segments.filter { it.isSelected }.sumOf { it.sizeBytes }

        if (totalSelectedBytes > 0) {
            var currentAngle = -90f
            val totalAngle = 360f

            for (seg in segments) {
                if (!seg.isSelected || seg.sizeBytes <= 0) continue
                val sweep = (seg.sizeBytes.toFloat() / totalSelectedBytes.toFloat()) * totalAngle
                arcPaint.color = seg.color
                canvas.drawArc(arcRect, currentAngle, sweep, false, arcPaint)
                currentAngle += sweep
            }
        }

        // Draw center text
        centerMainTextPaint.textSize = 28f * density
        centerUnitTextPaint.textSize = 13f * density

        val cx = width / 2f
        val cy = height / 2f

        val mainY = if (centerUnitText.isNotBlank()) cy - 2f * density else cy + (centerMainTextPaint.textSize / 3f)
        canvas.drawText(centerMainText, cx, mainY, centerMainTextPaint)

        if (centerUnitText.isNotBlank()) {
            val unitY = cy + 18f * density
            canvas.drawText(centerUnitText, cx, unitY, centerUnitTextPaint)
        }
    }
}
