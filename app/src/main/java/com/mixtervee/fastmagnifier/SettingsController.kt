package com.mixtervee.fastmagnifier

import android.graphics.Bitmap
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsController(
    private val activity: AppCompatActivity,
    private val settings: AppSettings,
    private val status: (String) -> Unit,
    private val onOverviewChanged: () -> Unit,
    private val onSpeechRateChanged: () -> Unit
) {
    private val recentCaptures = RecentCapturesController(
        activity = activity,
        status = status,
        onSelected = { bitmap -> showRecentCapture(bitmap) }
    )

    init {
        applyKeepScreenAwake()
    }

    fun show() {
        val items = arrayOf(
            "Overview time  •  ${settings.overviewLabel}",
            "Area enhance  •  ${settings.areaEnhanceLabel}",
            "Read aloud speed  •  ${settings.speechRateLabel}",
            "Keep screen awake  •  ${settings.keepScreenAwakeLabel}",
            "Recent captures  •  ${recentCaptures.count()}",
            "Restore defaults"
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle("Settings")
            .setItems(items) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showOverviewSettings()
                    1 -> showAreaEnhanceSettings()
                    2 -> showSpeechRateSettings()
                    3 -> showKeepScreenAwakeSettings()
                    4 -> recentCaptures.show()
                    5 -> confirmReset()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showOverviewSettings() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Overview display time")
            .setSingleChoiceItems(
                AppSettings.OVERVIEW_LABELS,
                settings.overviewIndex
            ) { dialog, which ->
                settings.overviewIndex = which
                onOverviewChanged()
                status("Overview time: ${settings.overviewLabel}")
                dialog.dismiss()
            }
            .setNegativeButton("Back") { _, _ -> show() }
            .show()
    }

    private fun showAreaEnhanceSettings() {
        val choices = arrayOf(
            "Gentle  •  subtle extra sharpening",
            "Normal  •  balanced (recommended)",
            "Strong  •  maximum extra sharpening"
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle("Area enhancement strength")
            .setSingleChoiceItems(
                choices,
                settings.areaEnhanceIndex
            ) { dialog, which ->
                settings.areaEnhanceIndex = which
                status("Area enhance: ${settings.areaEnhanceLabel}")
                dialog.dismiss()
            }
            .setNegativeButton("Back") { _, _ -> show() }
            .show()
    }

    private fun showSpeechRateSettings() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Read aloud speed")
            .setSingleChoiceItems(
                AppSettings.SPEECH_RATE_LABELS,
                settings.speechRateIndex
            ) { dialog, which ->
                settings.speechRateIndex = which
                onSpeechRateChanged()
                status("Read aloud speed: ${settings.speechRateLabel}")
                dialog.dismiss()
            }
            .setNegativeButton("Back") { _, _ -> show() }
            .show()
    }

    private fun showKeepScreenAwakeSettings() {
        val choices = arrayOf(
            "Off  •  use the normal Android screen timeout",
            "On  •  keep the display awake while Fast Magnifier is open"
        )
        val selected = if (settings.keepScreenAwake) 1 else 0

        MaterialAlertDialogBuilder(activity)
            .setTitle("Keep screen awake")
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                settings.keepScreenAwake = which == 1
                applyKeepScreenAwake()
                status("Keep screen awake: ${settings.keepScreenAwakeLabel}")
                dialog.dismiss()
            }
            .setNegativeButton("Back") { _, _ -> show() }
            .show()
    }

    private fun applyKeepScreenAwake() {
        if (settings.keepScreenAwake) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun showRecentCapture(bitmap: Bitmap) {
        val padding = (16f * activity.resources.displayMetrics.density).toInt()
        val image = ImageView(activity).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(padding, padding, padding, padding)
            contentDescription = "Recent magnifier capture"
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("Recent capture")
            .setView(image)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Restore defaults?")
            .setMessage("Overview time, area enhancement strength, read aloud speed, and keep-screen-awake will return to their original settings.")
            .setPositiveButton("Restore") { _, _ ->
                settings.resetDefaults()
                onOverviewChanged()
                onSpeechRateChanged()
                applyKeepScreenAwake()
                status("Settings restored to defaults")
            }
            .setNegativeButton("Cancel") { _, _ -> show() }
            .show()
    }
}
