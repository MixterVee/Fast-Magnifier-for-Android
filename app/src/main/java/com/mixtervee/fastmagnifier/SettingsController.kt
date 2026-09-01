package com.mixtervee.fastmagnifier

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsController(
    private val activity: AppCompatActivity,
    private val settings: AppSettings,
    private val status: (String) -> Unit,
    private val onOverviewChanged: () -> Unit,
    private val onSpeechRateChanged: () -> Unit
) {

    fun show() {
        val items = arrayOf(
            "Overview time  •  ${settings.overviewLabel}",
            "Area enhance  •  ${settings.areaEnhanceLabel}",
            "Read aloud speed  •  ${settings.speechRateLabel}",
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
                    3 -> confirmReset()
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

    private fun confirmReset() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Restore defaults?")
            .setMessage("Overview time, area enhancement strength, and read aloud speed will return to their original settings.")
            .setPositiveButton("Restore") { _, _ ->
                settings.resetDefaults()
                onOverviewChanged()
                onSpeechRateChanged()
                status("Settings restored to defaults")
            }
            .setNegativeButton("Cancel") { _, _ -> show() }
            .show()
    }
}
