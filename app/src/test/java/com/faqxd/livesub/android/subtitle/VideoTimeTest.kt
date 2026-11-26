package com.faqxd.livesub.android.subtitle

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimeTest {

    @Test
    fun onlyPlayingForwardCountsAsPlaying() {
        assertTrue(VideoTime.isPlaying(PlaybackState.STATE_PLAYING, 1f))
        assertTrue(VideoTime.isPlaying(PlaybackState.STATE_PLAYING, 0.5f))
        // Paused or rewinding by its speed, whatever the state says.
        assertFalse(VideoTime.isPlaying(PlaybackState.STATE_PLAYING, 0f))
        assertFalse(VideoTime.isPlaying(PlaybackState.STATE_PLAYING, -1f))
        // On the move, but not at a steady pace.
        assertFalse(VideoTime.isPlaying(PlaybackState.STATE_BUFFERING, 1f))
        assertFalse(VideoTime.isPlaying(PlaybackState.STATE_FAST_FORWARDING, 2f))
        assertFalse(VideoTime.isPlaying(PlaybackState.STATE_PAUSED, 1f))
    }

    @Test
    fun aPlayingVideoAdvancesAtItsSpeedSinceTheReport() {
        assertEquals(12_000L, VideoTime.positionAt(10_000, 50_000, 1f, playing = true, now = 52_000))
        assertEquals(13_000L, VideoTime.positionAt(10_000, 50_000, 1.5f, playing = true, now = 52_000))
    }

    @Test
    fun aPausedVideoStaysWhereReported() {
        assertEquals(10_000L, VideoTime.positionAt(10_000, 50_000, 1f, playing = false, now = 90_000))
    }

    @Test
    fun aClockCloseToThePlayingVideoIsLeftAlone() {
        // Jitter under the tolerance.
        assertFalse(VideoTime.needsSync(true, 1.0, 10_000, true, 1.0, 10_250, 300))
        assertFalse(VideoTime.needsSync(true, 1.5, 10_000, true, 1.5, 9_750, 300))
        // Both paused at one place: a paused video may report speed 0.
        assertFalse(VideoTime.needsSync(false, 1.0, 10_000, false, 0.0, 10_100, 300))
    }

    @Test
    fun aClockOffPlayingOrSpeedIsMoved() {
        // Seeked.
        assertTrue(VideoTime.needsSync(true, 1.0, 10_000, true, 1.0, 10_300, 300))
        assertTrue(VideoTime.needsSync(false, 1.0, 10_000, false, 1.0, 60_000, 300))
        // Paused or resumed in the video app.
        assertTrue(VideoTime.needsSync(true, 1.0, 10_000, false, 1.0, 10_000, 300))
        assertTrue(VideoTime.needsSync(false, 1.0, 10_000, true, 1.0, 10_000, 300))
        // Another speed.
        assertTrue(VideoTime.needsSync(true, 1.0, 10_000, true, 1.5, 10_000, 300))
    }

    @Test
    fun anUnsetOrFutureUpdateTimeDoesNotMoveIt() {
        // Never set: not a jump by the whole time since boot.
        assertEquals(10_000L, VideoTime.positionAt(10_000, 0, 1f, playing = true, now = 3_600_000))
        // Reported on another clock, ahead of this one.
        assertEquals(10_000L, VideoTime.positionAt(10_000, 60_000, 1f, playing = true, now = 52_000))
    }
}
