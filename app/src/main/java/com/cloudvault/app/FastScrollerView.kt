package com.cloudvault.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
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

    private val trackView: View
    private val thumbContainer: FrameLayout
    private val thumbPill: View
    private val bubbleView: TextView

    private var recyclerView: RecyclerView? = null
    private var isDragging = false
    private var popupTextProvider: ((Int) -> String?)? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        if (!isDragging) {
            animateFade(targetAlpha = 0.25f)
        }
    }

    private val thumbHeightPx = (52 * resources.displayMetrics.density).toInt()

    init {
        clipChildren = false
        clipToPadding = false

        // Vertical track line
        trackView = View(context).apply {
            val gd = GradientDrawable().apply {
                setColor(Color.parseColor("#1FFFFFFF"))
                cornerRadius = 2f * resources.displayMetrics.density
            }
            background = gd
            val trackWidthPx = (3 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(trackWidthPx, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END
                marginEnd = (6 * resources.displayMetrics.density).toInt()
            }
        }
        addView(trackView)

        // Draggable Thumb Container with generous touch area
        thumbContainer = FrameLayout(context).apply {
            clipChildren = false
            layoutParams = LayoutParams(
                (40 * resources.displayMetrics.density).toInt(),
                thumbHeightPx
            ).apply {
                gravity = Gravity.END or Gravity.TOP
            }
        }

        // Sleek visual pill inside the thumb container
        thumbPill = View(context).apply {
            val pillWidthPx = (7 * resources.displayMetrics.density).toInt()
            val gd = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.accent_cyan))
                cornerRadius = 10f * resources.displayMetrics.density
            }
            background = gd
            layoutParams = LayoutParams(pillWidthPx, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = (4 * resources.displayMetrics.density).toInt()
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
                marginEnd = (44 * resources.displayMetrics.density).toInt()
            }
        }
        addView(bubbleView)

        alpha = 0.25f
    }

    fun attachRecyclerView(rv: RecyclerView) {
        this.recyclerView = rv
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!isDragging) {
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

                // Highlight thumb pill
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
            val bHeight = if (bubbleView.height > 0) bubbleView.height else (32 * resources.displayMetrics.density).toInt()
            val bubbleY = (thumbContainer.y + (thumbHeightPx / 2f) - (bHeight / 2f))
                .coerceIn(paddingTop.toFloat(), (height - paddingBottom - bHeight).toFloat())
            bubbleView.y = bubbleY
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isDragging) {
            updateThumbPositionFromList()
        }
    }
}
