package com.faqxd.livesub.android.transcript

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide caption history: the service records into it, the main
 * screen exports or clears it even while no service runs.
 *
 * Persisted as JSON lines in filesDir, one finished entry per line and
 * only ever appended, so it survives process death and the write cost
 * doesn't grow with the history; a file beyond the cap is compacted when
 * loaded. Main thread only; the disk I/O runs on one background thread in
 * submission order.
 */
object TranscriptStore {

    private const val TAG = "TranscriptStore"
    private const val FILE_NAME = "transcript.jsonl"
    /** Earlier format: one JSON array snapshot, rewritten every 2 s. */
    private const val LEGACY_FILE_NAME = "transcript.json"

    private val recorder = TranscriptRecorder().also { r ->
        r.onEntry = { entry -> persist(entry) }
    }
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "transcript-io").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dir: File? = null
    // Bumped by clear(): a load finishing afterwards must not restore the old history.
    private var generation = 0

    /** Load the saved history in the background. Call once, from Application.onCreate. */
    fun init(context: Context) {
        if (dir != null) return
        val filesDir = context.filesDir.also { dir = it }
        val loadGeneration = generation
        io.execute {
            val loaded = load(filesDir)
            mainHandler.post { if (generation == loadGeneration) recorder.restore(loaded) }
        }
    }

    fun startSession() = recorder.startSession()

    fun endSession() = recorder.endSession()

    fun breakLines() = recorder.breakLines()

    fun append(stream: TranscriptRecorder.Stream, text: String) = recorder.append(stream, text)

    fun snapshot(): List<TranscriptRecorder.Entry> = recorder.snapshot()

    /** Delete the whole history, on disk too. */
    fun clear() {
        generation++
        recorder.clear()
        val d = dir ?: return
        io.execute { File(d, FILE_NAME).delete() }
    }

    private fun persist(entry: TranscriptRecorder.Entry) {
        val d = dir ?: return
        val json = encode(entry).toString()
        io.execute {
            try {
                File(d, FILE_NAME).appendText(json + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "transcript append failed: ${e.message}")
            }
        }
    }

    // ---------- disk (io thread) ----------

    private fun load(dir: File): List<TranscriptRecorder.Entry> = try {
        val file = File(dir, FILE_NAME)
        val legacy = File(dir, LEGACY_FILE_NAME)
        if (legacy.exists()) {
            if (!file.exists()) writeAll(file, readLegacy(legacy))
            legacy.delete()
        }
        val entries = ArrayList<TranscriptRecorder.Entry>()
        if (file.exists()) {
            // A line cut short by a crash mid-write simply doesn't decode.
            file.forEachLine { line -> decode(line)?.let(entries::add) }
        }
        if (entries.size > TranscriptRecorder.MAX_ENTRIES) {
            entries.takeLast(TranscriptRecorder.MAX_ENTRIES).also { writeAll(file, it) }
        } else {
            entries
        }
    } catch (e: Exception) {
        Log.w(TAG, "transcript load failed: ${e.message}")
        emptyList()
    }

    private fun writeAll(file: File, entries: List<TranscriptRecorder.Entry>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.bufferedWriter().use { writer ->
            for (entry in entries) {
                writer.write(encode(entry).toString())
                writer.newLine()
            }
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
            )
        }
    } catch (_: Exception) {
        null
    }
}
