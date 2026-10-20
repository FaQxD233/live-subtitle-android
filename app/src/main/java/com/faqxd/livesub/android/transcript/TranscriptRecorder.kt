package com.faqxd.livesub.android.transcript

/**
 * Caption history (original and translation), kept across service runs so
 * it can be exported.
 *
 * Segments are grouped into lines per stream: a pause longer than
 * [PAUSE_GAP_MS], a line running longer than [MAX_LINE_MS], or a new Live
 * session starts a new line. The Live API streams transcription as deltas
 * that carry their own leading spaces (verified 2026-10-20 against the
 * translate model), so segments are appended verbatim. Each service run is
 * a session whose start is recorded once its first line is. Entries that
 * became final (session starts, closed lines) are handed to [onEntry] in
 * that order, for persistence. Not thread-safe; main thread only.
 */
class TranscriptRecorder(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class Stream { ORIGINAL, TRANSLATION }

    sealed interface Entry {
        /** Wall-clock time the entry started at. */
        val at: Long
    }

    /** A service run that started at [at]. */
    data class Session(override val at: Long) : Entry

    /** One caption line of [stream] that started at [at]. */
    data class Line(val stream: Stream, override val at: Long, val text: String) : Entry

    /** Sink for entries that became final. */
    var onEntry: ((Entry) -> Unit)? = null

    private val entries = ArrayList<Entry>()
    private val tracks = Stream.values().associateWith { Track(it) }
    private var sessionActive = false
    // Start of the current session; recorded together with its first line,
    // so a run without captions leaves no empty header behind.
    private var pendingSessionAt = 0L

    /** Put previously persisted entries (oldest first) before the current ones. */
    fun restore(restored: List<Entry>) {
        if (restored.isEmpty()) return
        entries.addAll(0, restored)
        trim()
    }

    /** A service run begins; its lines are exported under a session header. */
    fun startSession() {
        breakLines()
        sessionActive = true
        pendingSessionAt = clock()
    }

    /** The service run ends: finish the lines in progress. */
    fun endSession() {
        breakLines()
        sessionActive = false
        pendingSessionAt = 0L
    }

    /** Record one transcription segment for [stream]. */
    fun append(stream: Stream, segment: String) {
        if (segment.isEmpty()) return
        val now = clock()
        val track = tracks.getValue(stream)
        if (!track.isEmpty &&
            (now - track.lastAt > PAUSE_GAP_MS || now - track.startedAt > MAX_LINE_MS)
        ) {
            closeLine(track)
        }
        if (track.isEmpty) track.startedAt = now
        track.text.append(segment)
        track.lastAt = now
    }

    /** End the lines in progress, e.g. when a new Live session starts. */
    fun breakLines() {
        tracks.values.forEach { closeLine(it) }
    }

    /** Forget everything; a running session continues under a new header. */
    fun clear() {
        entries.clear()
        tracks.values.forEach { it.reset() }
        pendingSessionAt = if (sessionActive) clock() else 0L
    }

    /** Every entry in time order, including the lines still being written. */
    fun snapshot(): List<Entry> {
        val open = tracks.values
            .filterNot { it.isEmpty }
            .map { Line(it.stream, it.startedAt, it.text.toString().trim()) }
            .filter { it.text.isNotEmpty() }
        val header = if (open.isNotEmpty() && pendingSessionAt > 0) {
            listOf(Session(pendingSessionAt))
        } else {
            emptyList()
        }
        return (entries + header + open).sortedBy { it.at }
    }

    private fun closeLine(track: Track) {
        if (track.isEmpty) return
        val text = track.text.toString().trim()
        val startedAt = track.startedAt
        track.reset()
        if (text.isEmpty()) return
        if (pendingSessionAt > 0) {
            record(Session(pendingSessionAt))
            pendingSessionAt = 0L
        }
        record(Line(track.stream, startedAt, text))
    }

    private fun record(entry: Entry) {
        entries.add(entry)
        trim()
        onEntry?.invoke(entry)
    }

    private fun trim() {
        if (entries.size > MAX_ENTRIES) entries.subList(0, entries.size - MAX_ENTRIES).clear()
    }

    private class Track(val stream: Stream) {
        val text = StringBuilder()
        var startedAt = 0L
        var lastAt = 0L

        val isEmpty: Boolean
            get() = text.isEmpty()

        fun reset() {
            text.setLength(0)
            startedAt = 0L
            lastAt = 0L
        }
    }

    companion object {
        /** A pause longer than this within a stream starts a new line. */
        const val PAUSE_GAP_MS = 2000L
        /**
         * A line running longer than this is closed even without a pause,
         * so a long monologue is timestamped and persisted in pieces.
         */
        const val MAX_LINE_MS = 30_000L
        /** Safety cap for the whole history; the oldest entries go first. */
        const val MAX_ENTRIES = 20_000
    }
}
