package com.mixtervee.fastmagnifier

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OcrController(
    private val activity: AppCompatActivity,
    private val status: (String) -> Unit
) {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun recognize(bitmap: android.graphics.Bitmap, sourceLabel: String, onFinished: () -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (text.isBlank()) {
                    status("No text recognized in $sourceLabel")
                } else {
                    status("Text recognized from $sourceLabel")
                    showResult(text)
                }
            }
            .addOnFailureListener { error ->
                status("Text recognition failed: ${error.javaClass.simpleName}")
            }
            .addOnCompleteListener {
                onFinished()
            }
    }

    fun close() {
        recognizer.close()
    }

    private fun showResult(text: String) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_ocr_result, null)
        view.findViewById<TextView>(R.id.ocrResultText).text = text

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .create()

        view.findViewById<MaterialButton>(R.id.ocrCopyButton).setOnClickListener {
            copyText(text)
        }
        view.findViewById<MaterialButton>(R.id.ocrSaveButton).setOnClickListener {
            saveText(text)
        }
        view.findViewById<MaterialButton>(R.id.ocrShareButton).setOnClickListener {
            shareText(text)
        }
        view.findViewById<MaterialButton>(R.id.ocrCloseButton).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun copyText(text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Recognized text", text))
        status("Recognized text copied")
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Fast Magnifier recognized text")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, "Share recognized text"))
    }

    private fun saveText(text: String) {
        status("Saving recognized text…")
        Thread {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val name = "FastMagnifier_OCR_$stamp.txt"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveWithMediaStore(name, text)
                    activity.runOnUiThread {
                        status("Text saved to Documents/Fast Magnifier/OCR")
                    }
                } else {
                    val base = activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                        ?: error("Documents folder unavailable")
                    val folder = File(base, "Fast Magnifier/OCR")
                    if (!folder.exists() && !folder.mkdirs()) error("Could not create OCR folder")
                    File(folder, name).writeText(text, Charsets.UTF_8)
                    activity.runOnUiThread {
                        status("Text saved to app Documents/Fast Magnifier/OCR")
                    }
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    status("Text save failed: ${t.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun saveWithMediaStore(name: String, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOCUMENTS}/Fast Magnifier/OCR"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = activity.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Could not create text file")

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(text)
                }
            } ?: error("Could not open text file")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
