package com.mixtervee.fastmagnifier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PanZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var bitmapSwapGeneration = 0
    private var lastReleasedRawX = Float.NaN
    private var lastReleasedRawY = Float.NaN

    // Keep the image point the user is inspecting stable while the scale changes.
    // Pixel translations mean different things at different zoom levels, so simply
    // clamping the old translation makes zooming unintentionally pan the picture.
    private var desiredCenterX = 0.5f
    private var desiredCenterY = 0.5f

    /**
     * Remember the last completed touch in screen coordinates. The frozen image
     * itself is scaled and translated, so local event coordinates shift as the
     * viewport moves. Screen coordinates stay stable and can be compared directly
     * with the navigator's actual on-screen rectangle.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            lastReleasedRawX = event.rawX
            lastReleasedRawY = event.rawY
        }
        return super.dispatchTouchEvent(event)
    }

    fun lastReleasedRawPosition(): Pair<Float, Float>? {
        if (lastReleasedRawX.isNaN() || lastReleasedRawY.isNaN()) return null
        return lastReleasedRawX to lastReleasedRawY
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this || visibility != View.VISIBLE) return

        // A newly shown frozen image starts centered. Full View does not change the
        // frozen image visibility, so this does not disturb Full View navigation.
        desiredCenterX = 0.5f
        desiredCenterY = 0.5f

        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return
        RecentCapturesController.record(context.applicationContext, bitmap)
    }

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

    fun panToNormalized(normalizedX: Float, normalizedY: Float) {
        val nx = normalizedX.coerceIn(0f, 1f)
        val ny = normalizedY.coerceIn(0f, 1f)

        if (width <= 0 || height <= 0 || scaleX <= 1.01f || scaleY <= 1.01f) {
            desiredCenterX = 0.5f
            desiredCenterY = 0.5f
            translationX = 0f
            translationY = 0f
            notifyNavigator()
            return
        }

        val content = drawableContentRect()
        if (content.width() <= 0f || content.height() <= 0f) return

        // Remember the requested image-space center, not the current pixel offset.
        // If an edge prevents this center at a low zoom, keeping the requested point
        // lets a reversed zoom gesture return smoothly instead of ratcheting away.
        desiredCenterX = nx
        desiredCenterY = ny

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
            desiredCenterX = 0.5f
            desiredCenterY = 0.5f
            translationX = 0f
            translationY = 0f
            notifyNavigator()
            return
        }

        // A direct reset elsewhere in the app sets translation back to zero. Treat
        // that as a centered viewport so a later zoom cannot resurrect an old pan.
        if (abs(translationX) < 0.5f && abs(translationY) < 0.5f) {
            desiredCenterX = 0.5f
            desiredCenterY = 0.5f
        }

        // Recompute the translation from the remembered image-space center at the
        // NEW scale. This is the key difference from clamping the old pixel offset.
        panToNormalized(desiredCenterX, desiredCenterY)
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
}
