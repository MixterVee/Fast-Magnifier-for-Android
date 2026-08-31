package com.mixtervee.fastmagnifier

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.mixtervee.fastmagnifier.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var scaleDetector: ScaleGestureDetector
    private var camera: Camera? = null
    private var original: Bitmap? = null
    private var enhanced: Bitmap? = null
    private var showingEnhanced = false
    private var mode = Mode.DETAIL
    private val worker = Executors.newSingleThreadExecutor()

    enum class Mode { TEXT, DETAIL, DISTANCE }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else binding.statusText.text = "Camera permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val c = camera ?: return false
                val state = c.cameraInfo.zoomState.value ?: return false
                val target = (state.zoomRatio * detector.scaleFactor).coerceIn(1f, state.maxZoomRatio)
                c.cameraControl.setZoomRatio(target)
                return true
            }
        })

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
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (binding.frozenImage.visibility == android.view.View.VISIBLE) return true

        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress && event.action == MotionEvent.ACTION_UP) {
            val point = binding.previewView.meteringPointFactory.createPoint(event.x, event.y)
            camera?.cameraControl?.startFocusAndMetering(
                FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            )
        }
        return true
    }

    private fun freezeOrResume() {
        if (binding.frozenImage.visibility == android.view.View.VISIBLE) {
            original = null
            enhanced = null
            showingEnhanced = false
            binding.frozenImage.visibility = android.view.View.GONE
            binding.previewView.visibility = android.view.View.VISIBLE
            binding.freezeButton.text = "Freeze"
            binding.enhanceButton.isEnabled = false
            binding.toggleButton.isEnabled = false
            binding.statusText.text = "Pinch to zoom • Tap to focus"
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
        binding.frozenImage.visibility = android.view.View.VISIBLE
        binding.previewView.visibility = android.view.View.GONE
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

        // Pass 1: brightness, contrast and saturation. This is intentionally
        // simple arithmetic so it stays fast and predictable on the phone.
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

        // Keep the outside edge unchanged; sharpen the interior with a light
        // Laplacian. Unlike the previous Canvas blend, this cannot create a
        // transparent/blank result.
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
