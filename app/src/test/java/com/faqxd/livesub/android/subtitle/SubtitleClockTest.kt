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
}
