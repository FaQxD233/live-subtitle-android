package com.faqxd.livesub.android.transcript

/**
 * Sentence end: a run of terminal punctuation (CJK, Latin ! ? …, and the
 * Devanagari / Arabic / Urdu / Burmese / Ethiopic / Armenian full stops),
 * a period followed by whitespace, the end or a closing quote/bracket (so
 * "3.14" is not split), or a line break — plus trailing closers and spaces.
 * Group 1 is the terminal punctuation alone.
 * Port of `hud_window.py:_SENTENCE_END_RE` with the extra scripts added.
 */
private val SENTENCE_END = Regex("""([。！？!?…।؟۔။።։]+|\.(?=\s|$|[”’"）】])|\n+)[\s”’"）】]*""")

/**
 * A sentence end found in a text: [mark] is the index just past its
 * terminal punctuation, [end] the index past the closers and spaces that
 * follow it, i.e. where the next sentence starts.
 */
class SentenceEnd(val mark: Int, val end: Int)

/** The sentence ends in [text], in order. */
fun sentenceEnds(text: CharSequence): Sequence<SentenceEnd> =
    SENTENCE_END.findAll(text).map { match ->
        val punctuation = match.groups[1]?.range ?: match.range
        SentenceEnd(mark = punctuation.last + 1, end = match.range.last + 1)
    }

/**
 * The newest [count] sentences of [text], oldest first, each trimmed; the
 * last one may still be in progress. Text without any sentence end counts
 * as one sentence. Port of `hud_window.py:_latest_sentences`.
 */
fun latestSentences(text: String, count: Int): List<String> {
    val sentences = ArrayList<String>()
    var start = 0
    for (sentenceEnd in sentenceEnds(text)) {
        text.substring(start, sentenceEnd.end).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
        start = sentenceEnd.end
    }
    text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
    return sentences.takeLast(count.coerceAtLeast(1))
}

/**
 * Display width of [text] in columns: characters of the CJK scripts and
 * fullwidth forms take two, everything else one. Plain Java, so it works
 * in JVM unit tests too.
 */
fun displayWidth(text: CharSequence): Int {
    var width = 0
    var i = 0
    while (i < text.length) {
        val codePoint = Character.codePointAt(text, i)
        width += if (isWide(codePoint)) 2 else 1
        i += Character.charCount(codePoint)
    }
    return width
}

private fun isWide(codePoint: Int): Boolean =
    when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL -> true
        // CJK symbols and punctuation, fullwidth forms.
        else -> codePoint in 0x3000..0x303F || codePoint in 0xFF01..0xFF60 || codePoint in 0xFFE0..0xFFE6
    }
