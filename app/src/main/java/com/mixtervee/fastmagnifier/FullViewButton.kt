package com.mixtervee.fastmagnifier

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.button.MaterialButton

/**
 * Full View is intentionally chrome-free.
 *
 * While Full View is active, one transparent full-screen surface waits for the
 * user's touch:
 * - a tap in the navigator's normal rectangle opens the Overview;
 * - a tap anywhere else leaves Full View and restores the controls.
 *
 * No Overview icon or other visible Full View control is shown.
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

        // Capture the exact Overview rectangle while the normal frozen-view layout
        // is still intact. Full View will use this invisible hit area later.
        navigator?.cacheOverviewAreaForFullView()
        navigator?.hideImmediately()

        root.findViewById<View>(R.id.focusRing)?.visibility = View.GONE
        root.findViewById<View>(R.id.cameraToolsBar)?.visibility = View.GONE
        root.findViewById<View>(R.id.statusText)?.visibility = View.GONE
        root.findViewById<View>(R.id.frozenToolsBar)?.visibility = View.GONE
        root.findViewById<View>(R.id.bottomBar)?.visibility = View.GONE

        // Legacy Full View Overview surfaces stay permanently hidden. The restore
        // layer below is now the only Full View touch listener.
        root.findViewById<View>(R.id.fullViewOverviewButton)?.visibility = View.GONE
        root.findViewById<View>(R.id.fullViewOverviewHotspot)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
            setOnTouchListener(null)
        }
        root.findViewById<View>(R.id.fullViewOverviewDismissLayer)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
            setOnTouchListener(null)
        }

        root.findViewById<View>(R.id.fullViewRestoreLayer)?.apply {
            visibility = View.VISIBLE
            isClickable = true
            isFocusable = true
            bringToFront()
            setOnClickListener(null)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> true
                    MotionEvent.ACTION_UP -> {
                        if (navigator?.isFullViewOverviewTap(event.rawX, event.rawY) == true) {
                            navigator.showForFullView()
                        } else {
                            restoreControls()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
    }

    private fun restoreControls() {
        val root = rootView

        root.findViewById<View>(R.id.fullViewRestoreLayer)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
            setOnTouchListener(null)
        }

        root.findViewById<View>(R.id.fullViewOverviewButton)?.visibility = View.GONE
        root.findViewById<View>(R.id.fullViewOverviewHotspot)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
            setOnTouchListener(null)
            translationX = 0f
            translationY = 0f
        }
        root.findViewById<View>(R.id.fullViewOverviewDismissLayer)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
            setOnTouchListener(null)
        }

        val navigator = root.findViewById<View>(R.id.navigatorView) as? FrozenNavigatorView
        navigator?.finishFullView()

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
