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
        isSelected = state == context.getString(R.string.light_on) ||
            state == context.getString(R.string.assist_on)
        contentDescription = when {
            state == context.getString(R.string.no_light) ||
                state == context.getString(R.string.no_assist) -> context.getString(R.string.torch_unavailable)
            isSelected -> context.getString(R.string.torch_on)
            else -> context.getString(R.string.torch_off)
        }
        super.setText("", type)
    }
}
