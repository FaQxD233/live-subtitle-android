package com.faqxd.livesub.android.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtWriterTest {

    @Test
    fun timestampsArePaddedAndClamped() {
        assertEquals("00:00:00,000", SrtWriter.timestamp(0))
        assertEquals("01:02:03,004", SrtWriter.timestamp(3_723_004))
        assertEquals("00:00:00,000", SrtWriter.timestamp(-5))
    }

    @Test
    fun blocksAreNumberedWithCrlfAndNoBlankLinesInside() {
        val text = SrtWriter.format(
            listOf(SubtitleCue(1_000, 2_500, "Hello"), SubtitleCue(3_000, 4_000, "a\n\nb"))
        )
        assertEquals(
            "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\n\r\n" +
                "2\r\n00:00:03,000 --> 00:00:04,000\r\na\r\nb\r\n\r\n",
            text,
        )
    }

    @Test
    fun fileStartsWithAUtf8ByteOrderMark() {
        val bytes = SrtWriter.encode(listOf(SubtitleCue(0, 1_000, "你好")))
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
    }
}
