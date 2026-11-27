package com.faqxd.livesub.android.subtitle

import android.media.session.PlaybackState
import kotlin.math.abs

/**
 * Waits out the load after a drag (docs/plan-player-sync-ui.md, 3.1).
 * Some video apps, B 站 among them, report "playing at the new position"
 * the moment the progress bar is let go, while the video gets there only
 * once it has loaded, and report nothing more until the next pause:
 * following that report, the subtitles run ahead all along. The pause
 * shows how far behind the video was. That lag, in real time, is a
 * sample of the app's ([DragSamples]), and after the next drag the
 * subtitles wait at the new position as long as the samples say.
 *
 * One per media session, given its reports in order as the session sent
 * them (update times unchanged). Only [PlaybackState]'s constants are
 * used, so this runs in plain JVM tests.
 */
class DragWait(private val samples: DragSamples) {

    /** A session's report, as it sent it. */
    data class Report(
        val state: Int,
        val positionMs: Long,
        val speed: Float,
        /** As the session set it (elapsedRealtime); 0 when it set none. */
        val updateTimeMs: Long,
        /** The title its app shows, or null without one. */
        val title: String? = null,
    ) {
        val playing: Boolean
            get() = VideoTime.isPlaying(state, speed)
    }

    /** The subtitles wait at [positionMs] until [untilMs] (elapsedRealtime), then play on at [speed]. */
    data class Hold(val positionMs: Long, val untilMs: Long, val speed: Float) {
        /** Where the video is at [now]: where it waits, then on from there. */
        fun positionAt(now: Long): Long =
            if (now <= untilMs) positionMs else positionMs + ((now - untilMs) * speed.toDouble()).toLong()
    }

    /** Why the report after a drag gave no sample. */
    enum class Skip(val reason: String) {
        DRAGGED_AGAIN("dragged again"),
        NOT_A_PAUSE("not a pause"),
        UNKNOWN("no position or time"),
        OTHER_TITLE("another title"),
        TOO_SOON("paused too soon, maybe still loading"),
        TOO_LATE("paused too late, maybe stalled meanwhile"),
        TOO_FAR("too far off"),
    }

    /** What a report was: a drag and the wait it got, or the report after one and the sample it gave. */
    data class Result(
        /** Where the subtitles wait: set when this report is a drag and the samples make a wait. */
        val hold: Hold? = null,
        /** This report is a drag: the wait it got, 0 while the samples are too few. */
        val dragWaitMs: Long? = null,
        /** This report came after a drag and gave this sample, now among the samples. */
        val sampleMs: Long? = null,
        /** This report came after a drag and gave no sample, for this reason. */
        val skipped: Skip? = null,
    )

    // The report before, which a drag jumps away from.
    private var last: Report? = null
    // The drag whose lag the next report shows.
    private var pending: Report? = null

    /** Take [report], the session's next one. */
    fun onReport(report: Report): Result {
        val previous = last
        last = report
        val dragged = previous != null && isDrag(previous, report)
        val drag = pending
        pending = if (dragged) report else null
        val after = drag?.let { measure(it, report, dragged) } ?: Result()
        if (!dragged) return after
        val waitMs = samples.waitMs()
        val hold = if (waitMs > 0) Hold(report.positionMs, report.updateTimeMs + waitMs, report.speed) else null
        return after.copy(hold = hold, dragWaitMs = waitMs)
    }

    /** Whether [report] plays on [VideoTime.JUMP_MS] or more away from where [previous] put the video. */
    private fun isDrag(previous: Report, report: Report): Boolean {
        if (!report.playing) return false
        val drift = drift(previous, report) ?: return false
        return abs(drift) >= VideoTime.JUMP_MS
    }

    /** The sample [report] gives of how far the video lagged behind [drag], or why it gives none. */
    private fun measure(drag: Report, report: Report, dragged: Boolean): Result {
        if (dragged) return Result(skipped = Skip.DRAGGED_AGAIN)
        if (report.state != PlaybackState.STATE_PAUSED) return Result(skipped = Skip.NOT_A_PAUSE)
        val drift = drift(drag, report) ?: return Result(skipped = Skip.UNKNOWN)
        val since = report.updateTimeMs - drag.updateTimeMs
        val skip = when {
            report.title != drag.title -> Skip.OTHER_TITLE
            since < MIN_SAMPLE_AFTER_MS -> Skip.TOO_SOON
            since > MAX_SAMPLE_AFTER_MS -> Skip.TOO_LATE
            abs(drift) >= VideoTime.JUMP_MS -> Skip.TOO_FAR
            else -> null
        }
        if (skip != null) return Result(skipped = skip)
        // The drift is in video time, the wait in real time.
        val lagMs = ((-drift).coerceAtLeast(0L) / drag.speed.toDouble()).toLong()
        samples.add(lagMs)
        return Result(sampleMs = lagMs)
    }

    private fun drift(previous: Report, report: Report): Long? = VideoTime.drift(
        previous.positionMs, previous.updateTimeMs, previous.speed, previous.playing,
        report.positionMs, report.updateTimeMs,
    )

    companion object {
        /** A pause sooner after a drag may come while the video still loads: as long as the longest wait. */
        const val MIN_SAMPLE_AFTER_MS = DragSamples.MAX_WAIT_MS
        /** A pause later after a drag may count a stall meanwhile as the drag's. */
        const val MAX_SAMPLE_AFTER_MS = 120_000L
    }
}

/**
 * The lags an app's videos showed after drags, oldest first, and the wait
 * they make (docs/plan-player-sync-ui.md, 3.1). Kept across runs as text
 * ([encode], [decode]).
 */
class DragSamples(initial: List<Long> = emptyList()) {

    private val values = ArrayDeque<Long>()

    init {
        initial.forEach { add(it) }
    }

    /** The samples, oldest first. */
    val list: List<Long>
        get() = values.toList()

    /** Keep [lagMs], dropping the oldest beyond [MAX_SAMPLES]. */
    fun add(lagMs: Long) {
        values.addLast(lagMs.coerceAtLeast(0L))
        while (values.size > MAX_SAMPLES) values.removeFirst()
    }

    /**
     * How long the subtitles wait after a drag: the median of the samples
     * (the lower middle one of an even count), which one drag that loaded
     * very fast or very slow doesn't sway. None until there are
     * [MIN_SAMPLES], at most [MAX_WAIT_MS].
     */
    fun waitMs(): Long {
        if (values.size < MIN_SAMPLES) return 0L
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2].coerceAtMost(MAX_WAIT_MS)
    }

    /** The samples as text to keep, e.g. "1940,1150,1070". */
    fun encode(): String = values.joinToString(",")

    /** E.g. "1940 1150 1070", or "none". */
    override fun toString(): String = if (values.isEmpty()) "none" else values.joinToString(" ")

    companion object {
        const val MAX_SAMPLES = 5
        const val MIN_SAMPLES = 2
        /** The longest wait: a load that takes longer is rare, and the next pause sets it right. */
        const val MAX_WAIT_MS = 3_000L

        /** The samples [encode] wrote into [text]; anything else in it is skipped. */
        fun decode(text: String?): DragSamples =
            DragSamples(text.orEmpty().split(',').mapNotNull { part -> part.trim().toLongOrNull()?.takeIf { it >= 0 } })
    }
}
