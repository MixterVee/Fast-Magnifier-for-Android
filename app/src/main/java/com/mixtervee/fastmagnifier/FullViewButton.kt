package com.mixtervee.fastmagnifier

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.google.android.material.button.MaterialButton

/**
 * Hides all app chrome so the camera/frozen image can be viewed unobstructed.
 * A transparent restore layer consumes the next tap before showing the controls again,
 * preventing that tap from accidentally focusing, zooming, enhancing, or opening the navigator.
 */
class FullViewButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    init {
        setOnClickListener { enterFullView() }
    }

    private fun enterFullView() {
        val root = rootView

        (root.findViewById<View>(R.id.navigatorView) as? FrozenNavigatorView)?.hideImmediately()
        root.findViewById<View>(R.id.focusRing)?.visibility = View.GONE

        root.findViewById<View>(R.id.cameraToolsBar)?.visibility = View.GONE
        root.findViewById<View>(R.id.statusText)?.visibility = View.GONE
        root.findViewById<View>(R.id.modeBar)?.visibility = View.GONE
        root.findViewById<View>(R.id.frozenToolsBar)?.visibility = View.GONE
        root.findViewById<View>(R.id.bottomBar)?.visibility = View.GONE

        root.findViewById<View>(R.id.fullViewRestoreLayer)?.apply {
            visibility = View.VISIBLE
            isClickable = true
            isFocusable = true
            bringToFront()
            setOnClickListener { restoreControls() }
        }
    }

    private fun restoreControls() {
        val root = rootView
        root.findViewById<View>(R.id.fullViewRestoreLayer)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
        }

        root.findViewById<View>(R.id.cameraToolsBar)?.visibility = View.VISIBLE
        root.findViewById<View>(R.id.statusText)?.visibility = View.VISIBLE
        root.findViewById<View>(R.id.modeBar)?.visibility = View.VISIBLE
        root.findViewById<View>(R.id.bottomBar)?.visibility = View.VISIBLE

        val frozen = root.findViewById<View>(R.id.frozenImage)?.visibility == View.VISIBLE
        root.findViewById<View>(R.id.frozenToolsBar)?.visibility =
            if (frozen) View.VISIBLE else View.GONE

        // Keep the overview hidden after leaving Full View; a normal frozen-image tap can show it.
        (root.findViewById<View>(R.id.navigatorView) as? FrozenNavigatorView)?.hideImmediately()
    }
}
