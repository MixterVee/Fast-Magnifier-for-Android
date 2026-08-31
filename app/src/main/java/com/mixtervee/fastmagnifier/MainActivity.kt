package com.mixtervee.fastmagnifier

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private companion object {
        const val MAX_AREA_ENHANCE_PASSES = 3
    }

    private data class AreaUndoStep(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val pixels: IntArray
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var frozenTapDetector: GestureDetector
    private lateinit var ocrController: OcrController
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

    private var frozenTouchDownY = 0f
    private var frozenStartScale = 1f
    private var frozenScale = 1f
    private var frozenZoomGesture = false

    private val areaUndoHistory = mutableListOf<AreaUndoStep>()
    private var areaEnhancePasses = 0
    private var areaEnhanceInProgress = false
    private var ocrInProgress = false

    private var torchEnabled = false

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
        ocrController = OcrController(this) { message -> binding.statusText.text = message }

        setupFrozenTapDetector()
        setupFrozenImageGestures()

        binding.textMode.setOnClickListener { selectMode(Mode.TEXT) }
        binding.detailMode.setOnClickListener { selectMode(Mode.DETAIL) }
        binding.distanceMode.setOnClickListener { selectMode(Mode.DISTANCE) }
        binding.freezeButton.setOnClickListener {
            if (binding.frozenImage.visibility == View.VISIBLE) resumeLive() else freezeAndAutoEnhance()
        }
        binding.toggleButton.setOnClickListener { toggleOriginalEnhanced() }
        binding.undoButton.setOnClickListener { undoAreaEnhance() }
        binding.readTextButton.setOnClickListener { readTextFromFrozen() }
        binding.saveButton.setOnClickListener { saveCurrentPicture() }
        binding.lightButton.setOnClickListener { toggleTorch() }
        binding.exposureSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) setExposureCompensation(value.toInt())
        }
        binding.previewView.setOnTouchListener { _, event -> handleLiveTouch(event) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupFrozenTapDetector() {
        frozenTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleFrozenSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (binding.frozenImage.visibility == View.VISIBLE && frozenScale > 1.01f) {
                    enhanceVisibleArea()
                    return true
                }
                return false
            }
        })
    }

    private fun setupFrozenImageGestures() {
        binding.frozenImage.setOnTouchListener { _, event ->
            frozenTapDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    frozenTouchDownY = event.y
                    frozenStartScale = frozenScale
                    frozenZoomGesture = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - frozenTouchDownY
                    if (!frozenZoomGesture && kotlin.math.abs(dy) > touchSlop * 1.25f) {
                        frozenZoomGesture = true
                    }

                    if (frozenZoomGesture) {
                        val height = max(binding.frozenImage.height.toFloat(), 1f)
                        val verticalTravel = (frozenTouchDownY - event.y) / (height * 0.22f)
                        frozenScale = (frozenStartScale * exp(verticalTravel.toDouble()).toFloat())
                            .coerceIn(1f, 8f)
                        applyFrozenScale()
                        binding.statusText.text = if (frozenScale > 1.01f) {
                            "Frozen zoom ${formatZoom(frozenScale)}× • double-tap to enhance area"
                        } else {
                            "Frozen 1.0× • slide up to zoom"
                        }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (frozenZoomGesture) {
                        binding.statusText.text = if (frozenScale > 1.01f) {
                            "Frozen zoom ${formatZoom(frozenScale)}× • tap overview • double-tap enhance"
                        } else {
                            "Slide up/down to zoom • Save keeps full image"
                        }
                    }
                    frozenZoomGesture = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    frozenZoomGesture = false
                }
            }
            true
        }
    }

    private fun handleFrozenSingleTap() {
        if (binding.frozenImage.visibility != View.VISIBLE) return

        if (frozenScale > 1.01f) {
            val shown = binding.navigatorView.toggleVisibility()
            binding.statusText.text = if (shown) {
                "Overview shown • drag cyan box • double-tap image to enhance"
            } else {
                "Overview hidden • tap to show • double-tap to enhance"
            }
        } else {
            binding.navigatorView.hideImmediately()
            binding.statusText.text = "Slide up/down to zoom • Save keeps full image"
        }
    }

    private fun enhanceVisibleArea() {
        if (binding.frozenImage.visibility != View.VISIBLE || frozenScale <= 1.01f) return

        if (areaEnhanceInProgress) {
            binding.statusText.text = "Area enhancement is already running…"
            return
        }

        if (areaEnhancePasses >= MAX_AREA_ENHANCE_PASSES) {
            binding.statusText.text =
                "Maximum $MAX_AREA_ENHANCE_PASSES area enhancements reached • Undo to go back"
            return
        }

        val source = enhanced
        if (source == null) {
            binding.statusText.text = "Wait for the first enhancement to finish"
            return
        }

        val visible = binding.frozenImage.visibleBitmapRectNormalized()
        val left = (visible.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (visible.top * source.height).toInt().coerceIn(0, source.height - 1)
        val right = (visible.right * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = (visible.bottom * source.height).toInt().coerceIn(top + 1, source.height)
        val cropWidth = right - left
        val cropHeight = bottom - top

        if (cropWidth < 8 || cropHeight < 8) {
            binding.statusText.text = "Zoomed area is too small to enhance"
            return
        }

        val previousPixels = IntArray(cropWidth * cropHeight)
        source.getPixels(previousPixels, 0, cropWidth, left, top, cropWidth, cropHeight)
        val crop = Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
        val selectedMode = mode
        val requestId = ++enhanceRequestId
        areaEnhanceInProgress = true
        binding.navigatorView.hideImmediately()
        binding.frozenImage.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        binding.toggleButton.isEnabled = false
        binding.undoButton.isEnabled = false
        binding.readTextButton.isEnabled = false
        binding.saveButton.isEnabled = false
        binding.statusText.text =
            "Enhancing visible area… pass ${areaEnhancePasses + 1}/$MAX_AREA_ENHANCE_PASSES"
        val start = System.nanoTime()

        worker.execute {
            try {
                val focused = fastEnhance(crop, selectedMode, 1.45f)
                val out = source.copy(Bitmap.Config.ARGB_8888, true)
                val pixels = IntArray(cropWidth * cropHeight)
                focused.getPixels(pixels, 0, cropWidth, 0, 0, cropWidth, cropHeight)
                out.setPixels(pixels, 0, cropWidth, left, top, cropWidth, cropHeight)
                val ms = (System.nanoTime() - start) / 1_000_000

                runOnUiThread {
                    areaEnhanceInProgress = false
                    if (requestId != enhanceRequestId || binding.frozenImage.visibility != View.VISIBLE) {
                        return@runOnUiThread
                    }

                    areaUndoHistory.add(
                        AreaUndoStep(left, top, cropWidth, cropHeight, previousPixels)
                    )
                    areaEnhancePasses++
                    enhanced = out
                    showingEnhanced = true
                    binding.frozenImage.setImageBitmap(out)
                    binding.frozenImage.clampPan()
                    binding.toggleButton.isEnabled = true
                    binding.toggleButton.text = "Original"
                    binding.undoButton.isEnabled = true
                    binding.readTextButton.isEnabled = !ocrInProgress
                    binding.saveButton.isEnabled = true

                    binding.statusText.text = if (areaEnhancePasses >= MAX_AREA_ENHANCE_PASSES) {
                        "Area enhanced in ${ms} ms • $areaEnhancePasses/$MAX_AREA_ENHANCE_PASSES max reached • Undo available"
                    } else {
                        "Area enhanced in ${ms} ms • $areaEnhancePasses/$MAX_AREA_ENHANCE_PASSES • double-tap for another pass"
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    areaEnhanceInProgress = false
                    if (requestId != enhanceRequestId) return@runOnUiThread
                    binding.toggleButton.isEnabled = enhanced != null
                    binding.undoButton.isEnabled = areaUndoHistory.isNotEmpty()
                    binding.readTextButton.isEnabled = !ocrInProgress && binding.frozenImage.visibility == View.VISIBLE
                    binding.saveButton.isEnabled = true
                    binding.statusText.text = "Area enhance error: ${t.javaClass.simpleName}"
                }
            }
        }
    }

    private fun undoAreaEnhance() {
        if (areaEnhanceInProgress) {
            binding.statusText.text = "Wait for area enhancement to finish"
            return
        }

        if (areaUndoHistory.isEmpty()) {
            binding.undoButton.isEnabled = false
            binding.statusText.text = "Nothing to undo"
            return
        }

        val current = enhanced
        if (current == null) {
            resetAreaEnhanceHistory()
            binding.statusText.text = "Nothing to undo"
            return
        }

        val step = areaUndoHistory.removeAt(areaUndoHistory.lastIndex)
        val out = current.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(
            step.pixels,
            0,
            step.width,
            step.left,
            step.top,
            step.width,
            step.height
        )

        areaEnhancePasses = (areaEnhancePasses - 1).coerceAtLeast(0)
        enhanced = out
        showingEnhanced = true
        binding.frozenImage.setImageBitmap(out)
        binding.frozenImage.clampPan()
        binding.toggleButton.isEnabled = true
        binding.toggleButton.text = "Original"
        binding.undoButton.isEnabled = areaUndoHistory.isNotEmpty()
        binding.readTextButton.isEnabled = !ocrInProgress
        binding.saveButton.isEnabled = true
        binding.frozenImage.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        binding.statusText.text = if (areaEnhancePasses == 0) {
            "Undone • back to normal enhanced image"
        } else {
            "Undone • $areaEnhancePasses/$MAX_AREA_ENHANCE_PASSES area enhancements remain"
        }
    }

    private fun resetAreaEnhanceHistory() {
        areaUndoHistory.clear()
        areaEnhancePasses = 0
        areaEnhanceInProgress = false
        binding.undoButton.isEnabled = false
    }

    private fun applyFrozenScale() {
        binding.frozenImage.pivotX = binding.frozenImage.width / 2f
        binding.frozenImage.pivotY = binding.frozenImage.height / 2f
        binding.frozenImage.scaleX = frozenScale
        binding.frozenImage.scaleY = frozenScale
        binding.frozenImage.clampPan()

        if (frozenScale > 1.01f) {
            binding.navigatorView.showTemporarily()
        } else {
            binding.navigatorView.hideImmediately()
        }
    }

    private fun resetFrozenZoom() {
        frozenScale = 1f
        frozenStartScale = 1f
        frozenZoomGesture = false
        binding.frozenImage.scaleX = 1f
        binding.frozenImage.scaleY = 1f
        binding.frozenImage.translationX = 0f
        binding.frozenImage.translationY = 0f
        binding.navigatorView.hideImmediately()
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
            setupCameraControls()
            binding.statusText.text = liveHint()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupCameraControls() {
        val c = camera ?: return

        val hasLight = c.cameraInfo.hasFlashUnit()
        binding.lightButton.isEnabled = hasLight
        binding.lightButton.text = when {
            !hasLight -> "No Light"
            torchEnabled -> "Light On"
            else -> "Light"
        }
        if (hasLight && torchEnabled) {
            c.cameraControl.enableTorch(true)
        }

        val exposureState = c.cameraInfo.exposureState
        val range = exposureState.exposureCompensationRange
        if (exposureState.isExposureCompensationSupported && range.lower < range.upper) {
            binding.exposureSlider.isEnabled = binding.frozenImage.visibility != View.VISIBLE
            binding.exposureSlider.valueFrom = range.lower.toFloat()
            binding.exposureSlider.valueTo = range.upper.toFloat()
            binding.exposureSlider.stepSize = 1f
            val current = exposureState.exposureCompensationIndex.coerceIn(range.lower, range.upper)
            binding.exposureSlider.value = current.toFloat()
            updateExposureLabel(current)
        } else {
            binding.exposureSlider.isEnabled = false
            binding.exposureText.text = "EV Auto"
        }
    }

    private fun toggleTorch() {
        val c = camera ?: return
        if (!c.cameraInfo.hasFlashUnit()) {
            binding.lightButton.isEnabled = false
            binding.lightButton.text = "No Light"
            return
        }

        val target = !torchEnabled
        binding.lightButton.isEnabled = false
        val future = c.cameraControl.enableTorch(target)
        future.addListener({
            try {
                future.get()
                torchEnabled = target
                binding.lightButton.text = if (torchEnabled) "Light On" else "Light"
                binding.statusText.text = if (binding.frozenImage.visibility == View.VISIBLE) {
                    if (torchEnabled) "Light on • Frozen" else "Light off • Frozen"
                } else {
                    if (torchEnabled) "Light on • ${liveHint()}" else "Light off • ${liveHint()}"
                }
            } catch (_: Throwable) {
                binding.statusText.text = "Could not change camera light"
            } finally {
                binding.lightButton.isEnabled = true
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setExposureCompensation(requestedIndex: Int) {
        val c = camera ?: return
        val state = c.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return

        val range = state.exposureCompensationRange
        val index = requestedIndex.coerceIn(range.lower, range.upper)
        updateExposureLabel(index)
        c.cameraControl.setExposureCompensationIndex(index)
    }

    private fun updateExposureLabel(index: Int) {
        val step = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f
        val ev = index * step
        binding.exposureText.text = String.format(Locale.US, "EV %+.1f", ev)
    }

    private fun liveHint(): String = "Slide up/down to zoom • Tap focus • Hold to freeze"

    private fun handleLiveTouch(event: MotionEvent): Boolean {
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
                    val verticalTravel = (touchDownY - event.y) / (height * 0.22f)
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
            resetAreaEnhanceHistory()
            enhanced = null
            showingEnhanced = false
            binding.frozenImage.setImageBitmap(original)
            binding.frozenImage.clampPan()
            binding.toggleButton.isEnabled = false
            binding.readTextButton.isEnabled = false
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
        resetAreaEnhanceHistory()
        resetFrozenZoom()
        binding.frozenImage.setImageBitmap(original)
        binding.frozenImage.visibility = View.VISIBLE
        binding.navigatorView.hideImmediately()
        binding.previewView.visibility = View.GONE
        binding.focusRing.visibility = View.GONE
        binding.freezeButton.text = "Resume"
        binding.toggleButton.isEnabled = false
        binding.toggleButton.text = "Original"
        binding.frozenToolsBar.visibility = View.VISIBLE
        binding.undoButton.visibility = View.VISIBLE
        binding.undoButton.isEnabled = false
        binding.readTextButton.isEnabled = false
        binding.saveButton.isEnabled = true
        binding.exposureSlider.isEnabled = false
        binding.statusText.text = "Frozen • Enhancing…"
        enhanceFrozen()
    }

    private fun resumeLive() {
        enhanceRequestId++
        original = null
        enhanced = null
        showingEnhanced = false
        ocrInProgress = false
        resetAreaEnhanceHistory()
        resetFrozenZoom()
        binding.navigatorView.hideImmediately()
        binding.frozenImage.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        binding.focusRing.visibility = View.GONE
        binding.freezeButton.text = "Freeze + Enhance"
        binding.toggleButton.isEnabled = false
        binding.toggleButton.text = "Original"
        binding.frozenToolsBar.visibility = View.GONE
        binding.undoButton.visibility = View.GONE
        binding.undoButton.isEnabled = false
        binding.readTextButton.isEnabled = false
        binding.saveButton.isEnabled = false
        setupCameraControls()
        binding.statusText.text = liveHint()
    }

    private fun enhanceFrozen() {
        val src = original ?: return
        val selectedMode = mode
        val requestId = ++enhanceRequestId
        binding.toggleButton.isEnabled = false
        binding.readTextButton.isEnabled = false
        val start = System.nanoTime()

        worker.execute {
            try {
                val out = fastEnhance(src, selectedMode)
                val ms = (System.nanoTime() - start) / 1_000_000
                runOnUiThread {
                    if (requestId != enhanceRequestId || binding.frozenImage.visibility != View.VISIBLE) {
                        return@runOnUiThread
                    }
                    resetAreaEnhanceHistory()
                    enhanced = out
                    showingEnhanced = true
                    binding.frozenImage.setImageBitmap(out)
                    binding.frozenImage.clampPan()
                    binding.toggleButton.isEnabled = true
                    binding.toggleButton.text = "Original"
                    binding.readTextButton.isEnabled = !ocrInProgress
                    binding.saveButton.isEnabled = true
                    binding.statusText.text =
                        "Enhanced in ${ms} ms • Slide zoom • double-tap zoomed area to enhance"
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (requestId != enhanceRequestId) return@runOnUiThread
                    showingEnhanced = false
                    binding.frozenImage.setImageBitmap(original)
                    binding.frozenImage.clampPan()
                    binding.toggleButton.isEnabled = false
                    binding.readTextButton.isEnabled = !ocrInProgress
                    binding.saveButton.isEnabled = true
                    binding.statusText.text = "Enhance error: ${t.javaClass.simpleName}"
                }
            }
        }
    }

    private fun toggleOriginalEnhanced() {
        val e = enhanced ?: return
        showingEnhanced = !showingEnhanced
        binding.frozenImage.setImageBitmap(if (showingEnhanced) e else original)
        binding.frozenImage.clampPan()
        binding.toggleButton.text = if (showingEnhanced) "Original" else "Enhanced"
        binding.statusText.text = if (showingEnhanced) {
            "Enhanced • Slide zoom • double-tap area to enhance • Save available"
        } else {
            "Original • Slide zoom • tap Enhanced before area enhancement • Save available"
        }
    }

    private fun readTextFromFrozen() {
        if (ocrInProgress || binding.frozenImage.visibility != View.VISIBLE) return

        val source = if (showingEnhanced) enhanced ?: original else original
        if (source == null) {
            binding.statusText.text = "Nothing available to read"
            return
        }

        val zoomed = frozenScale > 1.01f
        val ocrBitmap = if (zoomed) cropVisibleBitmap(source) else source
        val sourceLabel = if (zoomed) "visible area" else "full image"

        ocrInProgress = true
        binding.navigatorView.hideImmediately()
        binding.readTextButton.isEnabled = false
        binding.statusText.text = "Reading text from $sourceLabel…"

        ocrController.recognize(ocrBitmap, sourceLabel) {
            ocrInProgress = false
            if (binding.frozenImage.visibility == View.VISIBLE) {
                binding.readTextButton.isEnabled = !areaEnhanceInProgress
            }
        }
    }

    private fun cropVisibleBitmap(source: Bitmap): Bitmap {
        val visible = binding.frozenImage.visibleBitmapRectNormalized()
        val left = (visible.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (visible.top * source.height).toInt().coerceIn(0, source.height - 1)
        val right = (visible.right * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = (visible.bottom * source.height).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun saveCurrentPicture() {
        val bitmap = if (showingEnhanced) enhanced ?: original else original
        if (bitmap == null) {
            binding.statusText.text = "Nothing to save"
            return
        }

        binding.saveButton.isEnabled = false
        binding.statusText.text = "Saving picture…"

        worker.execute {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val name = "FastMagnifier_$stamp.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Fast Magnifier")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create image")

                try {
                    resolver.openOutputStream(uri)?.use { stream ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                            error("Could not encode image")
                        }
                    } ?: error("Could not open image file")

                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    throw t
                }

                runOnUiThread {
                    binding.saveButton.isEnabled = true
                    binding.statusText.text = "Saved to Pictures/Fast Magnifier"
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    binding.saveButton.isEnabled = true
                    binding.statusText.text = "Save failed: ${t.javaClass.simpleName}"
                }
            }
        }
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

    private fun fastEnhance(src: Bitmap, mode: Mode, boost: Float = 1f): Bitmap {
        val width = src.width
        val height = src.height
        val count = width * height
        val input = IntArray(count)
        val adjusted = IntArray(count)
        val output = IntArray(count)
        src.getPixels(input, 0, width, 0, 0, width, height)

        val baseContrast = when (mode) {
            Mode.TEXT -> 1.34f
            Mode.DETAIL -> 1.16f
            Mode.DISTANCE -> 1.22f
        }
        val baseSaturation = when (mode) {
            Mode.TEXT -> 0.45f
            Mode.DETAIL -> 1.03f
            Mode.DISTANCE -> 0.95f
        }
        val baseBrightness = when (mode) {
            Mode.TEXT -> 7f
            Mode.DETAIL -> 2f
            Mode.DISTANCE -> 4f
        }
        val baseSharpenAmount = when (mode) {
            Mode.TEXT -> 0.55f
            Mode.DETAIL -> 0.38f
            Mode.DISTANCE -> 0.46f
        }

        val tonalBoost = boost.coerceIn(1f, 1.35f)
        val contrast = 1f + (baseContrast - 1f) * boost
        val saturation = 1f + (baseSaturation - 1f) * tonalBoost
        val brightness = baseBrightness * tonalBoost
        val sharpenAmount = baseSharpenAmount * boost

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

                val lapR = 4 * cr - ((l shr 16) and 0xff) - ((r shr 16) and 0xff) -
                    ((u shr 16) and 0xff) - ((d shr 16) and 0xff)
                val lapG = 4 * cg - ((l shr 8) and 0xff) - ((r shr 8) and 0xff) -
                    ((u shr 8) and 0xff) - ((d shr 8) and 0xff)
                val lapB = 4 * cb - (l and 0xff) - (r and 0xff) -
                    (u and 0xff) - (d and 0xff)

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
        if (::ocrController.isInitialized) ocrController.close()
        worker.shutdownNow()
        super.onDestroy()
    }
}
