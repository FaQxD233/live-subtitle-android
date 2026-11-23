package com.faqxd.livesub.android.subtitle

/**
 * Playback position of the subtitle player, driven by a monotonic clock
 * ([now], e.g. `SystemClock::elapsedRealtime`): while playing, the position
 * advances with it from the last anchor; seeking moves the anchor.
 */
class SubtitleClock(private val now: () -> Long) {

    var isPlaying = false
        private set

    private var anchorPosition = 0L
    private var anchorTime = 0L

    /** The position in ms. */
    fun position(): Long =
        if (isPlaying) anchorPosition + (now() - anchorTime) else anchorPosition

    fun play() {
        if (isPlaying) return
        anchorTime = now()
        isPlaying = true
    }

    fun pause() {
        if (!isPlaying) return
        anchorPosition = position()
        isPlaying = false
    }

    /** Jump to [positionMs] (not before 0), playing on if playing. */
    fun seekTo(positionMs: Long) {
        anchorPosition = positionMs.coerceAtLeast(0)
        anchorTime = now()
    }

    /** Move the position by [deltaMs]: back delays the subtitles, forward brings them earlier. */
    fun shift(deltaMs: Long) = seekTo(position() + deltaMs)
}
