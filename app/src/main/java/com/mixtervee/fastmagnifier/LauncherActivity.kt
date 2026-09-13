package com.mixtervee.fastmagnifier

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Lightweight entry point that lets the user choose which magnifier they want
 * before CameraX is started or camera permission is requested.
 */
class LauncherActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var waitingForAccessibility = false
    private var chooserShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showModeChooser()
    }

    override fun onResume() {
        super.onResume()
        if (!waitingForAccessibility) return

        // The Accessibility service can take a moment to reconnect after the user
        // enables it. Retry briefly so returning with Back feels automatic.
        tryStartScreenMagnifier(0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showModeChooser() {
        if (isFinishing || chooserShowing) return
        chooserShowing = true

        MaterialAlertDialogBuilder(this)
            .setTitle("Fast Magnifier")
            .setMessage("What would you like to magnify?")
            .setPositiveButton("Camera Magnifier") { _, _ ->
                chooserShowing = false
                waitingForAccessibility = false
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .setNegativeButton("Screen Magnifier") { _, _ ->
                chooserShowing = false
                chooseScreenMagnifier()
            }
            .setOnCancelListener {
                chooserShowing = false
                finish()
            }
            .show()
    }

    private fun chooseScreenMagnifier() {
        val service = ScreenMagnifierService.instance
        if (service != null) {
            startLensThenBackground(service)
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Enable Screen Magnifier")
            .setMessage(
                "Android requires a one-time Accessibility permission so Fast Magnifier can magnify other apps and read screen text when you long-press inside the lens.\n\n" +
                    "Tap Enable, choose Fast Magnifier Screen Magnifier, turn it on, then press Back. Screen Magnifier should start automatically."
            )
            .setPositiveButton("Enable") { _, _ ->
                waitingForAccessibility = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Back") { _, _ ->
                waitingForAccessibility = false
                showModeChooser()
            }
            .show()
    }

    private fun tryStartScreenMagnifier(attempt: Int) {
        if (!waitingForAccessibility || isFinishing) return

        val service = ScreenMagnifierService.instance
        if (service != null) {
            waitingForAccessibility = false
            startLensThenBackground(service)
            return
        }

        if (attempt < 10) {
            handler.postDelayed({ tryStartScreenMagnifier(attempt + 1) }, 200L)
        } else {
            waitingForAccessibility = false
            MaterialAlertDialogBuilder(this)
                .setTitle("Screen Magnifier not enabled")
                .setMessage("Fast Magnifier could not connect to its Accessibility service. Make sure Fast Magnifier Screen Magnifier is turned on, then try again.")
                .setPositiveButton("Try Again") { _, _ -> showModeChooser() }
                .setNegativeButton("Accessibility Settings") { _, _ ->
                    waitingForAccessibility = true
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .show()
        }
    }

    /**
     * Create the accessibility overlay while this activity is definitely alive,
     * then move the task to the background after the lens has had a chance to attach.
     * This avoids losing a delayed callback when some OEMs destroy the launcher as
     * soon as moveTaskToBack() is called.
     */
    private fun startLensThenBackground(service: ScreenMagnifierService) {
        waitingForAccessibility = false
        service.startScreenMagnifier()
        handler.postDelayed({
            if (!isFinishing) moveTaskToBack(true)
        }, 220L)
    }
}
