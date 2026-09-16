from pathlib import Path

# Camera Magnifier -----------------------------------------------------------
main_path = Path("app/src/main/java/com/mixtervee/fastmagnifier/MainActivity.kt")
text = main_path.read_text(encoding="utf-8")

# Controller/presenter fields.
anchor = "    private lateinit var settingsController: SettingsController\n"
addition = (
    anchor
    + "    private lateinit var barcodeScanner: BarcodeScannerController\n"
    + "    private lateinit var barcodePresenter: CameraCodePresenter\n"
    + "    private var barcodeLoopActive = false\n"
    + "    private var lastDetectedBarcodeValue = \"\"\n"
    + "    private var lastDetectedBarcodeAt = 0L\n"
)
if "private lateinit var barcodeScanner: BarcodeScannerController" not in text:
    if anchor not in text:
        raise SystemExit("Could not find MainActivity settingsController field")
    text = text.replace(anchor, addition, 1)

# Scanner loop runnable after the main handler.
anchor = "    private val mainHandler = Handler(Looper.getMainLooper())\n"
addition = (
    anchor
    + "    private val barcodeScanRunnable = Runnable { scanLiveBarcodeFrame() }\n"
)
if "barcodeScanRunnable" not in text:
    if anchor not in text:
        raise SystemExit("Could not find MainActivity mainHandler")
    text = text.replace(anchor, addition, 1)

# Initialize after view/settings setup.
anchor = "        binding.navigatorView.setManualShowDuration(appSettings.overviewDurationMs)\n\n"
addition = (
    "        binding.navigatorView.setManualShowDuration(appSettings.overviewDurationMs)\n"
    "        barcodeScanner = BarcodeScannerController()\n"
    "        barcodePresenter = CameraCodePresenter(this, binding.root, R.id.statusText)\n\n"
)
if "barcodePresenter = CameraCodePresenter" not in text:
    if anchor not in text:
        raise SystemExit("Could not find MainActivity navigator setup anchor")
    text = text.replace(anchor, addition, 1)

# Live scanning + presentation helpers before frozen tap setup.
marker = "    private fun setupFrozenTapDetector() {\n"
helpers = r'''    private fun scanLiveBarcodeFrame() {
        if (!barcodeLoopActive || !::barcodeScanner.isInitialized) return

        if (
            binding.previewView.visibility == View.VISIBLE &&
            binding.frozenImage.visibility != View.VISIBLE
        ) {
            val frame = binding.previewView.bitmap
            if (frame != null) {
                barcodeScanner.scan(frame) { code ->
                    if (code != null) runOnUiThread { presentDetectedCode(code) }
                }
                if (!frame.isRecycled) frame.recycle()
            }
        }

        if (barcodeLoopActive) {
            mainHandler.postDelayed(barcodeScanRunnable, 1100L)
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

'''
if "private fun scanLiveBarcodeFrame()" not in text:
    if marker not in text:
        raise SystemExit("Could not find MainActivity frozen tap setup marker")
    text = text.replace(marker, helpers + marker, 1)

# Scan the fast frozen frame immediately.
anchor = "        original = scaleForSpeed(shot)\n        enhanced = null\n"
replacement = "        original = scaleForSpeed(shot)\n        scanFrozenBarcode(original)\n        enhanced = null\n"
if "scanFrozenBarcode(original)" not in text:
    if anchor not in text:
        raise SystemExit("Could not find freeze original assignment")
    text = text.replace(anchor, replacement, 1)

# Scan the later hi-res source too; it can reveal a code the preview missed.
anchor = "                            original = preparedSource\n                            enhanced = qualityEnhanced\n"
replacement = (
    "                            original = preparedSource\n"
    "                            scanFrozenBarcode(preparedSource)\n"
    "                            enhanced = qualityEnhanced\n"
)
if "scanFrozenBarcode(preparedSource)" not in text:
    if anchor not in text:
        raise SystemExit("Could not find hi-res original assignment")
    text = text.replace(anchor, replacement, 1)

# Dismiss stale camera card when returning to live camera.
anchor = "    private fun resumeLive() {\n        freezeSessionId++\n"
replacement = (
    "    private fun resumeLive() {\n"
    "        if (::barcodePresenter.isInitialized) barcodePresenter.dismiss()\n"
    "        freezeSessionId++\n"
)
if "barcodePresenter.dismiss()" not in text:
    if anchor not in text:
        raise SystemExit("Could not find resumeLive anchor")
    text = text.replace(anchor, replacement, 1)

# Lifecycle controls keep live recognition off when the activity is backgrounded.
marker = "    override fun onDestroy() {\n"
lifecycle = r'''    override fun onResume() {
        super.onResume()
        barcodeLoopActive = true
        mainHandler.removeCallbacks(barcodeScanRunnable)
        mainHandler.postDelayed(barcodeScanRunnable, 700L)
    }

    override fun onPause() {
        barcodeLoopActive = false
        mainHandler.removeCallbacks(barcodeScanRunnable)
        super.onPause()
    }

'''
if "override fun onResume()" not in text:
    if marker not in text:
        raise SystemExit("Could not find MainActivity onDestroy marker")
    text = text.replace(marker, lifecycle + marker, 1)

# Clean up scanner/card.
anchor = "    override fun onDestroy() {\n        mainHandler.removeCallbacks(longPressRunnable)\n"
replacement = (
    "    override fun onDestroy() {\n"
    "        barcodeLoopActive = false\n"
    "        mainHandler.removeCallbacks(barcodeScanRunnable)\n"
    "        mainHandler.removeCallbacks(longPressRunnable)\n"
    "        if (::barcodePresenter.isInitialized) barcodePresenter.dismiss()\n"
    "        if (::barcodeScanner.isInitialized) barcodeScanner.close()\n"
)
if "if (::barcodeScanner.isInitialized) barcodeScanner.close()" not in text:
    if anchor not in text:
        raise SystemExit("Could not find MainActivity onDestroy body")
    text = text.replace(anchor, replacement, 1)

