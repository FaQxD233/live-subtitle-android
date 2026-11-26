package com.faqxd.livesub.android.subtitle

import kotlin.math.ceil

/**
 * Playback position of the subtitle player, driven by a monotonic clock
 * ([now], e.g. `SystemClock::elapsedRealtime`): while playing, the position
 * advances from the last anchor at [rate] times the clock's pace; seeking
 * and speed changes move the anchor.
 */
class SubtitleClock(private val now: () -> Long) {

    var isPlaying = false
        private set

    /**
     * Playback speed, for videos watched faster or slower: at 1.5 the
     * position advances 1.5 s per second. Changing it keeps the position.
     */
    var rate = 1.0
        set(value) {
            require(value > 0) { "rate must be positive" }
            anchorPosition = position()
            anchorTime = now()
            field = value
        }

    private var anchorPosition = 0L
    private var anchorTime = 0L

    /** The position in ms. */
    fun position(): Long = positionAt(now())

    /**
     * Play, as of time [at] of [now]'s clock (by default now): a play that
     * is only noticed later, e.g. a double tap confirmed after a moment,
     * still counts from when it happened.
     */
    fun play(at: Long = now()) {
        if (isPlaying) return
        anchorTime = at
        isPlaying = true
    }

    /** Pause at the position of time [at] (by default now), see [play]. */
    fun pause(at: Long = now()) {
        if (!isPlaying) return
        anchorPosition = positionAt(at)
        isPlaying = false
    }

    /** Jump to [positionMs] (not before 0), playing on if playing. */
    fun seekTo(positionMs: Long) {
        anchorPosition = positionMs.coerceAtLeast(0)
        anchorTime = now()
    }

    /** Move the position by [deltaMs]: back delays the subtitles, forward brings them earlier. */
    fun shift(deltaMs: Long) = seekTo(position() + deltaMs)

    /** Real time in ms until playback reaches [positionMs]; 0 if it is there already. */
    fun timeUntil(positionMs: Long): Long =
        ceil((positionMs - position()) / rate).toLong().coerceAtLeast(0)

    /** The position at time [time]; never before the last anchor, about which nothing earlier is known. */
    private fun positionAt(time: Long): Long =
        if (isPlaying) anchorPosition + ((time - anchorTime).coerceAtLeast(0) * rate).toLong() else anchorPosition
}
