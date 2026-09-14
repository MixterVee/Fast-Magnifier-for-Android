from pathlib import Path

main_path = Path("app/src/main/java/com/mixtervee/fastmagnifier/MainActivity.kt")
text = main_path.read_text(encoding="utf-8")

# ScaleGestureDetector import.
if "import android.view.ScaleGestureDetector\n" not in text:
    text = text.replace(
        "import android.view.MotionEvent\n",
        "import android.view.MotionEvent\nimport android.view.ScaleGestureDetector\n",
        1,
    )

# Add gesture detector fields and pan state.
text = text.replace(
    "    private lateinit var frozenTapDetector: GestureDetector\n",
    "    private lateinit var frozenTapDetector: GestureDetector\n"
    "    private lateinit var liveScaleDetector: ScaleGestureDetector\n"
    "    private lateinit var frozenScaleDetector: ScaleGestureDetector\n",
    1,
)

text = text.replace(
    "    private var frozenTouchDownY = 0f\n"
    "    private var frozenStartScale = 1f\n"
    "    private var frozenScale = 1f\n"
    "    private var frozenZoomGesture = false\n",
    "    private var frozenScale = 1f\n"
    "    private var frozenZoomGesture = false\n"
    "    private var frozenPanGesture = false\n"
    "    private var frozenLastTouchX = 0f\n"
    "    private var frozenLastTouchY = 0f\n",
    1,
)

# Initialize scale detectors before frozen touch setup.
text = text.replace(
    "        setupFrozenTapDetector()\n        setupFrozenImageGestures()\n",
    "        setupFrozenTapDetector()\n"
    "        setupLiveScaleDetector()\n"
    "        setupFrozenScaleDetector()\n"
    "        setupFrozenImageGestures()\n",
    1,
)

# Insert detector setup functions before setupFrozenImageGestures.
marker = "    private fun setupFrozenImageGestures() {\n"
insert = r'''    private fun setupLiveScaleDetector() {
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

'''
if marker not in text:
    raise SystemExit("Could not find setupFrozenImageGestures marker")
text = text.replace(marker, insert + marker, 1)

# Replace frozen slide-to-zoom listener with pinch + one-finger pan.
start = text.index("    private fun setupFrozenImageGestures() {\n")
end = text.index("\n    private fun handleFrozenSingleTap() {", start)
old_block = text[start:end]
new_block = r'''    private fun setupFrozenImageGestures() {
        binding.frozenImage.setOnTouchListener { _, event ->
            frozenTapDetector.onTouchEvent(event)
            frozenScaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    frozenLastTouchX = event.x
                    frozenLastTouchY = event.y
                    frozenPanGesture = false
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    frozenPanGesture = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!frozenScaleDetector.isInProgress && event.pointerCount == 1) {
                        val dx = event.x - frozenLastTouchX
                        val dy = event.y - frozenLastTouchY
                        val movement = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                        if (!frozenPanGesture && movement > touchSlop * 0.35f) {
                            frozenPanGesture = true
                        }

                        if (frozenPanGesture && frozenScale > 1.01f) {
                            binding.frozenImage.panBy(dx, dy)
                            binding.navigatorView.showTemporarily()
                            binding.statusText.text = "Drag to move • pinch to zoom"
                        }
                    }
                    frozenLastTouchX = event.x
                    frozenLastTouchY = event.y
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    if (remainingIndex < event.pointerCount) {
                        frozenLastTouchX = event.getX(remainingIndex)
                        frozenLastTouchY = event.getY(remainingIndex)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (frozenPanGesture) {
                        binding.navigatorView.showTemporarily()
                    }
                    frozenPanGesture = false
                    frozenZoomGesture = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    frozenPanGesture = false
                    frozenZoomGesture = false
                }
            }
            true
        }
    }
'''
text = text[:start] + new_block + text[end:]

# Remove stale frozenStartScale reset.
text = text.replace("        frozenStartScale = 1f\n", "")
text = text.replace(
    "        frozenZoomGesture = false\n"
    "        binding.frozenImage.scaleX = 1f\n",
    "        frozenZoomGesture = false\n"
    "        frozenPanGesture = false\n"
    "        binding.frozenImage.scaleX = 1f\n",
    1,
)

# Update live hint.
text = text.replace(
    '    private fun liveHint(): String = "Slide up/down to zoom • Tap focus • Hold to freeze"',
    '    private fun liveHint(): String = "Pinch to zoom • Tap focus • Hold to freeze"',
    1,
)

# Replace live slide-to-zoom touch handling with pinch zoom + tap/hold.
start = text.index("    private fun handleLiveTouch(event: MotionEvent): Boolean {\n")
end = text.index("\n    private fun focusAt(x: Float, y: Float) {", start)
old_live = text[start:end]
new_live = r'''    private fun handleLiveTouch(event: MotionEvent): Boolean {
        if (binding.frozenImage.visibility == View.VISIBLE) return true

        liveScaleDetector.onTouchEvent(event)

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

            MotionEvent.ACTION_POINTER_DOWN -> {
                zoomGesture = true
                mainHandler.removeCallbacks(longPressRunnable)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || liveScaleDetector.isInProgress) {
                    zoomGesture = true
                    mainHandler.removeCallbacks(longPressRunnable)
                } else {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop * 1.25) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val movement = hypot(dx.toDouble(), dy.toDouble())

                if (!longPressTriggered && !zoomGesture && movement <= touchSlop * 1.5) {
                    focusAt(event.x, event.y)
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
'''
text = text[:start] + new_live + text[end:]

main_path.write_text(text, encoding="utf-8")

# Add a clean one-finger pan API to PanZoomImageView so pan state and navigator stay in sync.
pan_path = Path("app/src/main/java/com/mixtervee/fastmagnifier/PanZoomImageView.kt")
pan = pan_path.read_text(encoding="utf-8")

marker = "    fun visibleBitmapRectNormalized(): RectF {\n"
pan_method = r'''    fun panBy(dx: Float, dy: Float) {
        if (width <= 0 || height <= 0 || scaleX <= 1.01f || scaleY <= 1.01f) return

        translationX = (translationX + dx).coerceIn(-maxPanX(), maxPanX())
        translationY = (translationY + dy).coerceIn(-maxPanY(), maxPanY())

        val visible = visibleBitmapRectNormalized()
        desiredCenterX = visible.centerX().coerceIn(0f, 1f)
        desiredCenterY = visible.centerY().coerceIn(0f, 1f)
        notifyNavigator()
    }

'''
if marker not in pan:
    raise SystemExit("Could not find PanZoomImageView insertion marker")
pan = pan.replace(marker, pan_method + marker, 1)
pan_path.write_text(pan, encoding="utf-8")

print("Applied pinch-to-zoom and one-finger frozen-image pan")
