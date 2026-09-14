from pathlib import Path

path = Path("app/src/main/java/com/mixtervee/fastmagnifier/ScreenMagnifierService.kt")
text = path.read_text(encoding="utf-8")

old_click = '''        var candidate = bestNode\n        while (candidate != null) {\n            if (candidate.isClickable &&\n                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)\n            ) {\n                return true\n            }\n            candidate = candidate.parent\n        }\n        return false'''

new_click = '''        var candidate = bestNode\n        while (candidate != null) {\n            val supportsClick = candidate.isClickable ||\n                candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }\n            if (supportsClick &&\n                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)\n            ) {\n                return true\n            }\n            candidate = candidate.parent\n        }\n        return false'''

if old_click not in text:
    raise SystemExit("Could not find accessibility click block")
text = text.replace(old_click, new_click, 1)

old_delay = ''')\n\n            if (!dispatched) restoreOverlay()\n        }, 50L)\n\n        mainHandler.postDelayed({ restoreOverlay() }, 500L)'''
new_delay = ''')\n\n            if (!dispatched) restoreOverlay()\n        }, 140L)\n\n        mainHandler.postDelayed({ restoreOverlay() }, 900L)'''

if old_delay not in text:
    raise SystemExit("Could not find safe tap timing block")
text = text.replace(old_delay, new_delay, 1)

path.write_text(text, encoding="utf-8")
print("Applied WebView-friendly screen tap fix")
