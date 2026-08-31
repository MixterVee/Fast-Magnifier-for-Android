package com.mixtervee.fastmagnifier

import android.graphics.Bitmap
import android.graphics.Matrix
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
                        val oriented = rotate(raw, image.imageInfo.rotationDegrees)
                        val working = scaleForQuality(oriented)
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
