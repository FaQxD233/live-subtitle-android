package com.faqxd.livesub.android.transcript

/**
 * Captions (original and translation) of the service run in progress, so
 * the run can be exported while it goes and kept once it ends.
 *
 * Each stream is cut into subtitle-sized lines (cues) with a start and an
 * end time, so a run can be exported as subtitles:
 *  - a pause longer than [PAUSE_GAP_MS] ends the line;
 *  - once a line has run for [MIN_CUE_MS], it is cut after its last
 *    complete sentence that more text follows (a sentence end at the very
 *    end waits for the next segment, so "3." + "14" stays together, and
 *    short sentences share a line instead of flashing by);
 *  - a line that would run longer than [MAX_CUE_MS] or grow wider than
 *    [MAX_CUE_WIDTH] columns is cut before the next segment, after its
 *    last sentence end if it has one;
 *  - a new Live session starts a new line ([breakLines]).
 * The Live API streams transcription as deltas that carry their own
 * leading spaces (verified 2026-10-20 against the translate model), so
 * segments are appended verbatim. A line starts when the segment holding
 * its first character arrived and ends when the segment holding its last
 * character (its terminal punctuation, for a sentence cut) arrived.
 *
 * Each service run is a session whose start is recorded once its first
 * line is. Entries that became final (the session start, closed lines) are
 * handed to [onEntry] in that order, for persistence. Not thread-safe;
 * main thread only.
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

    /**
     * One caption line of [stream] shown from [at] to [end]; [end] is null
     * for lines recorded before end times were (older journals).
     */
    data class Line(
        val stream: Stream,
        override val at: Long,
        val text: String,
        val end: Long? = null,
    ) : Entry

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
        if (!track.isEmpty) {
            if (now - track.lastAt > PAUSE_GAP_MS) {
                closeLine(track, track.text.length)
            } else if (overflows(track, segment, now)) {
                // Prefer ending the line after a sentence; the rest stays open.
                val sentenceEnd = sentenceEnds(track.text).lastOrNull()
                if (sentenceEnd != null && sentenceEnd.end < track.text.length) {
                    closeLine(track, sentenceEnd.end, sentenceEnd.mark)
                }
                if (!track.isEmpty && overflows(track, segment, now)) {
                    closeLine(track, track.text.length)
                }
            }
        }
        track.add(segment, now)
        if (now - track.startedAt >= MIN_CUE_MS) {
            // The last complete sentence that more text follows.
            val sentenceEnd = sentenceEnds(track.text).lastOrNull { it.end < track.text.length }
            if (sentenceEnd != null) closeLine(track, sentenceEnd.end, sentenceEnd.mark)
        }
    }

    /** End the lines in progress, e.g. when a new Live session starts. */
    fun breakLines() {
        tracks.values.forEach { closeLine(it, it.text.length) }
    }

    /** The session's entries in time order, including the lines still being written. */
    fun snapshot(): List<Entry> {
        val open = tracks.values.mapNotNull { it.line(it.text.length, it.text.length) }
        val header = if (open.isNotEmpty() && headerPending) {
            listOf(Session(sessionStartedAt))
        } else {
            emptyList()
        }
        return (entries + header + open).sortedBy { it.at }
    }

    private fun overflows(track: Track, segment: String, now: Long): Boolean =
        now - track.startedAt > MAX_CUE_MS ||
            displayWidth(track.text) + displayWidth(segment) > MAX_CUE_WIDTH

    /**
     * Record the first [length] characters of [track] as a line and keep
     * the rest open. The line ends when the character before [mark] (its
     * terminal punctuation) arrived; by default, its last character.
     */
    private fun closeLine(track: Track, length: Int, mark: Int = length) {
        if (track.isEmpty) return
        val line = track.line(length, mark)
        track.dropHead(length)
        if (line == null) return
        if (headerPending) {
            record(Session(sessionStartedAt))
            headerPending = false
        }
        lineCount++
        record(line)
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

    /** The line in progress of one stream, with the arrival time of each of its segments. */
    private class Track(val stream: Stream) {
        val text = StringBuilder()
        // Start offset in [text] and arrival time of each segment, oldest first.
        private val offsets = ArrayList<Int>()
        private val times = ArrayList<Long>()

        val isEmpty: Boolean
            get() = text.isEmpty()

        /** When the oldest segment still in the line arrived. */
        val startedAt: Long
            get() = times.first()

        /** When the newest segment arrived. */
        val lastAt: Long
            get() = times.last()

        fun add(segment: String, at: Long) {
            offsets.add(text.length)
            times.add(at)
            text.append(segment)
        }

        /**
         * The first [length] characters as a line: from the arrival of its
         * first non-blank character to that of the last non-blank one before
         * [mark]. Null if they are all blank.
         */
        fun line(length: Int, mark: Int): Line? {
            val first = (0 until length).firstOrNull { !text[it].isWhitespace() } ?: return null
            val last = (minOf(mark, length) - 1 downTo first).firstOrNull { !text[it].isWhitespace() } ?: first
            return Line(stream, timeAt(first), text.substring(first, length).trim(), timeAt(last))
        }

        /** Drop the first [length] characters; the segment holding the new first one starts the line. */
        fun dropHead(length: Int) {
            if (length >= text.length) {
                reset()
                return
            }
            text.delete(0, length)
            val keepFrom = offsets.indexOfLast { it <= length }
            for (i in keepFrom until offsets.size) offsets[i] = (offsets[i] - length).coerceAtLeast(0)
            offsets.subList(0, keepFrom).clear()
            times.subList(0, keepFrom).clear()
        }

        fun reset() {
            text.setLength(0)
            offsets.clear()
            times.clear()
        }

        /** When the segment holding the character at [index] arrived. */
        private fun timeAt(index: Int): Long = times[offsets.indexOfLast { it <= index }]
    }

    companion object {
        /** A pause longer than this within a stream starts a new line. */
        const val PAUSE_GAP_MS = 2000L
        /** A line is cut after a complete sentence only once it has run this long. */
        const val MIN_CUE_MS = 1_500L
        /** A line running longer than this is cut, so subtitles stay short. */
        const val MAX_CUE_MS = 6_000L
        /** A line wider than this many columns (about two subtitle lines) is cut. */
        const val MAX_CUE_WIDTH = 84
        /** A run shorter than this is not kept once it ends. */
        const val MIN_KEPT_SESSION_MS = 5_000L
        /**
         * Safety cap for the session's entries in memory (what the overlay
         * exports); the oldest go first. The journal on disk keeps them all.
         */
        const val MAX_ENTRIES = 20_000
    }
}
