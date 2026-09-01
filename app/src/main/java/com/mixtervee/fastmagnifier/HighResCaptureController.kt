package com.mixtervee.fastmagnifier

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class HighResCaptureController(
    private val mainExecutor: Executor
) {
    private companion object {
        const val MAX_WORKING_DIMENSION = 2560
    }

    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null

    fun createUseCase(targetRotation: Int): ImageCapture {
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(targetRotation)
            .build()
            .also { imageCapture = it }
    }

    fun capture(
        targetAspectRatio: Float,
        onReady: (Bitmap) -> Unit,
        onError: (ImageCaptureException) -> Unit
    ) {
        val capture = imageCapture ?: return

        capture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val raw = image.toBitmap()
                        val cameraCropped = cropToImageCropRect(raw, image.cropRect)
                        val oriented = rotate(cameraCropped, image.imageInfo.rotationDegrees)
                        val framed = cropToAspectRatio(oriented, targetAspectRatio)
                        val working = scaleForQuality(framed)
                        mainExecutor.execute { onReady(working) }
                    } catch (t: Throwable) {
                        val wrapped = ImageCaptureException(
                            ImageCapture.ERROR_UNKNOWN,
                            "Could not prepare high-resolution capture",
                            t
                        )
                        mainExecutor.execute { onError(wrapped) }
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    mainExecutor.execute { onError(exception) }
                }
            }
        )
    }

    fun close() {
        imageCapture = null
        captureExecutor.shutdownNow()
    }

    private fun cropToImageCropRect(source: Bitmap, requested: Rect): Bitmap {
        val left = requested.left.coerceIn(0, source.width - 1)
        val top = requested.top.coerceIn(0, source.height - 1)
        val right = requested.right.coerceIn(left + 1, source.width)
        val bottom = requested.bottom.coerceIn(top + 1, source.height)

        if (left == 0 && top == 0 && right == source.width && bottom == source.height) {
            return source
        }

        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun cropToAspectRatio(source: Bitmap, targetAspectRatio: Float): Bitmap {
        if (targetAspectRatio <= 0f || source.width <= 0 || source.height <= 0) return source

        val currentAspect = source.width.toFloat() / source.height.toFloat()
        if (kotlin.math.abs(currentAspect - targetAspectRatio) < 0.002f) return source

        return if (currentAspect > targetAspectRatio) {
            val cropWidth = (source.height * targetAspectRatio)
                .toInt()
                .coerceIn(1, source.width)
            val left = ((source.width - cropWidth) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(source, left, 0, cropWidth, source.height)
        } else {
            val cropHeight = (source.width / targetAspectRatio)
                .toInt()
                .coerceIn(1, source.height)
            val top = ((source.height - cropHeight) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(source, 0, top, source.width, cropHeight)
        }
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source

        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
    }

    private fun scaleForQuality(source: Bitmap): Bitmap {
        val maxDim = max(source.width, source.height)
        if (maxDim <= MAX_WORKING_DIMENSION) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val scale = MAX_WORKING_DIMENSION.toFloat() / maxDim
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
