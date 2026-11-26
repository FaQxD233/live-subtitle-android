package com.faqxd.livesub.android.transcript

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.faqxd.livesub.android.R
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide caption records. The service records each run (one session)
 * into it; when the run ends it is kept as a recent transcript, at most
 * [MAX_RECENT] of them, until the user saves or discards it. The transcript
 * list works on them even while no service runs.
 *
 * A run is journaled as JSON lines in filesDir, one finished entry (a line
 * with its start and end time) per line and only ever appended, so it
 * survives process death; a journal left behind by a killed process is
 * kept like an ended run on the next start. Recent and saved runs both
 * stay journals in filesDir, so any of them can later be exported as
 * subtitles or text, or played. Transcripts that earlier versions saved as
 * text files in the app's external files directory (Android/data) are
 * still listed, and shared through the FileProvider.
 *
 * Main thread only. All disk I/O runs on one background thread in
 * submission order, so a journal is complete before it is kept, and list
 * operations never race each other.
 */
object TranscriptStore {

    private const val TAG = "TranscriptStore"
    /** Journal of the run in progress. */
    private const val JOURNAL_FILE_NAME = "session.jsonl"
    private const val JOURNAL_SUFFIX = ".jsonl"
    /** Recent runs in filesDir, named "<startedAt>_<endedAt>.jsonl". */
    private const val RECENT_DIR = "transcripts/recent"
    /** Saved runs in filesDir, named like the recent ones. */
    private const val SAVED_DIR = "transcripts/saved"
    /** Text transcripts of earlier versions in the external files directory, as in file_paths.xml. */
    private const val TEXT_DIR = "transcripts"
    /** Earlier versions kept every run in one history of JSON lines… */
    private const val LEGACY_FILE_NAME = "transcript.jsonl"
    /** …and before that in one JSON array snapshot, rewritten every 2 s. */
    private const val LEGACY_ARRAY_FILE_NAME = "transcript.json"

    /** Ended runs kept for the user to save or discard; the oldest go first. */
    const val MAX_RECENT = 3

    /** A transcript in the list, by the start of its run. */
    sealed interface Record {
        val file: File
        val startedAt: Long
    }

    /**
     * A run kept as its journal: [saved], or recent (waiting to be saved or
     * discarded). [preview] is one of its captions.
     */
    data class Journal(
        override val file: File,
        override val startedAt: Long,
        val endedAt: Long,
        val preview: String,
        val saved: Boolean,
    ) : Record

    /** A transcript an earlier version saved as a text file of [size] bytes. */
    data class TextFile(override val file: File, override val startedAt: Long, val size: Long) : Record

    private val recorder = TranscriptRecorder().also { r ->
        r.onEntry = { entry -> persist(entry) }
    }
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "transcript-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var onChanged: (() -> Unit)? = null

