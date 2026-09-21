package com.mixtervee.fastmagnifier

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
            activity.getString(R.string.settings_screen_magnifier_item),
            activity.getString(R.string.settings_overview_item, settings.overviewLabel),
            activity.getString(R.string.settings_area_item, settings.areaEnhanceLabel),
            activity.getString(R.string.settings_speech_item, settings.speechRateLabel),
            activity.getString(R.string.settings_awake_item, settings.keepScreenAwakeLabel),
            activity.getString(R.string.settings_recent_item, recentCaptures.count()),
            activity.getString(R.string.restore_defaults)
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings)
            .setItems(items) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showScreenMagnifier()
                    1 -> showOverviewSettings()
                    2 -> showAreaEnhanceSettings()
                    3 -> showSpeechRateSettings()
                    4 -> showKeepScreenAwakeSettings()
                    5 -> recentCaptures.show()
                    6 -> confirmReset()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showScreenMagnifier() {
        val service = ScreenMagnifierService.instance
        if (service != null) {
            status(activity.getString(R.string.starting_screen_magnifier))
            activity.moveTaskToBack(true)
            Handler(Looper.getMainLooper()).postDelayed({
                ScreenMagnifierService.instance?.startScreenMagnifier()
            }, 250L)
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.enable_screen_magnifier)
            .setMessage(R.string.accessibility_permission_settings)
            .setPositiveButton(R.string.enable) { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> show() }
            .show()
    }

    private fun showOverviewSettings() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_display_time)
            .setSingleChoiceItems(
                settings.overviewLabels,
                settings.overviewIndex
            ) { dialog, which ->
                settings.overviewIndex = which
                onOverviewChanged()
                status(activity.getString(R.string.overview_time_status, settings.overviewLabel))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.back) { _, _ -> show() }
            .show()
    }

    private fun showAreaEnhanceSettings() {
        val choices = arrayOf(
            activity.getString(R.string.area_gentle_detail),
            activity.getString(R.string.area_normal_detail),
            activity.getString(R.string.area_strong_detail)
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.area_enhancement_strength)
            .setSingleChoiceItems(
                choices,
                settings.areaEnhanceIndex
            ) { dialog, which ->
                settings.areaEnhanceIndex = which
                status(activity.getString(R.string.area_enhance_status, settings.areaEnhanceLabel))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.back) { _, _ -> show() }
            .show()
    }

    private fun showSpeechRateSettings() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.read_aloud_speed)
            .setSingleChoiceItems(
                settings.speechRateLabels,
                settings.speechRateIndex
            ) { dialog, which ->
                settings.speechRateIndex = which
                onSpeechRateChanged()
                status(activity.getString(R.string.read_aloud_speed_status, settings.speechRateLabel))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.back) { _, _ -> show() }
            .show()
    }

    private fun showKeepScreenAwakeSettings() {
        val choices = arrayOf(
            activity.getString(R.string.awake_off_detail),
            activity.getString(R.string.awake_on_detail)
        )
        val selected = if (settings.keepScreenAwake) 1 else 0

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.keep_screen_awake)
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                settings.keepScreenAwake = which == 1
                applyKeepScreenAwake()
                status(activity.getString(R.string.keep_screen_awake_status, settings.keepScreenAwakeLabel))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.back) { _, _ -> show() }
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
            contentDescription = activity.getString(R.string.recent_capture_description)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.recent_capture)
            .setView(image)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.restore_defaults_question)
            .setMessage(R.string.restore_defaults_message)
            .setPositiveButton(R.string.restore) { _, _ ->
                settings.resetDefaults()
                onOverviewChanged()
                onSpeechRateChanged()
                applyKeepScreenAwake()
                status(activity.getString(R.string.settings_restored))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> show() }
            .show()
    }
}
