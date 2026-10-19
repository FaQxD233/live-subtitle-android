package com.faqxd.livesub.android.transcript

/**
 * Caption segment rule shared by the HUD and the transcript (port of the
 * draft / committed handling in `hud_window.py:HUDWindow.set_output`):
 * a segment that extends the current draft is a cumulative update and
 * replaces it; any other segment finalizes the draft and becomes the new one.
 */
class SegmentMerger {
    var draft: String = ""
        private set

    /** Feed a segment; returns the text it finalized ("" if none). */
    fun push(segment: String): String {
        if (draft.isNotEmpty() && segment.startsWith(draft)) {
            draft = segment
            return ""
        }
        val finalized = draft
        draft = segment
        return finalized
    }

    /** Finalize the current draft and return it. */
    fun flush(): String {
        val finalized = draft
        draft = ""
        return finalized
    }

    fun reset() {
        draft = ""
    }
}

/**
 * Full caption history of one service run, kept so it can be exported.
 *
 * Original and translated segments are recorded separately and grouped
 * into lines: a pause longer than [PAUSE_GAP_MS] within a stream starts a
 * new line. Segments are stored untrimmed so word spacing survives. Not
 * thread-safe; call it from the main thread only.
 */
class TranscriptRecorder(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class Stream { ORIGINAL, TRANSLATION }

    data class Line(val stream: Stream, val startedAt: Long, val text: String)

    /** Wall-clock time of the first recorded segment, or 0 if none. */
    var startedAt = 0L
        private set

    private val lines = ArrayList<Line>()
    private val tracks = Stream.values().associateWith { Track(it) }

    /** Record one transcription segment for [stream]. */
    fun append(stream: Stream, segment: String) {
        if (segment.isEmpty()) return
        val now = clock()
        if (startedAt == 0L) startedAt = now
        val track = tracks.getValue(stream)
        if (!track.isEmpty && now - track.lastAt > PAUSE_GAP_MS) closeLine(track)
        if (track.isEmpty) track.startedAt = now
        track.finalized.append(track.merger.push(segment))
        track.lastAt = now
    }

    /** End the lines in progress, e.g. when a new session starts. */
    fun breakLines() {
        tracks.values.forEach { closeLine(it) }
    }

    /** Every line in time order, including the ones still being written. */
    fun snapshot(): List<Line> {
        val open = tracks.values
            .filterNot { it.isEmpty }
            .map { Line(it.stream, it.startedAt, it.text()) }
        return (lines + open)
            .filter { it.text.isNotEmpty() }
            .sortedBy { it.startedAt }
    }

    private fun closeLine(track: Track) {
        if (track.isEmpty) return
        track.finalized.append(track.merger.flush())
        val text = track.finalized.toString().trim()
        if (text.isNotEmpty()) {
            lines.add(Line(track.stream, track.startedAt, text))
            if (lines.size > MAX_LINES) lines.subList(0, lines.size - MAX_LINES).clear()
        }
        track.finalized.setLength(0)
    }

    private class Track(val stream: Stream) {
        val merger = SegmentMerger()
        val finalized = StringBuilder()
        var startedAt = 0L
        var lastAt = 0L

        val isEmpty: Boolean
            get() = finalized.isEmpty() && merger.draft.isEmpty()

        fun text(): String = (finalized.toString() + merger.draft).trim()
    }

    companion object {
        /** A pause longer than this within a stream starts a new line. */
        const val PAUSE_GAP_MS = 2000L
        // Safety cap for very long runs; the oldest lines are dropped first.
        private const val MAX_LINES = 20_000
    }
}
