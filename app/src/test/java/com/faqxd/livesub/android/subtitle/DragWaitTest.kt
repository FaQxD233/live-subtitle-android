package com.faqxd.livesub.android.subtitle

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DragWaitTest {

    private fun playing(positionMs: Long, updateTimeMs: Long, speed: Float = 1f, title: String? = null) =
        DragWait.Report(PlaybackState.STATE_PLAYING, positionMs, speed, updateTimeMs, title)

    private fun paused(positionMs: Long, updateTimeMs: Long, title: String? = null) =
        DragWait.Report(PlaybackState.STATE_PAUSED, positionMs, 1f, updateTimeMs, title)

    /** Resumed at 25.2 s, dragged to 64.2 s 2.5 s later, as B 站 was on 2026-11-27. */
    private fun dragged(drags: DragWait): DragWait.Result {
        drags.onReport(playing(25_200, 100_000))
        return drags.onReport(playing(64_200, 102_500))
    }

    @Test
    fun thePauseAfterADragShowsHowLongTheVideoLagged() {
        val samples = DragSamples()
        val drags = DragWait(samples)
        // No samples yet: no wait.
        assertEquals(DragWait.Result(dragWaitMs = 0L), dragged(drags))
        // Paused at 67.3 s 5 s later: the video got there 1.9 s late.
        assertEquals(DragWait.Result(sampleMs = 1_900L), drags.onReport(paused(67_300, 107_500)))
        assertEquals(listOf(1_900L), samples.list)
    }

    @Test
    fun aDragWaitsAtItsNewPositionAsLongAsTheSamplesSay() {
        val drags = DragWait(DragSamples(listOf(1_940, 1_150, 1_070)))
        drags.onReport(playing(45_700, 56_000))
        // 11 s ahead of where the video would be.
        val drag = drags.onReport(playing(60_700, 60_000))
        assertEquals(1_150L, drag.dragWaitMs)
        assertEquals(DragWait.Hold(60_700, 61_150, 1f), drag.hold)
    }

    @Test
    fun aHoldWaitsThenPlaysOnFromWhereItWaited() {
        val hold = DragWait.Hold(60_700, 61_150, 1.5f)
        assertEquals(60_700L, hold.positionAt(60_100))
        assertEquals(60_700L, hold.positionAt(61_150))
        assertEquals(62_200L, hold.positionAt(62_150))
    }

    @Test
    fun theNextReportEndsTheWait() {
        val drags = DragWait(DragSamples(listOf(1_000, 1_000)))
        assertEquals(DragWait.Hold(64_200, 103_500, 1f), dragged(drags).hold)
        assertNull(drags.onReport(paused(64_400, 102_900)).hold)
    }

    @Test
    fun plainPausesAndResumesAreNoDrags() {
        val drags = DragWait(DragSamples(listOf(1_000, 1_000)))
        val reports = listOf(
            playing(5_700, 10_000),
            paused(9_200, 13_500),
            // Resumed where it paused.
            playing(9_200, 15_100),
            // Drifted a little.
            playing(12_398, 18_100),
            paused(12_500, 18_300),
        )
        for (report in reports) assertEquals(DragWait.Result(), drags.onReport(report))
    }

    @Test
    fun theLagIsInRealTimeAtAnySpeed() {
        val drags = DragWait(DragSamples())
        drags.onReport(playing(10_000, 100_000, speed = 2f))
        drags.onReport(playing(60_000, 101_000, speed = 2f))
        // Loaded for 1 s, then played 3 s at 2×: 2 s of video behind, 1 s of waiting.
        assertEquals(1_000L, drags.onReport(paused(66_000, 105_000)).sampleMs)
    }

    @Test
    fun aPauseTooSoonOrTooLateAfterTheDragGivesNoSample() {
        val samples = DragSamples()
        val soon = DragWait(samples)
        dragged(soon)
        // 1 s after: the video may still have been loading.
        assertEquals(DragWait.Skip.TOO_SOON, soon.onReport(paused(64_200, 103_500)).skipped)
        val late = DragWait(samples)
        dragged(late)
        // Over 2 minutes after: the video may have stalled meanwhile.
        assertEquals(DragWait.Skip.TOO_LATE, late.onReport(paused(188_000, 225_000)).skipped)
        assertEquals(emptyList<Long>(), samples.list)
    }

    @Test
    fun onlyAPauseRightAfterTheDragGivesASample() {
        val samples = DragSamples()
        val drags = DragWait(samples)
        dragged(drags)
        val buffering = DragWait.Report(PlaybackState.STATE_BUFFERING, 64_200, 1f, 103_000)
        assertEquals(DragWait.Skip.NOT_A_PAUSE, drags.onReport(buffering).skipped)
        // A pause after that measures nothing.
        assertEquals(DragWait.Result(), drags.onReport(paused(70_000, 110_000)))
        assertEquals(emptyList<Long>(), samples.list)
    }

    @Test
    fun aSecondDragTakesOverTheFirst() {
        val drags = DragWait(DragSamples())
        dragged(drags)
        val again = drags.onReport(playing(10_000, 104_000))
        assertEquals(DragWait.Skip.DRAGGED_AGAIN, again.skipped)
        assertEquals(0L, again.dragWaitMs)
        // The pause shows the second drag's lag.
        assertEquals(1_100L, drags.onReport(paused(13_900, 109_000)).sampleMs)
    }

    @Test
    fun anotherTitleGivesNoSample() {
        val drags = DragWait(DragSamples())
        drags.onReport(playing(25_200, 100_000, title = "A"))
        drags.onReport(playing(64_200, 102_500, title = "A"))
        assertEquals(DragWait.Skip.OTHER_TITLE, drags.onReport(paused(2_000, 110_000, title = "B")).skipped)
    }

    @Test
    fun aPauseFarOffTheDragGivesNoSample() {
        val drags = DragWait(DragSamples())
        dragged(drags)
        // 5 s on, still at the drag's position.
        assertEquals(DragWait.Skip.TOO_FAR, drags.onReport(paused(64_200, 107_500)).skipped)
    }

    @Test
    fun aVideoAheadOfTheDragLaggedNothing() {
        val drags = DragWait(DragSamples())
        dragged(drags)
        assertEquals(0L, drags.onReport(paused(69_400, 107_500)).sampleMs)
    }

    @Test
    fun waitsOnceThereAreTwoSamplesTheMedianOfThem() {
        assertEquals(0L, DragSamples().waitMs())
        assertEquals(0L, DragSamples(listOf(1_940)).waitMs())
        // The lower middle one of an even count.
        assertEquals(1_150L, DragSamples(listOf(1_940, 1_150)).waitMs())
        assertEquals(1_150L, DragSamples(listOf(1_940, 1_150, 1_070)).waitMs())
        assertEquals(1_070L, DragSamples(listOf(1_940, 1_150, 1_070, 1_067)).waitMs())
    }

    @Test
    fun oneVeryFastOrSlowLoadDoesNotSwayTheWait() {
        // From the cache at once.
        assertEquals(1_100L, DragSamples(listOf(1_100, 0, 1_120)).waitMs())
        // Very slowly.
        assertEquals(1_120L, DragSamples(listOf(1_100, 2_900, 1_120)).waitMs())
        // Never longer than the longest wait.
        assertEquals(DragSamples.MAX_WAIT_MS, DragSamples(listOf(5_000, 6_000)).waitMs())
    }

    @Test
    fun keepsTheLatestSamples() {
        val samples = DragSamples(listOf(1, 2, 3, 4, 5))
        samples.add(6)
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), samples.list)
    }

    @Test
    fun samplesAreKeptAsText() {
        val samples = DragSamples(listOf(1_940, 1_150, 1_070))
        assertEquals("1940,1150,1070", samples.encode())
        assertEquals(samples.list, DragSamples.decode(samples.encode()).list)
        assertEquals(emptyList<Long>(), DragSamples.decode(null).list)
        // What isn't a sample is skipped.
        assertEquals(listOf(12L), DragSamples.decode("x,-5, 12,").list)
    }
}
