package com.faqxd.livesub.android.player

import com.faqxd.livesub.android.subtitle.SubtitleCue

/**
 * What the subtitle player plays next. Screens load it here in process,
 * then start [SubtitlePlayerService]: a long subtitle file is too big for
 * an Intent. Main thread only.
 */
object SubtitlePlayback {

    var title: String = ""
        private set

    /** Cue text is HTML (see [com.faqxd.livesub.android.subtitle.SubtitleText.clean]). */
    var cues: List<SubtitleCue> = emptyList()
        private set

    fun load(title: String, cues: List<SubtitleCue>) {
        this.title = title
        this.cues = cues
    }
}
