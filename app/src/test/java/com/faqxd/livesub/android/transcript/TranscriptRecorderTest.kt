package com.faqxd.livesub.android.transcript

import com.faqxd.livesub.android.transcript.TranscriptRecorder.EndedSession
import com.faqxd.livesub.android.transcript.TranscriptRecorder.Entry
import com.faqxd.livesub.android.transcript.TranscriptRecorder.Line
import com.faqxd.livesub.android.transcript.TranscriptRecorder.Session
import com.faqxd.livesub.android.transcript.TranscriptRecorder.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranscriptRecorderTest {

    private var now = BASE
    private val recorded = ArrayList<Entry>()
    private val recorder = TranscriptRecorder { now }.also { r -> r.onEntry = { recorded.add(it) } }

    @Before
    fun startSession() {
        now = BASE
        recorder.startSession()
    }

    @Test
    fun pauseEndsTheLine() {
        at(0, "Hello.")
        at(3_000, " World")
        assertEquals(listOf(Session(BASE), translation(0, "Hello.", 0)), recorded)
        assertEquals(translation(3_000, "World", 3_000), openLine())
    }

    @Test
    fun cutsAfterTheLastCompleteSentenceOnceLongEnough() {
        at(0, "Hello everyone.")
        at(500, " Today we")
        at(1_000, " talk.")
        assertTrue(lines().isEmpty())
        at(1_600, " Next")
        assertEquals(listOf(translation(0, "Hello everyone. Today we talk.", 1_000)), lines())
        assertEquals(translation(1_600, "Next", 1_600), openLine())
    }

    @Test
    fun decimalPointSplitAcrossSegmentsIsNotASentenceEnd() {
        at(0, "Pi is 3.")
        at(1_800, "14 roughly.")
        assertTrue(lines().isEmpty())
        at(2_100, " Yes")
        assertEquals(listOf(translation(0, "Pi is 3.14 roughly.", 1_800)), lines())
    }

    @Test
    fun shortSentencesShareALine() {
        at(0, "Yes. No. Maybe")
        at(600, " so. Fine")
        assertTrue(lines().isEmpty())
        at(1_500, " then.")
        assertEquals(listOf(translation(0, "Yes. No. Maybe so.", 600)), lines())
        assertEquals(translation(600, "Fine then.", 1_500), openLine())
    }

    @Test
    fun longLineIsCutBeforeTheNextSegment() {
        at(0, "a")
        for (i in 1..6) at(i * 1_000L, " w$i")
        assertTrue(lines().isEmpty())
        at(7_000, " w7")
        assertEquals(listOf(translation(0, "a w1 w2 w3 w4 w5 w6", 6_000)), lines())
        assertEquals(translation(7_000, "w7", 7_000), openLine())
    }

    @Test
    fun wideLineIsCutAfterItsLastSentence() {
        val long = "天".repeat(38)
        at(0, "你好。")
        at(100, "今天")
        at(200, long)
        assertEquals(listOf(translation(0, "你好。", 0)), lines())
        assertEquals(translation(100, "今天$long", 200), openLine())
    }

    @Test
    fun wideLineWithoutSentenceEndIsCutWhole() {
        // 4 + 82 columns: just over MAX_CUE_WIDTH.
        val long = "天".repeat(41)
        at(0, "今天")
        at(100, long)
        assertEquals(listOf(translation(0, "今天", 0)), lines())
        assertEquals(translation(100, long, 100), openLine())
    }

    @Test
    fun breakLinesClosesEveryStream() {
        at(0, "Bonjour", Stream.ORIGINAL)
        at(100, "Hello")
        recorder.breakLines()
        assertEquals(
            listOf(Line(Stream.ORIGINAL, t(0), "Bonjour", t(0)), translation(100, "Hello", 100)),
            lines(),
        )
    }

    @Test
    fun runWithoutCaptionsHasNoHeader() {
        assertTrue(recorder.snapshot().isEmpty())
        now = t(10_000)
        assertEquals(EndedSession(BASE, t(10_000), 0), recorder.endSession())
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun endSessionFinishesTheOpenLine() {
        at(0, "Hi there.")
        now = t(6_000)
        val ended = recorder.endSession()
        assertEquals(EndedSession(BASE, t(6_000), 1), ended)
        assertTrue(ended!!.isWorthKeeping)
        assertEquals(listOf(Session(BASE), translation(0, "Hi there.", 0)), recorded)
    }

    @Test
    fun segmentsOutsideASessionAreIgnored() {
        recorder.endSession()
        at(100, "Late")
        assertTrue(recorder.snapshot().isEmpty())
    }

    private fun at(offset: Long, segment: String, stream: Stream = Stream.TRANSLATION) {
        now = t(offset)
        recorder.append(stream, segment)
    }

    private fun lines(): List<Line> = recorded.filterIsInstance<Line>()

    private fun openLine(): Line = recorder.snapshot().filterIsInstance<Line>().last()

    private fun translation(start: Long, text: String, end: Long) =
        Line(Stream.TRANSLATION, t(start), text, t(end))

    private fun t(offset: Long) = BASE + offset

    private companion object {
        // The recorder treats 0 as "no session", so the clock starts later.
        const val BASE = 1_000_000L
    }
}
