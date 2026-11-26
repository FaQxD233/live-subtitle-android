package com.faqxd.livesub.android.player

import com.faqxd.livesub.android.subtitle.SubtitleCue
import java.io.File

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

    /** Names the subtitle across runs, so playback can continue where it stopped (see [SubtitlePositions]). */
    var key: String = ""
        private set

    /** The kept run (its journal) the cues come from; null for a subtitle file. */
    var record: File? = null
        private set

    /** Loaded while the overlay permission was missing: to be played once it is granted. */
    var awaitingOverlayPermission = false

    fun load(title: String, cues: List<SubtitleCue>, key: String, record: File? = null) {
        this.title = title
        this.cues = cues
        this.key = key
        this.record = record
        awaitingOverlayPermission = false
    }
}
