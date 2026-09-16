from pathlib import Path

# Camera mode: keep live preview smooth by removing continuous QR/barcode scans.
# Add a deliberate one-shot Scan button instead. Frozen-frame auto scanning remains.

main_path = Path("app/src/main/java/com/mixtervee/fastmagnifier/MainActivity.kt")
text = main_path.read_text(encoding="utf-8")

# Add a manual Scan button to the existing bottom camera controls.
layout_path = Path("app/src/main/res/layout/activity_main.xml")
layout = layout_path.read_text(encoding="utf-8")

if 'android:id="@+id/scanCodeButton"' not in layout:
    freeze_block = '''        <com.mixtervee.fastmagnifier.CompactFreezeButton
            android:id="@+id/freezeButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:minWidth="0dp"
            android:paddingHorizontal="12dp"
            android:text="Freeze" />
'''
    scan_block = freeze_block + '''
        <com.google.android.material.button.MaterialButton
            android:id="@+id/scanCodeButton"
            style="@style/Widget.MaterialComponents.Button.OutlinedButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:contentDescription="Scan QR code or barcode"
            android:minWidth="0dp"
            android:paddingHorizontal="10dp"
            android:text="Scan" />
'''
    if freeze_block not in layout:
        raise SystemExit("Could not find Freeze button layout block")
    layout = layout.replace(freeze_block, scan_block, 1)

layout_path.write_text(layout, encoding="utf-8")

# Wire the Scan button beside the existing Freeze action.
listener_anchor = '''        binding.freezeButton.setOnClickListener {
            if (binding.frozenImage.visibility == View.VISIBLE) resumeLive() else freezeAndAutoEnhance()
        }
'''
if "binding.scanCodeButton.setOnClickListener" not in text:
    if listener_anchor not in text:
        raise SystemExit("Could not find Freeze button listener")
    text = text.replace(
        listener_anchor,
        listener_anchor + "        binding.scanCodeButton.setOnClickListener { scanCameraCodeOnce() }\n",
        1,
    )

# Replace the continuous live scanner with a no-op plus a deliberate one-shot scan.
start = text.find("    private fun scanLiveBarcodeFrame() {\n")
end_marker = "\n    private fun scanFrozenBarcode(bitmap: Bitmap?) {"
if start == -1:
    raise SystemExit("Could not find live barcode scanner function")
end = text.find(end_marker, start)
if end == -1:
    raise SystemExit("Could not find frozen barcode scanner function")

manual_scan = r'''    private fun scanLiveBarcodeFrame() {
        // Intentionally disabled. Continuous PreviewView bitmap grabs caused a
        // visible hitch roughly once per second while panning the camera.
    }

    private fun scanCameraCodeOnce() {
        if (!::barcodeScanner.isInitialized || !::barcodePresenter.isInitialized) return

        barcodePresenter.dismiss()

        // Frozen images already scan automatically once, but allowing a manual
        // retry here is useful if the first pass missed a small or blurry code.
        if (binding.frozenImage.visibility == View.VISIBLE) {
            val source = if (showingEnhanced) enhanced ?: original else original ?: enhanced
            if (source == null || source.isRecycled) {
                binding.statusText.text = "No frozen image available to scan"
                return
            }

            binding.statusText.text = "Scanning QR/barcode…"
            barcodeScanner.scan(source) { code ->
                runOnUiThread {
                    if (code != null) {
                        lastDetectedBarcodeValue = code.value
                        lastDetectedBarcodeAt = android.os.SystemClock.elapsedRealtime()
                        barcodePresenter.show(code)
                        binding.statusText.text = code.title
                    } else {
                        binding.statusText.text = "No QR code or barcode found"
                    }
                }
            }
            return
        }

        val frame = binding.previewView.bitmap
        if (frame == null || frame.isRecycled) {
            binding.statusText.text = "Camera frame not ready"
            return
        }

        binding.scanCodeButton.isEnabled = false
        binding.statusText.text = "Scanning QR/barcode…"

        barcodeScanner.scan(frame) { code ->
            runOnUiThread {
                binding.scanCodeButton.isEnabled = true
                if (code != null) {
                    lastDetectedBarcodeValue = code.value
                    lastDetectedBarcodeAt = android.os.SystemClock.elapsedRealtime()
                    barcodePresenter.show(code)
                    binding.statusText.text = code.title
                } else {
                    binding.statusText.text = "No QR code or barcode found"
                }
            }
        }

        // BarcodeScannerController copies/prepares the bitmap synchronously
        // before ML Kit runs, so the PreviewView bitmap can be recycled now.
        if (!frame.isRecycled) frame.recycle()

        // Safety reset in case ML Kit never calls back on a device-specific failure.
        mainHandler.postDelayed({
            if (!binding.scanCodeButton.isEnabled) {
                binding.scanCodeButton.isEnabled = true
                if (binding.statusText.text.toString().contains("Scanning QR/barcode")) {
                    binding.statusText.text = "Scan timed out • try again"
                }
            }
        }, 3000L)
    }
'''

text = text[:start] + manual_scan + text[end:]

# The barcode patch adds lifecycle scheduling for the old continuous scanner.
# Keep the lifecycle hooks, but ensure they never schedule live scans.
resume_start = text.find("    override fun onResume() {\n")
on_destroy = text.find("    override fun onDestroy() {\n")
if resume_start != -1 and on_destroy != -1 and resume_start < on_destroy:
    lifecycle = r'''    override fun onResume() {
        super.onResume()
        barcodeLoopActive = false
        mainHandler.removeCallbacks(barcodeScanRunnable)
    }

    override fun onPause() {
        barcodeLoopActive = false
        mainHandler.removeCallbacks(barcodeScanRunnable)
        super.onPause()
    }

'''
    text = text[:resume_start] + lifecycle + text[on_destroy:]

main_path.write_text(text, encoding="utf-8")
print("Disabled continuous Camera barcode scanning and added one-shot Scan button")
