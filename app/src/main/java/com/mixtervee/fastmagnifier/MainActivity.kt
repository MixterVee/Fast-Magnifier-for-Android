package com.mixtervee.fastmagnifier

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
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

        binding.textMode.setOnClickListener { mode = Mode.TEXT; binding.statusText.text = "Text mode" }
        binding.detailMode.setOnClickListener { mode = Mode.DETAIL; binding.statusText.text = "Detail mode" }
        binding.distanceMode.setOnClickListener { mode = Mode.DISTANCE; binding.statusText.text = "Distance mode" }
        binding.freezeButton.setOnClickListener { freezeOrResume() }
        binding.enhanceButton.setOnClickListener { enhanceFrozen() }
        binding.toggleButton.setOnClickListener { toggleOriginalEnhanced() }
        binding.previewView.setOnTouchListener { _, event -> handleTouch(event) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = binding.previewView.surfaceProvider }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (binding.frozenImage.visibility == android.view.View.VISIBLE) return true
        if (event.action == MotionEvent.ACTION_DOWN) {
            val point = binding.previewView.meteringPointFactory.createPoint(event.x, event.y)
            camera?.cameraControl?.startFocusAndMetering(
                FocusMeteringAction.Builder(point).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
            )
            return true
        }
        return false
    }

    private fun freezeOrResume() {
        if (binding.frozenImage.visibility == android.view.View.VISIBLE) {
            original = null; enhanced = null; showingEnhanced = false
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
        binding.enhanceButton.isEnabled = false
        binding.statusText.text = "Enhancing…"
        val start = System.nanoTime()
        worker.execute {
            val out = fastEnhance(src, mode)
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
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    private fun fastEnhance(src: Bitmap, mode: Mode): Bitmap {
        val contrast = when (mode) { Mode.TEXT -> 1.42f; Mode.DETAIL -> 1.22f; Mode.DISTANCE -> 1.30f }
        val saturation = when (mode) { Mode.TEXT -> 0.55f; Mode.DETAIL -> 1.05f; Mode.DISTANCE -> 1.0f }
        val brightness = when (mode) { Mode.TEXT -> 8f; Mode.DETAIL -> 3f; Mode.DISTANCE -> 5f }

        val base = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply {
            setSaturation(saturation)
            val offset = 128f * (1f - contrast) + brightness
            postConcat(ColorMatrix(floatArrayOf(
                contrast,0f,0f,0f,offset,
                0f,contrast,0f,0f,offset,
                0f,0f,contrast,0f,offset,
                0f,0f,0f,1f,0f
            )))
        }
        Canvas(base).drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })

        val small = Bitmap.createScaledBitmap(base, max(1, base.width / 2), max(1, base.height / 2), true)
        val blur = Bitmap.createScaledBitmap(small, base.width, base.height, true)
        val amount = when (mode) { Mode.TEXT -> 1.35f; Mode.DETAIL -> 0.9f; Mode.DISTANCE -> 1.1f }
        val a = 1f + amount
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(base, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 255 })
        val matrix = ColorMatrix(floatArrayOf(
            -amount,0f,0f,0f,0f,
            0f,-amount,0f,0f,0f,
            0f,0f,-amount,0f,0f,
            0f,0f,0f,1f,0f
        ))
        canvas.drawBitmap(blur, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        val gain = ColorMatrix(floatArrayOf(
            a,0f,0f,0f,0f,
            0f,a,0f,0f,0f,
            0f,0f,a,0f,0f,
            0f,0f,0f,1f,0f
        ))
        val finalBmp = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
        Canvas(finalBmp).drawBitmap(out, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(gain) })
        return finalBmp
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
