package com.mixtervee.fastmagnifier

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.camera.view.PreviewView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Quietly selects one of the existing enhancement profiles while the camera is live.
 * The classifier is deliberately conservative and uses only inexpensive local image
 * statistics so it does not add a heavy model or network dependency.
 */
class AutoEnhanceControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class Profile { TEXT, DETAIL, DISTANCE }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var classificationInFlight = false
    private var lastApplied = Profile.DETAIL

    private val classifyRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            classifyLivePreview()
            handler.postDelayed(this, 1100L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.postDelayed(classifyRunnable, 300L)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(classifyRunnable)
        worker.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun classifyLivePreview() {
        if (classificationInFlight) return

        val root = rootView
        val frozen = root.findViewById<View>(R.id.frozenImage)?.visibility == View.VISIBLE
        if (frozen) return

        val preview = root.findViewById<PreviewView>(R.id.previewView) ?: return
        if (preview.visibility != View.VISIBLE) return
        val bitmap = preview.bitmap ?: return

        classificationInFlight = true
        worker.execute {
            val profile = try {
                classify(bitmap)
            } catch (_: Throwable) {
                Profile.DETAIL
            }

            post {
                classificationInFlight = false
                if (!isAttachedToWindow) return@post
                val stillLive = rootView.findViewById<View>(R.id.frozenImage)?.visibility != View.VISIBLE
                if (!stillLive || profile == lastApplied) return@post

                val buttonId = when (profile) {
                    Profile.TEXT -> R.id.textMode
                    Profile.DETAIL -> R.id.detailMode
                    Profile.DISTANCE -> R.id.distanceMode
                }
                rootView.findViewById<View>(buttonId)?.performClick()
                lastApplied = profile
            }
        }
    }

    private fun classify(bitmap: Bitmap): Profile {
        if (bitmap.width < 8 || bitmap.height < 8) return Profile.DETAIL

        val stepX = max(1, bitmap.width / 56)
        val stepY = max(1, bitmap.height / 56)
        var count = 0L
        var lumaSum = 0.0
        var lumaSquaredSum = 0.0
        var saturationSum = 0.0
        var edgeSum = 0.0
        var edgeCount = 0L

        var y = stepY / 2
        while (y < bitmap.height) {
            var previousLuma = -1
            var x = stepX / 2
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                val luma = (77 * r + 150 * g + 29 * b) shr 8
                val hi = max(r, max(g, b))
                val lo = minOf(r, g, b)

                lumaSum += luma
                lumaSquaredSum += luma.toDouble() * luma
                saturationSum += (hi - lo) / 255.0
                count++

                if (previousLuma >= 0) {
                    edgeSum += abs(luma - previousLuma)
                    edgeCount++
                }
                previousLuma = luma
                x += stepX
            }
            y += stepY
        }

        if (count == 0L) return Profile.DETAIL
        val mean = lumaSum / count
        val variance = (lumaSquaredSum / count - mean * mean).coerceAtLeast(0.0)
        val contrast = sqrt(variance)
        val saturation = saturationSum / count
        val edge = if (edgeCount > 0) edgeSum / edgeCount else 0.0

        // Strong black/white structure is usually print, labels, menus, or paperwork.
        if (saturation < 0.13 && contrast > 48.0 && edge > 24.0) {
            return Profile.TEXT
        }

        // Low contrast / sparse detail benefits from the slightly stronger distance profile.
        if (contrast < 27.0 || edge < 13.0) {
            return Profile.DISTANCE
        }

        // Most scenes stay on the balanced profile.
        return Profile.DETAIL
    }
}
