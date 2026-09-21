from pathlib import Path

path = Path("app/src/main/java/com/mixtervee/fastmagnifier/ScreenMagnifierService.kt")
text = path.read_text(encoding="utf-8")

# AccessibilityService survives device rotation, so explicitly react to configuration
# changes and make sure both the full lens and minimized restore bubble remain reachable.
if "import android.content.res.Configuration\n" not in text:
    anchor = "import android.content.Intent\n"
    if anchor not in text:
        raise SystemExit("Could not find Configuration import anchor")
    text = text.replace(anchor, anchor + "import android.content.res.Configuration\n", 1)

old_runnables = '''    private val refreshRunnable = Runnable { refreshLensScreenshot() }
    private val longPressRunnable = Runnable {
'''
new_runnables = '''    private val refreshRunnable = Runnable { refreshLensScreenshot() }
    private val orientationClampRunnable = Runnable { clampOverlaysToCurrentDisplay() }
    private val longPressRunnable = Runnable {
'''
if old_runnables not in text:
    raise SystemExit("Could not find refresh runnable anchor")
text = text.replace(old_runnables, new_runnables, 1)

old_interrupt = '''    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
'''
new_interrupt = '''    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!magnifierRunning) return

        // Give WindowManager/resources a moment to publish the new display bounds,
        // then shrink/reposition any overlay that no longer fits.
        mainHandler.removeCallbacks(orientationClampRunnable)
        mainHandler.postDelayed(orientationClampRunnable, 120L)
    }

    override fun onUnbind(intent: Intent?): Boolean {
'''
if old_interrupt not in text:
    raise SystemExit("Could not find configuration callback anchor")
text = text.replace(old_interrupt, new_interrupt, 1)

old_stop_callbacks = '''        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(longPressRunnable)
'''
new_stop_callbacks = '''        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(orientationClampRunnable)
'''
if old_stop_callbacks not in text:
    raise SystemExit("Could not find stop callback cleanup")
text = text.replace(old_stop_callbacks, new_stop_callbacks, 1)

marker = '''    private fun returnToMainMenu() {'''
methods = '''    private fun clampOverlaysToCurrentDisplay() {
        if (!magnifierRunning || !::windowManager.isInitialized) return

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.coerceAtLeast(1)
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)

        // A transient copy popup is safer to dismiss on rotation than to leave it
        // positioned for the old orientation.
        copyPopupView?.let { removeOverlay(it) }
        copyPopupView = null

        val lens = lensView
        val params = lensParams
        if (lens != null && params != null) {
            val chromeHeight = dp(58)

            val minWidth = min(
                screenWidth,
                max((screenWidth * 0.42f).toInt(), dp(220))
            )
            val maxWidth = max(
                minWidth,
                (screenWidth * 0.96f).toInt().coerceAtMost(screenWidth)
            )

            val minHeight = min(
                screenHeight,
                max((screenHeight * 0.16f).toInt(), chromeHeight + dp(80))
            )
            val maxHeight = max(
                minHeight,
                (screenHeight * 0.72f).toInt().coerceAtMost(screenHeight)
            )

            val currentWidth = if (params.width > 0) params.width else lens.width
            val currentHeight = if (params.height > 0) params.height else lens.height

            params.width = currentWidth.coerceAtLeast(minWidth).coerceAtMost(maxWidth)
            params.height = currentHeight.coerceAtLeast(minHeight).coerceAtMost(maxHeight)

            val maxX = max(0, screenWidth - params.width)
            val maxY = max(0, screenHeight - params.height)
            params.x = params.x.coerceIn(0, maxX)
            params.y = params.y.coerceIn(0, maxY)

            (lensImage?.parent as? View)?.layoutParams =
                (lensImage?.parent as? View)?.layoutParams?.apply {
                    height = (params.height - chromeHeight).coerceAtLeast(dp(80))
                }

            // The full lens is detached while minimized. Update its saved params now
            // so restoring after a rotation is still safe, but only call WindowManager
            // when it is actually attached.
            if (lens.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(lens, params) }
            }
            lens.requestLayout()
            lens.post {
                updateLensMatrix()
                requestImmediateRefresh()
            }
        }

        val bubble = minimizedView
        val bubbleParams = minimizedParams
        if (bubble != null && bubbleParams != null) {
            val bubbleWidth = when {
                bubble.width > 0 -> bubble.width
                bubbleParams.width > 0 -> bubbleParams.width
                else -> dp(58)
            }
            val bubbleHeight = when {
                bubble.height > 0 -> bubble.height
                bubbleParams.height > 0 -> bubbleParams.height
                else -> dp(58)
            }

            bubbleParams.x = bubbleParams.x.coerceIn(0, max(0, screenWidth - bubbleWidth))
            bubbleParams.y = bubbleParams.y.coerceIn(0, max(0, screenHeight - bubbleHeight))
            miniLastX = bubbleParams.x
            miniLastY = bubbleParams.y

            if (bubble.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(bubble, bubbleParams) }
            }
        }
    }

'''
if marker not in text:
    raise SystemExit("Could not find navigation insertion anchor")
text = text.replace(marker, methods + marker, 1)

path.write_text(text, encoding="utf-8")
print("Added rotation-aware Screen Magnifier size/position clamping")
