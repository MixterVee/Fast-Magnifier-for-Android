package com.mixtervee.fastmagnifier

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.google.android.material.button.MaterialButton

/**
 * Dedicated Full View control.
 *
 * The bottom-right Full View button only enters Full View. Overview is handled by
 * the navigator's own dedicated rectangle, never by guessing a tap on the image.
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
        val navigator = root.findViewById<View>(R.id.navigatorView) as? FrozenNavigatorView

        // Cache/hide the navigator while its normal frozen-view rectangle still has
        // the exact location the user recognizes as the Overview area.
        navigator?.hideImmediately()
        root.findViewById<View>(R.id.focusRing)?.visibility = View.GONE

        root.findViewById<View>(R.id.cameraToolsBar)?.visibility = View.GONE
        root.findViewById<View>(R.id.statusText)?.visibility = View.GONE
        root.findViewById<View>(R.id.frozenToolsBar)?.visibility = View.GONE
        root.findViewById<View>(R.id.bottomBar)?.visibility = View.GONE

        root.findViewById<View>(R.id.fullViewOverviewDismissLayer)?.apply {
            visibility = View.GONE
            setOnClickListener {
                navigator?.hideImmediately()
            }
        }

        root.findViewById<View>(R.id.fullViewRestoreLayer)?.apply {
            visibility = View.VISIBLE
            isClickable = true
            isFocusable = true
            bringToFront()
            setOnClickListener { restoreControls() }
        }

        // Put the Overview toggle back in the navigator's cached rectangle, above
        // the Full View restore surface. The little overview icon remains only a cue;
        // the whole rectangle is the actual target.
        root.findViewById<View>(R.id.fullViewOverviewButton)?.apply {
            isClickable = false
            isFocusable = false
        }
        navigator?.prepareFullViewOverviewToggle()
    }

    private fun restoreControls() {
        val root = rootView

        root.findViewById<View>(R.id.fullViewOverviewButton)?.apply {
            isClickable = false
            isFocusable = false
        }
        root.findViewById<View>(R.id.fullViewOverviewHotspot)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
            translationX = 0f
            translationY = 0f
        }
        root.findViewById<View>(R.id.fullViewOverviewDismissLayer)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
        }
        root.findViewById<View>(R.id.fullViewRestoreLayer)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
        }

        val navigator = root.findViewById<View>(R.id.navigatorView) as? FrozenNavigatorView
        navigator?.hideImmediately()

        root.findViewById<View>(R.id.cameraToolsBar)?.visibility = View.VISIBLE
        root.findViewById<View>(R.id.statusText)?.visibility = View.VISIBLE
        root.findViewById<View>(R.id.bottomBar)?.visibility = View.VISIBLE

        val frozen = root.findViewById<View>(R.id.frozenImage)?.visibility == View.VISIBLE
        root.findViewById<View>(R.id.frozenToolsBar)?.visibility =
            if (frozen) View.VISIBLE else View.GONE

        // Text/Detail/Distance are selected automatically and stay hidden.
        root.findViewById<View>(R.id.modeBar)?.visibility = View.GONE
    }
}
