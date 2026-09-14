from pathlib import Path

path = Path("app/src/main/java/com/mixtervee/fastmagnifier/ScreenMagnifierService.kt")
text = path.read_text(encoding="utf-8")

text = text.replace(
    "import android.accessibilityservice.AccessibilityService\n",
    "import android.accessibilityservice.AccessibilityService\nimport android.accessibilityservice.GestureDescription\n"
)
text = text.replace(
    "import android.graphics.Rect\n",
    "import android.graphics.Rect\nimport android.graphics.Path\n"
)

text = text.replace(
    '            "Drag to move • long-press text to copy",',
    '            "Tap to activate • drag to move • long-press text to copy",'
)
text = text.replace(
    '            contentDescription = "Screen magnifier lens. Drag to move. Long-press text to copy."',
    '            contentDescription = "Screen magnifier lens. Tap to activate. Drag to move. Long-press text to copy."'
)

old = '''            MotionEvent.ACTION_UP,\n            MotionEvent.ACTION_CANCEL -> {\n                mainHandler.removeCallbacks(longPressRunnable)\n                if (dragging) requestImmediateRefresh()\n                dragging = false\n                longPressTriggered = false\n                return true\n            }'''
new = '''            MotionEvent.ACTION_UP -> {\n                mainHandler.removeCallbacks(longPressRunnable)\n                val wasDragging = dragging\n                val wasLongPress = longPressTriggered\n                if (wasDragging) {\n                    requestImmediateRefresh()\n                } else if (!wasLongPress) {\n                    handleLensTap(event.x, event.y)\n                }\n                dragging = false\n                longPressTriggered = false\n                return true\n            }\n\n            MotionEvent.ACTION_CANCEL -> {\n                mainHandler.removeCallbacks(longPressRunnable)\n                if (dragging) requestImmediateRefresh()\n                dragging = false\n                longPressTriggered = false\n                return true\n            }'''
if old not in text:
    raise SystemExit("Could not find touch-up block")
text = text.replace(old, new, 1)

marker = '''    private fun changeScale(delta: Float) {'''
insert = '''    private fun handleLensTap(localX: Float, localY: Float) {\n        if (!magnifierRunning || copyInProgress) return\n        val (screenX, screenY) = sourceScreenPoint(localX, localY)\n\n        if (clickAccessibleNodeAt(screenX, screenY)) {\n            mainHandler.postDelayed({ requestImmediateRefresh() }, 180L)\n            return\n        }\n\n        dispatchMappedTap(screenX, screenY)\n    }\n\n    private fun clickAccessibleNodeAt(screenX: Float, screenY: Float): Boolean {\n        val root = rootInActiveWindow ?: return false\n        var bestNode: AccessibilityNodeInfo? = null\n        var bestArea = Long.MAX_VALUE\n\n        fun visit(node: AccessibilityNodeInfo?) {\n            if (node == null || !node.isVisibleToUser) return\n            val rect = Rect().also(node::getBoundsInScreen)\n            if (!rect.contains(screenX.toInt(), screenY.toInt())) return\n\n            val area = rect.width().toLong() * rect.height().toLong()\n            if (area < bestArea) {\n                bestArea = area\n                bestNode = node\n            }\n\n            for (i in 0 until node.childCount) {\n                visit(node.getChild(i))\n            }\n        }\n\n        visit(root)\n\n        var candidate = bestNode\n        while (candidate != null) {\n            if (candidate.isClickable &&\n                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)\n            ) {\n                return true\n            }\n            candidate = candidate.parent\n        }\n        return false\n    }\n\n    private fun dispatchMappedTap(screenX: Float, screenY: Float) {\n        val lens = lensView\n        val params = lensParams\n        val originalFlags = params?.flags\n\n        // Let the synthetic tap pass through the accessibility overlay instead of\n        // landing back on the magnifier itself when source and lens overlap.\n        if (lens != null && params != null && originalFlags != null) {\n            params.flags = originalFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE\n            runCatching { windowManager.updateViewLayout(lens, params) }\n        }\n\n        val path = Path().apply { moveTo(screenX, screenY) }\n        val gesture = GestureDescription.Builder()\n            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))\n            .build()\n\n        val restoreOverlay = {\n            if (lens != null && params != null && originalFlags != null && lens.isAttachedToWindow) {\n                params.flags = originalFlags\n                runCatching { windowManager.updateViewLayout(lens, params) }\n            }\n            mainHandler.postDelayed({ requestImmediateRefresh() }, 120L)\n        }\n\n        val dispatched = dispatchGesture(\n            gesture,\n            object : GestureResultCallback() {\n                override fun onCompleted(gestureDescription: GestureDescription?) {\n                    restoreOverlay()\n                }\n\n                override fun onCancelled(gestureDescription: GestureDescription?) {\n                    restoreOverlay()\n                }\n            },\n            mainHandler\n        )\n\n        if (!dispatched) restoreOverlay()\n    }\n\n'''
if marker not in text:
    raise SystemExit("Could not find insertion marker")
text = text.replace(marker, insert + marker, 1)

path.write_text(text, encoding="utf-8")
print("Applied screen magnifier tap-through patch")
