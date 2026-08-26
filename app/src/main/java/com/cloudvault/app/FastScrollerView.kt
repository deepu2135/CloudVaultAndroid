package com.cloudvault.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

class FastScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val thumbContainer: FrameLayout
    private val thumbPill: View
    private val bubbleView: TextView

    private var recyclerView: RecyclerView? = null
    private var isDragging = false
    private var popupTextProvider: ((Int) -> String?)? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        if (!isDragging) {
            animateFade(targetAlpha = 0f)
        }
    }

    private val thumbHeightPx = (46 * resources.displayMetrics.density).toInt()

    init {
        clipChildren = false
        clipToPadding = false

        // Draggable Thumb Container with 32dp touch area
        thumbContainer = FrameLayout(context).apply {
            clipChildren = false
            layoutParams = LayoutParams(
                (32 * resources.displayMetrics.density).toInt(),
                thumbHeightPx
            ).apply {
                gravity = Gravity.END or Gravity.TOP
            }
        }

        // Sleek visual pill handle (5dp wide, rounded, cyan accent)
        thumbPill = View(context).apply {
            val pillWidthPx = (5 * resources.displayMetrics.density).toInt()
            val gd = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.accent_cyan))
                cornerRadius = 8f * resources.displayMetrics.density
            }
            background = gd
            layoutParams = LayoutParams(pillWidthPx, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = (6 * resources.displayMetrics.density).toInt()
            }
        }
        thumbContainer.addView(thumbPill)
        addView(thumbContainer)

        // Floating Date / Section Popup Bubble
        bubbleView = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.accent_cyan_bright))
            textSize = 12f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(
                (14 * resources.displayMetrics.density).toInt(),
                (6 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt(),
                (6 * resources.displayMetrics.density).toInt()
            )
            background = ContextCompat.getDrawable(context, R.drawable.bg_fastscroll_bubble)
            elevation = 8f * resources.displayMetrics.density
            alpha = 0f
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.TOP
                marginEnd = (38 * resources.displayMetrics.density).toInt()
            }
        }
        addView(bubbleView)

        // Initially hidden until user scrolls or touches
        alpha = 0f
    }

    fun attachRecyclerView(rv: RecyclerView) {
        this.recyclerView = rv
        rv.isVerticalScrollBarEnabled = false
        rv.isHorizontalScrollBarEnabled = false

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!isDragging && (dx != 0 || dy != 0)) {
                    showScrollerBriefly()
                    updateThumbPositionFromList()
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING || newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    showScrollerBriefly()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && !isDragging) {
                    postHide()
                }
            }
        })

        rv.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                post { updateThumbPositionFromList() }
            }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                post { updateThumbPositionFromList() }
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                post { updateThumbPositionFromList() }
            }
        })

        post { updateThumbPositionFromList() }
    }

    fun setPopupTextProvider(provider: (Int) -> String?) {
        this.popupTextProvider = provider
    }

    private fun showScrollerBriefly() {
        hideHandler.removeCallbacks(hideRunnable)
        animateFade(targetAlpha = 1.0f)
        postHide()
    }

    private fun postHide() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 1500)
    }

    private fun animateFade(targetAlpha: Float) {
        animate()
            .alpha(targetAlpha)
            .setDuration(200)
            .start()
    }

    fun updateThumbPositionFromList() {
        val rv = recyclerView ?: return
        val totalRange = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent()
        val offset = rv.computeVerticalScrollOffset()

        val scrollableHeight = totalRange - extent
        if (scrollableHeight <= 0 || totalRange <= 0) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE

        val fraction = (offset.toFloat() / scrollableHeight).coerceIn(0f, 1f)
        val availableTrack = (height - paddingTop - paddingBottom - thumbHeightPx).toFloat()
        if (availableTrack > 0) {
            thumbContainer.y = paddingTop + (fraction * availableTrack)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rv = recyclerView ?: return super.onTouchEvent(event)
        val itemCount = rv.adapter?.itemCount ?: 0
        if (itemCount == 0) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                hideHandler.removeCallbacks(hideRunnable)
                animateFade(1.0f)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

                // Highlight & expand thumb pill slightly
                thumbPill.animate().scaleX(1.4f).setDuration(120).start()

                // Show bubble
                bubbleView.visibility = View.VISIBLE
                bubbleView.scaleX = 0.85f
                bubbleView.scaleY = 0.85f
                bubbleView.animate()
                    .alpha(1.0f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(150)
                    .setListener(null)
                    .start()

                handleDrag(event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    handleDrag(event.y)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    thumbPill.animate().scaleX(1.0f).setDuration(120).start()

                    bubbleView.animate()
                        .alpha(0f)
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setDuration(150)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                bubbleView.visibility = View.GONE
                            }
                        })
                        .start()

                    postHide()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleDrag(rawY: Float) {
        val rv = recyclerView ?: return
        val itemCount = rv.adapter?.itemCount ?: return
        if (itemCount == 0) return

        val topBound = paddingTop.toFloat()
        val availableTrack = (height - paddingTop - paddingBottom - thumbHeightPx).toFloat()
        if (availableTrack <= 0) return

        val clampedY = (rawY - topBound - (thumbHeightPx / 2f)).coerceIn(0f, availableTrack)
        val fraction = clampedY / availableTrack

        thumbContainer.y = topBound + clampedY

        // Target position in adapter
        val targetPos = (fraction * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)
        (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetPos, 0)

        // Update bubble text and position
        val text = popupTextProvider?.invoke(targetPos)
        if (!text.isNullOrBlank()) {
            bubbleView.text = text
            bubbleView.visibility = View.VISIBLE
            val bHeight = if (bubbleView.height > 0) bubbleView.height else (32 * resources.displayMetrics.density).toInt()
            val bubbleY = (thumbContainer.y + (thumbHeightPx / 2f) - (bHeight / 2f))
                .coerceIn(paddingTop.toFloat(), (height - paddingBottom - bHeight).toFloat())
            bubbleView.y = bubbleY
        } else {
            bubbleView.visibility = View.GONE
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isDragging) {
            updateThumbPositionFromList()
        }
    }
}
