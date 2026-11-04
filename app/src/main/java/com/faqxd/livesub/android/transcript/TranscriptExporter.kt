package com.faqxd.livesub.android.transcript

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.faqxd.livesub.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders caption entries as a plain-text document, and hands transcript
 * files to the system share sheet (chat apps, notes, cloud drives, "save to
 * files"…) through a [FileProvider] content URI.
 */
object TranscriptExporter {

    private const val TAG = "TranscriptExporter"
    private const val EXPORT_DIR = "exports"
    private const val FILE_PREFIX = "LiveBuddy-"
    private const val FILE_TIME_PATTERN = "yyyyMMdd-HHmmss"
    const val FILE_SUFFIX = ".txt"

    /**
     * How long an exported file is kept. Share targets read the file
     * asynchronously long after the chooser is dismissed, so deleting
     * younger files would break an in-flight share; only prune old ones.
     */
    private const val EXPORT_RETENTION_MS = 60 * 60 * 1000L

    /**
     * Export [entries] (the run in progress) through the share sheet, or
     * toast that there is nothing to export yet / why it failed. Call from
     * the main thread. Works from a Service (the chooser gets a new task).
     */
    suspend fun shareTranscript(context: Context, entries: List<TranscriptRecorder.Entry>) {
        if (entries.none { it is TranscriptRecorder.Line }) {
            Toast.makeText(context, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val exportedAt = System.currentTimeMillis()
        val file = try {
            withContext(Dispatchers.IO) { writeExport(context, entries, exportedAt) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            showFailure(context, e)
            return
        }
        shareFile(context, file)
    }

    /** Open the share sheet for a transcript [file], or toast why that failed. */
    fun shareFile(context: Context, file: File) {
        try {
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
        } catch (e: Exception) {
            showFailure(context, e)
        }
    }

    /** Render [entries] as the exported text: a header, then each session's lines. */
    fun format(context: Context, entries: List<TranscriptRecorder.Entry>, exportedAt: Long): String {
        val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val originalLabel = context.getString(R.string.transcript_label_original)
        val translationLabel = context.getString(R.string.transcript_label_translation)
        return buildString {
            appendLine(context.getString(R.string.transcript_title))
            appendLine(context.getString(R.string.transcript_exported_at, dateTime.format(Date(exportedAt))))
            appendLine()
            var first = true
            for (entry in entries) {
                when (entry) {
                    is TranscriptRecorder.Session -> {
                        // A blank line separates sessions.
                        if (!first) appendLine()
                        appendLine(
                            context.getString(R.string.transcript_session, dateTime.format(Date(entry.at)))
                        )
                    }
                    is TranscriptRecorder.Line -> {
                        val label = when (entry.stream) {
                            TranscriptRecorder.Stream.ORIGINAL -> originalLabel
                            TranscriptRecorder.Stream.TRANSLATION -> translationLabel
                        }
                        appendLine(
                            context.getString(
                                R.string.transcript_line,
                                time.format(Date(entry.at)),
                                label,
                                entry.text,
                            )
                        )
                    }
                }
                first = false
            }
        }
    }

    /** Name of a transcript file for [at]: when it was exported, or when its saved run started. */
    fun fileName(at: Long): String =
        FILE_PREFIX + SimpleDateFormat(FILE_TIME_PATTERN, Locale.US).format(Date(at)) + FILE_SUFFIX

    /** The time a [fileName] was made for; null for other names. */
    fun fileTime(name: String): Long? {
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null
        val time = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        return try {
            SimpleDateFormat(FILE_TIME_PATTERN, Locale.US).parse(time)?.time
        } catch (_: ParseException) {
            null
        }
    }

    /** Write the formatted [entries] to a file in the cache directory. */
    private fun writeExport(
        context: Context,
        entries: List<TranscriptRecorder.Entry>,
        exportedAt: Long,
    ): File {
        val text = format(context, entries, exportedAt)
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        // Prune exports older than the retention window. A previous
        // export may still be queued for reading by a share target
        // (cloud drives and chat apps read asynchronously), so its
        // file must survive until then.
        val cutoff = System.currentTimeMillis() - EXPORT_RETENTION_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        return File(dir, fileName(exportedAt)).apply { writeText(text) }
    }

    private fun showFailure(context: Context, e: Exception) {
        Log.e(TAG, "transcript export failed", e)
        Toast.makeText(
            context,
            context.getString(R.string.export_failed, e.message ?: e.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
    }
}
