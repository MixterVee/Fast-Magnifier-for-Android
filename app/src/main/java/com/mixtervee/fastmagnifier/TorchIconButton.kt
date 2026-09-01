package com.mixtervee.fastmagnifier

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Keeps MainActivity's existing torch-state text updates while presenting an icon-only button. */
class TorchIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        val state = text?.toString().orEmpty()
        isSelected = state.contains("On", ignoreCase = true)
        contentDescription = when {
            state.contains("No Light", ignoreCase = true) -> "Torch unavailable"
            isSelected -> "Torch on"
            else -> "Torch off"
        }
        super.setText("", type)
    }
}
