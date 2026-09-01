package com.mixtervee.fastmagnifier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class PanZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchMoved = false
    private var bitmapSwapGeneration = 0

    /**
     * Keep the user's current zoomed viewport when the displayed bitmap is replaced.
     * This is especially important when the fast PreviewView freeze is upgraded to
     * the higher-resolution still image: the detail being inspected should sharpen
     * in place instead of appearing to jump back to a full-image view.
     */
    override fun setImageBitmap(bm: Bitmap?) {
        val preserveViewport =
            drawable != null && width > 0 && height > 0 && scaleX > 1.01f && scaleY > 1.01f
        val visibleBefore = if (preserveViewport) visibleBitmapRectNormalized() else null
        val savedScaleX = scaleX
        val savedScaleY = scaleY
        val generation = ++bitmapSwapGeneration

        super.setImageBitmap(bm)

        if (visibleBefore != null) {
            val centerX = visibleBefore.centerX()
            val centerY = visibleBefore.centerY()
            post {
                if (generation != bitmapSwapGeneration || drawable == null || width <= 0 || height <= 0) {
                    return@post
                }

                pivotX = width / 2f
                pivotY = height / 2f
                scaleX = savedScaleX
                scaleY = savedScaleY
                panToNormalized(centerX, centerY)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchDownX = event.x
            touchDownY = event.y
            touchMoved = false
        }

        val handled = super.dispatchTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val movement = hypot(
                    (event.x - touchDownX).toDouble(),
                    (event.y - touchDownY).toDouble()
                )
                if (movement > tapSlop * 1.25f) touchMoved = true

                if (scaleX > 1.01f) {
                    navigator()?.showTemporarily()
                    setStatus("Frozen zoom ${formatZoom(scaleX)}× • overview fades automatically")
                } else {
                    navigator()?.hideImmediately()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!touchMoved && scaleX > 1.01f) {
                    val shown = navigator()?.toggleVisibility() ?: false
                    setStatus(
                        if (shown) {
                            "Overview shown • drag cyan box to move • tap image to hide"
                        } else {
                            "Overview hidden • tap image to show"
                        }
                    )
                } else if (scaleX > 1.01f) {
                    navigator()?.showTemporarily()
                    setStatus("Frozen zoom ${formatZoom(scaleX)}× • tap image to show/hide overview")
                } else {
                    navigator()?.hideImmediately()
                    setStatus("Slide up/down to zoom • Save keeps full image")
                }
                touchMoved = false
            }

            MotionEvent.ACTION_CANCEL -> {
                touchMoved = false
            }
        }

        return handled
    }

    fun panToNormalized(normalizedX: Float, normalizedY: Float) {
        if (width <= 0 || height <= 0 || scaleX <= 1.01f || scaleY <= 1.01f) {
            translationX = 0f
            translationY = 0f
            notifyNavigator()
            return
        }

        val content = drawableContentRect()
        if (content.width() <= 0f || content.height() <= 0f) return

        val nx = normalizedX.coerceIn(0f, 1f)
        val ny = normalizedY.coerceIn(0f, 1f)
        val localX = content.left + content.width() * nx
        val localY = content.top + content.height() * ny
        val px = pivotX
        val py = pivotY

        val desiredX = width / 2f - (px + scaleX * (localX - px))
        val desiredY = height / 2f - (py + scaleY * (localY - py))

        translationX = desiredX.coerceIn(-maxPanX(), maxPanX())
        translationY = desiredY.coerceIn(-maxPanY(), maxPanY())
        notifyNavigator()
    }

    fun visibleBitmapRectNormalized(): RectF {
        if (width <= 0 || height <= 0) return RectF(0f, 0f, 1f, 1f)

        val content = drawableContentRect()
        if (content.width() <= 0f || content.height() <= 0f) return RectF(0f, 0f, 1f, 1f)

        val sx = scaleX.coerceAtLeast(0.001f)
        val sy = scaleY.coerceAtLeast(0.001f)
        val px = pivotX
        val py = pivotY

        val localLeft = (0f - translationX - px) / sx + px
        val localTop = (0f - translationY - py) / sy + py
        val localRight = (width.toFloat() - translationX - px) / sx + px
        val localBottom = (height.toFloat() - translationY - py) / sy + py

        val left = ((localLeft - content.left) / content.width()).coerceIn(0f, 1f)
        val top = ((localTop - content.top) / content.height()).coerceIn(0f, 1f)
        val right = ((localRight - content.left) / content.width()).coerceIn(0f, 1f)
        val bottom = ((localBottom - content.top) / content.height()).coerceIn(0f, 1f)

        return RectF(
            min(left, right),
            min(top, bottom),
            max(left, right),
            max(top, bottom)
        )
    }

    fun clampPan() {
        if (scaleX <= 1.01f || scaleY <= 1.01f) {
            translationX = 0f
            translationY = 0f
        } else {
            translationX = translationX.coerceIn(-maxPanX(), maxPanX())
            translationY = translationY.coerceIn(-maxPanY(), maxPanY())
        }
        notifyNavigator()
    }

    private fun drawableContentRect(): RectF {
        val d = drawable ?: return RectF(0f, 0f, width.toFloat(), height.toFloat())
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (iw <= 0f || ih <= 0f || width <= 0 || height <= 0) {
            return RectF(0f, 0f, width.toFloat(), height.toFloat())
        }

        val fit = min(width / iw, height / ih)
        val displayedWidth = iw * fit
        val displayedHeight = ih * fit
        val left = (width - displayedWidth) / 2f
        val top = (height - displayedHeight) / 2f
        return RectF(left, top, left + displayedWidth, top + displayedHeight)
    }

    private fun maxPanX(): Float {
        val content = drawableContentRect()
        return ((content.width() * scaleX - width) / 2f).coerceAtLeast(0f)
    }

    private fun maxPanY(): Float {
        val content = drawableContentRect()
        return ((content.height() * scaleY - height) / 2f).coerceAtLeast(0f)
    }

    private fun navigator(): FrozenNavigatorView? =
        rootView.findViewById(R.id.navigatorView)

    private fun notifyNavigator() {
        navigator()?.invalidate()
    }

    private fun setStatus(message: String) {
        rootView.findViewById<TextView?>(R.id.statusText)?.text = message
    }

    private fun formatZoom(value: Float): String =
        String.format(Locale.US, "%.1f", value)
}
