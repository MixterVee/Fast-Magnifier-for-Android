package com.mixtervee.fastmagnifier

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
    private lateinit var liveScaleDetector: ScaleGestureDetector
    private lateinit var frozenScaleDetector: ScaleGestureDetector
    private lateinit var ocrController: OcrController
    private lateinit var highResCaptureController: HighResCaptureController
    private lateinit var appSettings: AppSettings
    private lateinit var settingsController: SettingsController
    private lateinit var barcodeScanner: BarcodeScannerController
    private lateinit var barcodePresenter: CameraCodePresenter
    private var lastDetectedBarcodeValue = ""
    private var lastDetectedBarcodeAt = 0L
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
    private var freezeSessionId = 0

    private var frozenScale = 1f
    private var frozenZoomGesture = false
    private var frozenPanGesture = false
    private var frozenLastTouchX = 0f
    private var frozenLastTouchY = 0f
    private var livePinchStartSpan = 0f
    private var livePinchStartZoom = 1f
    private var frozenPinchStartSpan = 0f
    private var frozenPinchStartScale = 1f
    private var frozenMultiTouchSequence = false
    private var frozenNeedsPanRebase = false

    private val areaUndoHistory = mutableListOf<AreaUndoStep>()
    private var areaEnhancePasses = 0
    private var areaEnhanceInProgress = false
    private var ocrInProgress = false

    private var torchEnabled = false
    private var selfieAssistCameraId: String? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK
    private var cameraFlipAvailable = false

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

        appSettings = AppSettings(this)
        ocrController = OcrController(this) { message -> binding.statusText.text = message }
        ocrController.setSpeechRate(appSettings.speechRate)
        highResCaptureController = HighResCaptureController(ContextCompat.getMainExecutor(this))
        settingsController = SettingsController(
            activity = this,
            settings = appSettings,
            status = { message -> binding.statusText.text = message },
            onOverviewChanged = {
                binding.navigatorView.setManualShowDuration(appSettings.overviewDurationMs)
            },
            onSpeechRateChanged = {
                ocrController.setSpeechRate(appSettings.speechRate)
            }
        )
        binding.navigatorView.setManualShowDuration(appSettings.overviewDurationMs)
        barcodeScanner = BarcodeScannerController()
        barcodePresenter = CameraCodePresenter(this, binding.root, R.id.statusText)

        setupFrozenTapDetector()
        setupLiveScaleDetector()
        setupFrozenScaleDetector()
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
        binding.cameraFlipButton.setOnClickListener { flipCamera() }
        binding.settingsButton.setOnClickListener { settingsController.show() }
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

    private fun scanFrozenBarcode(bitmap: Bitmap?) {
        val source = bitmap ?: return
        if (!::barcodeScanner.isInitialized || source.isRecycled) return
        barcodeScanner.scan(source) { code ->
            if (code != null) runOnUiThread { presentDetectedCode(code) }
        }
    }

    private fun presentDetectedCode(code: BarcodeScannerController.DetectedCode) {
        if (!::barcodePresenter.isInitialized || isFinishing || isDestroyed) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (code.value == lastDetectedBarcodeValue && now - lastDetectedBarcodeAt < 8000L) return
        lastDetectedBarcodeValue = code.value
        lastDetectedBarcodeAt = now
        barcodePresenter.show(code)
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

    private fun physicalPointerSpan(event: MotionEvent, coordinateScale: Float = 1f): Float {
        if (event.pointerCount < 2) return 0f
        val dx = (event.getX(1) - event.getX(0)) * coordinateScale
        val dy = (event.getY(1) - event.getY(0)) * coordinateScale
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun responsivePinchRatio(currentSpan: Float, startSpan: Float): Float {
        if (currentSpan <= 0f || startSpan <= 0f) return 1f
        val rawRatio = (currentSpan / startSpan).coerceIn(0.12f, 8f)
        // A modest sensitivity boost: enough to feel immediate without becoming jumpy.
        return Math.pow(rawRatio.toDouble(), 1.35).toFloat()
    }

    private fun setupLiveScaleDetector() {
        liveScaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    zoomGesture = true
                    mainHandler.removeCallbacks(longPressRunnable)
                    return camera != null && binding.frozenImage.visibility != View.VISIBLE
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val c = camera ?: return false
                    val state = c.cameraInfo.zoomState.value ?: return false
                    val current = state.zoomRatio
                    val target = (current * detector.scaleFactor)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    c.cameraControl.setZoomRatio(target)
                    binding.statusText.text = "Zoom ${formatZoom(target)}×"
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    val ratio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: return
                    binding.statusText.text = "Zoom ${formatZoom(ratio)}×"
                }
            }
        )
    }

    private fun setupFrozenScaleDetector() {
        frozenScaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    frozenZoomGesture = true
                    frozenPanGesture = false
                    return binding.frozenImage.visibility == View.VISIBLE
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val previous = frozenScale
                    frozenScale = (frozenScale * detector.scaleFactor).coerceIn(1f, 8f)
                    if (kotlin.math.abs(frozenScale - previous) > 0.0005f) {
                        applyFrozenScale()
                        binding.statusText.text = "Frozen zoom ${formatZoom(frozenScale)}×"
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    binding.statusText.text = "Frozen zoom ${formatZoom(frozenScale)}×"
                    frozenZoomGesture = false
                }
            }
        )
    }

    private fun setupFrozenImageGestures() {
        binding.frozenImage.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    frozenMultiTouchSequence = false
                    frozenNeedsPanRebase = false
                    frozenLastTouchX = event.rawX
                    frozenLastTouchY = event.rawY
                    frozenPanGesture = false
                    frozenZoomGesture = false
                    frozenTapDetector.onTouchEvent(event)
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        frozenMultiTouchSequence = true
                        frozenZoomGesture = true
                        frozenPanGesture = false
                        frozenNeedsPanRebase = false
                        frozenPinchStartScale = frozenScale
                        frozenPinchStartSpan = physicalPointerSpan(event, frozenScale)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2 && frozenMultiTouchSequence) {
                        val span = physicalPointerSpan(event, frozenScale)
                        val ratio = responsivePinchRatio(span, frozenPinchStartSpan)
                        val target = (frozenPinchStartScale * ratio).coerceIn(1f, 8f)

                        if (kotlin.math.abs(target - frozenScale) > 0.002f) {
                            frozenScale = target
                            applyFrozenScale()
                            binding.statusText.text = "Frozen zoom ${formatZoom(frozenScale)}×"
                        }
                        return@setOnTouchListener true
                    }

                    if (event.pointerCount == 1) {
                        if (!frozenMultiTouchSequence) {
                            frozenTapDetector.onTouchEvent(event)
                        }

                        if (frozenNeedsPanRebase) {
                            frozenLastTouchX = event.rawX
                            frozenLastTouchY = event.rawY
                            frozenNeedsPanRebase = false
                            return@setOnTouchListener true
                        }

                        val dx = event.rawX - frozenLastTouchX
                        val dy = event.rawY - frozenLastTouchY
                        val movement = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                        if (!frozenPanGesture && movement > touchSlop * 0.35f) {
                            frozenPanGesture = true
                        }

                        if (frozenPanGesture && frozenScale > 1.01f && !frozenMultiTouchSequence) {
                            binding.frozenImage.panBy(dx, dy)
                            binding.navigatorView.showTemporarily()
                            binding.statusText.text = "Drag to move • pinch to zoom"
                        }

                        frozenLastTouchX = event.rawX
                        frozenLastTouchY = event.rawY
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    // One finger remains after a two-finger pinch. Re-baseline before
                    // allowing a pan so there is never a jump from one finger to the other.
                    if (event.pointerCount <= 2) {
                        frozenZoomGesture = false
                        frozenNeedsPanRebase = true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (!frozenMultiTouchSequence) {
                        frozenTapDetector.onTouchEvent(event)
                    }
                    if (frozenPanGesture) {
                        binding.navigatorView.showTemporarily()
                    }
                    if (frozenMultiTouchSequence) {
                        binding.statusText.text = "Frozen zoom ${formatZoom(frozenScale)}×"
                    }
                    frozenPanGesture = false
                    frozenZoomGesture = false
                    frozenMultiTouchSequence = false
                    frozenNeedsPanRebase = false
                    frozenPinchStartSpan = 0f
                }

                MotionEvent.ACTION_CANCEL -> {
                    frozenPanGesture = false
                    frozenZoomGesture = false
                    frozenMultiTouchSequence = false
                    frozenNeedsPanRebase = false
                    frozenPinchStartSpan = 0f
                }
            }
            true
        }
    }

    private fun handleFrozenSingleTap() {
        if (binding.frozenImage.visibility != View.VISIBLE) return

        if (frozenScale > 1.01f) {
            binding.navigatorView.showForManualTap()
            binding.statusText.text = "Overview shown"
        } else {
            binding.navigatorView.hideImmediately()
            binding.fullViewButton.performClick()
        }
    }

    private fun enhanceVisibleArea() {
        if (binding.frozenImage.visibility != View.VISIBLE || frozenScale <= 1.01f) return

        if (areaEnhanceInProgress) {
            binding.statusText.text = "Area enhancement is already running…"
            return
        }

        if (areaEnhancePasses >= MAX_AREA_ENHANCE_PASSES) {
            binding.statusText.text = "Maximum $MAX_AREA_ENHANCE_PASSES area enhancements reached"
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
                val focused = fastEnhance(crop, selectedMode, appSettings.areaEnhanceBoost)
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
                    binding.toggleButton.text = getString(R.string.original)
                    binding.undoButton.isEnabled = true
                    binding.readTextButton.isEnabled = !ocrInProgress
                    binding.saveButton.isEnabled = true

                    binding.statusText.text = if (areaEnhancePasses >= MAX_AREA_ENHANCE_PASSES) {
                        "Area enhanced in ${ms} ms • $areaEnhancePasses/$MAX_AREA_ENHANCE_PASSES max reached"
                    } else {
                        "Area enhanced in ${ms} ms • $areaEnhancePasses/$MAX_AREA_ENHANCE_PASSES"
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
        binding.toggleButton.text = getString(R.string.original)
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
        frozenZoomGesture = false
        frozenPanGesture = false
        binding.frozenImage.scaleX = 1f
        binding.frozenImage.scaleY = 1f
        binding.frozenImage.translationX = 0f
        binding.frozenImage.translationY = 0f
        binding.navigatorView.hideImmediately()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val hasBack = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }
                    .getOrDefault(false)
                val hasFront = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }
                    .getOrDefault(false)
                cameraFlipAvailable = hasBack && hasFront

                var selector = cameraSelectorForFacing(cameraFacing)
                if (!runCatching { provider.hasCamera(selector) }.getOrDefault(false)) {
                    cameraFacing = CameraSelector.LENS_FACING_BACK
                    selector = CameraSelector.DEFAULT_BACK_CAMERA
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }
                val stillCapture = highResCaptureController.createUseCase(
                    binding.previewView.display?.rotation ?: 0
                )

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    stillCapture
                )
                setupCameraControls()
                updateSelfieScreenBrightness()
                binding.statusText.text = if (isFrontCamera()) {
                    getString(R.string.selfie_camera_ready)
                } else {
                    getString(R.string.camera_ready)
                }
            } catch (_: Throwable) {
                restoreSystemScreenBrightness()
                binding.cameraFlipButton.isEnabled = false
                binding.selfieLightFrame.visibility = View.GONE
                binding.statusText.text = "Could not start camera"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun cameraSelectorForFacing(facing: Int): CameraSelector =
        if (facing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

    private fun isFrontCamera(): Boolean = cameraFacing == CameraSelector.LENS_FACING_FRONT

    private fun flipCamera() {
        if (binding.frozenImage.visibility == View.VISIBLE || !cameraFlipAvailable) return

        if (isFrontCamera()) {
            setSelfieAssistTorch(false, quiet = true)
        } else {
            camera?.cameraControl?.enableTorch(false)
        }
        torchEnabled = false
        cameraFacing = if (isFrontCamera()) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }

        binding.cameraFlipButton.isEnabled = false
        binding.selfieLightFrame.visibility = View.GONE
        binding.statusText.text = if (isFrontCamera()) {
            "Switching to selfie camera…"
        } else {
            "Switching to rear camera…"
        }
        startCamera()
    }

    private fun setupCameraControls() {
        val c = camera ?: return
        val frozen = binding.frozenImage.visibility == View.VISIBLE

        binding.cameraFlipButton.isEnabled = cameraFlipAvailable && !frozen
        binding.cameraFlipButton.contentDescription = getString(
            if (isFrontCamera()) R.string.switch_to_rear_camera else R.string.switch_to_selfie_camera
        )

        val hasLight = c.cameraInfo.hasFlashUnit()
        if (isFrontCamera()) {
            selfieAssistCameraId = findRearTorchCameraId()
            val assistAvailable = selfieAssistCameraId != null
            binding.lightButton.isEnabled = assistAvailable && !frozen
            binding.lightButton.contentDescription = if (torchEnabled) {
                getString(R.string.turn_off_selfie_assist)
            } else {
                getString(R.string.turn_on_selfie_assist)
            }
            binding.lightButton.text = when {
                !assistAvailable -> getString(R.string.no_assist)
                torchEnabled -> getString(R.string.assist_on)
                else -> getString(R.string.assist)
            }
        } else {
            selfieAssistCameraId = null
            binding.lightButton.isEnabled = hasLight && !frozen
            binding.lightButton.contentDescription = getString(if (torchEnabled) R.string.turn_off_light else R.string.turn_on_light)
            binding.lightButton.text = when {
                !hasLight -> getString(R.string.no_light)
                torchEnabled -> getString(R.string.light_on)
                else -> getString(R.string.light)
            }
            if (hasLight && torchEnabled) {
                c.cameraControl.enableTorch(true)
            }
        }
        updateSelfieFillLight()

        val exposureState = c.cameraInfo.exposureState
        val range = exposureState.exposureCompensationRange
        if (exposureState.isExposureCompensationSupported && range.lower < range.upper) {
            binding.exposureSlider.isEnabled = !frozen
            binding.exposureSlider.valueFrom = range.lower.toFloat()
            binding.exposureSlider.valueTo = range.upper.toFloat()
            binding.exposureSlider.stepSize = 1f
            val current = exposureState.exposureCompensationIndex.coerceIn(range.lower, range.upper)
            binding.exposureSlider.value = current.toFloat()
            updateExposureLabel(current)
        } else {
            binding.exposureSlider.isEnabled = false
            binding.exposureText.text = getString(R.string.ev_auto)
        }
    }

    private fun updateSelfieFillLight() {
        binding.selfieLightFrame.visibility = if (
            isFrontCamera() &&
            !torchEnabled &&
            binding.frozenImage.visibility != View.VISIBLE &&
            binding.previewView.visibility == View.VISIBLE
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun toggleTorch() {
        if (isFrontCamera()) {
            toggleSelfieLightAssist()
            return
        }

        val c = camera ?: return
        if (!c.cameraInfo.hasFlashUnit()) {
            binding.lightButton.isEnabled = false
            binding.lightButton.text = getString(R.string.no_light)
            updateSelfieFillLight()
            return
        }

        val target = !torchEnabled
        binding.lightButton.isEnabled = false
        val future = c.cameraControl.enableTorch(target)
        future.addListener({
            try {
                future.get()
                torchEnabled = target
                binding.lightButton.text = getString(if (torchEnabled) R.string.light_on else R.string.light)
                binding.statusText.text = if (torchEnabled) "Light on" else "Light off"
            } catch (_: Throwable) {
                binding.statusText.text = "Could not change camera light"
            } finally {
                binding.lightButton.isEnabled = true
                updateSelfieFillLight()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleSelfieLightAssist() {
        if (selfieAssistCameraId == null) {
            binding.lightButton.isEnabled = false
            binding.statusText.text = "Rear light assist is not available on this device"
            return
        }

        setSelfieAssistTorch(!torchEnabled)
    }

    private fun setSelfieAssistTorch(enabled: Boolean, quiet: Boolean = false) {
        val id = selfieAssistCameraId ?: findRearTorchCameraId() ?: run {
            torchEnabled = false
            if (!quiet) binding.statusText.text = "Rear light assist is not available on this device"
            return
        }

        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.setTorchMode(id, enabled)
            torchEnabled = enabled
            binding.lightButton.text = getString(if (enabled) R.string.assist_on else R.string.assist)
            binding.lightButton.contentDescription = if (enabled) {
                getString(R.string.turn_off_selfie_assist)
            } else {
                getString(R.string.turn_on_selfie_assist)
            }
            if (!quiet) {
                binding.statusText.text = if (enabled) {
                    "Selfie light assist on • using rear LED bounce"
                } else {
                    "Selfie light assist off"
                }
            }
        } catch (_: Throwable) {
            torchEnabled = false
            binding.lightButton.text = getString(R.string.assist)
            if (!quiet) {
                binding.statusText.text = "This phone cannot use the rear LED with the selfie camera"
            }
        } finally {
            updateSelfieFillLight()
        }
    }

    private fun findRearTorchCameraId(): String? {
        return try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.cameraIdList.firstOrNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun updateSelfieScreenBrightness() {
        if (!isFrontCamera()) {
            restoreSystemScreenBrightness()
            return
        }

        val systemBrightness = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        }.getOrDefault(0.5f)
        val boosted = (systemBrightness + 0.18f).coerceIn(0f, 1f)
        window.attributes = window.attributes.apply {
            screenBrightness = boosted
        }
    }

    private fun restoreSystemScreenBrightness() {
        window.attributes = window.attributes.apply {
            screenBrightness = -1f
        }
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

    private fun liveHint(): String = "Pinch to zoom • Tap focus • Hold to freeze"

    private fun handleLiveTouch(event: MotionEvent): Boolean {
        if (binding.frozenImage.visibility == View.VISIBLE) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchStartZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                livePinchStartSpan = 0f
                livePinchStartZoom = touchStartZoom
                zoomGesture = false
                longPressTriggered = false
                binding.focusRing.animate().cancel()
                binding.focusRing.visibility = View.GONE
                binding.focusRing.alpha = 1f
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    zoomGesture = true
                    mainHandler.removeCallbacks(longPressRunnable)
                    livePinchStartSpan = physicalPointerSpan(event)
                    livePinchStartZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: touchStartZoom
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && zoomGesture) {
                    mainHandler.removeCallbacks(longPressRunnable)
                    val c = camera ?: return true
                    val state = c.cameraInfo.zoomState.value ?: return true
                    val span = physicalPointerSpan(event)
                    val ratio = responsivePinchRatio(span, livePinchStartSpan)
                    val target = (livePinchStartZoom * ratio)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    c.cameraControl.setZoomRatio(target)
                    binding.statusText.text = "Zoom ${formatZoom(target)}×"
                } else if (event.pointerCount == 1) {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop * 1.25) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val movement = hypot(dx.toDouble(), dy.toDouble())

                if (!longPressTriggered && !zoomGesture && movement <= touchSlop * 1.5) {
                    focusAt(event.x, event.y)
                } else if (zoomGesture) {
                    val ratio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: livePinchStartZoom
                    binding.statusText.text = "Zoom ${formatZoom(ratio)}×"
                }
                zoomGesture = false
                longPressTriggered = false
                livePinchStartSpan = 0f
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                zoomGesture = false
                longPressTriggered = false
                livePinchStartSpan = 0f
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
                        "Focus locked"
                    } else {
                        "Focus adjusted"
                    }
                } catch (_: Throwable) {
                    binding.statusText.text = if (isFrontCamera()) getString(R.string.selfie_camera_ready) else getString(R.string.camera_ready)
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
            binding.statusText.text = "${modeLabel(newMode)} mode"
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

        val sessionId = ++freezeSessionId
        original = scaleForSpeed(shot)
        scanFrozenBarcode(original)
        enhanced = null
        showingEnhanced = false
        resetAreaEnhanceHistory()
        resetFrozenZoom()
        binding.frozenImage.setImageBitmap(original)
        binding.frozenImage.visibility = View.VISIBLE
        binding.navigatorView.hideImmediately()
        binding.previewView.visibility = View.GONE
        binding.selfieLightFrame.visibility = View.GONE
        binding.focusRing.visibility = View.GONE
        binding.cameraFlipButton.isEnabled = false
        binding.freezeButton.text = getString(R.string.resume)
        binding.toggleButton.isEnabled = false
        binding.toggleButton.text = getString(R.string.original)
        binding.frozenToolsBar.visibility = View.VISIBLE
        binding.undoButton.visibility = View.VISIBLE
        binding.undoButton.isEnabled = false
        binding.readTextButton.isEnabled = false
        binding.saveButton.isEnabled = true
        binding.exposureSlider.isEnabled = false
        binding.statusText.text = "Frozen • Enhancing…"
        enhanceFrozen()
        captureHighResolutionUpgrade(sessionId)
    }

    private fun captureHighResolutionUpgrade(sessionId: Int) {
        val selectedMode = mode
        val selectedFacing = cameraFacing
        highResCaptureController.capture(
            onReady = { qualitySource ->
                if (
                    sessionId != freezeSessionId ||
                    binding.frozenImage.visibility != View.VISIBLE ||
                    mode != selectedMode ||
                    cameraFacing != selectedFacing ||
                    areaEnhancePasses > 0 ||
                    areaEnhanceInProgress
                ) {
                    return@capture
                }

                worker.execute {
                    try {
                        val preparedSource = if (selectedFacing == CameraSelector.LENS_FACING_FRONT) {
                            mirrorHorizontal(qualitySource)
                        } else {
                            qualitySource
                        }
                        val qualityEnhanced = fastEnhance(preparedSource, selectedMode)
                        runOnUiThread {
                            if (
                                sessionId != freezeSessionId ||
                                binding.frozenImage.visibility != View.VISIBLE ||
                                mode != selectedMode ||
                                cameraFacing != selectedFacing ||
                                areaEnhancePasses > 0 ||
                                areaEnhanceInProgress
                            ) {
                                return@runOnUiThread
                            }

                            val displayEnhanced = showingEnhanced
                            original = preparedSource
                            scanFrozenBarcode(preparedSource)
                            enhanced = qualityEnhanced
                            resetAreaEnhanceHistory()
                            showingEnhanced = displayEnhanced
                            binding.frozenImage.setImageBitmap(
                                if (showingEnhanced) qualityEnhanced else preparedSource
                            )
                            binding.frozenImage.clampPan()
                            binding.toggleButton.isEnabled = true
                            binding.toggleButton.text = getString(if (showingEnhanced) R.string.original else R.string.enhanced)
                            binding.readTextButton.isEnabled = !ocrInProgress
                            binding.saveButton.isEnabled = true
                            if (!ocrInProgress) {
                                binding.statusText.text = "Hi-Res Picture Taken"
                            }
                        }
                    } catch (_: Throwable) {
                        // Keep the already-working preview-based frozen image if the upgrade fails.
                    }
                }
            },
            onError = {
                // The fast PreviewView capture remains fully usable if still capture is unavailable.
            }
        )
    }

    private fun resumeLive() {
        if (::barcodePresenter.isInitialized) barcodePresenter.dismiss()
        freezeSessionId++
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
        binding.freezeButton.text = getString(R.string.freeze_enhance)
        binding.toggleButton.isEnabled = false
        binding.toggleButton.text = getString(R.string.original)
        binding.frozenToolsBar.visibility = View.GONE
        binding.undoButton.visibility = View.GONE
        binding.undoButton.isEnabled = false
        binding.readTextButton.isEnabled = false
        binding.saveButton.isEnabled = false
        setupCameraControls()
        updateSelfieScreenBrightness()
        binding.statusText.text = if (isFrontCamera()) getString(R.string.selfie_camera_ready) else getString(R.string.camera_ready)
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
                    binding.toggleButton.text = getString(R.string.original)
                    binding.readTextButton.isEnabled = !ocrInProgress
                    binding.saveButton.isEnabled = true
                    binding.statusText.text = "Enhanced in ${ms} ms"
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
        binding.toggleButton.text = getString(if (showingEnhanced) R.string.original else R.string.enhanced)
        binding.statusText.text = if (showingEnhanced) "Enhanced" else "Original"
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

    private fun mirrorHorizontal(source: Bitmap): Bitmap {
        val matrix = Matrix().apply {
            setScale(-1f, 1f, source.width / 2f, source.height / 2f)
        }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
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
        if (::barcodePresenter.isInitialized) barcodePresenter.dismiss()
        if (::barcodeScanner.isInitialized) barcodeScanner.close()
        if (isFrontCamera() && torchEnabled) {
            setSelfieAssistTorch(false, quiet = true)
        }
        restoreSystemScreenBrightness()
        mainHandler.removeCallbacks(longPressRunnable)
        if (::ocrController.isInitialized) ocrController.close()
        if (::highResCaptureController.isInitialized) highResCaptureController.close()
        worker.shutdownNow()
        super.onDestroy()
    }
}