    /**
     * Keep what an earlier process left behind (an interrupted run, the old
     * history) in the background. Call once, from Application.onCreate.
     */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext.also { appContext = it }
        runIo("journal recovery") { recoverJournal(app) }
        runIo("history migration") { migrateLegacy(app) }
    }

    fun startSession() = recorder.startSession()

    /**
     * The run ends: keep it as a recent transcript if it is worth keeping
     * (see [TranscriptRecorder.EndedSession.isWorthKeeping]), otherwise
     * drop its journal. Making room for it never drops [spare], a recent
     * run in use (the subtitle player shows it). Returns whether it was kept.
     */
    fun endSession(spare: File? = null): Boolean {
        val ended = recorder.endSession() ?: return false
        val app = appContext ?: return false
        val keep = ended.isWorthKeeping
        // Queued after the appends of the lines just finished.
        runIo("keeping the run") {
            val journal = journalFile(app)
            if (keep) keepRecent(app, journal, ended.startedAt, ended.endedAt, spare) else journal.delete()
        }
        return keep
    }

    fun breakLines() = recorder.breakLines()

    fun append(stream: TranscriptRecorder.Stream, text: String) = recorder.append(stream, text)

    /** The run in progress, including the lines still being written. */
    fun snapshot(): List<TranscriptRecorder.Entry> = recorder.snapshot()

    /**
     * Single listener for changes the transcript list didn't make itself (a
     * run kept, the old history migrated); called on the main thread.
     */
    fun setOnChangedListener(listener: (() -> Unit)?) {
        onChanged = listener
    }

    /** Recent runs, newest first. */
    suspend fun recent(): List<Journal> {
        val app = appContext ?: return emptyList()
        return withContext(io) { journals(recentDir(app), saved = false).map { it.withPreview() } }
    }

    /** Saved runs and the text transcripts of earlier versions, newest first. */
    suspend fun saved(): List<Record> {
        val app = appContext ?: return emptyList()
        return withContext(io) {
            val journals = journals(savedDir(app), saved = true).map { it.withPreview() }
            val texts = textDir(app)?.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(TranscriptExporter.FILE_SUFFIX) }
                .map { TextFile(it, TranscriptExporter.fileTime(it.name) ?: it.lastModified(), it.length()) }
            (journals + texts).sortedByDescending { it.startedAt }
        }
    }

    /** Where the text transcripts of earlier versions are; null while shared storage is unavailable. */
    suspend fun textLocation(): File? {
        val app = appContext ?: return null
        return withContext(io) { textDir(app) }
    }

    /**
     * Save a recent run: move its journal, unchanged, to the saved ones.
     * Throws if it is gone (a newer run replaced it) or can't be moved.
     */
    suspend fun save(context: Context, recent: Journal) {
        withContext(io) {
            if (!recent.file.exists()) throw IOException(context.getString(R.string.transcript_gone))
            val dir = savedDir(context).apply { mkdirs() }
            if (!recent.file.renameTo(File(dir, recent.file.name))) {
                throw IOException("can't move ${recent.file.name}")
            }
        }
    }

    /** Discard a recent run or delete a saved transcript. */
    suspend fun delete(record: Record) {
        withContext(io) { record.file.delete() }
    }

    /** The entries of a kept run; throws if it is gone (a newer run replaced it). */
    suspend fun readEntries(context: Context, journal: Journal): List<TranscriptRecorder.Entry> =
        withContext(io) { readJournal(context, journal) }

    /** The transcript's text; a kept run is rendered as the bilingual text transcript. */
    suspend fun readText(context: Context, record: Record): String = withContext(io) {
        when (record) {
            is Journal -> TranscriptExporter.format(
                context, readJournal(context, record), System.currentTimeMillis(),
            )
            is TextFile -> record.file.readText()
        }
    }

    private fun persist(entry: TranscriptRecorder.Entry) {
        val app = appContext ?: return
        val json = encode(entry).toString()
        runIo("transcript append") { journalFile(app).appendText(json + "\n") }
    }

    /** Run [block] on the I/O thread; a failure is logged, not thrown. */
    private fun runIo(what: String, block: () -> Unit) {
        io.executor.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "$what failed: ${e.message}")
            }
        }
    }

    private fun notifyChanged() {
        mainHandler.post { onChanged?.invoke() }
    }

    // ---------- disk (io thread) ----------

    private fun journalFile(context: Context) = File(context.filesDir, JOURNAL_FILE_NAME)

    private fun recentDir(context: Context) = File(context.filesDir, RECENT_DIR)

    private fun savedDir(context: Context) = File(context.filesDir, SAVED_DIR)

    /** Null while shared storage is unavailable. */
    private fun textDir(context: Context): File? = context.getExternalFilesDir(TEXT_DIR)

    /**
     * Move [journal] into the recent runs, dropping the oldest beyond
     * [MAX_RECENT] except [spare] (which then stays one more for a while).
     */
    private fun keepRecent(context: Context, journal: File, startedAt: Long, endedAt: Long, spare: File? = null) {
        val dir = recentDir(context).apply { mkdirs() }
        if (!journal.renameTo(File(dir, "${startedAt}_$endedAt$JOURNAL_SUFFIX"))) {
            throw IOException("can't move ${journal.name}")
        }
        journals(dir, saved = false).drop(MAX_RECENT).filter { it.file != spare }.forEach { it.file.delete() }
        notifyChanged()
    }

    /** The journals in [dir], newest first, without their preview. */
    private fun journals(dir: File, saved: Boolean): List<Journal> =
        dir.listFiles().orEmpty()
            .mapNotNull { file -> journal(file, saved) }
            .sortedByDescending { it.startedAt }

    /** A kept journal is named after its run: "<startedAt>_<endedAt>.jsonl". */
    private fun journal(file: File, saved: Boolean): Journal? {
        if (!file.name.endsWith(JOURNAL_SUFFIX)) return null
        val times = file.name.removeSuffix(JOURNAL_SUFFIX).split('_')
            .map { it.toLongOrNull() ?: return null }
        if (times.size != 2) return null
        return Journal(file, startedAt = times[0], endedAt = times[1], preview = "", saved = saved)
    }

    private fun Journal.withPreview(): Journal = copy(preview = preview(file))

    /** The entries of [journal]; throws if a newer run replaced it meanwhile. */
    private fun readJournal(context: Context, journal: Journal): List<TranscriptRecorder.Entry> {
        if (!journal.file.exists()) throw IOException(context.getString(R.string.transcript_gone))
        return readJournalFile(journal.file)
    }

    /** The run's first translated caption, else its first caption at all. */
    private fun preview(file: File): String {
        var first = ""
        try {
            file.useLines { lines ->
                for (raw in lines) {
                    val line = decode(raw) as? TranscriptRecorder.Line ?: continue
                    if (line.stream == TranscriptRecorder.Stream.TRANSLATION) return line.text
                    if (first.isEmpty()) first = line.text
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "transcript preview failed: ${e.message}")
        }
        return first
    }

    /** A journal a killed process left behind: that run ended with its last write. */
    private fun recoverJournal(context: Context) {
        val journal = journalFile(context)
        if (!journal.exists()) return
        val entries = readJournalFile(journal)
        val ended = TranscriptRecorder.EndedSession(
            startedAt = entries.firstOrNull()?.at ?: 0L,
            endedAt = journal.lastModified(),
            lineCount = entries.count { it is TranscriptRecorder.Line },
        )
        if (ended.isWorthKeeping) {
            keepRecent(context, journal, ended.startedAt, ended.endedAt)
        } else {
            journal.delete()
        }
    }

    /**
     * Earlier versions kept every run in one history. Save each of its runs
     * as a text file, since back then all of them were kept, and delete it.
     * Retried on the next start while shared storage is unavailable.
     */
    private fun migrateLegacy(context: Context) {
        val history = File(context.filesDir, LEGACY_FILE_NAME)
        val snapshot = File(context.filesDir, LEGACY_ARRAY_FILE_NAME)
        if (!history.exists() && !snapshot.exists()) return
        val dir = textDir(context) ?: return
        // The snapshot was converted into the JSON lines history before, so
        // with both present the history holds everything.
        val entries = if (history.exists()) readJournalFile(history) else readLegacy(snapshot)
        for (session in splitSessions(entries)) {
            writeText(context, dir, session.first().at, session)
        }
        history.delete()
        snapshot.delete()
        notifyChanged()
    }

    /** Split a history at its session starts; sessions without lines are left out. */
    private fun splitSessions(
        entries: List<TranscriptRecorder.Entry>,
    ): List<List<TranscriptRecorder.Entry>> {
        val sessions = ArrayList<MutableList<TranscriptRecorder.Entry>>()
        for (entry in entries) {
            if (entry is TranscriptRecorder.Session || sessions.isEmpty()) sessions.add(ArrayList())
            sessions.last().add(entry)
        }
        return sessions.filter { session -> session.any { it is TranscriptRecorder.Line } }
    }

    /** Write the run that started at [startedAt] as a text file named after its start. */
    private fun writeText(
        context: Context,
        dir: File,
        startedAt: Long,
        entries: List<TranscriptRecorder.Entry>,
    ) {
        val text = TranscriptExporter.format(context, entries, System.currentTimeMillis())
        File(dir, TranscriptExporter.fileName(startedAt)).writeText(text)
    }

    /** Decode a JSON lines file; a line cut short by a crash mid-write simply doesn't decode. */
    private fun readJournalFile(file: File): List<TranscriptRecorder.Entry> {
        val entries = ArrayList<TranscriptRecorder.Entry>()
        file.forEachLine { line -> decode(line)?.let(entries::add) }
        return entries
    }

    /** Convert the earlier array snapshot (first element: session start). */
    private fun readLegacy(legacy: File): List<TranscriptRecorder.Entry> {
        val arr = JSONArray(legacy.readText())
        val entries = ArrayList<TranscriptRecorder.Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.has("session_started_at")) {
                val at = o.optLong("session_started_at", 0L)
                if (at > 0) entries.add(TranscriptRecorder.Session(at))
                continue
            }
            val stream = runCatching {
                TranscriptRecorder.Stream.valueOf(o.getString("stream"))
            }.getOrNull() ?: continue
            entries.add(TranscriptRecorder.Line(stream, o.optLong("started_at"), o.optString("text")))
        }
        return entries
    }

    private fun encode(entry: TranscriptRecorder.Entry): JSONObject = when (entry) {
        is TranscriptRecorder.Session -> JSONObject().put("session", entry.at)
        is TranscriptRecorder.Line -> JSONObject()
            .put("stream", entry.stream.name)
            .put("at", entry.at)
            .put("text", entry.text)
            .apply { entry.end?.let { put("end", it) } }
    }

    private fun decode(raw: String): TranscriptRecorder.Entry? = try {
        val o = JSONObject(raw)
        if (o.has("session")) {
            TranscriptRecorder.Session(o.getLong("session"))
        } else {
            TranscriptRecorder.Line(
                TranscriptRecorder.Stream.valueOf(o.getString("stream")),
                o.getLong("at"),
                o.getString("text"),
                // Journals written before end times were recorded have none.
                if (o.has("end")) o.getLong("end") else null,
            )
        }
    } catch (_: Exception) {
        null
    }
}
