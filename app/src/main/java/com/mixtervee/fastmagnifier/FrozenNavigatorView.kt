package com.mixtervee.fastmagnifier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.min

class FrozenNavigatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val DEFAULT_SHOW_MS = 2200L
        const val DEFAULT_MANUAL_SHOW_MS = 4500L
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 10, 16, 24)
        style = Paint.Style.FILL
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val viewportFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 220, 255)
        style = Paint.Style.FILL
    }
    private val viewportStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 220, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }

    private val imageRect = RectF()
    private val viewportRect = RectF()
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dragging = false
    private var manualShowMs = DEFAULT_MANUAL_SHOW_MS

    // The navigator's normal frozen-view rectangle is the permanent Overview zone.
    // Cache it so Full View can use that same familiar location after app chrome is hidden.
    private var overviewZoneLeft = 0
    private var overviewZoneTop = 0
    private var overviewZoneWidth = 0
    private var overviewZoneHeight = 0

    private val hideRunnable = Runnable {
        if (!dragging) hideImmediately()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!isFullViewActive() && width > 0 && height > 0) cacheOverviewZone()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val target = targetImage() ?: return
        if (target.visibility != VISIBLE) return
        val bitmap = currentBitmap(target) ?: return

        val radius = dp(10f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, backgroundPaint)

        calculateImageRect(bitmap)
        canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint)
        canvas.drawRect(imageRect, borderPaint)

        updateViewportRect(target)
        canvas.drawRect(viewportRect, viewportFillPaint)
        canvas.drawRect(viewportRect, viewportStrokePaint)
    }

    fun setManualShowDuration(durationMs: Long) {
        manualShowMs = durationMs.coerceIn(2500L, 10000L)
    }

    fun showTemporarily(
        autoHideMs: Long = DEFAULT_SHOW_MS,
        allowInFullView: Boolean = false
    ): Boolean {
        val inFullView = isFullViewActive()
        if (inFullView && !allowInFullView) {
            hideImmediately()
            return false
        }

        val target = targetImage()
        if (target == null || target.visibility != VISIBLE || target.scaleX <= 1.01f) {
            hideImmediately()
            return false
        }

        if (!inFullView) cacheOverviewZone()

        // While the overview is visible, its invisible toggle must not sit on top of
        // it. The navigator itself owns all taps/drags until it fades.
        fullViewOverviewHotspot()?.apply {
            visibility = GONE
            setOnClickListener(null)
        }

        removeCallbacks(hideRunnable)
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        bringToFront()
        invalidate()

        if (!dragging && autoHideMs > 0L) postDelayed(hideRunnable, autoHideMs)
        return true
    }

    /** A normal frozen-image tap outside the dedicated Overview rectangle enters Full View. */
    fun showForManualTap(): Boolean {
        val target = targetImage() ?: return false
        if (target.visibility != VISIBLE) return false

        hideImmediately()
        rootView.findViewById<View>(R.id.fullViewButton)?.performClick()
        return true
    }

    /** Dedicated Overview toggle at the navigator's normal on-screen rectangle. */
    fun showForOverviewZone(): Boolean {
        val shown = showTemporarily(manualShowMs)
        if (shown) setStatus("Overview shown")
        return shown
    }

    /** Dedicated Full View Overview path using the same familiar rectangle. */
    fun showForFullView(): Boolean {
        val target = targetImage()
        if (target == null || target.visibility != VISIBLE || target.scaleX <= 1.01f) return false

        removeCallbacks(hideRunnable)
        animate().cancel()
        dragging = false
        alpha = 1f
        visibility = VISIBLE

        fullViewOverviewHotspot()?.apply {
            visibility = GONE
            setOnClickListener(null)
        }
        fullViewOverviewDismissLayer()?.apply {
            visibility = VISIBLE
            isClickable = true
            isFocusable = true
            bringToFront()
            setOnClickListener { hideImmediately() }
        }

        bringToFront()
        invalidate()
        postDelayed(hideRunnable, manualShowMs)
        return true
    }

    /** Prepare the permanent Overview toggle after Full View hides the normal controls. */
    fun prepareFullViewOverviewToggle() {
        val target = targetImage()
        if (target == null || target.visibility != VISIBLE || target.scaleX <= 1.01f) {
            fullViewOverviewHotspot()?.visibility = GONE
            return
        }
        configureOverviewToggleZone(inFullView = true)
    }

    fun toggleVisibility(): Boolean = showForOverviewZone()

    fun hideImmediately() {
        if (!isFullViewActive()) cacheOverviewZone()
        removeCallbacks(hideRunnable)
        dragging = false
        animate().cancel()
        alpha = 0f
        visibility = INVISIBLE
        finishOverviewState()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = targetImage() ?: return false
        if (target.visibility != VISIBLE || target.scaleX <= 1.01f) return false
        val bitmap = currentBitmap(target) ?: return false
        calculateImageRect(bitmap)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                removeCallbacks(hideRunnable)
                animate().cancel()
                alpha = 1f
                parent?.requestDisallowInterceptTouchEvent(true)
                updateViewportRect(target)
                if (viewportRect.contains(event.x, event.y)) {
                    dragOffsetX = event.x - viewportRect.centerX()
                    dragOffsetY = event.y - viewportRect.centerY()
                } else {
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                }
                moveTarget(target, event.x - dragOffsetX, event.y - dragOffsetY)
                setStatus("Moving overview")
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                moveTarget(target, event.x - dragOffsetX, event.y - dragOffsetY)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                moveTarget(target, event.x - dragOffsetX, event.y - dragOffsetY)
                dragging = false
                setStatus("Overview moved")
                removeCallbacks(hideRunnable)
                val afterDragMs = (manualShowMs - 1000L).coerceAtLeast(2500L)
                postDelayed(hideRunnable, afterDragMs)
                performClick()
                return true
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRunnable)
        super.onDetachedFromWindow()
    }

    private fun moveTarget(target: PanZoomImageView, centerX: Float, centerY: Float) {
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return
        val nx = ((centerX - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
        val ny = ((centerY - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
        target.panToNormalized(nx, ny)
        invalidate()
    }

    private fun updateViewportRect(target: PanZoomImageView) {
        val visible = target.visibleBitmapRectNormalized()
        viewportRect.set(
            imageRect.left + visible.left * imageRect.width(),
            imageRect.top + visible.top * imageRect.height(),
            imageRect.left + visible.right * imageRect.width(),
            imageRect.top + visible.bottom * imageRect.height()
        )
    }

    private fun calculateImageRect(bitmap: Bitmap) {
        val padding = dp(6f)
        val availableWidth = (width - padding * 2f).coerceAtLeast(1f)
        val availableHeight = (height - padding * 2f).coerceAtLeast(1f)
        val fit = min(availableWidth / bitmap.width, availableHeight / bitmap.height)
        val drawWidth = bitmap.width * fit
        val drawHeight = bitmap.height * fit
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        imageRect.set(left, top, left + drawWidth, top + drawHeight)
    }

    private fun cacheOverviewZone() {
        if (width <= 0 || height <= 0) return
        overviewZoneLeft = left
        overviewZoneTop = top
        overviewZoneWidth = width
        overviewZoneHeight = height
    }

    private fun configureOverviewToggleZone(inFullView: Boolean) {
        val target = targetImage()
        val hotspot = fullViewOverviewHotspot() ?: return
        if (target == null || target.visibility != VISIBLE || target.scaleX <= 1.01f) {
            hotspot.visibility = GONE
            hotspot.setOnClickListener(null)
            return
        }

        if (overviewZoneWidth <= 0 || overviewZoneHeight <= 0) cacheOverviewZone()
        if (overviewZoneWidth <= 0 || overviewZoneHeight <= 0) return

        val params = hotspot.layoutParams
        params.width = overviewZoneWidth
        params.height = overviewZoneHeight
        hotspot.layoutParams = params

        hotspot.visibility = VISIBLE
        hotspot.isClickable = true
        hotspot.isFocusable = true
        hotspot.setOnClickListener {
            if (inFullView || isFullViewActive()) showForFullView() else showForOverviewZone()
        }

        // Reuse the existing hotspot view, but move/resize it to the navigator's exact
        // cached rectangle. This does not move the actual overview itself.
        hotspot.post {
            hotspot.translationX = (overviewZoneLeft - hotspot.left).toFloat()
            hotspot.translationY = (overviewZoneTop - hotspot.top).toFloat()
            hotspot.bringToFront()
        }
    }

    private fun currentBitmap(target: PanZoomImageView): Bitmap? =
        (target.drawable as? BitmapDrawable)?.bitmap

    private fun targetImage(): PanZoomImageView? =
        rootView.findViewById(R.id.frozenImage)

    private fun isFullViewActive(): Boolean =
        rootView.findViewById<View>(R.id.fullViewRestoreLayer)?.visibility == VISIBLE

    private fun fullViewOverviewHotspot(): View? =
        rootView.findViewById(R.id.fullViewOverviewHotspot)

    private fun fullViewOverviewDismissLayer(): View? =
        rootView.findViewById(R.id.fullViewOverviewDismissLayer)

    private fun finishOverviewState() {
        fullViewOverviewDismissLayer()?.apply {
            visibility = GONE
            setOnClickListener(null)
        }

        val target = targetImage()
        if (target == null || target.visibility != VISIBLE || target.scaleX <= 1.01f) {
            fullViewOverviewHotspot()?.apply {
                visibility = GONE
                setOnClickListener(null)
            }
            return
        }

        // As soon as the overview disappears (including after a drag), restore the
        // permanent toggle in exactly the same rectangle.
        configureOverviewToggleZone(inFullView = isFullViewActive())
    }

    private fun setStatus(message: String) {
        rootView.findViewById<TextView?>(R.id.statusText)?.text = message
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
