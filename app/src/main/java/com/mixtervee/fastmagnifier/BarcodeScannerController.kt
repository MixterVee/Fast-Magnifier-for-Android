package com.mixtervee.fastmagnifier

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlin.math.max

class BarcodeScannerController(private val context: Context) {

    data class DetectedCode(
        val value: String,
        val formatLabel: String,
        val title: String,
        val isQr: Boolean,
        val isUrl: Boolean,
        val isProductBarcode: Boolean
    )

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_PDF417,
            Barcode.FORMAT_AZTEC
        )
        .build()

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(options)
    @Volatile private var inFlight = false
    @Volatile private var closed = false

    fun scan(bitmap: Bitmap, onDetected: (DetectedCode?) -> Unit) {
        if (closed || inFlight || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return

        val prepared = prepareBitmap(bitmap) ?: return
        inFlight = true

        scanner.process(InputImage.fromBitmap(prepared, 0))
            .addOnSuccessListener { barcodes ->
                if (!closed) {
                    val code = barcodes
                        .asSequence()
                        .mapNotNull(::toDetectedCode)
                        .firstOrNull()
                    onDetected(code)
                }
            }
            .addOnFailureListener {
                if (!closed) onDetected(null)
            }
            .addOnCompleteListener {
                inFlight = false
                if (!prepared.isRecycled) prepared.recycle()
            }
    }

    private fun prepareBitmap(source: Bitmap): Bitmap? {
        return runCatching {
            val maxDimension = max(source.width, source.height)
            if (maxDimension <= 1600) {
                source.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                val ratio = 1600f / maxDimension
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * ratio).toInt().coerceAtLeast(1),
                    (source.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            }
        }.getOrNull()
    }

    private fun toDetectedCode(barcode: Barcode): DetectedCode? {
        val value = when (barcode.valueType) {
            Barcode.TYPE_URL -> barcode.url?.url
            else -> barcode.rawValue
        }?.trim().orEmpty()

        if (value.isEmpty()) return null

        val isUrl = barcode.valueType == Barcode.TYPE_URL ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)

        val isProduct = barcode.format == Barcode.FORMAT_UPC_A ||
            barcode.format == Barcode.FORMAT_UPC_E ||
            barcode.format == Barcode.FORMAT_EAN_8 ||
            barcode.format == Barcode.FORMAT_EAN_13

        return DetectedCode(
            value = value,
            formatLabel = formatLabel(barcode.format),
            title = if (barcode.format == Barcode.FORMAT_QR_CODE) {
                context.getString(R.string.qr_code_detected)
            } else {
                context.getString(R.string.code_format_detected, formatLabel(barcode.format))
            },
            isQr = barcode.format == Barcode.FORMAT_QR_CODE,
            isUrl = isUrl,
            isProductBarcode = isProduct
        )
    }

    private fun formatLabel(format: Int): String = context.getString(
        when (format) {
            Barcode.FORMAT_QR_CODE -> R.string.format_qr
            Barcode.FORMAT_UPC_A -> R.string.format_upca
            Barcode.FORMAT_UPC_E -> R.string.format_upce
            Barcode.FORMAT_EAN_8 -> R.string.format_ean8
            Barcode.FORMAT_EAN_13 -> R.string.format_ean13
            Barcode.FORMAT_CODE_39 -> R.string.format_code39
            Barcode.FORMAT_CODE_128 -> R.string.format_code128
            Barcode.FORMAT_DATA_MATRIX -> R.string.format_datamatrix
            Barcode.FORMAT_PDF417 -> R.string.format_pdf417
            Barcode.FORMAT_AZTEC -> R.string.format_aztec
            else -> R.string.format_barcode
        }
    )

    fun close() {
        closed = true
        scanner.close()
    }
}
