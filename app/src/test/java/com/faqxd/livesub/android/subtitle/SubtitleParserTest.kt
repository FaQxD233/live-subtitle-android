package com.faqxd.livesub.android.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class SubtitleParserTest {

    private fun parse(text: String, charset: Charset = Charsets.UTF_8) =
        SubtitleParser.parse(text.toByteArray(charset))

    @Test
    fun srtWithBomAndCrlf() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\nWorld\r\n\r\n" +
            "2\r\n00:00:03,000 --> 00:00:04,000\r\n<I>Bye</I>\r\n"
        assertEquals(
            listOf(SubtitleCue(1_000, 2_500, "Hello\nWorld"), SubtitleCue(3_000, 4_000, "<i>Bye</i>")),
            parse(srt),
        )
    }

    @Test
    fun srtWithoutIndexOrBlankLinesAndDotMilliseconds() {
        val srt = "00:00:01.000 --> 00:00:02.000\nOne\n2\n00:00:03.000 --> 00:00:04.000\nTwo"
        assertEquals(
            listOf(SubtitleCue(1_000, 2_000, "One"), SubtitleCue(3_000, 4_000, "Two")),
            parse(srt),
        )
    }

    @Test
    fun srtMarkupOtherThanItalicBoldUnderlineIsRemoved() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n{\\an8}<font color=\"#ffff00\">Top</font> <b>line</b>\n"
        assertEquals(listOf(SubtitleCue(1_000, 2_000, "Top <b>line</b>")), parse(srt))
    }

    @Test
    fun srtCuesAreSortedAndFixedWhenTheyEndTooEarly() {
        val srt = "1\n00:00:05,000 --> 00:00:05,000\nLate\n\n2\n00:00:01,000 --> 00:00:02,000\nEarly\n"
        assertEquals(
            listOf(SubtitleCue(1_000, 2_000, "Early"), SubtitleCue(5_000, 7_000, "Late")),
            parse(srt),
        )
    }

    @Test
    fun webVtt() {
        val vtt = "WEBVTT\n\nNOTE a comment\n\nSTYLE\n::cue { color: red }\n\n" +
            "intro\n00:01.000 --> 00:02.500 align:start position:10%\n<v Bob>Hi &amp; bye</v>\n\n" +
            "00:00:03.000 --> 00:00:04.000\n<c.yellow>Two</c> <00:00:03.500>words\n"
        assertEquals(
            listOf(SubtitleCue(1_000, 2_500, "Hi &amp; bye"), SubtitleCue(3_000, 4_000, "Two words")),
            parse(vtt),
        )
    }

    @Test
    fun ass() {
        val ass = "[Script Info]\nTitle: x\n\n[V4+ Styles]\nFormat: Name, Fontname\nStyle: Default,Arial\n\n" +
            "[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Comment: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,ignored\n" +
            "Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\\i1}Hello,{\\i0} world\\Nsecond line\n" +
            "Dialogue: 0,0:00:04.00,0:00:05.00,Default,,0,0,0,,{\\p1}m 0 0 l 10 0{\\p0}\n"
        assertEquals(listOf(SubtitleCue(1_500, 3_000, "Hello, world\nsecond line")), parse(ass))
    }

    @Test
    fun assColumnsFollowTheFormatLine() {
        val ass = "[Events]\nFormat: Start, End, Text\nDialogue: 0:00:01.00,0:00:02.00,Hi, there\n"
        assertEquals(listOf(SubtitleCue(1_000, 2_000, "Hi, there")), parse(ass))
    }

    @Test
    fun encodingsAreRecognized() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n你好世界\n"
        val expected = listOf(SubtitleCue(1_000, 2_000, "你好世界"))
        assertEquals(expected, parse(srt))
        assertEquals(expected, parse("﻿" + srt, Charsets.UTF_16LE))
        assertEquals(expected, parse(srt, Charsets.UTF_16LE))
        assertEquals(expected, parse(srt, Charsets.UTF_16BE))
        assertEquals(expected, parse(srt, Charset.forName("GBK")))
    }

    @Test
    fun textWithoutTimingHasNoCues() {
        assertTrue(parse("hello world\nno subtitles here").isEmpty())
        assertTrue(SubtitleParser.parse(ByteArray(0)).isEmpty())
    }
}
