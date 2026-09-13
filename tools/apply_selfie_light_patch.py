from pathlib import Path

path = Path("app/src/main/java/com/mixtervee/fastmagnifier/MainActivity.kt")
text = path.read_text(encoding="utf-8")

if "toggleSelfieLightAssist" in text:
    print("Selfie light patch already applied")
    raise SystemExit(0)

replacements = [
    (
        "import android.content.ContentValues\n",
        "import android.content.ContentValues\nimport android.content.Context\n"
    ),
    (
        "import android.graphics.Matrix\n",
        "import android.graphics.Matrix\nimport android.hardware.camera2.CameraCharacteristics\nimport android.hardware.camera2.CameraManager\n"
    ),
    (
        "import android.provider.MediaStore\n",
        "import android.provider.MediaStore\nimport android.provider.Settings\n"
    ),
    (
        "    private var torchEnabled = false\n    private var cameraFacing = CameraSelector.LENS_FACING_BACK\n",
        "    private var torchEnabled = false\n    private var selfieAssistCameraId: String? = null\n    private var cameraFacing = CameraSelector.LENS_FACING_BACK\n"
    ),
    (
        "                setupCameraControls()\n                binding.statusText.text = if (isFrontCamera()) {\n",
        "                setupCameraControls()\n                updateSelfieScreenBrightness()\n                binding.statusText.text = if (isFrontCamera()) {\n"
    ),
    (
        "            } catch (_: Throwable) {\n                binding.cameraFlipButton.isEnabled = false\n                binding.selfieLightFrame.visibility = View.GONE\n",
        "            } catch (_: Throwable) {\n                restoreSystemScreenBrightness()\n                binding.cameraFlipButton.isEnabled = false\n                binding.selfieLightFrame.visibility = View.GONE\n"
    ),
    (
        "        camera?.cameraControl?.enableTorch(false)\n        torchEnabled = false\n        cameraFacing = if (isFrontCamera()) {\n",
        "        if (isFrontCamera()) {\n            setSelfieAssistTorch(false, quiet = true)\n        } else {\n            camera?.cameraControl?.enableTorch(false)\n        }\n        torchEnabled = false\n        cameraFacing = if (isFrontCamera()) {\n"
    ),
    (
        "        val hasLight = c.cameraInfo.hasFlashUnit()\n        binding.lightButton.isEnabled = hasLight && !frozen\n        binding.lightButton.text = when {\n            !hasLight -> \"No Light\"\n            torchEnabled -> \"Light On\"\n            else -> \"Light\"\n        }\n        if (hasLight && torchEnabled) {\n            c.cameraControl.enableTorch(true)\n        }\n        updateSelfieFillLight()\n",
        "        val hasLight = c.cameraInfo.hasFlashUnit()\n        if (isFrontCamera()) {\n            selfieAssistCameraId = findRearTorchCameraId()\n            val assistAvailable = selfieAssistCameraId != null\n            binding.lightButton.isEnabled = assistAvailable && !frozen\n            binding.lightButton.contentDescription = if (torchEnabled) {\n                \"Turn off selfie light assist\"\n            } else {\n                \"Turn on selfie light assist\"\n            }\n            binding.lightButton.text = when {\n                !assistAvailable -> \"No Assist\"\n                torchEnabled -> \"Assist On\"\n                else -> \"Assist\"\n            }\n        } else {\n            selfieAssistCameraId = null\n            binding.lightButton.isEnabled = hasLight && !frozen\n            binding.lightButton.contentDescription = if (torchEnabled) \"Turn off light\" else \"Turn on light\"\n            binding.lightButton.text = when {\n                !hasLight -> \"No Light\"\n                torchEnabled -> \"Light On\"\n                else -> \"Light\"\n            }\n            if (hasLight && torchEnabled) {\n                c.cameraControl.enableTorch(true)\n            }\n        }\n        updateSelfieFillLight()\n"
    ),
    (
        "    private fun toggleTorch() {\n        val c = camera ?: return\n",
        "    private fun toggleTorch() {\n        if (isFrontCamera()) {\n            toggleSelfieLightAssist()\n            return\n        }\n\n        val c = camera ?: return\n"
    ),
    (
        "    private fun setExposureCompensation(requestedIndex: Int) {\n",
        "    private fun toggleSelfieLightAssist() {\n        if (selfieAssistCameraId == null) {\n            binding.lightButton.isEnabled = false\n            binding.statusText.text = \"Rear light assist is not available on this device\"\n            return\n        }\n\n        setSelfieAssistTorch(!torchEnabled)\n    }\n\n    private fun setSelfieAssistTorch(enabled: Boolean, quiet: Boolean = false) {\n        val id = selfieAssistCameraId ?: findRearTorchCameraId() ?: run {\n            torchEnabled = false\n            if (!quiet) binding.statusText.text = \"Rear light assist is not available on this device\"\n            return\n        }\n\n        try {\n            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager\n            manager.setTorchMode(id, enabled)\n            torchEnabled = enabled\n            binding.lightButton.text = if (enabled) \"Assist On\" else \"Assist\"\n            binding.lightButton.contentDescription = if (enabled) {\n                \"Turn off selfie light assist\"\n            } else {\n                \"Turn on selfie light assist\"\n            }\n            if (!quiet) {\n                binding.statusText.text = if (enabled) {\n                    \"Selfie light assist on • using rear LED bounce\"\n                } else {\n                    \"Selfie light assist off\"\n                }\n            }\n        } catch (_: Throwable) {\n            torchEnabled = false\n            binding.lightButton.text = \"Assist\"\n            if (!quiet) {\n                binding.statusText.text = \"This phone cannot use the rear LED with the selfie camera\"\n            }\n        } finally {\n            updateSelfieFillLight()\n        }\n    }\n\n    private fun findRearTorchCameraId(): String? {\n        return try {\n            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager\n            manager.cameraIdList.firstOrNull { id ->\n                val characteristics = manager.getCameraCharacteristics(id)\n                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&\n                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true\n            }\n        } catch (_: Throwable) {\n            null\n        }\n    }\n\n    private fun updateSelfieScreenBrightness() {\n        if (!isFrontCamera()) {\n            restoreSystemScreenBrightness()\n            return\n        }\n\n        val systemBrightness = runCatching {\n            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f\n        }.getOrDefault(0.5f)\n        val boosted = (systemBrightness + 0.18f).coerceIn(0f, 1f)\n        window.attributes = window.attributes.apply {\n            screenBrightness = boosted\n        }\n    }\n\n    private fun restoreSystemScreenBrightness() {\n        window.attributes = window.attributes.apply {\n            screenBrightness = -1f\n        }\n    }\n\n    private fun setExposureCompensation(requestedIndex: Int) {\n"
    ),
    (
        "        setupCameraControls()\n        binding.statusText.text = if (isFrontCamera()) \"Selfie camera ready\" else \"Camera ready\"\n",
        "        setupCameraControls()\n        updateSelfieScreenBrightness()\n        binding.statusText.text = if (isFrontCamera()) \"Selfie camera ready\" else \"Camera ready\"\n"
    ),
    (
        "    override fun onDestroy() {\n        mainHandler.removeCallbacks(longPressRunnable)\n",
        "    override fun onDestroy() {\n        if (isFrontCamera() && torchEnabled) {\n            setSelfieAssistTorch(false, quiet = true)\n        }\n        restoreSystemScreenBrightness()\n        mainHandler.removeCallbacks(longPressRunnable)\n"
    ),
]

for old, new in replacements:
    if old not in text:
        raise RuntimeError(f"Expected source block not found:\n{old[:180]}")
    text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("Applied selfie brightness + rear LED assist patch")
