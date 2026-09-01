package com.mixtervee.fastmagnifier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Keeps a small private rolling history of recently frozen images.
 * These are convenience copies for viewing inside Fast Magnifier; the normal Save
 * action remains the way to put a permanent image in Pictures/Fast Magnifier.
 */
class RecentCapturesController(
    private val activity: AppCompatActivity,
    private val status: (String) -> Unit,
    private val onSelected: (Bitmap) -> Unit
) {
    companion object {
        private const val MAX_CAPTURES = 8
        private const val MAX_DIMENSION = 1920
        private const val JPEG_QUALITY = 90
        private const val DIRECTORY_NAME = "recent_captures"
        private const val FILE_PREFIX = "capture_"
        private val io = Executors.newSingleThreadExecutor()

        /** Called when a new freeze first becomes visible. */
        fun record(context: Context, bitmap: Bitmap) {
            io.execute {
                try {
                    val folder = folder(context)
                    if (!folder.exists() && !folder.mkdirs()) return@execute

                    val stored = scaleForHistory(bitmap)
                    val file = File(folder, "$FILE_PREFIX${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { stream ->
                        stored.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                    }
                    if (stored !== bitmap) stored.recycle()
                    recentFiles(context).drop(MAX_CAPTURES).forEach { it.delete() }
                } catch (_: Throwable) {
                    // History is convenience-only; never interrupt magnifier use if it fails.
                }
            }
        }

        private fun folder(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

        private fun recentFiles(context: Context): List<File> {
            val folder = folder(context)
            if (!folder.exists()) return emptyList()
            return folder.listFiles()
                ?.filter {
                    it.isFile && it.name.startsWith(FILE_PREFIX) && it.extension.equals("jpg", true)
                }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }

        private fun scaleForHistory(src: Bitmap): Bitmap {
            val maxDim = max(src.width, src.height)
            if (maxDim <= MAX_DIMENSION) return src
            val scale = MAX_DIMENSION.toFloat() / maxDim
            return Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }
    }

    fun count(): Int = recentFiles(activity).size

    fun show() {
        val files = recentFiles(activity)
        if (files.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Recent captures")
                .setMessage("No recent captures yet. Frozen images will appear here automatically.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val formatter = SimpleDateFormat("MMM d  •  h:mm a", Locale.getDefault())
        val labels = files.map { formatter.format(Date(it.lastModified())) }.toTypedArray()

        MaterialAlertDialogBuilder(activity)
            .setTitle("Recent captures")
            .setItems(labels) { dialog, which ->
                dialog.dismiss()
                files.getOrNull(which)?.let { load(it) }
            }
            .setNeutralButton("Clear all") { _, _ -> confirmClear() }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun load(file: File) {
        status("Opening recent capture…")
        io.execute {
            val bitmap = try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (_: Throwable) {
                null
            }

            activity.runOnUiThread {
                if (bitmap == null) {
                    status("Could not open recent capture")
                } else {
                    onSelected(bitmap)
                }
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Clear recent captures?")
            .setMessage("This clears only Fast Magnifier's recent-history copies. Pictures you saved normally are not affected.")
            .setPositiveButton("Clear") { _, _ -> clearAll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAll() {
        io.execute {
            recentFiles(activity).forEach { it.delete() }
            activity.runOnUiThread { status("Recent captures cleared") }
        }
    }
}
