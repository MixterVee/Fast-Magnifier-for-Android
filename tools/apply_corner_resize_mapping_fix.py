from pathlib import Path

path = Path("app/src/main/java/com/mixtervee/fastmagnifier/ScreenMagnifierService.kt")
text = path.read_text(encoding="utf-8")

text = text.replace(
    "import android.widget.Button\n",
    "import android.widget.Button\nimport android.widget.FrameLayout\n"
)

text = text.replace(
    "    private var longPressTriggered = false\n",
    "    private var longPressTriggered = false\n"
    "    private var cornerResizing = false\n"
    "    private var cornerResizeStartRawX = 0f\n"
    "    private var cornerResizeStartRawY = 0f\n"
    "    private var cornerResizeStartX = 0\n"
    "    private var cornerResizeStartY = 0\n"
    "    private var cornerResizeStartWidth = 0\n"
    "    private var cornerResizeStartHeight = 0\n"
)

old_image_block = '''        val image = ImageView(this).apply {\n            scaleType = ImageView.ScaleType.MATRIX\n            setBackgroundColor(Color.BLACK)\n            contentDescription = "Screen magnifier lens. Tap to activate. Drag to move. Pinch with two fingers to resize. Long-press text to copy."\n            setOnTouchListener { _, event -> handleLensTouch(event) }\n        }\n        container.addView(\n            image,\n            LinearLayout.LayoutParams(\n                LinearLayout.LayoutParams.MATCH_PARENT,\n                imageHeight\n            )\n        )'''

new_image_block = '''        val imageFrame = FrameLayout(this)\n        val image = ImageView(this).apply {\n            scaleType = ImageView.ScaleType.MATRIX\n            setBackgroundColor(Color.BLACK)\n            contentDescription = "Screen magnifier lens. Tap to activate. Drag to move. Pinch or drag a cyan corner to resize. Long-press text to copy."\n            setOnTouchListener { _, event -> handleLensTouch(event) }\n        }\n        imageFrame.addView(\n            image,\n            FrameLayout.LayoutParams(\n                FrameLayout.LayoutParams.MATCH_PARENT,\n                FrameLayout.LayoutParams.MATCH_PARENT\n            )\n        )\n\n        fun addCornerHandle(symbol: String, gravity: Int, corner: Int) {\n            val handle = TextView(this).apply {\n                text = symbol\n                textSize = 30f\n                setTextColor(CYAN)\n                setShadowLayer(3f, 0f, 0f, Color.BLACK)\n                this.gravity = Gravity.CENTER\n                contentDescription = "Resize magnifier"\n                setOnTouchListener { _, event -> handleCornerResize(event, corner) }\n            }\n            imageFrame.addView(\n                handle,\n                FrameLayout.LayoutParams(dp(46), dp(46), gravity)\n            )\n        }\n\n        addCornerHandle("┌", Gravity.TOP or Gravity.START, 0)\n        addCornerHandle("┐", Gravity.TOP or Gravity.END, 1)\n        addCornerHandle("└", Gravity.BOTTOM or Gravity.START, 2)\n        addCornerHandle("┘", Gravity.BOTTOM or Gravity.END, 3)\n\n        container.addView(\n            imageFrame,\n            LinearLayout.LayoutParams(\n                LinearLayout.LayoutParams.MATCH_PARENT,\n                imageHeight\n            )\n        )'''

if old_image_block not in text:
    raise SystemExit("Could not find magnifier image block")
text = text.replace(old_image_block, new_image_block, 1)

# lensImage still points to the actual ImageView; make resize code size its parent frame instead.
text = text.replace(
    '''        lensImage?.layoutParams = lensImage?.layoutParams?.apply {\n            height = (newHeight - chromeHeight).coerceAtLeast(dp(80))\n        }''',
    '''        (lensImage?.parent as? View)?.layoutParams =\n            (lensImage?.parent as? View)?.layoutParams?.apply {\n                height = (newHeight - chromeHeight).coerceAtLeast(dp(80))\n            }'''
)

