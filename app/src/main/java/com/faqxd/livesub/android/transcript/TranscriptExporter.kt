package com.faqxd.livesub.android.transcript

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.faqxd.livesub.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a [TranscriptRecorder] snapshot into a plain-text document and hands
 * it to the system share sheet (chat apps, notes, cloud drives, "save to
 * files"…) through a [FileProvider] content URI.
 */
object TranscriptExporter {

    private const val EXPORT_DIR = "exports"

    /**
     * How long an exported file is kept. Share targets read the file
     * asynchronously long after the chooser is dismissed, so deleting
     * younger files would break an in-flight share; only prune old ones.
     */
    private const val EXPORT_RETENTION_MS = 60 * 60 * 1000L

    /** Render [lines] as the exported text, with a small header. */
    fun format(
        context: Context,
        lines: List<TranscriptRecorder.Line>,
        startedAt: Long,
        exportedAt: Long,
    ): String {
        val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val originalLabel = context.getString(R.string.transcript_label_original)
        val translationLabel = context.getString(R.string.transcript_label_translation)
        return buildString {
            appendLine(context.getString(R.string.transcript_title))
            appendLine(context.getString(R.string.transcript_started_at, dateTime.format(Date(startedAt))))
            appendLine(context.getString(R.string.transcript_exported_at, dateTime.format(Date(exportedAt))))
            appendLine()
            for (line in lines) {
                val label = when (line.stream) {
                    TranscriptRecorder.Stream.ORIGINAL -> originalLabel
                    TranscriptRecorder.Stream.TRANSLATION -> translationLabel
                }
                appendLine(
                    context.getString(
                        R.string.transcript_line,
                        time.format(Date(line.startedAt)),
                        label,
                        line.text,
                    )
                )
            }
        }
    }

    /**
     * Write [text] to a file in the cache directory and open the share sheet
     * for it. Must be called from the main thread; the file is written on
     * [Dispatchers.IO]. Works from a Service (the chooser gets a new task).
     */
    suspend fun share(context: Context, text: String, exportedAt: Long) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            // Prune exports older than the retention window. A previous
            // export may still be queued for reading by a share target
            // (cloud drives and chat apps read asynchronously), so its
            // file must survive until then.
            val cutoff = System.currentTimeMillis() - EXPORT_RETENTION_MS
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            File(dir, fileName(exportedAt)).apply { writeText(text) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData carries the read grant through the chooser to the target.
        send.clipData = ClipData.newRawUri(file.name, uri)
        val chooser = Intent.createChooser(send, context.getString(R.string.export_chooser_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun fileName(exportedAt: Long): String =
        "LiveBuddy-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(exportedAt)) + ".txt"
}
