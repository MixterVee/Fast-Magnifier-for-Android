package com.mixtervee.fastmagnifier

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max

class ScreenMagnifierService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ScreenMagnifierService? = null
            private set

        private const val DEFAULT_SCALE = 2.5f
        private const val MIN_SCALE = 1.5f
        private const val MAX_SCALE = 6.0f
        private const val SCALE_STEP = 0.5f
        private const val REFRESH_DELAY_MS = 360L
        private const val CYAN = 0xff00bcd4.toInt()
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var lensView: View? = null
    private var lensImage: ImageView? = null
    private var zoomLabel: TextView? = null
    private var lensParams: WindowManager.LayoutParams? = null
    private var resultView: View? = null

    private var currentScale = DEFAULT_SCALE
    private var magnifierRunning = false
    private var refreshEnabled = false
    private var screenshotInFlight = false
    private var copyInProgress = false
    private var lastLensBitmap: Bitmap? = null

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0

    private val refreshRunnable = Runnable { refreshLens() }

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
        Toast.makeText(
            this,
            "Drag the magnified area to move it • use −/+ to zoom",
            Toast.LENGTH_LONG
        ).show()
    }

    fun stopScreenMagnifier() {
        magnifierRunning = false
        refreshEnabled = false
        screenshotInFlight = false
        copyInProgress = false
        mainHandler.removeCallbacks(refreshRunnable)

        if (::windowManager.isInitialized) {
            lensView?.let { removeOverlay(it) }
            resultView?.let { removeOverlay(it) }
        }
        lensView = null
        lensImage = null
        zoomLabel = null
        lensParams = null
        resultView = null
        lastLensBitmap = null
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
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.BLACK)
            contentDescription = "Screen magnifier lens. Drag to move."
            setOnTouchListener { _, event -> handleLensDrag(event) }
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
            LinearLayout.LayoutParams(dp(58), controlsHeight)
        )

        controls.addView(controlButton("+") { changeScale(SCALE_STEP) })
        controls.addView(controlButton("Copy") { copyLensText() })
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

    private fun handleLensDrag(event: MotionEvent): Boolean {
        val params = lensParams ?: return false
        val view = lensView ?: return false
        val metrics = resources.displayMetrics

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWindowX = params.x
                dragStartWindowY = params.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - dragStartRawX).toInt()
                val dy = (event.rawY - dragStartRawY).toInt()
                val maxX = max(0, metrics.widthPixels - view.width)
                val maxY = max(0, metrics.heightPixels - view.height)
                params.x = (dragStartWindowX + dx).coerceIn(0, maxX)
                params.y = (dragStartWindowY + dy).coerceIn(0, maxY)
                runCatching { windowManager.updateViewLayout(view, params) }
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                requestImmediateRefresh()
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

    private fun scheduleNextRefresh(delayMs: Long = REFRESH_DELAY_MS) {
        if (!magnifierRunning || !refreshEnabled) return
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, delayMs)
    }

    private fun refreshLens() {
        if (
            !magnifierRunning ||
            !refreshEnabled ||
            screenshotInFlight ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            return
        }

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
                        renderLensFromWindow(screenBitmap, windowBounds)
                    }
                    scheduleNextRefresh()
                }

                override fun onFailure(errorCode: Int) {
                    screenshotInFlight = false
                    scheduleNextRefresh(if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) 450L else 650L)
                }
            }
        )
    }

    private fun renderLensFromWindow(bitmap: Bitmap, windowBounds: Rect) {
        val params = lensParams ?: return
        val image = lensImage ?: return
        val lens = lensView ?: return
        if (image.width <= 0 || image.height <= 0 || lens.width <= 0) return

        val centerScreenX = params.x + lens.width / 2f
        val centerScreenY = params.y + image.height / 2f

        val scaleX = bitmap.width.toFloat() / windowBounds.width().coerceAtLeast(1)
        val scaleY = bitmap.height.toFloat() / windowBounds.height().coerceAtLeast(1)
        val centerBitmapX = (centerScreenX - windowBounds.left) * scaleX
        val centerBitmapY = (centerScreenY - windowBounds.top) * scaleY

        val cropWidth = ((image.width / currentScale) * scaleX)
            .toInt()
            .coerceIn(1, bitmap.width)
        val cropHeight = ((image.height / currentScale) * scaleY)
            .toInt()
            .coerceIn(1, bitmap.height)

        val left = (centerBitmapX - cropWidth / 2f)
            .toInt()
            .coerceIn(0, bitmap.width - cropWidth)
        val top = (centerBitmapY - cropHeight / 2f)
            .toInt()
            .coerceIn(0, bitmap.height - cropHeight)

        val crop = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
        lastLensBitmap = crop
        image.setImageBitmap(crop)
    }

    private fun copyLensText() {
        if (copyInProgress || !magnifierRunning) return
        val source = lastLensBitmap
        if (source == null) {
            Toast.makeText(this, "Wait for the lens image to appear", Toast.LENGTH_SHORT).show()
            return
        }

        copyInProgress = true
        refreshEnabled = false
        mainHandler.removeCallbacks(refreshRunnable)
        Toast.makeText(this, "Reading text in magnifier…", Toast.LENGTH_SHORT).show()

        val boost = currentScale.coerceAtLeast(2f)
        val ocrBitmap = Bitmap.createScaledBitmap(
            source,
            (source.width * boost).toInt().coerceAtMost(2400),
            (source.height * boost).toInt().coerceAtMost(2400),
            true
        )
        val image = InputImage.fromBitmap(ocrBitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                copyInProgress = false
                val text = result.text.trim()
                if (text.isNotEmpty()) {
                    showTextResult(text, "Magnified area")
                } else {
                    showAccessibilityTextFallback("OCR found no text in the magnified area")
                }
            }
            .addOnFailureListener {
                copyInProgress = false
                showAccessibilityTextFallback("OCR could not read the magnified area")
            }
    }

    private fun showAccessibilityTextFallback(reason: String) {
        val text = collectAccessibleTextInLens().trim()
        if (text.isNotEmpty()) {
            showTextResult(text, "App-provided text in lens")
        } else {
            Toast.makeText(this, "$reason • no selectable text found", Toast.LENGTH_LONG).show()
            refreshEnabled = true
            startRefreshing()
        }
    }

    private fun collectAccessibleTextInLens(): String {
        val root = rootInActiveWindow ?: return ""
        val lines = LinkedHashSet<String>()
        val lensRect = lensScreenRect()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || lines.size >= 120) return
            val nodeRect = Rect().also(node::getBoundsInScreen)
            if (node.isVisibleToUser && Rect.intersects(nodeRect, lensRect)) {
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(lines::add)
                node.contentDescription?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(lines::add)
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
                if (lines.size >= 120) break
            }
        }

        visit(root)
        return lines.joinToString("\n")
    }

    private fun lensScreenRect(): Rect {
        val params = lensParams
        val image = lensImage
        if (params == null || image == null) return Rect()
        return Rect(
            params.x,
            params.y,
            params.x + image.width,
            params.y + image.height
        )
    }

    private fun showTextResult(text: String, source: String) {
        refreshEnabled = false
        mainHandler.removeCallbacks(refreshRunnable)
        lensView?.visibility = View.GONE
        resultView?.let { removeOverlay(it) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(Color.argb(245, 17, 21, 27), 16f)
        }

        panel.addView(TextView(this).apply {
            this.text = "Screen text • $source"
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(0, 0, 0, dp(8))
        })

        val recognizedText = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        panel.addView(
            ScrollView(this).apply { addView(recognizedText) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(controlButton("Copy All") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Screen text", text))
            Toast.makeText(this, "Screen text copied", Toast.LENGTH_SHORT).show()
        })
        actions.addView(controlButton("Close") {
            resultView?.let { removeOverlay(it) }
            resultView = null
            lensView?.visibility = View.VISIBLE
            refreshEnabled = true
            startRefreshing()
        })
        panel.addView(actions)

        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            (metrics.widthPixels * 0.90f).toInt(),
            (metrics.heightPixels * 0.62f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        runCatching {
            windowManager.addView(panel, params)
            resultView = panel
        }.onFailure {
            Toast.makeText(this, "Could not show recognized text", Toast.LENGTH_LONG).show()
            lensView?.visibility = View.VISIBLE
            refreshEnabled = true
            startRefreshing()
        }
    }

    private fun controlButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(44)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
        }

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun removeOverlay(view: View) {
        runCatching {
            if (view.isAttachedToWindow) windowManager.removeView(view)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
