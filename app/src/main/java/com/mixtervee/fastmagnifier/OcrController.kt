package com.mixtervee.fastmagnifier

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var speechChunks: List<String> = emptyList()
    private var speechIndex = 0
    private var speechPaused = false
    private var speechStopped = true
    private var speechSession = 0
    private var speakButton: MaterialButton? = null
    private var stopSpeechButton: MaterialButton? = null

    init {
        textToSpeech = TextToSpeech(activity) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val engine = textToSpeech
                val languageResult = engine?.setLanguage(Locale.getDefault())
                    ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                installSpeechListener()
            } else {
                ttsReady = false
            }
            activity.runOnUiThread {
                speakButton?.isEnabled = ttsReady
            }
        }
    }

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
        stopSpeech(updateStatus = false)
        textToSpeech?.shutdown()
        textToSpeech = null
        recognizer.close()
    }

    private fun showResult(text: String) {
        stopSpeech(updateStatus = false)

        val view = activity.layoutInflater.inflate(R.layout.dialog_ocr_result, null)
        view.findViewById<TextView>(R.id.ocrResultText).text = text

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .create()

        speakButton = view.findViewById<MaterialButton>(R.id.ocrSpeakButton).apply {
            isEnabled = ttsReady
            text = "Read Aloud"
            setOnClickListener {
                when {
                    speechStopped -> startSpeech(text)
                    speechPaused -> resumeSpeech()
                    else -> pauseSpeech()
                }
            }
        }

        stopSpeechButton = view.findViewById<MaterialButton>(R.id.ocrStopSpeechButton).apply {
            isEnabled = false
            setOnClickListener { stopSpeech(updateStatus = true) }
        }

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

        dialog.setOnDismissListener {
            stopSpeech(updateStatus = false)
            speakButton = null
            stopSpeechButton = null
        }
        dialog.show()
    }

    private fun installSpeechListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                val session = utteranceId?.substringBefore(':')?.toIntOrNull() ?: return
                if (session != speechSession || speechPaused || speechStopped) return

                activity.runOnUiThread {
                    if (session != speechSession || speechPaused || speechStopped) return@runOnUiThread
                    speechIndex++
                    if (speechIndex >= speechChunks.size) {
                        finishSpeech()
                    } else {
                        speakCurrentChunk()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                activity.runOnUiThread {
                    stopSpeech(updateStatus = false)
                    status("Could not read recognized text aloud")
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onError(utteranceId)
            }
        })
    }

    private fun startSpeech(text: String) {
        if (!ttsReady || textToSpeech == null) {
            status("Text-to-speech voice is not ready on this device")
            return
        }

        speechChunks = chunkForSpeech(text)
        if (speechChunks.isEmpty()) return

        speechSession++
        speechIndex = 0
        speechPaused = false
        speechStopped = false
        speakButton?.text = "Pause"
        stopSpeechButton?.isEnabled = true
        status("Reading recognized text aloud")
        speakCurrentChunk()
    }

    private fun pauseSpeech() {
        if (speechStopped || speechPaused) return
        textToSpeech?.stop()
        speechPaused = true
        speakButton?.text = "Resume"
        stopSpeechButton?.isEnabled = true
        status("Reading paused")
    }

    private fun resumeSpeech() {
        if (speechStopped || !speechPaused) return
        speechPaused = false
        speakButton?.text = "Pause"
        stopSpeechButton?.isEnabled = true
        status("Reading recognized text aloud")
        speakCurrentChunk()
    }

    private fun stopSpeech(updateStatus: Boolean) {
        textToSpeech?.stop()
        speechSession++
        speechChunks = emptyList()
        speechIndex = 0
        speechPaused = false
        speechStopped = true
        speakButton?.text = "Read Aloud"
        speakButton?.isEnabled = ttsReady
        stopSpeechButton?.isEnabled = false
        if (updateStatus) status("Reading stopped")
    }

    private fun finishSpeech() {
        speechChunks = emptyList()
        speechIndex = 0
        speechPaused = false
        speechStopped = true
        speakButton?.text = "Read Aloud"
        speakButton?.isEnabled = ttsReady
        stopSpeechButton?.isEnabled = false
        status("Finished reading recognized text")
    }

    private fun speakCurrentChunk() {
        if (speechStopped || speechPaused || speechIndex !in speechChunks.indices) return
        val utteranceId = "$speechSession:$speechIndex"
        textToSpeech?.speak(
            speechChunks[speechIndex],
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
    }

    private fun chunkForSpeech(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val maxChars = 420
        var remaining = normalized

        while (remaining.isNotBlank()) {
            if (remaining.length <= maxChars) {
                chunks += remaining.trim()
                break
            }

            val candidate = remaining.substring(0, maxChars)
            val splitAt = maxOf(
                candidate.lastIndexOf(". "),
                candidate.lastIndexOf("? "),
                candidate.lastIndexOf("! "),
                candidate.lastIndexOf('\n'),
                candidate.lastIndexOf(' ')
            ).takeIf { it >= maxChars / 2 } ?: maxChars

            val end = (splitAt + 1).coerceAtMost(remaining.length)
            chunks += remaining.substring(0, end).trim()
            remaining = remaining.substring(end).trimStart()
        }

        return chunks.filter { it.isNotBlank() }
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
