package com.faqxd.livesub.android.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueTimelineTest {

    private val one = SubtitleCue(1_000, 3_000, "one")
    private val sign = SubtitleCue(2_000, 10_000, "sign")
    private val two = SubtitleCue(4_000, 5_000, "two")
    private val timeline = CueTimeline(listOf(two, sign, one))

    @Test
    fun activeCuesIncludeOverlapsInStartOrder() {
        assertTrue(timeline.active(500).isEmpty())
        assertEquals(listOf(one), timeline.active(1_000))
        assertEquals(listOf(one, sign), timeline.active(2_500))
        assertEquals(listOf(sign), timeline.active(3_000))
        assertEquals(listOf(sign, two), timeline.active(4_500))
        assertTrue(timeline.active(10_000).isEmpty())
        assertEquals(10_000L, timeline.durationMs)
    }

    @Test
    fun nextChangeIsTheNearestStartOrEnd() {
        assertEquals(1_000L, timeline.nextChange(0))
        assertEquals(2_000L, timeline.nextChange(1_500))
        assertEquals(3_000L, timeline.nextChange(2_500))
        assertEquals(5_000L, timeline.nextChange(4_500))
        assertEquals(10_000L, timeline.nextChange(6_000))
        assertNull(timeline.nextChange(10_000))
    }

    @Test
    fun nextGoesToTheNextStart() {
        assertEquals(1_000L, timeline.nextStart(0))
        assertEquals(4_000L, timeline.nextStart(2_000))
        assertNull(timeline.nextStart(4_000))
    }

    @Test
    fun previousRestartsTheLineOrGoesToTheOneBefore() {
        assertNull(timeline.previousStart(500, 1_000))
        // "two" started 2 s ago: back to its start.
        assertEquals(4_000L, timeline.previousStart(6_000, 1_000))
        // "two" just started: back to the line before it.
        assertEquals(2_000L, timeline.previousStart(4_500, 1_000))
        // The first line just started: stay at its start.
        assertEquals(1_000L, timeline.previousStart(1_200, 1_000))
    }

    @Test
    fun emptyTimeline() {
        val empty = CueTimeline(emptyList())
        assertTrue(empty.active(0).isEmpty())
        assertNull(empty.nextChange(0))
        assertEquals(0L, empty.durationMs)
    }
}
