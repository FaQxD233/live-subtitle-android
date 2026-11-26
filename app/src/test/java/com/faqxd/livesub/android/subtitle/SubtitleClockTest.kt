package com.faqxd.livesub.android.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleClockTest {

    private var now = 10_000L
    private val clock = SubtitleClock { now }

    @Test
    fun advancesOnlyWhilePlaying() {
        assertFalse(clock.isPlaying)
        now += 5_000
        assertEquals(0L, clock.position())
        clock.play()
        now += 1_500
        assertEquals(1_500L, clock.position())
        clock.pause()
        now += 3_000
        assertEquals(1_500L, clock.position())
    }

    @Test
    fun seekAndShiftMoveThePositionButNotBeforeZero() {
        clock.play()
        now += 1_000
        clock.seekTo(60_000)
        now += 500
        assertEquals(60_500L, clock.position())
        clock.shift(-500)
        assertEquals(60_000L, clock.position())
        clock.shift(-100_000)
        assertEquals(0L, clock.position())
        assertTrue(clock.isPlaying)
    }

    @Test
    fun rateScalesTheAdvanceAndKeepsThePositionWhenChanged() {
        clock.play()
        now += 2_000
        clock.rate = 1.5
        assertEquals(2_000L, clock.position())
        now += 2_000
        assertEquals(5_000L, clock.position())
        // Real time until a position, at that speed.
        assertEquals(2_000L, clock.timeUntil(8_000))
        assertEquals(0L, clock.timeUntil(1_000))
        clock.pause()
        clock.rate = 0.5
        now += 4_000
        assertEquals(5_000L, clock.position())
    }

    @Test
    fun playAndPauseCanCountFromEarlier() {
        clock.play(at = now - 400)
        assertEquals(400L, clock.position())
        now += 1_000
        clock.rate = 2.0
        now += 1_000
        // 1 400 ms at normal speed, then 600 ms at double speed until the pause 400 ms ago.
        clock.pause(at = now - 400)
        assertEquals(2_600L, clock.position())
        clock.play()
        now += 100
        // Never before the last anchor: a pause dated before the play stops where it played from.
        clock.pause(at = now - 1_000)
        assertEquals(2_600L, clock.position())
    }
}
