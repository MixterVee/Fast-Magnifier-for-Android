package com.mixtervee.fastmagnifier

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * Keeps the primary freeze action compact enough to share the bottom bar with
 * Original, Save, and Full View. Enhancement already happens automatically, so
 * the longer legacy label "Freeze + Enhance" is displayed simply as "Freeze".
 */
class CompactFreezeButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        val compactText = if (text?.toString() == "Freeze + Enhance") "Freeze" else text
        super.setText(compactText, type)
    }
}
