package com.faqxd.livesub.android.transcript

/**
 * Captions (original and translation) of the service run in progress, so
 * the run can be exported while it goes and kept once it ends.
 *
 * Segments are grouped into lines per stream: a pause longer than
 * [PAUSE_GAP_MS], a line running longer than [MAX_LINE_MS], or a new Live
 * session starts a new line. The Live API streams transcription as deltas
 * that carry their own leading spaces (verified 2026-10-20 against the
 * translate model), so segments are appended verbatim. Each service run is
 * a session whose start is recorded once its first line is. Entries that
 * became final (the session start, closed lines) are handed to [onEntry] in
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

    /** A service run from [startedAt] to [endedAt] that recorded [lineCount] lines. */
    data class EndedSession(val startedAt: Long, val endedAt: Long, val lineCount: Int) {
        /** It has captions and lasted at least [MIN_KEPT_SESSION_MS]. */
        val isWorthKeeping: Boolean
            get() = lineCount > 0 && endedAt - startedAt >= MIN_KEPT_SESSION_MS
    }

    /** Sink for entries that became final. */
    var onEntry: ((Entry) -> Unit)? = null

    private val entries = ArrayList<Entry>()
    private val tracks = Stream.values().associateWith { Track(it) }
    // Start of the session in progress; 0 while none runs.
    private var sessionStartedAt = 0L
    // The session start is recorded together with its first line, so a run
    // without captions leaves no empty header behind.
    private var headerPending = false
    private var lineCount = 0

    /** A service run begins; its lines are recorded under a session header. */
    fun startSession() {
        reset()
        sessionStartedAt = clock()
        headerPending = true
    }

    /**
     * The service run ends: finish the lines in progress and forget the
     * session. Null if none was in progress.
     */
    fun endSession(): EndedSession? {
        if (sessionStartedAt == 0L) return null
        breakLines()
        val ended = EndedSession(sessionStartedAt, clock(), lineCount)
        reset()
        return ended
    }

    /** Record one transcription segment for [stream]; ignored outside a session. */
    fun append(stream: Stream, segment: String) {
        if (segment.isEmpty() || sessionStartedAt == 0L) return
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

    /** The session's entries in time order, including the lines still being written. */
    fun snapshot(): List<Entry> {
        val open = tracks.values
            .filterNot { it.isEmpty }
            .map { Line(it.stream, it.startedAt, it.text.toString().trim()) }
            .filter { it.text.isNotEmpty() }
        val header = if (open.isNotEmpty() && headerPending) {
            listOf(Session(sessionStartedAt))
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
        if (headerPending) {
            record(Session(sessionStartedAt))
            headerPending = false
        }
        lineCount++
        record(Line(track.stream, startedAt, text))
    }

    private fun record(entry: Entry) {
        entries.add(entry)
        if (entries.size > MAX_ENTRIES) entries.subList(0, entries.size - MAX_ENTRIES).clear()
        onEntry?.invoke(entry)
    }

    private fun reset() {
        entries.clear()
        tracks.values.forEach { it.reset() }
        sessionStartedAt = 0L
        headerPending = false
        lineCount = 0
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
        /** A run shorter than this is not kept once it ends. */
        const val MIN_KEPT_SESSION_MS = 5_000L
        /**
         * Safety cap for the session's entries in memory (what the overlay
         * exports); the oldest go first. The journal on disk keeps them all.
         */
        const val MAX_ENTRIES = 20_000
    }
}
