package com.mixtervee.fastmagnifier

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ScreenMagnifierService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ScreenMagnifierService? = null
            private set

        private const val DEFAULT_SCALE = 2.5f
        private const val MIN_SCALE = 1.5f
        private const val MAX_SCALE = 6.0f
        private const val SCALE_STEP = 0.5f
        private const val SCREENSHOT_REFRESH_MS = 360L
        private const val CYAN = 0xff00bcd4.toInt()
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val barcodeScanner by lazy { BarcodeScannerController() }

    private var lensView: View? = null
    private var lensImage: ImageView? = null
    private var zoomLabel: TextView? = null
    private var lensParams: WindowManager.LayoutParams? = null
    private var copyPopupView: View? = null
    private var barcodePopupView: View? = null
    private var lastBarcodeScanAt = 0L
    private var lastScreenBarcodeValue = ""
    private var lastScreenBarcodeShownAt = 0L
    private var minimizedView: View? = null
    private var minimizedParams: WindowManager.LayoutParams? = null
    private var magnifierMinimized = false
    private var miniDragging = false
    private var miniDownRawX = 0f
    private var miniDownRawY = 0f
    private var miniStartX = 0
    private var miniStartY = 0
    private var miniLastX = -1
    private var miniLastY = -1

    private var currentScale = DEFAULT_SCALE
    private var magnifierRunning = false
    private var refreshEnabled = false
    private var screenshotInFlight = false
    private var copyInProgress = false

    private var lastWindowBitmap: Bitmap? = null
    private var lastWindowBounds = Rect()
    private var sourceCenterScreenX = 0f
    private var sourceCenterScreenY = 0f

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0
    private var dragging = false
    private var resizing = false
    private var resizeStartSpan = 0f
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var longPressTriggered = false
    private var cornerResizing = false
    private var cornerResizeStartRawX = 0f
    private var cornerResizeStartRawY = 0f
    private var cornerResizeStartX = 0
    private var cornerResizeStartY = 0
    private var cornerResizeStartWidth = 0
    private var cornerResizeStartHeight = 0
    private var pendingLongPressX = 0f
    private var pendingLongPressY = 0f

    private val touchSlop: Float by lazy {
        ViewConfiguration.get(this).scaledTouchSlop.toFloat()
    }

    private val refreshRunnable = Runnable { refreshLensScreenshot() }
    private val orientationClampRunnable = Runnable { clampOverlaysToCurrentDisplay() }
    private val longPressRunnable = Runnable {
        if (magnifierRunning && !dragging && !copyInProgress) {
            longPressTriggered = true
            handleTextLongPress(pendingLongPressX, pendingLongPressY)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!magnifierRunning) return

        // Give WindowManager/resources a moment to publish the new display bounds,
        // then shrink/reposition any overlay that no longer fits.
        mainHandler.removeCallbacks(orientationClampRunnable)
        mainHandler.postDelayed(orientationClampRunnable, 120L)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopScreenMagnifier()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopScreenMagnifier()
        recognizer.close()
        barcodeScanner.close()
        instance = null
        super.onDestroy()
    }

    fun startScreenMagnifier(): Boolean {
        if (!::windowManager.isInitialized) return false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Toast.makeText(
                this,
                getString(R.string.screen_requires_android_14),
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        stopScreenMagnifier()
        currentScale = DEFAULT_SCALE
        magnifierRunning = true

        if (!showLens()) {
            magnifierRunning = false
            return false
        }

        startRefreshing()
        Toast.makeText(
            this,
            getString(R.string.screen_usage_hint),
            Toast.LENGTH_LONG
        ).show()
        return true
    }

    fun stopScreenMagnifier() {
        magnifierRunning = false
        refreshEnabled = false
        screenshotInFlight = false
        copyInProgress = false
        dragging = false
        longPressTriggered = false
        magnifierMinimized = false
        miniDragging = false
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(orientationClampRunnable)

        if (::windowManager.isInitialized) {
            lensView?.let { removeOverlay(it) }
            copyPopupView?.let { removeOverlay(it) }
            barcodePopupView?.let { removeOverlay(it) }
            minimizedView?.let { removeOverlay(it) }
        }

        lensView = null
        lensImage = null
        zoomLabel = null
        lensParams = null
        copyPopupView = null
        minimizedView = null
        minimizedParams = null
        copyPopupView = null
        barcodePopupView = null

        val old = lastWindowBitmap
        lastWindowBitmap = null
        lastWindowBounds.setEmpty()
        if (old != null && !old.isRecycled) old.recycle()
    }

    private fun minimizeScreenMagnifier() {
        if (!magnifierRunning || magnifierMinimized) return

        magnifierMinimized = true
        refreshEnabled = false
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(longPressRunnable)

        copyPopupView?.let { removeOverlay(it) }
        copyPopupView = null
        lensView?.let { removeOverlay(it) }

        if (!showMinimizedBubble()) {
            // If Android refuses the small overlay, put the full lens back rather
            // than leaving the user with no way to restore it.
            magnifierMinimized = false
            val lens = lensView
            val params = lensParams
            if (lens != null && params != null && !lens.isAttachedToWindow) {
                runCatching { windowManager.addView(lens, params) }
            }
            startRefreshing()
            Toast.makeText(this, R.string.could_not_minimize, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMinimizedBubble(): Boolean {
        minimizedView?.let { removeOverlay(it) }
        minimizedView = null
        minimizedParams = null

        val metrics = resources.displayMetrics
        val size = dp(58)
        val margin = dp(10)

        val bubble = TextView(this).apply {
            text = "🔍"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = getString(R.string.restore_screen_magnifier)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(242, 15, 18, 24))
                setStroke(dp(3), CYAN)
                cornerRadius = dp(17).toFloat()
            }
            elevation = dp(8).toFloat()
            setOnTouchListener { _, event -> handleMinimizedTouch(event) }
        }

        val maxX = max(0, metrics.widthPixels - size)
        val maxY = max(0, metrics.heightPixels - size)
        val startX = if (miniLastX >= 0) miniLastX.coerceIn(0, maxX)
            else (metrics.widthPixels - size - margin).coerceAtLeast(0)
        val startY = if (miniLastY >= 0) miniLastY.coerceIn(0, maxY)
            else (metrics.heightPixels / 3).coerceIn(0, maxY)

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        return try {
            windowManager.addView(bubble, params)
            minimizedView = bubble
            minimizedParams = params
            miniLastX = params.x
            miniLastY = params.y
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun handleMinimizedTouch(event: MotionEvent): Boolean {
        val bubble = minimizedView ?: return false
        val params = minimizedParams ?: return false
        val metrics = resources.displayMetrics

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                miniDownRawX = event.rawX
                miniDownRawY = event.rawY
                miniStartX = params.x
                miniStartY = params.y
                miniDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - miniDownRawX
                val dy = event.rawY - miniDownRawY
                val movement = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (!miniDragging && movement > touchSlop) miniDragging = true

                if (miniDragging) {
                    val maxX = max(0, metrics.widthPixels - bubble.width.coerceAtLeast(dp(58)))
                    val maxY = max(0, metrics.heightPixels - bubble.height.coerceAtLeast(dp(58)))
                    params.x = (miniStartX + dx.toInt()).coerceIn(0, maxX)
                    params.y = (miniStartY + dy.toInt()).coerceIn(0, maxY)
                    miniLastX = params.x
                    miniLastY = params.y
                    runCatching { windowManager.updateViewLayout(bubble, params) }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasDragging = miniDragging
                miniDragging = false
                if (!wasDragging) restoreScreenMagnifier()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                miniDragging = false
                return true
            }
        }
        return true
    }

    private fun restoreScreenMagnifier() {
        if (!magnifierRunning || !magnifierMinimized) return

        val lens = lensView ?: return
        val params = lensParams ?: return

        minimizedView?.let { removeOverlay(it) }
        minimizedView = null
        minimizedParams = null

        val restored = if (lens.isAttachedToWindow) {
            true
        } else {
            runCatching {
                windowManager.addView(lens, params)
                true
            }.getOrDefault(false)
        }

        if (!restored) {
            // Keep the restore bubble available if reattaching the lens fails.
            showMinimizedBubble()
            return
        }

        magnifierMinimized = false
        startRefreshing()
        lens.post {
            updateLensMatrix()
            requestImmediateRefresh()
        }
    }

    private fun clampOverlaysToCurrentDisplay() {
        if (!magnifierRunning || !::windowManager.isInitialized) return

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.coerceAtLeast(1)
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)

        // A transient copy popup is safer to dismiss on rotation than to leave it
        // positioned for the old orientation.
        copyPopupView?.let { removeOverlay(it) }
        copyPopupView = null

        val lens = lensView
        val params = lensParams
        if (lens != null && params != null) {
            val chromeHeight = dp(58)

            val minWidth = min(
                screenWidth,
                max((screenWidth * 0.42f).toInt(), dp(220))
            )
            val maxWidth = max(
                minWidth,
                (screenWidth * 0.96f).toInt().coerceAtMost(screenWidth)
            )

            val minHeight = min(
                screenHeight,
                max((screenHeight * 0.16f).toInt(), chromeHeight + dp(80))
            )
            val maxHeight = max(
                minHeight,
                (screenHeight * 0.72f).toInt().coerceAtMost(screenHeight)
            )

            val currentWidth = if (params.width > 0) params.width else lens.width
            val currentHeight = if (params.height > 0) params.height else lens.height

            params.width = currentWidth.coerceAtLeast(minWidth).coerceAtMost(maxWidth)
            params.height = currentHeight.coerceAtLeast(minHeight).coerceAtMost(maxHeight)

            val maxX = max(0, screenWidth - params.width)
            val maxY = max(0, screenHeight - params.height)
            params.x = params.x.coerceIn(0, maxX)
            params.y = params.y.coerceIn(0, maxY)

            (lensImage?.parent as? View)?.layoutParams =
                (lensImage?.parent as? View)?.layoutParams?.apply {
                    height = (params.height - chromeHeight).coerceAtLeast(dp(80))
                }

            // The full lens is detached while minimized. Update its saved params now
            // so restoring after a rotation is still safe, but only call WindowManager
            // when it is actually attached.
            if (lens.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(lens, params) }
            }
            lens.requestLayout()
            lens.post {
                updateLensMatrix()
                requestImmediateRefresh()
            }
        }

        val bubble = minimizedView
        val bubbleParams = minimizedParams
        if (bubble != null && bubbleParams != null) {
            val bubbleWidth = when {
                bubble.width > 0 -> bubble.width
                bubbleParams.width > 0 -> bubbleParams.width
                else -> dp(58)
            }
            val bubbleHeight = when {
                bubble.height > 0 -> bubble.height
                bubbleParams.height > 0 -> bubbleParams.height
                else -> dp(58)
            }

            bubbleParams.x = bubbleParams.x.coerceIn(0, max(0, screenWidth - bubbleWidth))
            bubbleParams.y = bubbleParams.y.coerceIn(0, max(0, screenHeight - bubbleHeight))
            miniLastX = bubbleParams.x
            miniLastY = bubbleParams.y

            if (bubble.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(bubble, bubbleParams) }
            }
        }
    }

    private fun returnToMainMenu() {
        stopScreenMagnifier()
        startActivity(
            Intent(this, LauncherActivity::class.java).apply {
                action = LauncherActivity.ACTION_SHOW_CHOOSER
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        )
    }

    private fun exitApplication() {
        stopScreenMagnifier()
        startActivity(
            Intent(this, LauncherActivity::class.java).apply {
                action = LauncherActivity.ACTION_EXIT_APP
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        )
    }

    private fun showLens(): Boolean {
        val metrics = resources.displayMetrics
        val lensWidth = (metrics.widthPixels * 0.72f).toInt()
        val imageHeight = (metrics.heightPixels * 0.24f).toInt()
        val controlsHeight = dp(52)
        val totalHeight = imageHeight + controlsHeight + dp(6)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = object : android.graphics.drawable.Drawable() {
                private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(245, 15, 18, 24)
                    style = android.graphics.Paint.Style.FILL
                }
                private val cyanPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = CYAN
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = dp(3).toFloat()
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
                private val yellowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.YELLOW
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = dp(3).toFloat()
                    strokeJoin = android.graphics.Paint.Join.ROUND
                    strokeCap = android.graphics.Paint.Cap.SQUARE
                }

                override fun draw(canvas: android.graphics.Canvas) {
                    val halfStroke = dp(3) / 2f
                    val left = bounds.left + halfStroke
                    val top = bounds.top + halfStroke
                    val right = bounds.right - halfStroke
                    val bottom = bounds.bottom - halfStroke
                    val radius = dp(16).toFloat()
                    val leg = dp(26).toFloat()
                    val innerRadius = dp(8).toFloat()
                    // The image frame ends above the 52dp control row plus the
                    // container's 3dp bottom padding. Bottom resize hit targets are
                    // anchored there, so the visual indicators must be there too.
                    val imageBottom = bottom - dp(55).toFloat()
                    val rect = android.graphics.RectF(left, top, right, bottom)

                    canvas.drawRoundRect(rect, radius, radius, fillPaint)
                    canvas.drawRoundRect(rect, radius, radius, cyanPaint)

                    fun cornerPath(corner: Int): android.graphics.Path {
                        return android.graphics.Path().apply {
                            when (corner) {
                                0 -> {
                                    moveTo(left, top + leg)
                                    lineTo(left, top + radius)
                                    quadTo(left, top, left + radius, top)
                                    lineTo(left + leg, top)
                                }
                                1 -> {
                                    moveTo(right - leg, top)
                                    lineTo(right - radius, top)
                                    quadTo(right, top, right, top + radius)
                                    lineTo(right, top + leg)
                                }
                                2 -> {
                                    moveTo(left, imageBottom - leg)
                                    lineTo(left, imageBottom - innerRadius)
                                    quadTo(left, imageBottom, left + innerRadius, imageBottom)
                                    lineTo(left + leg, imageBottom)
                                }
                                else -> {
                                    moveTo(right - leg, imageBottom)
                                    lineTo(right - innerRadius, imageBottom)
                                    quadTo(right, imageBottom, right, imageBottom - innerRadius)
                                    lineTo(right, imageBottom - leg)
                                }
                            }
                        }
                    }

                    for (corner in 0..3) {
                        canvas.drawPath(cornerPath(corner), yellowPaint)
                    }
                }

                override fun setAlpha(alpha: Int) {
                    fillPaint.alpha = alpha
                    cyanPaint.alpha = alpha
                    yellowPaint.alpha = alpha
                    invalidateSelf()
                }

                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                    fillPaint.colorFilter = colorFilter
                    cyanPaint.colorFilter = colorFilter
                    yellowPaint.colorFilter = colorFilter
                    invalidateSelf()
                }

                @Suppress("DEPRECATION")
                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }
        }

        val imageFrame = FrameLayout(this)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(Color.BLACK)
            contentDescription = getString(R.string.screen_lens_description)
            setOnTouchListener { _, event -> handleLensTouch(event) }
        }
        imageFrame.addView(
            image,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        fun addCornerHandle(gravity: Int, corner: Int) {
            val handle = FrameLayout(this).apply {
                // Invisible 46dp hit target. The visual indicator is drawn into
                // the border, so nothing covers the magnified content.
                contentDescription = getString(R.string.resize_magnifier)
                setOnTouchListener { _, event -> handleCornerResize(event, corner) }
            }
            imageFrame.addView(
                handle,
                FrameLayout.LayoutParams(dp(46), dp(46), gravity)
            )
        }

        addCornerHandle(Gravity.TOP or Gravity.START, 0)
        addCornerHandle(Gravity.TOP or Gravity.END, 1)
        addCornerHandle(Gravity.BOTTOM or Gravity.START, 2)
        addCornerHandle(Gravity.BOTTOM or Gravity.END, 3)

        container.addView(
            imageFrame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                imageHeight
            )
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        val equalButtonParams = LinearLayout.LayoutParams(
            0,
            controlsHeight,
            1f
        ).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }

        fun addEqualControl(label: String, textSizeSp: Float = 12f, action: () -> Unit) {
            controls.addView(
                controlButton(label, action).apply {
                    textSize = textSizeSp
                    isSingleLine = true
                    maxLines = 1
                    setPadding(dp(3), 0, dp(3), 0)
                },
                LinearLayout.LayoutParams(equalButtonParams)
            )
        }

        addEqualControl("−", 18f) { changeScale(-SCALE_STEP) }

        val scaleText = TextView(this).apply {
            text = formatScale()
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), 0)
        }
        controls.addView(
            scaleText,
            LinearLayout.LayoutParams(dp(46), controlsHeight)
        )

        addEqualControl("+", 18f) { changeScale(SCALE_STEP) }
        addEqualControl(getString(R.string.minimize_short)) { minimizeScreenMagnifier() }
        addEqualControl(getString(R.string.back)) { returnToMainMenu() }
        addEqualControl(getString(R.string.exit)) { exitApplication() }
        container.addView(
            controls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                controlsHeight
            )
        )

        val params = WindowManager.LayoutParams(
            lensWidth,
            totalHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - lensWidth) / 2
            y = (metrics.heightPixels - totalHeight) / 3
        }

        return try {
            windowManager.addView(container, params)
            lensView = container
            lensImage = image
            zoomLabel = scaleText
            lensParams = params
            true
        } catch (t: Throwable) {
            Toast.makeText(
                this,
                getString(R.string.lens_overlay_failed, t.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    private fun handleLensTouch(event: MotionEvent): Boolean {
        val params = lensParams ?: return false
        val view = lensView ?: return false
        val metrics = resources.displayMetrics

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                copyPopupView?.let { removeOverlay(it) }
                copyPopupView = null
                barcodePopupView?.let { removeOverlay(it) }
                barcodePopupView = null

                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWindowX = params.x
                dragStartWindowY = params.y
                pendingLongPressX = event.x
                pendingLongPressY = event.y
                dragging = false
                resizing = false
                longPressTriggered = false

                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(
                    longPressRunnable,
                    ViewConfiguration.getLongPressTimeout().toLong()
                )
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    mainHandler.removeCallbacks(longPressRunnable)
                    dragging = false
                    longPressTriggered = false
                    resizing = true
                    resizeStartSpan = pointerSpan(event)
                    resizeStartWidth = view.width.coerceAtLeast(params.width)
                    resizeStartHeight = view.height.coerceAtLeast(params.height)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (resizing && event.pointerCount >= 2) {
                    resizeLens(event, view, params, metrics.widthPixels, metrics.heightPixels)
                    return true
                }

                if (longPressTriggered) return true

                val movement = hypot(
                    (event.rawX - dragStartRawX).toDouble(),
                    (event.rawY - dragStartRawY).toDouble()
                ).toFloat()

                if (!dragging && movement > touchSlop) {
                    dragging = true
                    mainHandler.removeCallbacks(longPressRunnable)
                }

                if (dragging) {
                    val dx = (event.rawX - dragStartRawX).toInt()
                    val dy = (event.rawY - dragStartRawY).toInt()
                    val maxX = max(0, metrics.widthPixels - view.width)
                    val maxY = max(0, metrics.heightPixels - view.height)
                    params.x = (dragStartWindowX + dx).coerceIn(0, maxX)
                    params.y = (dragStartWindowY + dy).coerceIn(0, maxY)
                    runCatching { windowManager.updateViewLayout(view, params) }

                    // Smooth movement uses the already-cached screenshot, so dragging
                    // never waits for a new Accessibility screenshot.
                    updateLensMatrix()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (resizing && event.pointerCount <= 2) {
                    resizing = false
                    requestImmediateRefresh()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                val wasDragging = dragging
                val wasLongPress = longPressTriggered
                val wasResizing = resizing
                if (wasDragging || wasResizing) {
                    requestImmediateRefresh()
                } else if (!wasLongPress) {
                    handleLensTap(event.x, event.y)
                }
                dragging = false
                resizing = false
                longPressTriggered = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (dragging || resizing) requestImmediateRefresh()
                dragging = false
                resizing = false
                longPressTriggered = false
                return true
            }
        }
        return true
    }

    private fun handleCornerResize(event: MotionEvent, corner: Int): Boolean {
        val lens = lensView ?: return false
        val params = lensParams ?: return false
        val metrics = resources.displayMetrics

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mainHandler.removeCallbacks(longPressRunnable)
                cornerResizing = true
                cornerResizeStartRawX = event.rawX
                cornerResizeStartRawY = event.rawY
                cornerResizeStartX = params.x
                cornerResizeStartY = params.y
                cornerResizeStartWidth = lens.width.coerceAtLeast(params.width)
                cornerResizeStartHeight = lens.height.coerceAtLeast(params.height)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!cornerResizing) return true

                val dx = (event.rawX - cornerResizeStartRawX).toInt()
                val dy = (event.rawY - cornerResizeStartRawY).toInt()
                val startLeft = cornerResizeStartX
                val startTop = cornerResizeStartY
                val startRight = startLeft + cornerResizeStartWidth
                val startBottom = startTop + cornerResizeStartHeight

                val minWidth = (metrics.widthPixels * 0.42f).toInt().coerceAtLeast(dp(220))
                val maxWidth = (metrics.widthPixels * 0.96f).toInt()
                val chromeHeight = dp(58)
                val minHeight = (metrics.heightPixels * 0.16f).toInt().coerceAtLeast(chromeHeight + dp(80))
                val maxHeight = (metrics.heightPixels * 0.72f).toInt()

                var left = startLeft
                var top = startTop
                var right = startRight
                var bottom = startBottom

                when (corner) {
                    0 -> { left = startLeft + dx; top = startTop + dy }
                    1 -> { right = startRight + dx; top = startTop + dy }
                    2 -> { left = startLeft + dx; bottom = startBottom + dy }
                    else -> { right = startRight + dx; bottom = startBottom + dy }
                }

                if (corner == 0 || corner == 2) {
                    left = left.coerceIn(max(0, right - maxWidth), right - minWidth)
                } else {
                    right = right.coerceIn(left + minWidth, min(metrics.widthPixels, left + maxWidth))
                }

                if (corner == 0 || corner == 1) {
                    top = top.coerceIn(max(0, bottom - maxHeight), bottom - minHeight)
                } else {
                    bottom = bottom.coerceIn(top + minHeight, min(metrics.heightPixels, top + maxHeight))
                }

                left = left.coerceAtLeast(0)
                top = top.coerceAtLeast(0)
                right = right.coerceAtMost(metrics.widthPixels)
                bottom = bottom.coerceAtMost(metrics.heightPixels)

                params.x = left
                params.y = top
                params.width = (right - left).coerceAtLeast(minWidth)
                params.height = (bottom - top).coerceAtLeast(minHeight)

                (lensImage?.parent as? View)?.layoutParams =
                    (lensImage?.parent as? View)?.layoutParams?.apply {
                        height = (params.height - chromeHeight).coerceAtLeast(dp(80))
                    }

                runCatching { windowManager.updateViewLayout(lens, params) }
                lens.requestLayout()
                lens.post { updateLensMatrix() }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cornerResizing = false
                requestImmediateRefresh()
                return true
            }
        }
        return true
    }

    private fun pointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(
            (event.getX(1) - event.getX(0)).toDouble(),
            (event.getY(1) - event.getY(0)).toDouble()
        ).toFloat()
    }

    private fun resizeLens(
        event: MotionEvent,
        view: View,
        params: WindowManager.LayoutParams,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val startSpan = resizeStartSpan
        if (startSpan <= 0f) return

        val ratio = (pointerSpan(event) / startSpan).coerceIn(0.5f, 2.0f)
        val controlsHeight = dp(52)
        val chromeHeight = controlsHeight + dp(6)
        val minWidth = (screenWidth * 0.42f).toInt().coerceAtLeast(dp(220))
        val maxWidth = (screenWidth * 0.96f).toInt()
        val minHeight = (screenHeight * 0.16f).toInt().coerceAtLeast(chromeHeight + dp(80))
        val maxHeight = (screenHeight * 0.72f).toInt()

        val newWidth = (resizeStartWidth * ratio).toInt().coerceIn(minWidth, maxWidth)
        val newHeight = (resizeStartHeight * ratio).toInt().coerceIn(minHeight, maxHeight)

        params.width = newWidth
        params.height = newHeight
        params.x = params.x.coerceIn(0, max(0, screenWidth - newWidth))
        params.y = params.y.coerceIn(0, max(0, screenHeight - newHeight))

        (lensImage?.parent as? View)?.layoutParams =
            (lensImage?.parent as? View)?.layoutParams?.apply {
                height = (newHeight - chromeHeight).coerceAtLeast(dp(80))
            }

        runCatching { windowManager.updateViewLayout(view, params) }
        view.requestLayout()
        view.post { updateLensMatrix() }
    }

    private fun handleLensTap(localX: Float, localY: Float) {
        if (!magnifierRunning || copyInProgress) return
        val (screenX, screenY) = sourceScreenPoint(localX, localY)

        if (clickAccessibleNodeAt(screenX, screenY)) {
            mainHandler.postDelayed({ requestImmediateRefresh() }, 180L)
            return
        }

        dispatchMappedTap(screenX, screenY)
    }

    private fun clickAccessibleNodeAt(screenX: Float, screenY: Float): Boolean {
        val root = rootInActiveWindow ?: return false
        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val rect = Rect().also(node::getBoundsInScreen)
            if (!rect.contains(screenX.toInt(), screenY.toInt())) return

            val area = rect.width().toLong() * rect.height().toLong()
            if (area < bestArea) {
                bestArea = area
                bestNode = node
            }

            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }

        visit(root)

        var candidate = bestNode
        while (candidate != null) {
            val supportsClick = candidate.isClickable ||
                candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            if (supportsClick &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            candidate = candidate.parent
        }
        return false
    }

    private fun dispatchMappedTap(screenX: Float, screenY: Float) {
        val lens = lensView ?: return
        val params = lensParams ?: return
        var restored = false

        fun restoreOverlay() {
            if (restored) return
            restored = true
            if (magnifierRunning && !lens.isAttachedToWindow) {
                runCatching { windowManager.addView(lens, params) }
            }
            mainHandler.postDelayed({ requestImmediateRefresh() }, 120L)
        }

        // Remove the overlay for the synthetic tap instead of making the whole
        // overlay NOT_TOUCHABLE. Some Android builds can leave that flag stuck,
        // which disables Back/Exit until the service is restarted.
        if (lens.isAttachedToWindow) {
            runCatching { windowManager.removeViewImmediate(lens) }
        }

        val tapPath = Path().apply { moveTo(screenX, screenY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath, 0L, 70L))
            .build()

        // Give the underlying app one frame to become touchable after the overlay
        // is removed, then send the mapped tap. A watchdog always restores the lens
        // even if an OEM fails to deliver a gesture callback.
        mainHandler.postDelayed({
            if (!magnifierRunning) {
                restoreOverlay()
                return@postDelayed
            }

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        restoreOverlay()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        restoreOverlay()
                    }
                },
                mainHandler
            )

            if (!dispatched) restoreOverlay()
        }, 140L)

        mainHandler.postDelayed({ restoreOverlay() }, 900L)
    }

    private fun changeScale(delta: Float) {
        val requested = (currentScale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
        if (requested == currentScale) return
        currentScale = requested
        zoomLabel?.text = formatScale()
        updateLensMatrix()
        requestImmediateRefresh()
    }

    private fun formatScale(): String = String.format("%.1f×", currentScale)

    private fun startRefreshing() {
        refreshEnabled = true
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
    }

    private fun requestImmediateRefresh() {
        if (!magnifierRunning || !refreshEnabled || screenshotInFlight) return
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
    }

    private fun scheduleNextRefresh(delayMs: Long = SCREENSHOT_REFRESH_MS) {
        if (!magnifierRunning || !refreshEnabled) return
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, delayMs)
    }

    private fun refreshLensScreenshot() {
        if (
            !magnifierRunning ||
            !refreshEnabled ||
            screenshotInFlight ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) return

        val root = rootInActiveWindow
        if (root == null) {
            scheduleNextRefresh(500L)
            return
        }

        val windowId = root.windowId
        val windowBounds = Rect().also(root::getBoundsInScreen)
        if (windowBounds.width() <= 0 || windowBounds.height() <= 0) {
            scheduleNextRefresh(500L)
            return
        }

        screenshotInFlight = true
        takeScreenshotOfWindow(
            windowId,
            ContextCompat.getMainExecutor(this),
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    screenshotInFlight = false
                    val buffer = screenshot.hardwareBuffer
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    val screenBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()

                    if (screenBitmap != null) {
                        installWindowBitmap(screenBitmap, windowBounds)
                    }
                    scheduleNextRefresh()
                }

                override fun onFailure(errorCode: Int) {
                    screenshotInFlight = false
                    scheduleNextRefresh(
                        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) 450L else 650L
                    )
                }
            }
        )
    }

    private fun installWindowBitmap(bitmap: Bitmap, windowBounds: Rect) {
        val image = lensImage ?: run {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }

        val old = lastWindowBitmap
        lastWindowBitmap = bitmap
        lastWindowBounds = Rect(windowBounds)
        image.setImageBitmap(bitmap)
        updateLensMatrix()
        maybeScanBarcodeInLens()

        if (old != null && old !== bitmap && !old.isRecycled) {
            mainHandler.postDelayed({
                if (old !== lastWindowBitmap && !old.isRecycled) old.recycle()
            }, 1000L)
        }
    }

    private fun updateLensMatrix() {
        val bitmap = lastWindowBitmap ?: return
        val bounds = lastWindowBounds
        val params = lensParams ?: return
        val image = lensImage ?: return
        val lens = lensView ?: return

        if (
            bitmap.isRecycled ||
            bounds.width() <= 0 ||
            bounds.height() <= 0 ||
            image.width <= 0 ||
            image.height <= 0 ||
            lens.width <= 0 ||
            lens.height <= 0
        ) return

        val bitmapPerScreenX = bitmap.width.toFloat() / bounds.width()
        val bitmapPerScreenY = bitmap.height.toFloat() / bounds.height()

        // The lens itself stays fully on-screen, but its source position is mapped
        // across the entire capturable window. At the far left/top the source crop
        // touches the real left/top edge; at the far right/bottom it touches those
        // edges. This removes the old dead zones caused by tying the source to the
        // physical center of a large lens window.
        val metrics = resources.displayMetrics
        val travelX = (metrics.widthPixels - lens.width).coerceAtLeast(1)
        val travelY = (metrics.heightPixels - lens.height).coerceAtLeast(1)
        val fractionX = (params.x.toFloat() / travelX).coerceIn(0f, 1f)
        val fractionY = (params.y.toFloat() / travelY).coerceIn(0f, 1f)

        val halfSourceScreenWidth = (image.width / currentScale) / 2f
        val halfSourceScreenHeight = (image.height / currentScale) / 2f

        val minSourceCenterX = bounds.left + halfSourceScreenWidth
        val maxSourceCenterX = bounds.right - halfSourceScreenWidth
        val minSourceCenterY = bounds.top + halfSourceScreenHeight
        val maxSourceCenterY = bounds.bottom - halfSourceScreenHeight

        val requestedScreenCenterX = if (maxSourceCenterX >= minSourceCenterX) {
            minSourceCenterX + (maxSourceCenterX - minSourceCenterX) * fractionX
        } else {
            bounds.exactCenterX()
        }
        val requestedScreenCenterY = if (maxSourceCenterY >= minSourceCenterY) {
            minSourceCenterY + (maxSourceCenterY - minSourceCenterY) * fractionY
        } else {
            bounds.exactCenterY()
        }

        var centerBitmapX = (requestedScreenCenterX - bounds.left) * bitmapPerScreenX
        var centerBitmapY = (requestedScreenCenterY - bounds.top) * bitmapPerScreenY

        val halfSourceBitmapWidth = halfSourceScreenWidth * bitmapPerScreenX
        val halfSourceBitmapHeight = halfSourceScreenHeight * bitmapPerScreenY

        centerBitmapX = if (bitmap.width > halfSourceBitmapWidth * 2f) {
            centerBitmapX.coerceIn(halfSourceBitmapWidth, bitmap.width - halfSourceBitmapWidth)
        } else {
            bitmap.width / 2f
        }
        centerBitmapY = if (bitmap.height > halfSourceBitmapHeight * 2f) {
            centerBitmapY.coerceIn(halfSourceBitmapHeight, bitmap.height - halfSourceBitmapHeight)
        } else {
            bitmap.height / 2f
        }

        sourceCenterScreenX = bounds.left + centerBitmapX / bitmapPerScreenX
        sourceCenterScreenY = bounds.top + centerBitmapY / bitmapPerScreenY

        val displayScaleX = currentScale / bitmapPerScreenX
        val displayScaleY = currentScale / bitmapPerScreenY
        val translateX = image.width / 2f - centerBitmapX * displayScaleX
        val translateY = image.height / 2f - centerBitmapY * displayScaleY

        image.imageMatrix = Matrix().apply {
            setScale(displayScaleX, displayScaleY)
            postTranslate(translateX, translateY)
        }
        image.invalidate()
    }

    private fun maybeScanBarcodeInLens() {
        if (!magnifierRunning || !refreshEnabled || barcodePopupView != null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBarcodeScanAt < 1200L) return
        lastBarcodeScanAt = now

        val source = extractLensSourceBitmap() ?: return
        barcodeScanner.scan(source) { code ->
            if (code != null) {
                mainHandler.post { presentScreenCode(code) }
            }
        }
        if (!source.isRecycled) source.recycle()
    }

    private fun presentScreenCode(code: BarcodeScannerController.DetectedCode) {
        if (!magnifierRunning || barcodePopupView != null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (code.value == lastScreenBarcodeValue && now - lastScreenBarcodeShownAt < 10000L) return
        lastScreenBarcodeValue = code.value
        lastScreenBarcodeShownAt = now
        showBarcodePopup(code)
    }

    private fun showBarcodePopup(code: BarcodeScannerController.DetectedCode) {
        barcodePopupView?.let { removeOverlay(it) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(248, 20, 24, 31))
                setStroke(dp(2), CYAN)
                cornerRadius = dp(12).toFloat()
            }
        }

        panel.addView(TextView(this).apply {
            text = code.title
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        panel.addView(TextView(this).apply {
            text = code.value
            setTextColor(0xffe8eef5.toInt())
            textSize = 13f
            maxLines = 3
            setPadding(dp(2), dp(4), dp(2), dp(6))
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        if (code.isUrl) {
            actions.addView(controlButton(getString(R.string.open)) {
                val uri = runCatching { android.net.Uri.parse(code.value) }.getOrNull()
                if (uri != null && uri.scheme?.lowercase() in setOf("http", "https")) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }.onFailure {
                        Toast.makeText(this, R.string.no_app_open_link, Toast.LENGTH_SHORT).show()
                    }
                }
                barcodePopupView?.let { removeOverlay(it) }
                barcodePopupView = null
            })
        } else if (code.isProductBarcode) {
            actions.addView(controlButton(getString(R.string.search)) {
                val uri = android.net.Uri.parse(
                    "https://www.google.com/search?q=${android.net.Uri.encode(code.value)}"
                )
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.onFailure {
                    Toast.makeText(this, R.string.no_browser_available, Toast.LENGTH_SHORT).show()
                }
                barcodePopupView?.let { removeOverlay(it) }
                barcodePopupView = null
            })
        }

        actions.addView(controlButton(getString(R.string.copy)) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.scanned_code), code.value))
            Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
        })
        actions.addView(controlButton(getString(R.string.dismiss)) {
            barcodePopupView?.let { removeOverlay(it) }
            barcodePopupView = null
        })
        panel.addView(actions)

        val metrics = resources.displayMetrics
        val popupWidth = min(dp(340), (metrics.widthPixels * 0.88f).toInt())
        val popupHeightEstimate = dp(150)
        val lensX = lensParams?.x ?: dp(8)
        val lensY = lensParams?.y ?: dp(8)
        val lensHeight = lensView?.height ?: 0
        val belowY = lensY + lensHeight + dp(8)
        val aboveY = lensY - popupHeightEstimate - dp(8)
        val targetY = if (belowY + popupHeightEstimate <= metrics.heightPixels) belowY else aboveY

        val params = WindowManager.LayoutParams(
            popupWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lensX.coerceIn(dp(6), max(dp(6), metrics.widthPixels - popupWidth - dp(6)))
            y = targetY.coerceIn(dp(6), max(dp(6), metrics.heightPixels - popupHeightEstimate))
        }

        try {
            windowManager.addView(panel, params)
            barcodePopupView = panel
        } catch (_: Throwable) {
            Toast.makeText(this, getString(R.string.code_detected_value, code.value), Toast.LENGTH_LONG).show()
        }
    }

    private fun handleTextLongPress(localX: Float, localY: Float) {
        if (copyInProgress || !magnifierRunning) return

        val source = extractLensSourceBitmap()
        if (source == null) {
            Toast.makeText(this, R.string.wait_lens_image, Toast.LENGTH_SHORT).show()
            return
        }

        val imageView = lensImage ?: run {
            if (!source.isRecycled) source.recycle()
            return
        }

        copyInProgress = true

        val boost = currentScale.coerceAtLeast(2f)
        val rawWidth = (source.width * boost).toInt().coerceAtLeast(1)
        val rawHeight = (source.height * boost).toInt().coerceAtLeast(1)
        val fit = min(2000f / rawWidth, 2000f / rawHeight).coerceAtMost(1f)
        val ocrWidth = (rawWidth * fit).toInt().coerceAtLeast(1)
        val ocrHeight = (rawHeight * fit).toInt().coerceAtLeast(1)
        val ocrBitmap = Bitmap.createScaledBitmap(source, ocrWidth, ocrHeight, true)

        val targetX = (localX / imageView.width.coerceAtLeast(1)) * ocrBitmap.width
        val targetY = (localY / imageView.height.coerceAtLeast(1)) * ocrBitmap.height

        recognizer.process(InputImage.fromBitmap(ocrBitmap, 0))
            .addOnSuccessListener { result ->
                copyInProgress = false

                val lines = result.textBlocks.flatMap { it.lines }
                    .filter { it.text.isNotBlank() && it.boundingBox != null }

                val selected = lines.minByOrNull { line ->
                    distanceToRectSquared(targetX, targetY, line.boundingBox!!)
                }?.text?.trim().orEmpty()

                if (!ocrBitmap.isRecycled) ocrBitmap.recycle()
                if (!source.isRecycled) source.recycle()

                if (selected.isNotEmpty()) {
                    showCopyPopup(selected, localX, localY)
                } else {
                    showAccessibleTextAtLongPress(localX, localY)
                }
            }
            .addOnFailureListener {
                copyInProgress = false
                if (!ocrBitmap.isRecycled) ocrBitmap.recycle()
                if (!source.isRecycled) source.recycle()
                showAccessibleTextAtLongPress(localX, localY)
            }
    }

    private fun extractLensSourceBitmap(): Bitmap? {
        val bitmap = lastWindowBitmap ?: return null
        val bounds = lastWindowBounds
        val image = lensImage ?: return null

        if (
            bitmap.isRecycled ||
            bounds.width() <= 0 ||
            bounds.height() <= 0 ||
            image.width <= 0 ||
            image.height <= 0
        ) return null

        val bitmapPerScreenX = bitmap.width.toFloat() / bounds.width()
        val bitmapPerScreenY = bitmap.height.toFloat() / bounds.height()
        val centerBitmapX = (sourceCenterScreenX - bounds.left) * bitmapPerScreenX
        val centerBitmapY = (sourceCenterScreenY - bounds.top) * bitmapPerScreenY

        val cropWidth = ((image.width / currentScale) * bitmapPerScreenX)
            .roundToInt().coerceIn(1, bitmap.width)
        val cropHeight = ((image.height / currentScale) * bitmapPerScreenY)
            .roundToInt().coerceIn(1, bitmap.height)
        val left = (centerBitmapX - cropWidth / 2f)
            .roundToInt().coerceIn(0, bitmap.width - cropWidth)
        val top = (centerBitmapY - cropHeight / 2f)
            .roundToInt().coerceIn(0, bitmap.height - cropHeight)

        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }

    private fun distanceToRectSquared(x: Float, y: Float, rect: Rect): Float {
        val dx = when {
            x < rect.left -> rect.left - x
            x > rect.right -> x - rect.right
            else -> 0f
        }
        val dy = when {
            y < rect.top -> rect.top - y
            y > rect.bottom -> y - rect.bottom
            else -> 0f
        }
        return dx * dx + dy * dy
    }

    private fun showAccessibleTextAtLongPress(localX: Float, localY: Float) {
        val point = sourceScreenPoint(localX, localY)
        val text = findAccessibleTextAt(point.first, point.second)
        if (text.isNotEmpty()) {
            showCopyPopup(text, localX, localY)
        } else {
            Toast.makeText(this, R.string.no_text_at_point, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sourceScreenPoint(localX: Float, localY: Float): Pair<Float, Float> {
        val image = lensImage ?: return 0f to 0f
        val bitmap = lastWindowBitmap ?: return 0f to 0f
        val bounds = lastWindowBounds
        if (bitmap.isRecycled || bounds.width() <= 0 || bounds.height() <= 0) return 0f to 0f

        // Convert the exact displayed pixel back through the actual ImageView matrix.
        // This is more accurate than reconstructing the coordinate from zoom/center,
        // especially for WebViews and when the captured window has non-1:1 bitmap scaling.
        val inverse = Matrix()
        if (!image.imageMatrix.invert(inverse)) return 0f to 0f
        val point = floatArrayOf(localX, localY)
        inverse.mapPoints(point)

        val bitmapPerScreenX = bitmap.width.toFloat() / bounds.width()
        val bitmapPerScreenY = bitmap.height.toFloat() / bounds.height()
        val screenX = bounds.left + point[0] / bitmapPerScreenX
        val screenY = bounds.top + point[1] / bitmapPerScreenY

        return screenX.coerceIn(bounds.left.toFloat(), bounds.right.toFloat()) to
            screenY.coerceIn(bounds.top.toFloat(), bounds.bottom.toFloat())
    }

    private fun findAccessibleTextAt(screenX: Float, screenY: Float): String {
        val root = rootInActiveWindow ?: return ""
        var bestText = ""
        var bestArea = Long.MAX_VALUE

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val rect = Rect().also(node::getBoundsInScreen)
            if (node.isVisibleToUser && rect.contains(screenX.toInt(), screenY.toInt())) {
                val candidate = node.text?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: node.contentDescription?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }

                if (candidate != null) {
                    val area = rect.width().toLong() * rect.height().toLong()
                    if (area < bestArea) {
                        bestArea = area
                        bestText = candidate
                    }
                }

                for (i in 0 until node.childCount) {
                    visit(node.getChild(i))
                }
            }
        }

        visit(root)
        return bestText
    }

    private fun showCopyPopup(text: String, localX: Float, localY: Float) {
        copyPopupView?.let { removeOverlay(it) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(248, 20, 24, 31))
                setStroke(dp(2), CYAN)
                cornerRadius = dp(12).toFloat()
            }
        }

        panel.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 3
            setPadding(dp(4), 0, dp(4), dp(6))
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(controlButton(getString(R.string.copy)) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.screen_text), text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            copyPopupView?.let { removeOverlay(it) }
            copyPopupView = null
        })
        actions.addView(controlButton(getString(R.string.cancel)) {
            copyPopupView?.let { removeOverlay(it) }
            copyPopupView = null
        })
        panel.addView(actions)

        val metrics = resources.displayMetrics
        val popupWidth = min(dp(300), (metrics.widthPixels * 0.82f).toInt())
        val popupHeightEstimate = dp(130)
        val params = WindowManager.LayoutParams(
            popupWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val lensX = lensParams?.x ?: 0
            val lensY = lensParams?.y ?: 0
            val targetX = lensX + localX.toInt() - popupWidth / 2
            val targetY = lensY + localY.toInt() + dp(20)
            x = targetX.coerceIn(
                dp(6),
                max(dp(6), metrics.widthPixels - popupWidth - dp(6))
            )
            y = targetY.coerceIn(
                dp(6),
                max(dp(6), metrics.heightPixels - popupHeightEstimate)
            )
        }

        try {
            windowManager.addView(panel, params)
            copyPopupView = panel
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.could_not_show_copy, Toast.LENGTH_SHORT).show()
        }
    }

    private fun controlButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(44)
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }

    private fun removeOverlay(view: View) {
        runCatching {
            if (view.isAttachedToWindow) windowManager.removeView(view)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
