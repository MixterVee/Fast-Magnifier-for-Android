package com.mixtervee.fastmagnifier

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.mixtervee.fastmagnifier.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var camera: Camera? = null
    private var original: Bitmap? = null
    private var enhanced: Bitmap? = null
    private var showingEnhanced = false
    private var mode = Mode.DETAIL
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchStartZoom = 1f
    private var zoomGesture = false
    private var longPressTriggered = false
    private var touchSlop = 12f
    private var enhanceRequestId = 0

    enum class Mode { TEXT, DETAIL, DISTANCE }

    private val longPressRunnable = Runnable {
        if (!zoomGesture && binding.frozenImage.visibility != View.VISIBLE) {
            longPressTriggered = true
            binding.previewView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            freezeAndAutoEnhance()
        }
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else binding.statusText.text = "Camera permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        binding.textMode.setOnClickListener { selectMode(Mode.TEXT) }
        binding.detailMode.setOnClickListener { selectMode(Mode.DETAIL) }
        binding.distanceMode.setOnClickListener { selectMode(Mode.DISTANCE) }
        binding.freezeButton.setOnClickListener {
            if (binding.frozenImage.visibility == View.VISIBLE) resumeLive() else freezeAndAutoEnhance()
        }
        binding.toggleButton.setOnClickListener { toggleOriginalEnhanced() }
        binding.previewView.setOnTouchListener { _, event -> handleTouch(event) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            binding.statusText.text = liveHint()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun liveHint(): String = "Slide up/down to zoom • Tap focus • Hold to freeze"

    private fun handleTouch(event: MotionEvent): Boolean {
        if (binding.frozenImage.visibility == View.VISIBLE) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchStartZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                zoomGesture = false
                longPressTriggered = false
                binding.focusRing.animate().cancel()
                binding.focusRing.visibility = View.GONE
                binding.focusRing.alpha = 1f
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                if (!zoomGesture && hypot(dx.toDouble(), dy.toDouble()) > touchSlop * 1.25) {
                    zoomGesture = true
                    mainHandler.removeCallbacks(longPressRunnable)
                }

                if (zoomGesture) {
                    val c = camera ?: return true
                    val state = c.cameraInfo.zoomState.value ?: return true
                    val height = max(binding.previewView.height.toFloat(), 1f)
                    val verticalTravel = (touchDownY - event.y) / (height * 0.36f)
                    val target = (touchStartZoom * exp(verticalTravel.toDouble()).toFloat())
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    c.cameraControl.setZoomRatio(target)
                    binding.statusText.text = "Zoom ${formatZoom(target)}×"
                }
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val movement = hypot(dx.toDouble(), dy.toDouble())

                if (!longPressTriggered) {
                    if (!zoomGesture && movement <= touchSlop * 1.5) {
                        focusAt(event.x, event.y)
                    } else if (zoomGesture) {
                        val ratio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: touchStartZoom
                        binding.statusText.text = "Zoom ${formatZoom(ratio)}× • slide up/down"
                    }
                }
                zoomGesture = false
                longPressTriggered = false
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                zoomGesture = false
                longPressTriggered = false
            }
        }
        return true
    }

    private fun focusAt(x: Float, y: Float) {
        val c = camera ?: return
        showFocusRing(x, y)
        binding.statusText.text = "Focusing…"

        val point = binding.previewView.meteringPointFactory.createPoint(x, y, 0.15f)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()

        c.cameraControl.cancelFocusAndMetering()
        val future = c.cameraControl.startFocusAndMetering(action)
        future.addListener({
            if (binding.frozenImage.visibility != View.VISIBLE) {
                try {
                    val result = future.get()
                    binding.statusText.text = if (result.isFocusSuccessful) {
                        "Focus locked • Hold to freeze"
                    } else {
                        "Focus adjusted • Hold to freeze"
                    }
                } catch (_: Throwable) {
                    binding.statusText.text = liveHint()
                }
            }

            binding.focusRing.animate()
                .alpha(0f)
                .setStartDelay(500)
                .setDuration(300)
                .withEndAction {
                    binding.focusRing.visibility = View.GONE
                    binding.focusRing.alpha = 1f
                }
                .start()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showFocusRing(x: Float, y: Float) {
        binding.focusRing.animate().cancel()
        binding.focusRing.alpha = 1f
        binding.focusRing.scaleX = 0.72f
        binding.focusRing.scaleY = 0.72f
        binding.focusRing.visibility = View.VISIBLE

        binding.focusRing.post {
            binding.focusRing.x = x - binding.focusRing.width / 2f
            binding.focusRing.y = y - binding.focusRing.height / 2f
            binding.focusRing.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140)
                .start()
        }
    }

    private fun formatZoom(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun selectMode(newMode: Mode) {
        mode = newMode
        if (binding.frozenImage.visibility == View.VISIBLE && original != null) {
            enhanced = null
            showingEnhanced = false
            binding.frozenImage.setImageBitmap(original)
            binding.toggleButton.isEnabled = false
            binding.statusText.text = "${modeLabel(newMode)} mode • Enhancing…"
            enhanceFrozen()
        } else {
            binding.statusText.text = "${modeLabel(newMode)} mode • ${liveHint()}"
        }
    }

    private fun modeLabel(value: Mode): String = value.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun freezeAndAutoEnhance() {
        if (binding.frozenImage.visibility == View.VISIBLE) return
        mainHandler.removeCallbacks(longPressRunnable)

        val shot = binding.previewView.bitmap ?: run {
            binding.statusText.text = "Could not capture preview"
            return
        }

        original = scaleForSpeed(shot)
        enhanced = null
        showingEnhanced = false
        binding.frozenImage.setImageBitmap(original)
        binding.frozenImage.visibility = View.VISIBLE
        binding.previewView.visibility = View.GONE
        binding.focusRing.visibility = View.GONE
        binding.freezeButton.text = "Resume"
        binding.toggleButton.isEnabled = false
        binding.toggleButton.text = "Original"
        binding.statusText.text = "Frozen • Enhancing…"
        enhanceFrozen()
    }

    private fun resumeLive() {
        enhanceRequestId++
        original = null
        enhanced = null
        showingEnhanced = false
        binding.frozenImage.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        binding.focusRing.visibility = View.GONE
        binding.freezeButton.text = "Freeze + Enhance"
        binding.toggleButton.isEnabled = false
        binding.toggleButton.text = "Original"
        binding.statusText.text = liveHint()
    }

    private fun enhanceFrozen() {
        val src = original ?: return
        val selectedMode = mode
        val requestId = ++enhanceRequestId
        binding.toggleButton.isEnabled = false
        val start = System.nanoTime()

        worker.execute {
            try {
                val out = fastEnhance(src, selectedMode)
                val ms = (System.nanoTime() - start) / 1_000_000
                runOnUiThread {
                    if (requestId != enhanceRequestId || binding.frozenImage.visibility != View.VISIBLE) return@runOnUiThread
                    enhanced = out
                    showingEnhanced = true
                    binding.frozenImage.setImageBitmap(out)
                    binding.toggleButton.isEnabled = true
                    binding.toggleButton.text = "Original"
                    binding.statusText.text = "Enhanced in ${ms} ms • ${modeLabel(selectedMode)}"
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (requestId != enhanceRequestId) return@runOnUiThread
                    showingEnhanced = false
                    binding.frozenImage.setImageBitmap(original)
                    binding.toggleButton.isEnabled = false
                    binding.statusText.text = "Enhance error: ${t.javaClass.simpleName}"
                }
            }
        }
    }

    private fun toggleOriginalEnhanced() {
        val e = enhanced ?: return
        showingEnhanced = !showingEnhanced
        binding.frozenImage.setImageBitmap(if (showingEnhanced) e else original)
        binding.toggleButton.text = if (showingEnhanced) "Original" else "Enhanced"
        binding.statusText.text = if (showingEnhanced) "Enhanced • ${modeLabel(mode)}" else "Original"
    }

    private fun scaleForSpeed(src: Bitmap): Bitmap {
        val maxDim = max(src.width, src.height)
        if (maxDim <= 1920) return src.copy(Bitmap.Config.ARGB_8888, false)
        val scale = 1920f / maxDim
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt(),
            (src.height * scale).toInt(),
            true
        )
    }

    private fun fastEnhance(src: Bitmap, mode: Mode): Bitmap {
        val width = src.width
        val height = src.height
        val count = width * height
        val input = IntArray(count)
        val adjusted = IntArray(count)
        val output = IntArray(count)
        src.getPixels(input, 0, width, 0, 0, width, height)

        val contrast = when (mode) {
            Mode.TEXT -> 1.34f
            Mode.DETAIL -> 1.16f
            Mode.DISTANCE -> 1.22f
        }
        val saturation = when (mode) {
            Mode.TEXT -> 0.45f
            Mode.DETAIL -> 1.03f
            Mode.DISTANCE -> 0.95f
        }
        val brightness = when (mode) {
            Mode.TEXT -> 7f
            Mode.DETAIL -> 2f
            Mode.DISTANCE -> 4f
        }
        val sharpenAmount = when (mode) {
            Mode.TEXT -> 0.55f
            Mode.DETAIL -> 0.38f
            Mode.DISTANCE -> 0.46f
        }

        for (i in 0 until count) {
            val p = input[i]
            val r0 = (p shr 16) and 0xff
            val g0 = (p shr 8) and 0xff
            val b0 = p and 0xff
            val luma = (77 * r0 + 150 * g0 + 29 * b0) shr 8

            val rs = luma + ((r0 - luma) * saturation).toInt()
            val gs = luma + ((g0 - luma) * saturation).toInt()
            val bs = luma + ((b0 - luma) * saturation).toInt()

            val r = (((rs - 128) * contrast) + 128 + brightness).toInt().coerceIn(0, 255)
            val g = (((gs - 128) * contrast) + 128 + brightness).toInt().coerceIn(0, 255)
            val b = (((bs - 128) * contrast) + 128 + brightness).toInt().coerceIn(0, 255)
            adjusted[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }

        adjusted.copyInto(output)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val c = adjusted[i]
                val l = adjusted[i - 1]
                val r = adjusted[i + 1]
                val u = adjusted[i - width]
                val d = adjusted[i + width]

                val cr = (c shr 16) and 0xff
                val cg = (c shr 8) and 0xff
                val cb = c and 0xff

                val lapR = 4 * cr - ((l shr 16) and 0xff) - ((r shr 16) and 0xff) - ((u shr 16) and 0xff) - ((d shr 16) and 0xff)
                val lapG = 4 * cg - ((l shr 8) and 0xff) - ((r shr 8) and 0xff) - ((u shr 8) and 0xff) - ((d shr 8) and 0xff)
                val lapB = 4 * cb - (l and 0xff) - (r and 0xff) - (u and 0xff) - (d and 0xff)

                val nr = (cr + sharpenAmount * lapR).toInt().coerceIn(0, 255)
                val ng = (cg + sharpenAmount * lapG).toInt().coerceIn(0, 255)
                val nb = (cb + sharpenAmount * lapB).toInt().coerceIn(0, 255)
                output[i] = (0xff shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(longPressRunnable)
        worker.shutdownNow()
        super.onDestroy()
    }
}
