from pathlib import Path

# Camera QR/barcode recognition should have absolutely no live-preview polling.
# Keep automatic recognition only after a frame has been frozen. Screen Magnifier
# barcode recognition is intentionally untouched.

main_path = Path("app/src/main/java/com/mixtervee/fastmagnifier/MainActivity.kt")
text = main_path.read_text(encoding="utf-8")

# Remove live-loop state injected by the general barcode patch.
text = text.replace("    private var barcodeLoopActive = false\n", "")
text = text.replace(
    "    private val barcodeScanRunnable = Runnable { scanLiveBarcodeFrame() }\n",
    "",
)

# Remove the live scan function completely. Frozen-frame scan starts immediately
# after it in the barcode patch, so use that as the stable end marker.
start = text.find("    private fun scanLiveBarcodeFrame() {\n")
end_marker = "\n    private fun scanFrozenBarcode(bitmap: Bitmap?) {"
if start != -1:
    end = text.find(end_marker, start)
    if end == -1:
        raise SystemExit("Found live Camera scanner but not frozen scanner boundary")
    text = text[:start] + text[end + 1:]

# Remove barcode-specific onResume/onPause scheduling. Do not leave a periodic
# runnable behind, even as a no-op.
resume_start = text.find("    override fun onResume() {\n")
on_destroy = text.find("    override fun onDestroy() {\n")
if resume_start != -1 and on_destroy != -1 and resume_start < on_destroy:
    lifecycle_block = text[resume_start:on_destroy]
    if "barcodeScanRunnable" in lifecycle_block or "barcodeLoopActive" in lifecycle_block:
        text = text[:resume_start] + text[on_destroy:]

# Remove loop cleanup lines from onDestroy while preserving scanner/presenter cleanup.
text = text.replace("        barcodeLoopActive = false\n", "")
text = text.replace("        mainHandler.removeCallbacks(barcodeScanRunnable)\n", "")

# Defensive cleanup in case a prior/manual patch added a Scan button listener.
scan_listener = "        binding.scanCodeButton.setOnClickListener { scanCameraCodeOnce() }\n"
text = text.replace(scan_listener, "")

# Defensive cleanup in case the one-shot helper survived from an earlier patch.
start = text.find("    private fun scanCameraCodeOnce() {\n")
if start != -1:
    end_marker = "\n    private fun scanFrozenBarcode(bitmap: Bitmap?) {"
    end = text.find(end_marker, start)
    if end == -1:
        raise SystemExit("Found manual Camera scan helper but not frozen scanner boundary")
    text = text[:start] + text[end + 1:]

main_path.write_text(text, encoding="utf-8")

# Remove any Scan button from the Camera layout if present.
layout_path = Path("app/src/main/res/layout/activity_main.xml")
layout = layout_path.read_text(encoding="utf-8")
scan_id = 'android:id="@+id/scanCodeButton"'
if scan_id in layout:
    block_start = layout.rfind("        <com.google.android.material.button.MaterialButton", 0, layout.find(scan_id))
    block_end = layout.find("        </com.google.android.material.button.MaterialButton>", layout.find(scan_id))
    if block_start == -1 or block_end == -1:
        # MaterialButton is normally self-closing in this layout.
        block_end = layout.find(" />", layout.find(scan_id))
        if block_start == -1 or block_end == -1:
            raise SystemExit("Could not remove Camera Scan button block")
        block_end += len(" />")
    else:
        block_end += len("        </com.google.android.material.button.MaterialButton>")
    layout = layout[:block_start] + layout[block_end:]
layout_path.write_text(layout, encoding="utf-8")

# Hard audit: these must not exist in the final Camera source at all.
final_text = main_path.read_text(encoding="utf-8")
for forbidden in (
    "scanLiveBarcodeFrame",
    "barcodeScanRunnable",
    "barcodeLoopActive",
    "scanCameraCodeOnce",
    "scanCodeButton",
):
    if forbidden in final_text:
        raise SystemExit(f"Live/manual Camera scanning residue remains: {forbidden}")

if scan_id in layout_path.read_text(encoding="utf-8"):
    raise SystemExit("Camera Scan button still present in layout")

# Frozen recognition must still be present.
required = (
    "private fun scanFrozenBarcode(bitmap: Bitmap?)",
    "scanFrozenBarcode(original)",
)
for marker in required:
    if marker not in final_text:
        raise SystemExit(f"Frozen-frame barcode recognition missing: {marker}")

print("Camera barcode recognition is now frozen-frame only; all live scan code removed")