marker = '''    private fun pointerSpan(event: MotionEvent): Float {'''
corner_code = '''    private fun handleCornerResize(event: MotionEvent, corner: Int): Boolean {\n        val lens = lensView ?: return false\n        val params = lensParams ?: return false\n        val metrics = resources.displayMetrics\n\n        when (event.actionMasked) {\n            MotionEvent.ACTION_DOWN -> {\n                mainHandler.removeCallbacks(longPressRunnable)\n                cornerResizing = true\n                cornerResizeStartRawX = event.rawX\n                cornerResizeStartRawY = event.rawY\n                cornerResizeStartX = params.x\n                cornerResizeStartY = params.y\n                cornerResizeStartWidth = lens.width.coerceAtLeast(params.width)\n                cornerResizeStartHeight = lens.height.coerceAtLeast(params.height)\n                return true\n            }\n\n            MotionEvent.ACTION_MOVE -> {\n                if (!cornerResizing) return true\n\n                val dx = (event.rawX - cornerResizeStartRawX).toInt()\n                val dy = (event.rawY - cornerResizeStartRawY).toInt()\n                val startLeft = cornerResizeStartX\n                val startTop = cornerResizeStartY\n                val startRight = startLeft + cornerResizeStartWidth\n                val startBottom = startTop + cornerResizeStartHeight\n\n                val minWidth = (metrics.widthPixels * 0.42f).toInt().coerceAtLeast(dp(220))\n                val maxWidth = (metrics.widthPixels * 0.96f).toInt()\n                val chromeHeight = dp(58)\n                val minHeight = (metrics.heightPixels * 0.16f).toInt().coerceAtLeast(chromeHeight + dp(80))\n                val maxHeight = (metrics.heightPixels * 0.72f).toInt()\n\n                var left = startLeft\n                var top = startTop\n                var right = startRight\n                var bottom = startBottom\n\n                when (corner) {\n                    0 -> { left = startLeft + dx; top = startTop + dy }\n                    1 -> { right = startRight + dx; top = startTop + dy }\n                    2 -> { left = startLeft + dx; bottom = startBottom + dy }\n                    else -> { right = startRight + dx; bottom = startBottom + dy }\n                }\n\n                if (corner == 0 || corner == 2) {\n                    left = left.coerceIn(max(0, right - maxWidth), right - minWidth)\n                } else {\n                    right = right.coerceIn(left + minWidth, min(metrics.widthPixels, left + maxWidth))\n                }\n\n                if (corner == 0 || corner == 1) {\n                    top = top.coerceIn(max(0, bottom - maxHeight), bottom - minHeight)\n                } else {\n                    bottom = bottom.coerceIn(top + minHeight, min(metrics.heightPixels, top + maxHeight))\n                }\n\n                left = left.coerceAtLeast(0)\n                top = top.coerceAtLeast(0)\n                right = right.coerceAtMost(metrics.widthPixels)\n                bottom = bottom.coerceAtMost(metrics.heightPixels)\n\n                params.x = left\n                params.y = top\n                params.width = (right - left).coerceAtLeast(minWidth)\n                params.height = (bottom - top).coerceAtLeast(minHeight)\n\n                (lensImage?.parent as? View)?.layoutParams =\n                    (lensImage?.parent as? View)?.layoutParams?.apply {\n                        height = (params.height - chromeHeight).coerceAtLeast(dp(80))\n                    }\n\n                runCatching { windowManager.updateViewLayout(lens, params) }\n                lens.requestLayout()\n                lens.post { updateLensMatrix() }\n                return true\n            }\n\n            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {\n                cornerResizing = false\n                requestImmediateRefresh()\n                return true\n            }\n        }\n        return true\n    }\n\n'''
if marker not in text:
    raise SystemExit("Could not find pointerSpan insertion marker")
text = text.replace(marker, corner_code + marker, 1)

old_point = '''    private fun sourceScreenPoint(localX: Float, localY: Float): Pair<Float, Float> {\n        val image = lensImage ?: return 0f to 0f\n        return (\n            sourceCenterScreenX + (localX - image.width / 2f) / currentScale\n        ) to (\n            sourceCenterScreenY + (localY - image.height / 2f) / currentScale\n        )\n    }'''

new_point = '''    private fun sourceScreenPoint(localX: Float, localY: Float): Pair<Float, Float> {\n        val image = lensImage ?: return 0f to 0f\n        val bitmap = lastWindowBitmap ?: return 0f to 0f\n        val bounds = lastWindowBounds\n        if (bitmap.isRecycled || bounds.width() <= 0 || bounds.height() <= 0) return 0f to 0f\n\n        // Convert the exact displayed pixel back through the actual ImageView matrix.\n        // This is more accurate than reconstructing the coordinate from zoom/center,\n        // especially for WebViews and when the captured window has non-1:1 bitmap scaling.\n        val inverse = Matrix()\n        if (!image.imageMatrix.invert(inverse)) return 0f to 0f\n        val point = floatArrayOf(localX, localY)\n        inverse.mapPoints(point)\n\n        val bitmapPerScreenX = bitmap.width.toFloat() / bounds.width()\n        val bitmapPerScreenY = bitmap.height.toFloat() / bounds.height()\n        val screenX = bounds.left + point[0] / bitmapPerScreenX\n        val screenY = bounds.top + point[1] / bitmapPerScreenY\n\n        return screenX.coerceIn(bounds.left.toFloat(), bounds.right.toFloat()) to\n            screenY.coerceIn(bounds.top.toFloat(), bounds.bottom.toFloat())\n    }'''

if old_point not in text:
    raise SystemExit("Could not find sourceScreenPoint block")
text = text.replace(old_point, new_point, 1)

text = text.replace(
    '"Tap to activate • drag to move • pinch to resize • long-press text to copy"',
    '"Tap to activate • drag to move • pinch or drag corners to resize • long-press text to copy"'
)

path.write_text(text, encoding="utf-8")
print("Applied exact pixel tap mapping + visible corner resize handles")
