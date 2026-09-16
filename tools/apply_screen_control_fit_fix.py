from pathlib import Path

path = Path("app/src/main/java/com/mixtervee/fastmagnifier/ScreenMagnifierService.kt")
text = path.read_text(encoding="utf-8")

old = '''        controls.addView(controlButton("−") { changeScale(-SCALE_STEP) })

        val scaleText = TextView(this).apply {
            text = formatScale()
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        controls.addView(
            scaleText,
            LinearLayout.LayoutParams(dp(64), controlsHeight)
        )

        controls.addView(controlButton("+") { changeScale(SCALE_STEP) })
        controls.addView(
            controlButton("Min") { minimizeScreenMagnifier() }.apply {
                textSize = 12f
                setPadding(dp(6), 0, dp(6), 0)
            }
        )
        controls.addView(controlButton("Back") { returnToMainMenu() })
        controls.addView(controlButton("Exit") { exitApplication() })
'''

new = '''        val equalButtonParams = LinearLayout.LayoutParams(
            0,
            controlsHeight,
            1f
        ).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }

        fun addEqualControl(label: String, textSizeSp: Float = 12f, action: () -> Unit) {
            controls.addView(
                controlButton(label, action).apply {
                    textSize = textSizeSp
                    isSingleLine = true
                    maxLines = 1
                    setPadding(dp(3), 0, dp(3), 0)
                },
                LinearLayout.LayoutParams(equalButtonParams)
            )
        }

        addEqualControl("−", 18f) { changeScale(-SCALE_STEP) }

        val scaleText = TextView(this).apply {
            text = formatScale()
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), 0)
        }
        controls.addView(
            scaleText,
            LinearLayout.LayoutParams(dp(46), controlsHeight)
        )

        addEqualControl("+", 18f) { changeScale(SCALE_STEP) }
        addEqualControl("Min") { minimizeScreenMagnifier() }
        addEqualControl("Back") { returnToMainMenu() }
        addEqualControl("Exit") { exitApplication() }
'''

if old not in text:
    raise SystemExit("Could not find Screen Magnifier control row after minimize patch")

text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("Made -, +, Min, Back and Exit equal-sized Screen Magnifier controls")
