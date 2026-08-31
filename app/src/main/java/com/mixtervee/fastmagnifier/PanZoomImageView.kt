package com.mixtervee.fastmagnifier

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max

class PanZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private companion object {
        const val PAN_SENSITIVITY = 10.0f
    }

    private var twoFingerGestureActive = false
    private var suppressUntilAllUp = false
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    twoFingerGestureActive = true
                    suppressUntilAllUp = true
                    lastCenterX = pointerCenterX(event)
                    lastCenterY = pointerCenterY(event)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFingerGestureActive && event.pointerCount >= 2) {
                    val centerX = pointerCenterX(event)
                    val centerY = pointerCenterY(event)
                    val dx = (centerX - lastCenterX) * PAN_SENSITIVITY
                    val dy = (centerY - lastCenterY) * PAN_SENSITIVITY

                    if (scaleX > 1.01f || scaleY > 1.01f) {
                        translationX = (translationX + dx).coerceIn(-maxPanX(), maxPanX())
                        translationY = (translationY + dy).coerceIn(-maxPanY(), maxPanY())
                    }

                    lastCenterX = centerX
                    lastCenterY = centerY
                    return true
                }

                if (suppressUntilAllUp) return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (suppressUntilAllUp) {
                    twoFingerGestureActive = false
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (suppressUntilAllUp) {
                    twoFingerGestureActive = false
                    suppressUntilAllUp = false
                    return true
                }
            }
        }

        return super.dispatchTouchEvent(event)
    }

    private fun pointerCenterX(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getX(i)
        return sum / max(event.pointerCount, 1)
    }

    private fun pointerCenterY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / max(event.pointerCount, 1)
    }

    private fun maxPanX(): Float = width * (scaleX - 1f).coerceAtLeast(0f) / 2f

    private fun maxPanY(): Float = height * (scaleY - 1f).coerceAtLeast(0f) / 2f
}
