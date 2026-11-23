package com.faqxd.livesub.android.subtitle

/**
 * Subtitle cues in start order, answering what shows at a position, when
 * that changes next, and where "previous" / "next" go. Cues may overlap.
 */
class CueTimeline(cues: List<SubtitleCue>) {

    val cues: List<SubtitleCue> = cues.sortedBy { it.startMs }

    /** When the last cue ends. */
    val durationMs: Long = this.cues.maxOfOrNull { it.endMs } ?: 0L

    // maxEnd[i]: the latest end among cues[0..i], to stop the scan back for overlaps early.
    private val maxEnd = LongArray(this.cues.size).also { ends ->
        var max = Long.MIN_VALUE
        this.cues.forEachIndexed { i, cue ->
            max = maxOf(max, cue.endMs)
            ends[i] = max
        }
    }

    /** The cues showing at [t] (start <= t < end), in start order. */
    fun active(t: Long): List<SubtitleCue> {
        val showing = ArrayList<SubtitleCue>()
        var i = lastStartAtOrBefore(t)
        while (i >= 0 && maxEnd[i] > t) {
            if (cues[i].endMs > t) showing.add(cues[i])
            i--
        }
        showing.reverse()
        return showing
    }

    /** The next time after [t] at which [active] changes; null if it never does. */
    fun nextChange(t: Long): Long? {
        val nextStart = nextStart(t)
        val nextEnd = active(t).minOfOrNull { it.endMs }
        return listOfNotNull(nextStart, nextEnd).minOrNull()
    }

    /** Start of the first cue that starts after [t]; null if none does. */
    fun nextStart(t: Long): Long? = cues.getOrNull(lastStartAtOrBefore(t) + 1)?.startMs

    /**
     * Where "previous" goes from [t]: back to the start of the latest cue
     * that started by then if it has shown for longer than [restartAfterMs],
     * else to the start of the cue before it; null before the first cue.
     */
    fun previousStart(t: Long, restartAfterMs: Long): Long? {
        val i = lastStartAtOrBefore(t)
        if (i < 0) return null
        val latest = cues[i]
        if (t - latest.startMs > restartAfterMs) return latest.startMs
        return cues.getOrNull(i - 1)?.startMs ?: latest.startMs
    }

    /** Index of the last cue starting at or before [t]; -1 if none does. */
    private fun lastStartAtOrBefore(t: Long): Int {
        var low = 0
        var high = cues.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= t) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
