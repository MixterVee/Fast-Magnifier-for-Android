package com.mixtervee.fastmagnifier

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class CameraCodePresenter(
    private val activity: Activity,
    private val root: ConstraintLayout,
    private val anchorViewId: Int
) {
    private var card: MaterialCardView? = null

    fun show(code: BarcodeScannerController.DetectedCode) {
        dismiss()

        val panel = MaterialCardView(activity).apply {
            id = View.generateViewId()
            radius = dp(14).toFloat()
            cardElevation = dp(8).toFloat()
            setCardBackgroundColor(Color.argb(247, 18, 22, 29))
            strokeColor = 0xff00bcd4.toInt()
            strokeWidth = dp(2)
            contentDescription = code.title
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        content.addView(TextView(activity).apply {
            text = code.title
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })

        content.addView(TextView(activity).apply {
            text = code.value
            setTextColor(0xffe8eef5.toInt())
            textSize = 13f
            maxLines = 3
            setPadding(0, dp(4), 0, dp(7))
        })

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        when {
            code.isUrl -> actions.addView(actionButton(activity.getString(R.string.open)) {
                openWeb(code.value)
                dismiss()
            })

            code.isProductBarcode -> actions.addView(actionButton(activity.getString(R.string.search)) {
                searchWeb(code.value)
                dismiss()
            })
        }

        actions.addView(actionButton(activity.getString(R.string.copy)) {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.scanned_code), code.value))
            Toast.makeText(activity, R.string.code_copied, Toast.LENGTH_SHORT).show()
        })
        actions.addView(actionButton(activity.getString(R.string.dismiss)) { dismiss() })
        content.addView(actions)
        panel.addView(content)

        val params = ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToTop = anchorViewId
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(8)
        }

        root.addView(panel, params)
        panel.bringToFront()
        card = panel
    }

    fun dismiss() {
        card?.let { existing ->
            runCatching { root.removeView(existing) }
        }
        card = null
    }

    private fun actionButton(label: String, action: () -> Unit): MaterialButton =
        MaterialButton(activity).apply {
            text = label
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { action() }
        }

    private fun openWeb(value: String) {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            Toast.makeText(activity, R.string.no_app_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchWeb(value: String) {
        val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(value)}")
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            Toast.makeText(activity, R.string.no_browser_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
