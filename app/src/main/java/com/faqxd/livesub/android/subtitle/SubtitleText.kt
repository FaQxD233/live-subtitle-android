package com.faqxd.livesub.android.subtitle

import java.nio.charset.Charset

/** Decoding subtitle files and cleaning up their cue text. */
object SubtitleText {

    /**
     * Android only guarantees the UTF and ISO-8859-1 / ASCII charsets, and
     * a failure here would break this object for good, so the others are
     * looked up leniently: null where the device lacks them.
     */
    private fun charset(vararg names: String): Charset? = names.firstNotNullOfOrNull { name ->
        try {
            Charset.forName(name)
        } catch (_: Exception) {
            null
        }
    }

    /** Simplified Chinese (a superset of GBK and GB2312); UTF-8 on a device without it. */
    private val GB18030: Charset = charset("GB18030", "GBK") ?: Charsets.UTF_8
    /** Traditional Chinese. */
    private val BIG5: Charset? = charset("Big5")
    /** Japanese, as Windows writes it (with its extra symbols), else plain Shift_JIS. */
    private val SHIFT_JIS: Charset? = charset("windows-31j", "Shift_JIS")
    /** Western European text, whose curly quotes ISO-8859-1 lacks. */
    private val WINDOWS_1252: Charset = charset("windows-1252") ?: Charsets.ISO_8859_1

