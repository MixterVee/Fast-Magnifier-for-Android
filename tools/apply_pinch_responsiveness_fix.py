from pathlib import Path

path = Path("app/src/main/java/com/mixtervee/fastmagnifier/MainActivity.kt")
text = path.read_text(encoding="utf-8")

# Add stable pinch state after the camera pinch/pan patch has run.
needle = (
    "    private var frozenPanGesture = false\n"
    "    private var frozenLastTouchX = 0f\n"
    "    private var frozenLastTouchY = 0f\n"
)
replacement = needle + (
    "    private var livePinchStartSpan = 0f\n"
    "    private var livePinchStartZoom = 1f\n"
    "    private var frozenPinchStartSpan = 0f\n"
    "    private var frozenPinchStartScale = 1f\n"
    "    private var frozenMultiTouchSequence = false\n"
    "    private var frozenNeedsPanRebase = false\n"
)
if needle not in text:
    raise SystemExit("Could not find camera pinch state fields")
text = text.replace(needle, replacement, 1)

# Use finger spacing from the beginning of a pinch rather than accumulating
# ScaleGestureDetector factors. For the scaled frozen image, multiplying local
# pointer spacing by the current view scale reconstructs physical finger spacing.
marker = "    private fun setupLiveScaleDetector() {\n"
helpers = r'''    private fun physicalPointerSpan(event: MotionEvent, coordinateScale: Float = 1f): Float {
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

'''
if marker not in text:
    raise SystemExit("Could not find pinch helper insertion marker")
text = text.replace(marker, helpers + marker, 1)

# Replace frozen touch handling with start-referenced physical pinch tracking.
start = text.index("    private fun setupFrozenImageGestures() {\n")
end = text.index("\n    private fun handleFrozenSingleTap() {", start)
new_frozen = r'''    private fun setupFrozenImageGestures() {
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
'''
text = text[:start] + new_frozen + text[end:]

# Replace live camera pinch handling too. The target zoom is calculated from the
# pinch's starting zoom/span, so reversing finger direction always reverses zoom.
start = text.index("    private fun handleLiveTouch(event: MotionEvent): Boolean {\n")
end = text.index("\n    private fun focusAt(x: Float, y: Float) {", start)
new_live = r'''    private fun handleLiveTouch(event: MotionEvent): Boolean {
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
'''
text = text[:start] + new_live + text[end:]

path.write_text(text, encoding="utf-8")
print("Applied responsive reversible physical pinch tracking")
