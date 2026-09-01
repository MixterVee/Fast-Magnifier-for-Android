package com.mixtervee.fastmagnifier

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.button.MaterialButton

/**
 * Hides all app chrome so the camera/frozen image can be viewed unobstructed.
 * A transparent restore layer consumes taps outside the dedicated overview button.
 */
class FullViewButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    companion object {
        const val OVERVIEW_BUTTON_TAG = "fast_magnifier_full_view_overview"
    }

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

        root.findViewById<View>(R.id.fullViewRestoreLayer)?.apply {
            visibility = View.VISIBLE
            isClickable = true
            isFocusable = true
            bringToFront()
            setOnClickListener { restoreControls() }
        }

        val frozenImage = root.findViewById<View>(R.id.frozenImage)
        val showOverviewButton =
            frozenImage?.visibility == View.VISIBLE && (frozenImage.scaleX > 1.01f)

        ensureOverviewButton().apply {
            visibility = if (showOverviewButton) View.VISIBLE else View.GONE
            if (showOverviewButton) bringToFront()
        }
    }

    private fun ensureOverviewButton(): MaterialButton {
        val root = rootView as? ConstraintLayout
            ?: error("Fast Magnifier root must be ConstraintLayout")

        root.findViewWithTag<MaterialButton>(OVERVIEW_BUTTON_TAG)?.let { return it }

        return MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            tag = OVERVIEW_BUTTON_TAG
            id = View.generateViewId()
            contentDescription = "Show overview"
            text = ""
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            alpha = 0.78f
            cornerRadius = dp(12)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(Color.argb(180, 255, 255, 255))
            backgroundTintList = ColorStateList.valueOf(Color.argb(175, 10, 16, 24))
            setIconResource(R.drawable.ic_overview)
            iconTint = ColorStateList.valueOf(Color.WHITE)
            iconPadding = 0
            iconSize = dp(22)
            iconGravity = ICON_GRAVITY_TEXT_START
            visibility = View.GONE

            layoutParams = ConstraintLayout.LayoutParams(dp(48), dp(48)).apply {
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                marginEnd = dp(16)
                bottomMargin = dp(16)
            }

            setOnClickListener {
                visibility = View.GONE
                val navigator = rootView.findViewById<View>(R.id.navigatorView) as? FrozenNavigatorView
                if (navigator?.showForFullView() != true) {
                    val restoreLayer = rootView.findViewById<View>(R.id.fullViewRestoreLayer)
                    val frozen = rootView.findViewById<View>(R.id.frozenImage)
                    if (
                        restoreLayer?.visibility == View.VISIBLE &&
                        frozen?.visibility == View.VISIBLE &&
                        frozen.scaleX > 1.01f
                    ) {
                        visibility = View.VISIBLE
                        bringToFront()
                    }
                }
            }

            root.addView(this)
        }
    }

    private fun restoreControls() {
        val root = rootView

        root.findViewWithTag<View>(OVERVIEW_BUTTON_TAG)?.visibility = View.GONE

        root.findViewById<View>(R.id.fullViewRestoreLayer)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
        }

        root.findViewById<View>(R.id.cameraToolsBar)?.visibility = View.VISIBLE
        root.findViewById<View>(R.id.statusText)?.visibility = View.VISIBLE
        root.findViewById<View>(R.id.bottomBar)?.visibility = View.VISIBLE

        val frozen = root.findViewById<View>(R.id.frozenImage)?.visibility == View.VISIBLE
        root.findViewById<View>(R.id.frozenToolsBar)?.visibility =
            if (frozen) View.VISIBLE else View.GONE

        // Text/Detail/Distance are selected automatically and stay hidden.
        root.findViewById<View>(R.id.modeBar)?.visibility = View.GONE

        // A zoom gesture will show the overview again when navigation help is needed.
        (root.findViewById<View>(R.id.navigatorView) as? FrozenNavigatorView)?.hideImmediately()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
