package com.mixtervee.fastmagnifier

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.google.android.material.button.MaterialButton

/**
 * Hides all app chrome so the camera/frozen image can be viewed unobstructed.
 *
 * Full View uses separate touch surfaces:
 * - fullViewRestoreLayer exits Full View when no overview is open.
 * - fullViewOverviewDismissLayer closes only the overview while it is open.
 * - fullViewOverviewHotspot is a generous lower-right tap target that opens it.
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
        root.findViewById<View>(R.id.frozenToolsBar)?.visibility = View.GONE
        root.findViewById<View>(R.id.bottomBar)?.visibility = View.GONE

        root.findViewById<View>(R.id.fullViewOverviewDismissLayer)?.apply {
            visibility = View.GONE
            setOnClickListener {
                (root.findViewById<View>(R.id.navigatorView) as? FrozenNavigatorView)
                    ?.hideImmediately()
            }
        }

        root.findViewById<View>(R.id.fullViewRestoreLayer)?.apply {
            visibility = View.VISIBLE
            isClickable = true
            isFocusable = true
            bringToFront()
            setOnClickListener { restoreControls() }
        }

        val frozenImage = root.findViewById<View>(R.id.frozenImage)
        val showOverviewHotspot =
            frozenImage?.visibility == View.VISIBLE && frozenImage.scaleX > 1.01f

        val openOverview = View.OnClickListener {
            (root.findViewById<View>(R.id.navigatorView) as? FrozenNavigatorView)
                ?.showForFullView()
        }

        // Route both the generous corner target and the visible cue to the exact
        // same action. This makes the overview reliable whether Android delivers
        // the tap to the FrameLayout or to the MaterialButton inside it.
        root.findViewById<View>(R.id.fullViewOverviewHotspot)?.apply {
            setOnClickListener(openOverview)
            visibility = if (showOverviewHotspot) View.VISIBLE else View.GONE
            if (showOverviewHotspot) bringToFront()
        }
        root.findViewById<View>(R.id.fullViewOverviewButton)?.apply {
            setOnClickListener(openOverview)
            isClickable = true
            visibility = View.VISIBLE
        }
    }

    private fun restoreControls() {
        val root = rootView

        // Disable all Full View overlays before hiding the navigator so its cleanup
        // cannot recreate any Full View controls during the transition back.
        root.findViewById<View>(R.id.fullViewOverviewButton)?.setOnClickListener(null)
        root.findViewById<View>(R.id.fullViewOverviewHotspot)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
        }
        root.findViewById<View>(R.id.fullViewOverviewDismissLayer)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
        }
        root.findViewById<View>(R.id.fullViewRestoreLayer)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
        }

        (root.findViewById<View>(R.id.navigatorView) as? FrozenNavigatorView)?.hideImmediately()

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
