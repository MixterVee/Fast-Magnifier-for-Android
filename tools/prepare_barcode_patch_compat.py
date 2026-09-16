from pathlib import Path

# MainActivity compatibility -------------------------------------------------
path = Path("app/src/main/java/com/mixtervee/fastmagnifier/MainActivity.kt")
text = path.read_text(encoding="utf-8")

# The selfie-light patch inserts its torch/brightness cleanup at the start of
# onDestroy(). The barcode patch intentionally keys off the older callback line.
# Add one harmless early callback removal so that patch remains compatible;
# the existing later removal can stay in place safely.
needle = "    override fun onDestroy() {\n        if (isFrontCamera() && torchEnabled) {\n"
replacement = (
    "    override fun onDestroy() {\n"
    "        mainHandler.removeCallbacks(longPressRunnable)\n"
    "        if (isFrontCamera() && torchEnabled) {\n"
)

if needle in text and "override fun onDestroy() {\n        mainHandler.removeCallbacks(longPressRunnable)\n" not in text:
    text = text.replace(needle, replacement, 1)

path.write_text(text, encoding="utf-8")

# ScreenMagnifierService compatibility --------------------------------------
screen_path = Path("app/src/main/java/com/mixtervee/fastmagnifier/ScreenMagnifierService.kt")
screen = screen_path.read_text(encoding="utf-8")

# The minimize-bubble patch adds two null assignments between copyPopupView and
# the cached screenshot cleanup. Add one harmless repeated copyPopupView reset
# after those fields so the barcode patch's stable insertion anchor still exists.
needle = (
    "        copyPopupView = null\n"
    "        minimizedView = null\n"
    "        minimizedParams = null\n\n"
    "        val old = lastWindowBitmap\n"
)
replacement = (
    "        copyPopupView = null\n"
    "        minimizedView = null\n"
    "        minimizedParams = null\n"
    "        copyPopupView = null\n\n"
    "        val old = lastWindowBitmap\n"
)

if needle in screen:
    screen = screen.replace(needle, replacement, 1)

screen_path.write_text(screen, encoding="utf-8")
print("Prepared Camera and Screen Magnifier sources for barcode patch ordering")
