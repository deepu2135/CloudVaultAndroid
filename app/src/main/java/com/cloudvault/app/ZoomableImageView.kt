package com.cloudvault.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val mMatrix = Matrix()
    private var mode = NONE

    private val last = PointF()
    private val start = PointF()
    private var minScale = 1f
    private var maxScale = 5f
    private val m = FloatArray(9)

    private var viewWidth = 0
    private var viewHeight = 0
    private var saveScale = 1f
    private var baseScale = 1f
    private var origWidth = 0f
    private var origHeight = 0f

    private var scaleDetector: ScaleGestureDetector
    private var gestureDetector: GestureDetector

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    init {
        super.setClickable(true)
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
        scaleType = ScaleType.MATRIX
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            mode = ZOOM
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var scaleFactor = detector.scaleFactor
            val prevScale = saveScale
            saveScale *= scaleFactor

            if (saveScale > maxScale) {
                saveScale = maxScale
                scaleFactor = maxScale / prevScale
            } else if (saveScale < minScale) {
                saveScale = minScale
                scaleFactor = minScale / prevScale
            }

            val totalScale = baseScale * saveScale
            val focusX = if (origWidth * totalScale <= viewWidth) viewWidth / 2f else detector.focusX
            val focusY = if (origHeight * totalScale <= viewHeight) viewHeight / 2f else detector.focusY

            mMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY)
            fixTrans()
            imageMatrix = mMatrix
            invalidate()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (saveScale > minScale) {
                resetZoom()
            } else {
                val targetScale = 2.5f
                val factor = targetScale / saveScale
                saveScale = targetScale
                mMatrix.postScale(factor, factor, e.x, e.y)
                fixTrans()
                imageMatrix = mMatrix
                invalidate()
            }
            return true
        }
    }

    fun resetZoom() {
        saveScale = 1f
        fitToScreen()
    }

    private fun fixTrans() {
        mMatrix.getValues(m)
        val transX = m[Matrix.MTRANS_X]
        val transY = m[Matrix.MTRANS_Y]

        val totalScale = baseScale * saveScale
        val contentW = origWidth * totalScale
        val contentH = origHeight * totalScale

        val fixX = getFixTranslation(transX, viewWidth.toFloat(), contentW)
        val fixY = getFixTranslation(transY, viewHeight.toFloat(), contentH)

        if (fixX != 0f || fixY != 0f) {
            mMatrix.postTranslate(fixX, fixY)
        }
    }

    private fun getFixTranslation(trans: Float, viewSize: Float, contentSize: Float): Float {
        if (contentSize <= viewSize) {
            // Keep centered in view
            val targetCenter = (viewSize - contentSize) / 2f
            return targetCenter - trans
        }
        // Clamped inside bounds
        val minTrans = viewSize - contentSize
        val maxTrans = 0f

        return when {
            trans < minTrans -> minTrans - trans
            trans > maxTrans -> maxTrans - trans
            else -> 0f
        }
    }

    fun fitToScreen() {
        val d = drawable ?: return
        val bmW = d.intrinsicWidth
        val bmH = d.intrinsicHeight
        if (bmW <= 0 || bmH <= 0 || viewWidth <= 0 || viewHeight <= 0) return

        val scaleX = viewWidth.toFloat() / bmW.toFloat()
        val scaleY = viewHeight.toFloat() / bmH.toFloat()
        val scale = scaleX.coerceAtMost(scaleY)

        mMatrix.reset()
        mMatrix.setScale(scale, scale)

        val displayedW = scale * bmW.toFloat()
        val displayedH = scale * bmH.toFloat()

        val redundantX = (viewWidth.toFloat() - displayedW) / 2f
        val redundantY = (viewHeight.toFloat() - displayedH) / 2f

        mMatrix.postTranslate(redundantX, redundantY)

        origWidth = bmW.toFloat()
        origHeight = bmH.toFloat()
        baseScale = scale
        saveScale = 1f

        imageMatrix = mMatrix
        invalidate()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fitToScreen() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        fitToScreen()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val curr = PointF(event.x, event.y)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                last.set(curr)
                start.set(last)
                mode = DRAG
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    val deltaX = curr.x - last.x
                    val deltaY = curr.y - last.y

                    val totalScale = baseScale * saveScale
                    val contentW = origWidth * totalScale
                    val contentH = origHeight * totalScale

                    val dragX = if (contentW <= viewWidth) 0f else deltaX
                    val dragY = if (contentH <= viewHeight) 0f else deltaY

                    mMatrix.postTranslate(dragX, dragY)
                    fixTrans()
                    last.set(curr.x, curr.y)
                    imageMatrix = mMatrix
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
            }
        }
        return true
    }
}
