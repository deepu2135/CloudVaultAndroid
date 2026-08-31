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
    private var maxScale = 6f
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
            parent?.requestDisallowInterceptTouchEvent(true)
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
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            super.onScaleEnd(detector)
            if (saveScale <= 1.05f) {
                resetZoom()
            }
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (saveScale > 1.05f) {
                resetZoom()
            } else {
                val targetScale = 2.5f
                val factor = targetScale / saveScale
                saveScale = targetScale
                mMatrix.postScale(factor, factor, e.x, e.y)
                fixTrans()
                invalidate()
            }
            return true
        }
    }

    fun zoomIn(step: Float = 1.35f) {
        val targetScale = (saveScale * step).coerceAtMost(maxScale)
        val factor = targetScale / saveScale
        saveScale = targetScale
        val focusX = viewWidth / 2f
        val focusY = viewHeight / 2f
        mMatrix.postScale(factor, factor, focusX, focusY)
        fixTrans()
        invalidate()
    }

    fun zoomOut(step: Float = 1.35f) {
        val targetScale = (saveScale / step).coerceAtLeast(minScale)
        val factor = targetScale / saveScale
        saveScale = targetScale
        val focusX = viewWidth / 2f
        val focusY = viewHeight / 2f
        mMatrix.postScale(factor, factor, focusX, focusY)
        fixTrans()
        invalidate()
    }

    fun getScale(): Float = saveScale

    fun resetZoom() {
        saveScale = 1f
        fitToScreen()
        parent?.requestDisallowInterceptTouchEvent(false)
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
        imageMatrix = Matrix(mMatrix)
    }

    private fun getFixTranslation(trans: Float, viewSize: Float, contentSize: Float): Float {
        if (contentSize <= viewSize) {
            val targetCenter = (viewSize - contentSize) / 2f
            return targetCenter - trans
        }
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

        val w = if (viewWidth > 0) viewWidth else if (width > 0) width else resources.displayMetrics.widthPixels
        val h = if (viewHeight > 0) viewHeight else if (height > 0) height else resources.displayMetrics.heightPixels

        if (bmW <= 0 || bmH <= 0 || w <= 0 || h <= 0) return
        viewWidth = w
        viewHeight = h

        val scaleX = w.toFloat() / bmW.toFloat()
        val scaleY = h.toFloat() / bmH.toFloat()
        val scale = scaleX.coerceAtMost(scaleY)

        mMatrix.reset()
        mMatrix.setScale(scale, scale)

        val displayedW = scale * bmW.toFloat()
        val displayedH = scale * bmH.toFloat()

        val redundantX = (w.toFloat() - displayedW) / 2f
        val redundantY = (h.toFloat() - displayedH) / 2f

        mMatrix.postTranslate(redundantX, redundantY)

        origWidth = bmW.toFloat()
        origHeight = bmH.toFloat()
        baseScale = scale
        saveScale = 1f

        imageMatrix = Matrix(mMatrix)
        invalidate()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fitToScreen()
        post { fitToScreen() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            viewWidth = w
            viewHeight = h
            fitToScreen()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val h = bottom - top
        if (w > 0 && h > 0 && (w != viewWidth || h != viewHeight)) {
            viewWidth = w
            viewHeight = h
            fitToScreen()
        }
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
                if (saveScale > 1.05f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    val deltaX = curr.x - last.x
                    val deltaY = curr.y - last.y

                    val totalScale = baseScale * saveScale
                    val contentW = origWidth * totalScale
                    val contentH = origHeight * totalScale

                    if (saveScale > 1.05f) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }

                    val dragX = if (contentW <= viewWidth) 0f else deltaX
                    val dragY = if (contentH <= viewHeight) 0f else deltaY

                    mMatrix.postTranslate(dragX, dragY)
                    fixTrans()
                    last.set(curr.x, curr.y)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                if (saveScale <= 1.05f) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                mode = NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
