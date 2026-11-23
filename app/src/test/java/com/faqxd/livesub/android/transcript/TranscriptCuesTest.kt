package com.faqxd.livesub.android.transcript

import com.faqxd.livesub.android.subtitle.SubtitleCue
import com.faqxd.livesub.android.transcript.TranscriptRecorder.Line
import com.faqxd.livesub.android.transcript.TranscriptRecorder.Session
import com.faqxd.livesub.android.transcript.TranscriptRecorder.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptCuesTest {

    private val entries = listOf(
        Session(1_000),
        Line(Stream.ORIGINAL, 2_500, "Bonjour.", 3_500),
        Line(Stream.TRANSLATION, 3_000, "Hello.", 4_000),
        Line(Stream.TRANSLATION, 4_500, "World.", 4_600),
    )

    @Test
    fun cuesLingerButNeverOverlapTheNextOne() {
        assertEquals(
            listOf(SubtitleCue(2_000, 3_500, "Hello."), SubtitleCue(3_500, 4_700, "World.")),
            TranscriptCues.build(entries, Stream.TRANSLATION),
        )
    }

    @Test
    fun eachStreamIsTimedOnItsOwn() {
        assertEquals(
            listOf(SubtitleCue(1_500, 3_300, "Bonjour.")),
            TranscriptCues.build(entries, Stream.ORIGINAL),
        )
    }

    @Test
    fun linesWithoutEndTimesLastUntilTheNextOne() {
        val legacy = listOf(
            Session(1_000),
            Line(Stream.TRANSLATION, 2_000, "A"),
            Line(Stream.TRANSLATION, 10_000, "B"),
        )
        assertEquals(
            listOf(SubtitleCue(1_000, 9_000, "A"), SubtitleCue(9_000, 39_800, "B")),
            TranscriptCues.build(legacy, Stream.TRANSLATION),
        )
    }

    @Test
    fun runStartsAtItsSession() {
        assertEquals(1_000L, TranscriptCues.startOf(entries))
        assertEquals(2_500L, TranscriptCues.startOf(entries.drop(1)))
        assertNull(TranscriptCues.startOf(emptyList()))
        assertTrue(TranscriptCues.build(emptyList(), Stream.TRANSLATION).isEmpty())
    }
}
