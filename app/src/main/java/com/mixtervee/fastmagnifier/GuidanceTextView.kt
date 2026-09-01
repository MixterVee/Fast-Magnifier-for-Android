package com.mixtervee.fastmagnifier

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView

/**
 * Keeps transient status and persistent gesture guidance visually separate.
 * MainActivity can continue assigning statusText.text normally; this view
 * automatically adds the short instruction line that matches the current state.
 */
class GuidanceTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var applyingGuide = false

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        if (applyingGuide || text == null || isInEditMode) {
            super.setText(text, type)
            return
        }

        val raw = text.toString().substringBefore('\n').trim()
        val status = cleanStatus(raw)
        val guide = currentGuide(raw)
        val combined = SpannableStringBuilder()

        combined.append(status)
        combined.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            combined.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        if (guide.isNotBlank()) {
            val guideStart = combined.length + 1
            combined.append('\n').append(guide)
            combined.setSpan(
                RelativeSizeSpan(0.84f),
                guideStart,
                combined.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            combined.setSpan(
                ForegroundColorSpan(Color.argb(205, 255, 255, 255)),
                guideStart,
                combined.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        applyingGuide = true
        try {
            super.setText(combined, type)
        } finally {
            applyingGuide = false
        }
    }

    private fun cleanStatus(raw: String): String {
        val instructionFragments = listOf(
            "slide up/down",
            "slide zoom",
            "slide up to zoom",
            "tap focus",
            "tap image",
            "tap overview",
            "tap to show",
            "double-tap",
            "hold to freeze",
            "drag cyan",
            "save available",
            "save keeps full image",
            "undo available",
            "undo to go back",
            "overview fades automatically"
        )

        val meaningful = raw
            .split('•')
            .map { it.trim() }
            .filter { part ->
                part.isNotBlank() && instructionFragments.none { fragment ->
                    part.lowercase().contains(fragment)
                }
            }

        if (meaningful.isNotEmpty()) return meaningful.joinToString(" • ")

        val frozen = rootView.findViewById<View>(R.id.frozenImage)?.visibility == View.VISIBLE
        return if (frozen) "Frozen image" else "Camera ready"
    }

    private fun currentGuide(raw: String): String {
        val frozenImage = rootView.findViewById<View>(R.id.frozenImage)
        val frozen = frozenImage?.visibility == View.VISIBLE

        if (!frozen) {
            return "Slide ↑/↓ Zoom   •   Tap Focus   •   Hold Freeze"
        }

        val zoomed = (frozenImage?.scaleX ?: 1f) > 1.01f
        val undoButton = rootView.findViewById<View>(R.id.undoButton)
        val readTextButton = rootView.findViewById<View>(R.id.readTextButton)
        val canUndo = undoButton?.visibility == View.VISIBLE && undoButton.isEnabled
        val canRead = readTextButton?.isEnabled == true
        val maxReached = raw.contains("max reached", ignoreCase = true) ||
            raw.contains("Maximum", ignoreCase = true)

        val actions = mutableListOf<String>()
        actions += "Slide ↑/↓ Zoom"

        if (zoomed) {
            actions += "Tap Overview"
            if (!maxReached) actions += "Double-tap Enhance"
            if (canRead) actions += "Read Text (view)"
        } else if (canRead) {
            actions += "Read Text (full image)"
        } else {
            actions += "OCR after enhancement"
        }

        if (canUndo) actions += "Undo"
        return actions.joinToString("   •   ")
    }
}
