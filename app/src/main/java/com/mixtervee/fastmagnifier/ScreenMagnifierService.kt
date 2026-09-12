package com.mixtervee.fastmagnifier

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenMagnifierService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ScreenMagnifierService? = null
            private set

        private const val DEFAULT_SCALE = 2.0f
        private const val MIN_SCALE = 1.5f
        private const val MAX_SCALE = 8.0f
        private const val SCALE_STEP = 0.5f
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var controlView: View? = null
    private var resultView: View? = null
    private var currentScale = DEFAULT_SCALE
    private var magnifierRunning = false
    private var copyInProgress = false

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
        if (::windowManager.isInitialized) {
            controlView?.let { removeOverlay(it) }
            resultView?.let { removeOverlay(it) }
        }
        recognizer.close()
        instance = null
        super.onDestroy()
    }

    fun startScreenMagnifier() {
        if (!::windowManager.isInitialized) return

        resultView?.let { removeOverlay(it) }
        resultView = null
        currentScale = DEFAULT_SCALE

        val metrics = resources.displayMetrics
        val controller = magnificationController
        val scaled = controller.setScale(currentScale, false)
        val centered = controller.setCenter(
            metrics.widthPixels / 2f,
            metrics.heightPixels / 2f,
            false
        )

        if (!scaled && !centered) {
            Toast.makeText(
                this,
                "Android would not start screen magnification. Check the accessibility permission.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        magnifierRunning = true
        showControls()
        Toast.makeText(this, "Screen Magnifier on • two-finger drag to move", Toast.LENGTH_SHORT).show()
    }

    fun stopScreenMagnifier() {
        if (::windowManager.isInitialized) {
            controlView?.let { removeOverlay(it) }
            resultView?.let { removeOverlay(it) }
        }
        controlView = null
        resultView = null
        copyInProgress = false

        if (magnifierRunning) {
            runCatching { magnificationController.reset(false) }
        }
        magnifierRunning = false
    }

    private fun showControls() {
        if (!magnifierRunning || !::windowManager.isInitialized) return

        val existing = controlView
        if (existing != null) {
            existing.visibility = View.VISIBLE
            return
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedBackground(Color.argb(230, 18, 22, 29), 18f)
        }

        bar.addView(controlButton("−") { changeScale(-SCALE_STEP) })
        bar.addView(controlButton("Copy Text") { copyVisibleText() })
        bar.addView(controlButton("+") { changeScale(SCALE_STEP) })
        bar.addView(controlButton("Exit") { stopScreenMagnifier() })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(36)
        }

        runCatching {
            windowManager.addView(bar, params)
            controlView = bar
        }.onFailure {
            Toast.makeText(this, "Could not show Screen Magnifier controls", Toast.LENGTH_LONG).show()
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

    private fun changeScale(delta: Float) {
        if (!magnifierRunning) return
        val requested = (currentScale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
        if (requested == currentScale) return

        if (magnificationController.setScale(requested, true)) {
            currentScale = requested
            Toast.makeText(this, String.format("%.1f×", currentScale), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyVisibleText() {
        if (copyInProgress || !magnifierRunning) return
        copyInProgress = true
        controlView?.visibility = View.GONE
        Toast.makeText(this, "Reading visible text…", Toast.LENGTH_SHORT).show()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            copyInProgress = false
            showAccessibilityTextFallback("Screen capture OCR requires Android 11 or newer")
            return
        }

        // Give Android one frame to remove our floating controls from the screenshot.
        mainHandler.postDelayed({
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(this),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()

                        if (bitmap == null) {
                            copyInProgress = false
                            showAccessibilityTextFallback("Could not read the screen image")
                            return
                        }

                        recognizeScreenshot(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        copyInProgress = false
                        showAccessibilityTextFallback("Screen capture unavailable")
                    }
                }
            )
        }, 120L)
    }

    private fun recognizeScreenshot(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                copyInProgress = false
                val text = result.text.trim()
                if (text.isNotEmpty()) {
                    showTextResult(text, "OCR from visible screen")
                } else {
                    showAccessibilityTextFallback("OCR found no text")
                }
            }
            .addOnFailureListener {
                copyInProgress = false
                showAccessibilityTextFallback("OCR could not read this screen")
            }
    }

    private fun showAccessibilityTextFallback(reason: String) {
        val text = collectAccessibleText().trim()
        if (text.isNotEmpty()) {
            showTextResult(text, "App-provided text")
        } else {
            Toast.makeText(this, "$reason • no accessible text found", Toast.LENGTH_LONG).show()
            showControls()
        }
    }

    private fun collectAccessibleText(): String {
        val root = rootInActiveWindow ?: return ""
        val lines = LinkedHashSet<String>()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || lines.size >= 250) return
            if (node.isVisibleToUser) {
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(lines::add)
                node.contentDescription?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(lines::add)
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
                if (lines.size >= 250) break
            }
        }

        visit(root)
        return lines.joinToString("\n")
    }

    private fun showTextResult(text: String, source: String) {
        controlView?.visibility = View.GONE
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
            showControls()
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
            showControls()
        }
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
