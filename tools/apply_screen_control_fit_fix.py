from pathlib import Path

path = Path("app/src/main/java/com/mixtervee/fastmagnifier/ScreenMagnifierService.kt")
text = path.read_text(encoding="utf-8")

old = '''        controls.addView(controlButton("Back") { returnToMainMenu() })
        controls.addView(controlButton("Exit") { exitApplication() })
'''
new = '''        controls.addView(
            controlButton("Back") { returnToMainMenu() }.apply {
                textSize = 12f
                isSingleLine = true
                maxLines = 1
                setPadding(dp(5), 0, dp(5), 0)
            }
        )
        controls.addView(
            controlButton("Exit") { exitApplication() }.apply {
                textSize = 12f
                isSingleLine = true
                maxLines = 1
                setPadding(dp(5), 0, dp(5), 0)
            }
        )
'''

if old not in text:
    raise SystemExit("Could not find Back/Exit screen magnifier controls")

text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("Made Screen Magnifier Back/Exit controls compact and single-line")