    /**
     * The text of a subtitle file. A byte order mark decides (UTF-8,
     * UTF-16LE, UTF-16BE); without one, UTF-16 is recognized by its zero
     * bytes, then UTF-8 is tried (a few damaged bytes allowed), and any
     * other file is read as Simplified Chinese (GB18030, which covers GBK
     * and GB2312), Traditional Chinese (Big5), Japanese (Shift_JIS) or
     * Western European text (Windows-1252), whichever fits it best.
     */
    fun decode(bytes: ByteArray): String {
        fun startsWith(vararg prefix: Int) =
            bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> utf16WithoutBom(bytes)?.let { String(bytes, it) }
                ?: utf8(bytes)
                ?: String(bytes, legacyCharset(bytes))
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
            .replace("\\h", " ")
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

    /**
     * The text as UTF-8, or null if it isn't: at most [MAX_DAMAGED_SHARE]
     * of its non-ASCII characters may be damaged bytes, so that one stray
     * byte (a file pasted together from several) doesn't turn the whole
     * file into nonsense. Text in another charset breaks far more often.
     */
    private fun utf8(bytes: ByteArray): String? {
        // Malformed input decodes to the replacement character.
        val text = String(bytes, Charsets.UTF_8)
        var nonAscii = 0
        var damaged = 0
        for (c in text) {
            if (c.code < 0x80) continue
            nonAscii++
            if (c.code == REPLACEMENT_CHARACTER) damaged++
        }
        return text.takeIf { damaged <= nonAscii * MAX_DAMAGED_SHARE }
    }

    /**
     * The charset of text that isn't UTF: Windows-1252 if it looks like
     * Western text; else the East Asian double-byte one it reads best in
     * ([plausibility]), if it reads well in any; else GB18030.
     */
    private fun legacyCharset(bytes: ByteArray): Charset {
        val sample = if (bytes.size > SAMPLE_BYTES) bytes.copyOf(SAMPLE_BYTES) else bytes
        // Decided first: a Western byte followed by a letter decodes to some
        // double-byte character, now and then a common one.
        if (looksWestern(sample)) return WINDOWS_1252
        // GB18030 comes first, so it wins a tie.
        val best = candidates
            .map { it to plausibility(String(sample, it.charset), it) }
            .maxByOrNull { it.second }
        return if (best != null && best.second >= MIN_PLAUSIBILITY) best.first.charset else GB18030
    }

    /**
     * How much [text] reads like the language of [candidate]: the share of
     * its non-ASCII characters that are common in it (Japanese needs some
     * kana too). Text decoded with the wrong double-byte charset turns into
     * rare characters, symbols and replacement marks instead. 0 with fewer
     * than [MIN_COMMON] common characters: too little to tell.
     */
    private fun plausibility(text: String, candidate: Candidate): Double {
        var nonAscii = 0
        var common = 0
        var kana = 0
        for (c in text) {
            if (c.code < 0x80) continue
            nonAscii++
            if (c.code in KANA) kana++ else if (c in candidate.common) common++
        }
        if (candidate.japanese) {
            if (kana * 10 < nonAscii) return 0.0
            common += kana
        }
        return if (common < MIN_COMMON) 0.0 else common.toDouble() / nonAscii
    }

    /**
     * Western single-byte text (an accented letter, a curly quote): bytes
     * above 0x7F are rare next to the letters and mostly stand alone,
     * where East Asian double-byte text has them in pairs and runs, and
     * far more of them.
     */
    private fun looksWestern(bytes: ByteArray): Boolean {
        var letters = 0
        var high = 0
        var runs = 0
        var lone = 0
        var run = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v >= 0x80) {
                high++
                run++
                continue
            }
            if (run > 0) {
                runs++
                if (run == 1) lone++
                run = 0
            }
            if (v in 0x41..0x5A || v in 0x61..0x7A) letters++
        }
        if (run > 0) {
            runs++
            if (run == 1) lone++
        }
        return runs > 0 && lone * 2 >= runs && high * 6 <= letters
    }

    /** Non-ASCII characters of a UTF-8 file that may be damaged bytes. */
    private const val MAX_DAMAGED_SHARE = 0.02
    /** The start of a file that tells its charset. */
    private const val SAMPLE_BYTES = 64 * 1024
    /** A double-byte charset fits text in which at least this share of the non-ASCII characters is common. */
    private const val MIN_PLAUSIBILITY = 0.15
    /** Fewer common characters than this tell nothing. */
    private const val MIN_COMMON = 3
    /** U+FFFD, what a decoder puts in place of bytes it can't read. */
    private const val REPLACEMENT_CHARACTER = 0xFFFD
    /** Hiragana and katakana (code points). */
    private val KANA = 0x3041..0x30FF

    /*
     * About 200 of the most frequent Chinese characters, in the forms each
     * script writes them: together they make up a large share of any
     * Chinese text (and many are frequent in Japanese), while text in the
     * wrong charset hits them by chance only. Kept short on purpose: the
     * larger the set, the more often wrong text hits it. Each charset is
     * checked against its own script only, since a byte pair that isn't
     * text in one charset may well be a rare character of the other script.
     */
    /** Written the same in simplified and traditional Chinese. */
    private const val SHARED_HAN =
        "的一是不了人我在有他中上大和地到以要就出可也你生能而子那得下自之年作用道行所然家事成方多去法如都同面起" +
            "看定天分好小部其些主理心她本前但因只想日意力它把十民第公此已工使情明性知全三又正外高由很最重物手向文" +
            "相被什二等新己呢吧啊哦嗯呀您哪怎叫走吃喜快再跟真太爸哥姐死回西今先"
    private const val SIMPLIFIED_HAN =
        "这个们来为国说时会对过发后里么学现当没动还进样实开从无与长机关点将两间问应头体见并经种吗谁让给欢谢请" +
            "刚妈钱错听觉话爱东门车别于着"
    private const val TRADITIONAL_HAN =
        "這個們來為國說時會對過發後裡麼學現當沒動還進樣實開從無與長機關點將兩間問應頭體見並經種嗎誰讓給歡謝請" +
            "剛媽錢錯聽覺話愛東門車別於著裏妳"

    /** A double-byte charset to try, with the characters common in text written in it. */
    private class Candidate(val charset: Charset, commonChars: String, val japanese: Boolean = false) {
        val common: Set<Char> = commonChars.toSet()
    }

    /** In this order, so that on a tie GB18030 wins, then Big5. */
    private val candidates: List<Candidate> = listOfNotNull(
        Candidate(GB18030, SHARED_HAN + SIMPLIFIED_HAN),
        BIG5?.let { Candidate(it, SHARED_HAN + TRADITIONAL_HAN) },
        SHIFT_JIS?.let { Candidate(it, SHARED_HAN + SIMPLIFIED_HAN + TRADITIONAL_HAN, japanese = true) },
    )

    private val RUBY_TEXT = Regex("<rt>.*?</rt>", RegexOption.IGNORE_CASE)
    /** ASS override codes, which SRT files converted from ASS often keep, e.g. `{\an8}`. */
    private val OVERRIDE_CODES = Regex("""\{\\[^}]*\}""")
    private val TAG = Regex("<[^<>\n]*>")
    private val KEPT_TAGS = setOf("<i>", "</i>", "<b>", "</b>", "<u>", "</u>")
}
