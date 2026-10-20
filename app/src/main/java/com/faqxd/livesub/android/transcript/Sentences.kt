package com.faqxd.livesub.android.transcript

/**
 * Sentence end: a run of terminal punctuation (CJK, Latin ! ? …, and the
 * Devanagari / Arabic / Urdu / Burmese / Ethiopic / Armenian full stops),
 * a period followed by whitespace, the end or a closing quote/bracket (so
 * "3.14" is not split), or a line break — plus trailing closers and spaces.
 * Port of `hud_window.py:_SENTENCE_END_RE` with the extra scripts added.
 */
private val SENTENCE_END = Regex("""(?:[。！？!?…।؟۔။።։]+|\.(?=\s|$|[”’"）】])|\n+)[\s”’"）】]*""")

/**
 * The newest [count] sentences of [text], oldest first, each trimmed; the
 * last one may still be in progress. Text without any sentence end counts
 * as one sentence. Port of `hud_window.py:_latest_sentences`.
 */
fun latestSentences(text: String, count: Int): List<String> {
    val sentences = ArrayList<String>()
    var start = 0
    for (match in SENTENCE_END.findAll(text)) {
        val end = match.range.last + 1
        text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
        start = end
    }
    text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
    return sentences.takeLast(count.coerceAtLeast(1))
}
