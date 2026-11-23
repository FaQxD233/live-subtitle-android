package com.faqxd.livesub.android.subtitle

/**
 * One subtitle: [text] (possibly several lines) shown from [startMs] until
 * [endMs], both relative to the start of the media or run.
 */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)
