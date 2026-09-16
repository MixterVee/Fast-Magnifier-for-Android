from pathlib import Path

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
print("Prepared MainActivity for barcode patch after selfie cleanup")
