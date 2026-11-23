package com.faqxd.livesub.android.transcript

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.subtitle.SrtWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** MIME type of SubRip subtitles. */
const val SRT_MIME_TYPE = "application/x-subrip"

/** What a run's captions can be exported as. */
enum class ExportFormat(
    val key: String,
    @StringRes val label: Int,
    val mimeType: String,
    val suffix: String,
    /** The stream rendered as subtitles; null for the bilingual text transcript. */
    val stream: TranscriptRecorder.Stream?,
) {
    TRANSLATION_SRT(
        "translation_srt", R.string.export_format_translation_srt,
        SRT_MIME_TYPE, ".srt", TranscriptRecorder.Stream.TRANSLATION,
    ),
    ORIGINAL_SRT(
        "original_srt", R.string.export_format_original_srt,
        SRT_MIME_TYPE, ".original.srt", TranscriptRecorder.Stream.ORIGINAL,
    ),
    TEXT("text", R.string.export_format_text, "text/plain", ".txt", null);

    /** Whether [entries] have anything to export in this format. */
    fun isAvailableFor(entries: List<TranscriptRecorder.Entry>): Boolean =
        entries.any { it is TranscriptRecorder.Line && (stream == null || it.stream == stream) }

    companion object {
        fun fromKey(key: String?): ExportFormat? = values().firstOrNull { it.key == key }
    }
}

/**
 * Renders caption entries as SubRip subtitles or a plain-text transcript,
 * and hands the result to the system share sheet (chat apps, notes, cloud
 * drives…) through a [FileProvider] content URI or writes it to a document
 * the user picked.
 */
object TranscriptExporter {

    private const val TAG = "TranscriptExporter"
    private const val EXPORT_DIR = "exports"
    private const val FILE_PREFIX = "LiveBuddy-"
    private const val FILE_TIME_PATTERN = "yyyyMMdd-HHmmss"
    /** Suffix of the transcripts saved as text by earlier versions. */
    const val FILE_SUFFIX = ".txt"

    /**
     * How long an exported file is kept. Share targets read the file
     * asynchronously long after the chooser is dismissed, so deleting
     * younger files would break an in-flight share; only prune old ones.
     */
    private const val EXPORT_RETENTION_MS = 60 * 60 * 1000L

    /**
     * Share [entries] as [kind] through the share sheet, or toast why that
     * failed. Call from the main thread.
     */
    suspend fun shareExport(context: Context, entries: List<TranscriptRecorder.Entry>, kind: ExportFormat) {
        val file = try {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                writeExport(context, render(context, entries, kind, now), exportName(entries, kind, now))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            showFailure(context, e)
            return
        }
        shareFile(context, file, kind.mimeType)
    }

    /**
     * Write [entries] as [kind] to the document at [target] (from the
     * system file picker), then toast the outcome. Call from the main thread.
     */
    suspend fun saveExport(
        context: Context,
        entries: List<TranscriptRecorder.Entry>,
        kind: ExportFormat,
        target: Uri,
    ) {
        try {
            withContext(Dispatchers.IO) {
                val bytes = render(context, entries, kind, System.currentTimeMillis())
                val out = context.contentResolver.openOutputStream(target, "w")
                    ?: throw IOException("can't open $target")
                out.use { it.write(bytes) }
            }
            Toast.makeText(context, R.string.transcript_save_done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            showFailure(context, e)
        }
    }

    /** Open the share sheet for a transcript [file] of [mimeType], or toast why that failed. */
    fun shareFile(context: Context, file: File, mimeType: String = "text/plain") {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(mimeType)
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

    /** The file content of [entries] as [kind]; [now] is the export time named in a text transcript. */
    fun render(
        context: Context,
        entries: List<TranscriptRecorder.Entry>,
        kind: ExportFormat,
        now: Long,
    ): ByteArray {
        val stream = kind.stream ?: return format(context, entries, now).toByteArray(Charsets.UTF_8)
        return SrtWriter.encode(TranscriptCues.build(entries, stream))
    }

    /** Render [entries] as the text transcript: a header, then each session's lines. */
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

    /** Name of a [kind] file for the run that started at [at] (or was exported then). */
    fun fileName(at: Long, kind: ExportFormat = ExportFormat.TEXT): String =
        FILE_PREFIX + SimpleDateFormat(FILE_TIME_PATTERN, Locale.US).format(Date(at)) + kind.suffix

    /** Name of the [kind] export of [entries]: after the start of their run, else [now]. */
    fun exportName(entries: List<TranscriptRecorder.Entry>, kind: ExportFormat, now: Long): String =
        fileName(TranscriptCues.startOf(entries) ?: now, kind)

    /** The time a text transcript's [fileName] was made for; null for other names. */
    fun fileTime(name: String): Long? {
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null
        val time = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        return try {
            SimpleDateFormat(FILE_TIME_PATTERN, Locale.US).parse(time)?.time
        } catch (_: ParseException) {
            null
        }
    }

    /** Write [bytes] to a file named [name] in the cache directory. */
    private fun writeExport(context: Context, bytes: ByteArray, name: String): File {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        // Prune exports older than the retention window. A previous
        // export may still be queued for reading by a share target
        // (cloud drives and chat apps read asynchronously), so its
        // file must survive until then.
        val cutoff = System.currentTimeMillis() - EXPORT_RETENTION_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        return File(dir, name).apply { writeBytes(bytes) }
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
