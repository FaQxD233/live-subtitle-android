package com.faqxd.livesub.android.subtitle

import android.media.session.PlaybackState
import kotlin.math.abs

/**
 * Where a video another app plays is, from what its media session last
 * reported ([PlaybackState]): a position as of an update time on the
 * `SystemClock.elapsedRealtime` clock, advancing at a speed while it
 * plays. See docs/plan-mediasession-sync.md, 4.1. Only [PlaybackState]'s
 * constants are used, so this runs in plain JVM tests.
 */
object VideoTime {

    /**
     * Whether a session in [state] at [speed] counts as playing: only
     * [PlaybackState.STATE_PLAYING] at a speed above 0. Buffering, seeking,
     * fast-forwarding and the other states hold the video still, as does a
     * speed of 0 (paused) or below (rewinding) whatever the state says.
     */
    fun isPlaying(state: Int, speed: Float): Boolean =
        state == PlaybackState.STATE_PLAYING && speed > 0f

    /**
     * The position at [now] of a video reported at [positionMs] as of
     * [updateTimeMs], [playing] at [speed]: the reported one plus what has
     * played since. An update time of 0 means the session never set one,
     * and one after [now] belongs to another clock: neither moves the
     * position, rather than by the time since boot.
     */
    fun positionAt(positionMs: Long, updateTimeMs: Long, speed: Float, playing: Boolean, now: Long): Long {
        if (!playing || updateTimeMs <= 0L) return positionMs
        val since = (now - updateTimeMs).coerceAtLeast(0L)
        return positionMs + (since * speed.toDouble()).toLong()
    }

    /**
     * Whether a clock following a video must be moved to match it: one
     * plays while the other holds still, it runs at another speed than the
     * playing video, or it is [toleranceMs] or more off. A smaller gap is
     * the jitter of reports, and moving for it would make the subtitles
     * flicker. A paused video's speed doesn't count: it may report 0.
     */
    fun needsSync(
        clockPlaying: Boolean,
        clockRate: Double,
        clockPositionMs: Long,
        videoPlaying: Boolean,
        videoSpeed: Double,
        videoPositionMs: Long,
        toleranceMs: Long,
    ): Boolean =
        clockPlaying != videoPlaying ||
            (videoPlaying && abs(clockRate - videoSpeed) > SPEED_EPSILON) ||
            abs(clockPositionMs - videoPositionMs) >= toleranceMs

    /** Speeds closer than this are the same (a Float speed read as Double). */
    private const val SPEED_EPSILON = 0.001
}
