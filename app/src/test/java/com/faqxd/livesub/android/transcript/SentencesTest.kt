package com.faqxd.livesub.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

class SentencesTest {

    @Test
    fun latestSentencesKeepsTheNewestOnes() {
        assertEquals(listOf("Hello.", "This is a test"), latestSentences("Hello. This is a test", 2))
        assertEquals(listOf("今天下雨。"), latestSentences("你好！今天下雨。", 1))
    }

    @Test
    fun sentenceEndsMarkThePunctuationAndTheNextStart() {
        val ends = sentenceEnds("He said \"hi.\" Then").toList()
        assertEquals(1, ends.size)
        assertEquals(12, ends[0].mark)
        assertEquals(14, ends[0].end)
    }

    @Test
    fun decimalPointIsNotASentenceEnd() {
        assertEquals(0, sentenceEnds("Pi is 3.14 today").count())
    }

    @Test
    fun displayWidthCountsCjkAsTwoColumns() {
        assertEquals(6, displayWidth("ab你好"))
        assertEquals(4, displayWidth("。！"))
        assertEquals(5, displayWidth("Hello"))
    }
}
