package com.mixtervee.fastmagnifier

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
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

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchStartZoom = 1f
    private var zoomGesture = false
    private var touchSlop = 12f

    enum class Mode { TEXT, DETAIL, DISTANCE }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else binding.statusText.text = "Camera permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        binding.textMode.setOnClickListener { mode = Mode.TEXT; binding.statusText.text = "Text mode" }
        binding.detailMode.setOnClickListener { mode = Mode.DETAIL; binding.statusText.text = "Detail mode" }
        binding.distanceMode.setOnClickListener { mode = Mode.DISTANCE; binding.statusText.text = "Distance mode" }
        binding.freezeButton.setOnClickListener { freezeOrResume() }
        binding.enhanceButton.setOnClickListener { enhanceFrozen() }
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
            binding.statusText.text = "Slide up/down to zoom • Tap to focus"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (binding.frozenImage.visibility == View.VISIBLE) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchStartZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                zoomGesture = false
                binding.focusRing.animate().cancel()
                binding.focusRing.visibility = View.GONE
                binding.focusRing.alpha = 1f
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                if (!zoomGesture && hypot(dx.toDouble(), dy.toDouble()) > touchSlop * 1.25) {
                    zoomGesture = true
                }

                if (zoomGesture) {
                    val c = camera ?: return true
                    val state = c.cameraInfo.zoomState.value ?: return true
                    val height = max(binding.previewView.height.toFloat(), 1f)
                    val verticalTravel = (touchDownY - event.y) / (height * 0.55f)
                    val target = (touchStartZoom * exp(verticalTravel.toDouble()).toFloat())
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    c.cameraControl.setZoomRatio(target)
                    binding.statusText.text = "Zoom ${formatZoom(target)}×"
                }
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val movement = hypot(dx.toDouble(), dy.toDouble())

                if (!zoomGesture && movement <= touchSlop * 1.5) {
                    focusAt(event.x, event.y)
                } else if (zoomGesture) {
                    val ratio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: touchStartZoom
                    binding.statusText.text = "Zoom ${formatZoom(ratio)}× • slide up/down"
                }
                zoomGesture = false
            }

            MotionEvent.ACTION_CANCEL -> {
                zoomGesture = false
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
            try {
                val result = future.get()
                binding.statusText.text = if (result.isFocusSuccessful) {
                    "Focus locked"
                } else {
                    "Focus adjusted"
                }
            } catch (_: Throwable) {
                binding.statusText.text = "Tap to focus"
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

    private fun freezeOrResume() {
        if (binding.frozenImage.visibility == View.VISIBLE) {
            original = null
            enhanced = null
            showingEnhanced = false
            binding.frozenImage.visibility = View.GONE
            binding.previewView.visibility = View.VISIBLE
            binding.focusRing.visibility = View.GONE
            binding.freezeButton.text = "Freeze"
            binding.enhanceButton.isEnabled = false
            binding.toggleButton.isEnabled = false
            binding.statusText.text = "Slide up/down to zoom • Tap to focus"
            return
        }

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
        binding.enhanceButton.isEnabled = true
        binding.toggleButton.isEnabled = false
        binding.statusText.text = "Frozen • tap Enhance"
    }

    private fun enhanceFrozen() {
        val src = original ?: return
        val selectedMode = mode
        binding.enhanceButton.isEnabled = false
        binding.statusText.text = "Enhancing…"
        val start = System.nanoTime()

        worker.execute {
            try {
                val out = fastEnhance(src, selectedMode)
                val ms = (System.nanoTime() - start) / 1_000_000
                enhanced = out
                showingEnhanced = true
                runOnUiThread {
                    binding.frozenImage.setImageBitmap(out)
                    binding.toggleButton.isEnabled = true
                    binding.toggleButton.text = "Original"
                    binding.enhanceButton.isEnabled = true
                    binding.statusText.text = "Enhanced in ${ms} ms"
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    showingEnhanced = false
                    binding.frozenImage.setImageBitmap(original)
                    binding.enhanceButton.isEnabled = true
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
        worker.shutdownNow()
        super.onDestroy()
    }
}
