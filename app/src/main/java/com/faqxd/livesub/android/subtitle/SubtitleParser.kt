package com.faqxd.livesub.android.subtitle

/**
 * Reads SubRip (.srt), WebVTT (.vtt) and ASS/SSA subtitle files into cues,
 * sorted by start time. The format is told by the content, since file
 * names often change on the way. Lenient: malformed blocks are skipped.
 * Cue text keeps `<i>`, `<b>`, `<u>` and character entities (see
 * [SubtitleText.clean]); ASS styles, positions and effects are ignored.
 */
object SubtitleParser {

    /** At most this many cues are read. */
    const val MAX_CUES = 20_000
    /** A cue's text is cut at this length. */
    private const val MAX_CUE_CHARS = 1_000
    /** Shown this long when a cue doesn't end after it starts. */
    const val FALLBACK_DURATION_MS = 2_000L

    /** The cues of a subtitle file; empty if it has none. */
    fun parse(bytes: ByteArray): List<SubtitleCue> {
        val text = SubtitleText.decode(bytes)
        val cues = if (ASS_SECTION.containsMatchIn(text)) parseAss(text) else parseTimed(text)
        return cues.sortedBy { it.startMs }.take(MAX_CUES)
    }

    /**
     * SubRip and WebVTT: a timing line "start --> end" followed by text
     * lines, blocks separated by blank lines. Index and identifier lines
     * before the timing, cue settings after it, and NOTE / STYLE / REGION
     * blocks (which have no timing line) are skipped.
     */
    private fun parseTimed(text: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val timing = timing(lines[i])
            i++
            if (timing == null) continue
            val body = ArrayList<String>()
            var nextTiming = false
            while (i < lines.size && lines[i].isNotBlank()) {
                if (timing(lines[i]) != null) {
                    nextTiming = true
                    break
                }
                body.add(lines[i])
                i++
            }
            // A block without the blank line after it: its last line is the next block's index.
            if (nextTiming && body.isNotEmpty() && body.last().trim().all { it.isDigit() }) {
                body.removeAt(body.lastIndex)
            }
            cues.addCue(timing.first, timing.second, body.joinToString("\n"))
        }
        return cues
    }

    /** ASS / SSA: the Dialogue lines of the [Events] section, columns named by its Format line. */
    private fun parseAss(text: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        var inEvents = false
        var columns = DEFAULT_ASS_COLUMNS
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                val named = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                if (named.containsAll(listOf("start", "end", "text"))) columns = named
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            // The text is the last column and may itself contain commas.
            val fields = line.substringAfter(':').split(',', limit = columns.size)
            if (fields.size < columns.size) continue
            val start = time(fields[columns.indexOf("start")]) ?: continue
            val end = time(fields[columns.indexOf("end")]) ?: continue
            val body = fields[columns.indexOf("text")]
            // Vector drawings ({\p1}…) aren't text.
            if (ASS_DRAWING.containsMatchIn(body)) continue
            cues.addCue(start, end, body.replace(ASS_BLOCK, ""))
        }
        return cues
    }

    private fun MutableList<SubtitleCue>.addCue(start: Long, end: Long, raw: String) {
        val text = SubtitleText.clean(raw).take(MAX_CUE_CHARS)
        if (text.isEmpty()) return
        add(SubtitleCue(start, if (end > start) end else start + FALLBACK_DURATION_MS, text))
    }

    /** Start and end of a "start --> end" timing line, in ms; null for other lines. */
    private fun timing(line: String): Pair<Long, Long>? {
        val arrow = line.indexOf("-->")
        if (arrow < 0) return null
        val start = time(line.substring(0, arrow)) ?: return null
        val end = time(line.substring(arrow + 3)) ?: return null
        return start to end
    }

    /** The first time in [text] in ms: "[H:]MM:SS[,.fff]", with seconds fractions of any precision. */
    private fun time(text: String): Long? {
        val match = TIME.find(text) ?: return null
        val (hours, minutes, seconds, fraction) = match.destructured
        val ms = fraction.take(3).padEnd(3, '0').toLong()
        return ((hours.ifEmpty { "0" }.toLong() * 60 + minutes.toLong()) * 60 + seconds.toLong()) * 1000 + ms
    }

    private val TIME = Regex("""(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})(?:[,.](\d+))?""")
    private val ASS_SECTION = Regex("""^\s*\[(Script Info|Events)]""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val ASS_BLOCK = Regex("""\{[^}]*}""")
    private val ASS_DRAWING = Regex("""\{[^}]*\\p[1-9]""")
    /** Columns of a Dialogue line when the Format line is missing (ASS v4+ / SSA v4). */
    private val DEFAULT_ASS_COLUMNS =
        listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
}
