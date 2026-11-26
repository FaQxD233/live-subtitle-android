package com.faqxd.livesub.android.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class SubtitleTextTest {

    /** [text] written in [charset] reads back unchanged. */
    private fun assertReadsBack(text: String, charset: String) =
        assertEquals(text, SubtitleText.decode(text.toByteArray(Charset.forName(charset))))

    private fun cue(text: String) = "1\n00:00:01,000 --> 00:00:03,000\n$text\n\n"

    @Test
    fun simplifiedChineseInGbk() =
        assertReadsBack(cue("你在说什么？\n我不知道他们为什么还没来。"), "GBK")

    @Test
    fun traditionalChineseInBig5() =
        assertReadsBack(cue("你在說什麼？\n我不知道他們為什麼還沒來。"), "Big5")

    @Test
    fun japaneseInShiftJis() =
        assertReadsBack(cue("何を言ってるの？\nわからないよ、ごめんね。"), "Shift_JIS")

    @Test
    fun westernTextInWindows1252() = assertReadsBack(
        cue("Don’t worry — it’s fine…\nWe can still make it if we leave right now.") +
            cue("Ça va très bien, merci !\nThen let us go and see what happens next."),
        "windows-1252",
    )

    @Test
    fun englishWithChinesePunctuationInGbkStaysGbk() =
        assertReadsBack(cue("Hello！ How are you？"), "GBK")

    @Test
    fun utf8WithAStrayByteStaysUtf8() {
        val text = cue("你好，世界！这是一个测试。").repeat(50)
        val bytes = text.toByteArray(Charsets.UTF_8)
        // A byte UTF-8 never has, between two ASCII characters of the timing line.
        val damaged = bytes.copyOfRange(0, 9) + byteArrayOf(0xFF.toByte()) + bytes.copyOfRange(9, bytes.size)
        assertEquals(text, SubtitleText.decode(damaged).filter { it.code != 0xFFFD })
    }
}
