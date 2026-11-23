package com.faqxd.livesub.android.subtitle

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decoding subtitle files and cleaning up their cue text. */
object SubtitleText {

    private val GB18030: Charset = Charset.forName("GB18030")

    /**
     * The text of a subtitle file. A byte order mark decides (UTF-8,
     * UTF-16LE, UTF-16BE); without one, UTF-16 is recognized by its zero
     * bytes, then strict UTF-8 is tried, and anything else is read as
     * GB18030, which covers GBK and GB2312.
     */
    fun decode(bytes: ByteArray): String {
        fun startsWith(vararg prefix: Int) =
            bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> utf16WithoutBom(bytes)?.let { String(bytes, it) }
                ?: strictUtf8(bytes)
                ?: String(bytes, GB18030)
        }
    }

    /**
     * Cue text with only `<i>`, `<b>` and `<u>` left as markup (lowercase):
     * other tags (font colors, WebVTT classes, voices, timestamps), ruby
     * text and ASS override codes are removed, `\N` / `\n` become line
     * breaks and `\h` a no-break space. Character entities are kept, so
     * the result is meant to be rendered as HTML. Blank lines are dropped.
     */
    fun clean(raw: String): String {
        var text = raw
            .replace(RUBY_TEXT, "")
            .replace(OVERRIDE_CODES, "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
        text = TAG.replace(text) { match ->
            val tag = match.value.lowercase()
            if (tag in KEPT_TAGS) tag else ""
        }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** UTF-16 without a byte order mark: mostly-ASCII text has a zero in every other byte. */
    private fun utf16WithoutBom(bytes: ByteArray): Charset? {
        val sample = minOf(bytes.size, 4096) and 1.inv()
        if (sample < 4) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until sample) {
            if (bytes[i].toInt() != 0) continue
            if (i % 2 == 0) evenZeros++ else oddZeros++
        }
        val pairs = sample / 2
        return when {
            oddZeros > pairs * 0.3 && evenZeros < pairs * 0.05 -> Charsets.UTF_16LE
            evenZeros > pairs * 0.3 && oddZeros < pairs * 0.05 -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private val RUBY_TEXT = Regex("<rt>.*?</rt>", RegexOption.IGNORE_CASE)
    /** ASS override codes, which SRT files converted from ASS often keep, e.g. `{\an8}`. */
    private val OVERRIDE_CODES = Regex("""\{\\[^}]*\}""")
    private val TAG = Regex("<[^<>\n]*>")
    private val KEPT_TAGS = setOf("<i>", "</i>", "<b>", "</b>", "<u>", "</u>")
}
