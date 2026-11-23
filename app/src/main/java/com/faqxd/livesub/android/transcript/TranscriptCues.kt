package com.faqxd.livesub.android.transcript

import com.faqxd.livesub.android.subtitle.SubtitleCue

/**
 * Turns the recorded lines of one stream into subtitle cues, timed from
 * the start of the run: a cue stays [LINGER_MS] past its last word so it
 * can be read to the end, shows for at least [MIN_DISPLAY_MS], and never
 * overlaps the next cue of the same stream.
 */
object TranscriptCues {

    const val LINGER_MS = 800L
    const val MIN_DISPLAY_MS = 1_200L
    /** Lines recorded without an end time last at most this long. */
    const val MAX_UNKNOWN_MS = 30_000L

    /** When the run of [entries] started: its session start, else its first entry; null if empty. */
    fun startOf(entries: List<TranscriptRecorder.Entry>): Long? =
        (entries.firstOrNull { it is TranscriptRecorder.Session } ?: entries.minByOrNull { it.at })?.at

    /** The cues of [stream], relative to [startOf] the run. */
    fun build(entries: List<TranscriptRecorder.Entry>, stream: TranscriptRecorder.Stream): List<SubtitleCue> {
        val zero = startOf(entries) ?: return emptyList()
        val lines = entries.filterIsInstance<TranscriptRecorder.Line>()
            .filter { it.stream == stream && it.text.isNotBlank() }
            .sortedBy { it.at }
        return lines.mapIndexed { index, line ->
            val next = lines.getOrNull(index + 1)?.at
            // Older journals have no end times: assume the line lasted until the next one.
            val spokenUntil = line.end ?: minOf(next ?: Long.MAX_VALUE, line.at + MAX_UNKNOWN_MS)
            var end = maxOf(spokenUntil + LINGER_MS, line.at + MIN_DISPLAY_MS)
            if (next != null) end = minOf(end, next)
            val startMs = (line.at - zero).coerceAtLeast(0)
            SubtitleCue(startMs, maxOf(end - zero, startMs + 1), line.text)
        }
    }
}