main_path.write_text(text, encoding="utf-8")

# Screen Magnifier -----------------------------------------------------------
screen_path = Path("app/src/main/java/com/mixtervee/fastmagnifier/ScreenMagnifierService.kt")
screen = screen_path.read_text(encoding="utf-8")

# Scanner and state.
anchor = "    private val recognizer by lazy {\n        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)\n    }\n"
addition = (
    anchor
    + "    private val barcodeScanner by lazy { BarcodeScannerController() }\n"
)
if "private val barcodeScanner by lazy" not in screen:
    if anchor not in screen:
        raise SystemExit("Could not find ScreenMagnifier recognizer field")
    screen = screen.replace(anchor, addition, 1)

anchor = "    private var copyPopupView: View? = null\n"
addition = (
    anchor
    + "    private var barcodePopupView: View? = null\n"
    + "    private var lastBarcodeScanAt = 0L\n"
    + "    private var lastScreenBarcodeValue = \"\"\n"
    + "    private var lastScreenBarcodeShownAt = 0L\n"
)
if "private var barcodePopupView" not in screen:
    if anchor not in screen:
        raise SystemExit("Could not find ScreenMagnifier copy popup field")
    screen = screen.replace(anchor, addition, 1)

# Remove popup during shutdown.
anchor = "            copyPopupView?.let { removeOverlay(it) }\n"
replacement = (
    "            copyPopupView?.let { removeOverlay(it) }\n"
    "            barcodePopupView?.let { removeOverlay(it) }\n"
)
if "barcodePopupView?.let { removeOverlay(it) }" not in screen:
    if anchor not in screen:
        raise SystemExit("Could not find ScreenMagnifier popup shutdown anchor")
    screen = screen.replace(anchor, replacement, 1)

anchor = "        copyPopupView = null\n\n        val old = lastWindowBitmap\n"
replacement = "        copyPopupView = null\n        barcodePopupView = null\n\n        val old = lastWindowBitmap\n"
if "        barcodePopupView = null\n\n        val old" not in screen:
    if anchor not in screen:
        raise SystemExit("Could not find ScreenMagnifier popup reset anchor")
    screen = screen.replace(anchor, replacement, 1)

# Close barcode scanner with the service.
anchor = "        recognizer.close()\n        instance = null\n"
replacement = "        recognizer.close()\n        barcodeScanner.close()\n        instance = null\n"
if "barcodeScanner.close()" not in screen:
    if anchor not in screen:
        raise SystemExit("Could not find ScreenMagnifier recognizer close anchor")
    screen = screen.replace(anchor, replacement, 1)

# Touching/dragging the lens dismisses a stale result card.
anchor = "                copyPopupView = null\n\n                dragStartRawX = event.rawX\n"
replacement = (
    "                copyPopupView = null\n"
    "                barcodePopupView?.let { removeOverlay(it) }\n"
    "                barcodePopupView = null\n\n"
    "                dragStartRawX = event.rawX\n"
)
if "barcodePopupView?.let { removeOverlay(it) }" not in screen.split("private fun handleLensTouch", 1)[-1]:
    if anchor not in screen:
        raise SystemExit("Could not find ScreenMagnifier ACTION_DOWN popup anchor")
    screen = screen.replace(anchor, replacement, 1)

# Trigger a throttled scan after every installed screenshot.
anchor = "        image.setImageBitmap(bitmap)\n        updateLensMatrix()\n\n        if (old != null"
replacement = (
    "        image.setImageBitmap(bitmap)\n"
    "        updateLensMatrix()\n"
    "        maybeScanBarcodeInLens()\n\n"
    "        if (old != null"
)
if "maybeScanBarcodeInLens()" not in screen:
    if anchor not in screen:
        raise SystemExit("Could not find ScreenMagnifier bitmap install anchor")
    screen = screen.replace(anchor, replacement, 1)

# Detection and result overlay before text long-press handling.
marker = "    private fun handleTextLongPress(localX: Float, localY: Float) {\n"
methods = r'''    private fun maybeScanBarcodeInLens() {
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
            actions.addView(controlButton("Open") {
                val uri = runCatching { android.net.Uri.parse(code.value) }.getOrNull()
                if (uri != null && uri.scheme?.lowercase() in setOf("http", "https")) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }.onFailure {
                        Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
                    }
                }
                barcodePopupView?.let { removeOverlay(it) }
                barcodePopupView = null
            })
        } else if (code.isProductBarcode) {
            actions.addView(controlButton("Search") {
                val uri = android.net.Uri.parse(
                    "https://www.google.com/search?q=${android.net.Uri.encode(code.value)}"
                )
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.onFailure {
                    Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
                }
                barcodePopupView?.let { removeOverlay(it) }
                barcodePopupView = null
            })
        }

        actions.addView(controlButton("Copy") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Scanned code", code.value))
            Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show()
        })
        actions.addView(controlButton("Dismiss") {
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
            Toast.makeText(this, "Code detected: ${code.value}", Toast.LENGTH_LONG).show()
        }
    }

'''
if "private fun maybeScanBarcodeInLens()" not in screen:
    if marker not in screen:
        raise SystemExit("Could not find ScreenMagnifier text long-press marker")
    screen = screen.replace(marker, methods + marker, 1)

screen_path.write_text(screen, encoding="utf-8")
print("Applied automatic QR/barcode recognition to Camera and Screen Magnifier")
