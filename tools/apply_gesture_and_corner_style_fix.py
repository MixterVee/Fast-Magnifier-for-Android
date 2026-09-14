from pathlib import Path

service_path = Path("app/src/main/java/com/mixtervee/fastmagnifier/ScreenMagnifierService.kt")
text = service_path.read_text(encoding="utf-8")

old = '''        fun addCornerHandle(symbol: String, gravity: Int, corner: Int) {\n            val handle = TextView(this).apply {\n                text = symbol\n                textSize = 30f\n                setTextColor(CYAN)\n                setShadowLayer(3f, 0f, 0f, Color.BLACK)\n                this.gravity = Gravity.CENTER\n                contentDescription = "Resize magnifier"\n                setOnTouchListener { _, event -> handleCornerResize(event, corner) }\n            }\n            imageFrame.addView(\n                handle,\n                FrameLayout.LayoutParams(dp(46), dp(46), gravity)\n            )\n        }\n\n        addCornerHandle("┌", Gravity.TOP or Gravity.START, 0)\n        addCornerHandle("┐", Gravity.TOP or Gravity.END, 1)\n        addCornerHandle("└", Gravity.BOTTOM or Gravity.START, 2)\n        addCornerHandle("┘", Gravity.BOTTOM or Gravity.END, 3)'''

new = '''        fun addCornerHandle(gravity: Int, corner: Int) {\n            val handle = FrameLayout(this).apply {\n                contentDescription = "Resize magnifier"\n                setOnTouchListener { _, event -> handleCornerResize(event, corner) }\n            }\n\n            // Keep the grab target comfortably large, but draw only thin yellow\n            // border segments so the resize affordance does not cover magnified text.\n            val horizontal = View(this).apply { setBackgroundColor(Color.YELLOW) }\n            val vertical = View(this).apply { setBackgroundColor(Color.YELLOW) }\n\n            val top = corner == 0 || corner == 1\n            val start = corner == 0 || corner == 2\n            val verticalGravity = if (top) Gravity.TOP else Gravity.BOTTOM\n            val horizontalGravity = if (start) Gravity.START else Gravity.END\n\n            handle.addView(\n                horizontal,\n                FrameLayout.LayoutParams(\n                    dp(24),\n                    dp(3),\n                    verticalGravity or horizontalGravity\n                )\n            )\n            handle.addView(\n                vertical,\n                FrameLayout.LayoutParams(\n                    dp(3),\n                    dp(24),\n                    verticalGravity or horizontalGravity\n                )\n            )\n\n            imageFrame.addView(\n                handle,\n                FrameLayout.LayoutParams(dp(46), dp(46), gravity)\n            )\n        }\n\n        addCornerHandle(Gravity.TOP or Gravity.START, 0)\n        addCornerHandle(Gravity.TOP or Gravity.END, 1)\n        addCornerHandle(Gravity.BOTTOM or Gravity.START, 2)\n        addCornerHandle(Gravity.BOTTOM or Gravity.END, 3)'''

if old not in text:
    raise SystemExit("Could not find visible corner handle block")
text = text.replace(old, new, 1)
service_path.write_text(text, encoding="utf-8")

xml_path = Path("app/src/main/res/xml/screen_magnifier_service.xml")
xml = xml_path.read_text(encoding="utf-8")
if 'android:canPerformGestures="true"' not in xml:
    xml = xml.replace(
        '    android:canControlMagnification="true"\n',
        '    android:canControlMagnification="true"\n    android:canPerformGestures="true"\n',
        1,
    )
xml_path.write_text(xml, encoding="utf-8")

print("Enabled accessibility gestures and replaced cyan corner markers with yellow border corners")
