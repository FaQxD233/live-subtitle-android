package com.faqxd.livesub.android.subtitle

import java.util.Locale

/** Renders subtitle cues as a SubRip (.srt) file. */
object SrtWriter {

    private const val CRLF = "\r\n"
    private const val BOM = "﻿"

    /**
     * The file bytes: UTF-8 with a byte order mark and CRLF line breaks,
     * which older Windows players need to recognize UTF-8 text.
     */
    fun encode(cues: List<SubtitleCue>): ByteArray = (BOM + format(cues)).toByteArray(Charsets.UTF_8)

    /** Numbered blocks of "start --> end" and the text, separated by blank lines. */
    fun format(cues: List<SubtitleCue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1).append(CRLF)
            append(timestamp(cue.startMs)).append(" --> ").append(timestamp(cue.endMs)).append(CRLF)
            append(cueText(cue.text)).append(CRLF)
            append(CRLF)
        }
    }

    /** "HH:MM:SS,mmm"; negative times are clamped to zero. */
    fun timestamp(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return String.format(
            Locale.US, "%02d:%02d:%02d,%03d",
            t / 3_600_000, t / 60_000 % 60, t / 1_000 % 60, t % 1_000,
        )
    }

    /** A blank line would end the block early, so empty lines are dropped. */
    private fun cueText(text: String): String =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(CRLF).ifEmpty { " " }
}
