package com.mixtervee.fastmagnifier

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
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

        // Accessibility screenshots are rate-limited by Android. The lens itself is moved
        // from the latest cached full-window image at display speed, so dragging no longer
        // waits for this refresh interval.
        private const val SCREENSHOT_REFRESH_MS = 360L
        private const val CYAN = 0xff00bcd4.toInt()
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var lensView: View? = null
    private var lensImage: ImageView? = null
    private var zoomLabel: TextView? = null
    private var lensParams: WindowManager.LayoutParams? = null
    private var copyPopupView: View? = null

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
    private var touchDownLocalX = 0f
    private var touchDownLocalY = 0f
    private var dragging = false
    private var longPressTriggered = false
    private var pendingLongPressX = 0f
    private var pendingLongPressY = 0f

    private val touchSlop: Float by lazy {
        ViewConfiguration.get(this).scaledTouchSlop.toFloat()
    }

    private val refreshRunnable = Runnable { refreshLensScreenshot() }
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

    override fun onUnbind(intent: Intent?): Boolean {
        stopScreenMagnifier()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopScreenMagnifier()
        recognizer.close()
        instance = null
        super.onDestroy()
    }

    fun startScreenMagnifier() {
        if (!::windowManager.isInitialized) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Toast.makeText(
                this,
                "The custom screen lens requires Android 14 or newer.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        stopScreenMagnifier()
        currentScale = DEFAULT_SCALE
        magnifierRunning = true
        showLens()
        startRefreshing()

        val hz = maxDisplayRefreshRate().roundToInt()
        Toast.makeText(
            this,
            "Drag to move • long-press text to copy • display up to ${hz} Hz",
            Toast.LENGTH_LONG
        ).show()
    }

    fun stopScreenMagnifier() {
        magnifierRunning = false
        refreshEnabled = false
        screenshotInFlight = false
        copyInProgress = false
        dragging = false
        longPressTriggered = false
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(longPressRunnable)

        if (::windowManager.isInitialized) {
            lensView?.let { removeOverlay(it) }
            copyPopupView?.let { removeOverlay(it) }
        }

        lensView = null
        lensImage = null
        zoomLabel = null
        lensParams = null
        copyPopupView = null

        val old = lastWindowBitmap
        lastWindowBitmap = null
        lastWindowBounds.setEmpty()
        if (old != null && !old.isRecycled) old.recycle()
    }

    private fun showLens() {
        val metrics = resources.displayMetrics
        val lensWidth = (metrics.widthPixels * 0.72f).toInt()
        val imageHeight = (metrics.heightPixels * 0.24f).toInt()
        val controlsHeight = dp(52)
        val totalHeight = imageHeight + controlsHeight + dp(6)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(245, 15, 18, 24))
                setStroke(dp(3), CYAN)
                cornerRadius = dp(16).toFloat()
            }
        }

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            contentDescription = "Screen magnifier lens. Drag to move. Long-press text to copy."
            setOnTouchListener { _, event -> handleLensTouch(event) }
        }
        container.addView(
            image,
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

        controls.addView(controlButton("−") { changeScale(-SCALE_STEP) })

        val scaleText = TextView(this).apply {
            text = formatScale()
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        controls.addView(
            scaleText,
            LinearLayout.LayoutParams(dp(64), controlsHeight)
        )

        controls.addView(controlButton("+") { changeScale(SCALE_STEP) })
        controls.addView(controlButton("Exit") { stopScreenMagnifier() })
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
            val bestModeId = maxDisplayModeId()
            if (bestModeId != 0) preferredDisplayModeId = bestModeId
        }

        runCatching {
            windowManager.addView(container, params)
            lensView = container
            lensImage = image
            zoomLabel = scaleText
            lensParams = params
        }.onFailure {
            magnifierRunning = false
            Toast.makeText(this, "Could not show the screen magnifier lens", Toast.LENGTH_LONG).show()
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

                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWindowX = params.x
                dragStartWindowY = params.y
                touchDownLocalX = event.x
                touchDownLocalY = event.y
                pendingLongPressX = event.x
                pendingLongPressY = event.y
                dragging = false
                longPressTriggered = false

                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(
                    longPressRunnable,
                    ViewConfiguration.getLongPressTimeout().toLong()
                )
                return true
            }

            MotionEvent.ACTION_MOVE -> {
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

                    // This uses the already-cached full-window screenshot. No screenshot call
                    // is required for each movement, so the lens can track the finger at the
                    // display/touch cadence instead of the Accessibility screenshot cadence.
                    updateLensMatrix()
                }
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (dragging) requestImmediateRefresh()
                dragging = false
                longPressTriggered = false
                return true
            }
        }
        return true
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

    private fun maxDisplayRefreshRate(): Float {
        val d = display ?: return 60f
        return d.supportedModes.maxOfOrNull { it.refreshRate } ?: d.refreshRate
    }

    private fun maxDisplayModeId(): Int {
        val d = display ?: return 0
        return d.supportedModes.maxByOrNull { it.refreshRate }?.modeId ?: 0
    }

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
        val image = lensImage ?: return
        val old = lastWindowBitmap
        lastWindowBitmap = bitmap
        lastWindowBounds = Rect(windowBounds)
        image.setImageBitmap(bitmap)
        updateLensMatrix()

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
            lens.width <= 0
        ) return

        val bitmapPerScreenX = bitmap.width.toFloat() / bounds.width()
        val bitmapPerScreenY = bitmap.height.toFloat() / bounds.height()

        val requestedScreenCenterX = params.x + lens.width / 2f
        val requestedScreenCenterY = params.y + image.height / 2f
        var centerBitmapX = (requestedScreenCenterX - bounds.left) * bitmapPerScreenX
        var centerBitmapY = (requestedScreenCenterY - bounds.top) * bitmapPerScreenY

        val halfSourceBitmapWidth = (image.width / currentScale) * bitmapPerScreenX / 2f
        val halfSourceBitmapHeight = (image.height / currentScale) * bitmapPerScreenY / 2f

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

    private fun handleTextLongPress(localX: Float, localY: Float) {
        if (copyInProgress || !magnifierRunning) return
        val source = extractLensSourceBitmap()
        if (source == null) {
            Toast.makeText(this, "Wait for the lens image to appear", Toast.LENGTH_SHORT).show()
            return
        }

        copyInProgress = true
        val imageView = lensImage ?: run {
            copyInProgress = false
            return
        }

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

                if (selected.isNotEmpty()) {
                    showCopyPopup(selected, localX, localY)
                } else {
                    showAccessibleTextAtLongPress(localX, localY)
                }
            }
            .addOnFailureListener {
                copyInProgress = false
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
            Toast.makeText(this, "No text found at that point", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sourceScreenPoint(localX: Float, localY: Float): Pair<Float, Float> {
        val image = lensImage ?: return 0f to 0f
        return (
            sourceCenterScreenX + (localX - image.width / 2f) / currentScale
        ) to (
            sourceCenterScreenY + (localY - image.height / 2f) / currentScale
        )
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

                for (i in 0 until node.childCount) visit(node.getChild(i))
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
        actions.addView(controlButton("Copy") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Screen text", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            copyPopupView?.let { removeOverlay(it) }
            copyPopupView = null
        })
        actions.addView(controlButton("Cancel") {
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
            x = targetX.coerceIn(dp(6), max(dp(6), metrics.widthPixels - popupWidth - dp(6)))
            y = targetY.coerceIn(dp(6), max(dp(6), metrics.heightPixels - popupHeightEstimate))
        }

        runCatching {
            windowManager.addView(panel, params)
            copyPopupView = panel
        }.onFailure {
            Toast.makeText(this, "Could not show Copy", Toast.LENGTH_SHORT).show()
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
