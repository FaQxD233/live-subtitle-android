package com.faqxd.livesub.android.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.subtitle.DragSamples
import com.faqxd.livesub.android.subtitle.VideoTime
import java.util.Locale
import kotlin.math.abs

/**
 * A readout of the media sessions other apps publish, with the numbers
 * following them rests on (docs/plan-mediasession-sync.md): how often each
 * session reports, how old a report is when it arrives, and how far it
 * lands from where the playing report before it put the video. A pause
 * reports where the video stopped, so its drift is the error following
 * had just then. It only watches.
 *
 * Watching needs notification access ([MediaListenerService]): each
 * [refresh] starts it once access is there and stops it once access is
 * gone. Main thread only.
 */
class MediaSessionProbe(private val context: Context, handler: Handler) {

    private val startedAt = SystemClock.elapsedRealtime()
    // What each session reported, and how.
    private val records = HashMap<MediaSession.Token, Record>()
    private val events = ArrayDeque<String>()
    // Why the sessions can't be read; null while they can.
    private var problem: String? = null
    private var listenerState: MediaListenerService.State? = null
    private val watcher = MediaSessionWatcher(context, handler, Events())

    init {
        log("start, ${accessText(MediaListenerService.isGranted(context))}")
    }

    /**
     * Catch up: start watching once access is there (or stop once it is
     * gone), and re-read the sessions, whose order changes unannounced.
     */
    fun refresh() {
        val state = MediaListenerService.state
        if (state != listenerState) {
            listenerState = state
            log("listener ${state.name.lowercase(Locale.US)}")
        }
        val wasListening = watcher.isListening
        if (!watcher.refresh()) {
            // No access yet, or it was taken back.
            if (problem == null) log(REFUSED)
            problem = REFUSED
            for (record in records.values) log("${record.name} no longer watched: ${record.summary()}")
            records.clear()
            return
        }
        if (!wasListening) log("listening")
        if (problem != null) {
            problem = null
            log("session list allowed")
        }
    }

    /** Stop watching, for good. */
    fun stop() = watcher.stop()

    /** Log [text]: what following the video made of a report, such as a drag's wait. */
    fun note(text: String) = log(text)

    /** A few lines for the controls: access, the listener, each session now with its numbers so far. */
    fun readout(): String = buildString {
        val now = SystemClock.elapsedRealtime()
        val granted = MediaListenerService.isGranted(context)
        append(accessText(granted)).append(", listener ").append(MediaListenerService.state.name.lowercase(Locale.US))
        problem?.let { append('\n').append(it) }
        val sessions = watcher.sessions
        if (problem == null && sessions.isEmpty()) append("\nno active session")
        sessions.forEachIndexed { i, session ->
            val record = records[session.sessionToken] ?: return@forEachIndexed
            append('\n').append(i + 1).append(". ").append(short(record.name))
            append(' ').append(stateText(record.state, now))
            append("\n   ").append(metaText(record.metadata))
            append("\n   ").append(record.reports).append(" reports, drift ").append(record.drifts)
            append(", jumps ").append(record.jumps)
            dragText(record.name)?.let { append("\n   ").append(it) }
        }
        append(if (granted) "\nlong-press to copy" else "\ntap to grant access, long-press to copy")
    }

    /** The whole record as text to paste: device, access, each session in full, the events. */
    fun report(): String = buildString {
        val now = SystemClock.elapsedRealtime()
        appendLine("LiveBuddy media sessions (docs/plan-mediasession-sync.md)")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        append(secondsText(now - startedAt)).append(" s recorded, ")
        append(accessText(MediaListenerService.isGranted(context)))
        append(", listener ").append(MediaListenerService.state.name.lowercase(Locale.US))
        problem?.let { append(", ").append(it) }
        appendLine()
        val sessions = watcher.sessions
        if (sessions.isEmpty()) appendLine("no session watched")
        sessions.forEachIndexed { i, session ->
            val record = records[session.sessionToken] ?: return@forEachIndexed
            val label = watcher.label(record.name) ?: "not visible"
            appendLine("${i + 1}. ${record.name} (label $label): ${stateText(record.state, now)}")
            appendLine("   ${metaText(record.metadata)}")
            appendLine("   ${record.summary()}")
            dragText(record.name)?.let { appendLine("   $it") }
        }
        appendLine("events, last $MAX_EVENTS (steady reports only counted):")
        events.forEach { appendLine(it) }
    }

    private fun onState(record: Record, state: PlaybackState?) {
        val now = SystemClock.elapsedRealtime()
        if (record.reports > 0) record.gaps.add(now - record.lastReportAt)
        record.reports++
        record.lastReportAt = now
        val previous = record.state
        record.state = state
        if (state == null) {
            log("${record.name} state null")
            return
        }
        val age = if (state.lastPositionUpdateTime > 0) now - state.lastPositionUpdateTime else null
        if (age != null) record.ages.add(age)
        val drift = drift(previous, state)
        if (drift != null) {
            if (abs(drift) < VideoTime.JUMP_MS) record.drifts.add(drift) else record.jumps++
        }
        val changed = previous == null || previous.state != state.state ||
            previous.playbackSpeed != state.playbackSpeed
        // Steady reports only count; the rest is what following acts on.
        if (changed || drift == null || abs(drift) >= NOTABLE_DRIFT_MS) {
            log("${record.name} ${stateText(state)}, age ${age ?: "?"}" + (drift?.let { ", drift $it" } ?: ""))
        }
    }

    /**
     * How far [state]'s position lands from where [previous], playing, put
     * the video at [state]'s update time; null when [previous] wasn't
     * playing or a position or time is unknown.
     */
    private fun drift(previous: PlaybackState?, state: PlaybackState): Long? {
        if (previous == null) return null
        return VideoTime.drift(
            previous.position, previous.lastPositionUpdateTime, previous.playbackSpeed, playing(previous),
            state.position, state.lastPositionUpdateTime,
        )
    }

    /** The wait after a drag [app]'s samples make, and the samples; null before the first. */
    private fun dragText(app: String): String? {
        val samples = DragSamples.decode(AppSettings.dragSamples(context, app))
        if (samples.list.isEmpty()) return null
        return "drag wait ${samples.waitMs()} ms, samples $samples"
    }

    private fun playing(state: PlaybackState): Boolean = VideoTime.isPlaying(state.state, state.playbackSpeed)

    /** [state]'s name, position (at [now] if given, else as reported), speed and the actions it offers. */
    private fun stateText(state: PlaybackState?, now: Long? = null): String {
        if (state == null) return "no state"
        val position = if (now == null || state.position < 0) {
            state.position
        } else {
            VideoTime.positionAt(state.position, state.lastPositionUpdateTime, state.playbackSpeed, playing(state), now)
        }
        return "${stateName(state.state)} ${clock(position)} ${speed(state.playbackSpeed)} [${actions(state.actions)}]"
    }

    /** The title (and display title if another), cut short, and the duration (0 when there is none). */
    private fun metaText(metadata: MediaMetadata?): String {
        if (metadata == null) return "no metadata"
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val displayTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val titles = if (displayTitle.isEmpty() || displayTitle == title) {
            "title ${quote(title)}"
        } else {
            "title ${quote(title)}, display title ${quote(displayTitle)}"
        }
        return "$titles, duration ${clock(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))}"
    }

    private fun log(event: String) {
        events.addLast("+${secondsText(SystemClock.elapsedRealtime() - startedAt)} $event")
        if (events.size > MAX_EVENTS) events.removeFirst()
    }

    private inner class Events : MediaSessionWatcher.Listener {
        override fun onListChanged(controllers: List<MediaController>?) {
            // Documented as possibly null.
            log("list changed: " + (controllers?.joinToString(" ") { short(it.packageName) } ?: "null"))
        }

        override fun onSessionAdded(session: MediaController) {
            val record = Record(session)
            records[session.sessionToken] = record
            val label = watcher.label(record.name) ?: "not visible"
            log("${record.name} joins, label $label: ${stateText(record.state)}, ${metaText(record.metadata)}")
        }

        override fun onSessionRemoved(session: MediaController) {
            records.remove(session.sessionToken)?.let { log("${it.name} leaves: ${it.summary()}") }
        }

        override fun onSessionsChanged() {
            val sessions = watcher.sessions
            if (sessions.size > 1) log("order: " + sessions.joinToString(" ") { short(it.packageName) })
        }

        override fun onStateChanged(session: MediaController, state: PlaybackState?) {
            records[session.sessionToken]?.let { onState(it, state) }
        }

        override fun onMetadataChanged(session: MediaController, metadata: MediaMetadata?) {
            val record = records[session.sessionToken] ?: return
            record.metadata = metadata
            log("${record.name} meta: ${metaText(metadata)}")
        }

        override fun onSessionDestroyed(session: MediaController) = log("${session.packageName} destroyed")
    }

    /** A session watched: what it reported last, and how it reports. */
    private class Record(controller: MediaController) {
        val name: String = controller.packageName
        var state: PlaybackState? = controller.playbackState
        var metadata: MediaMetadata? = controller.metadata
        var reports = 0
        var lastReportAt = 0L
        /** Time between two state reports. */
        val gaps = Stat()
        /** How old a report's position was when it arrived: now − its update time. */
        val ages = Stat()
        /** Where a report lands against where the playing one before it put the video (+ ahead). */
        val drifts = Stat()
        /** Reports [VideoTime.JUMP_MS] or more off that: seeks and restarts rather than drift. */
        var jumps = 0

        fun summary(): String = "$reports reports, gap $gaps, age $ages, drift $drifts, jumps $jumps"
    }

    /** How many ms values, and their average, least and most. */
    private class Stat {
        private var count = 0
        private var sum = 0L
        private var min = 0L
        private var max = 0L

        fun add(value: Long) {
            if (count == 0 || value < min) min = value
            if (count == 0 || value > max) max = value
            count++
            sum += value
        }

        override fun toString(): String = if (count == 0) "-" else "n $count avg ${sum / count} min $min max $max"
    }

    private companion object {
        /** Events kept for the copied record. */
        const val MAX_EVENTS = 100
        /** A report landing this far from the predicted position is logged: the sync tolerance (plan, 4.1). */
        const val NOTABLE_DRIFT_MS = 300L
        const val TITLE_CHARS = 24
        const val REFUSED = "session list refused (SecurityException)"
        val ACTIONS = listOf(
            PlaybackState.ACTION_PLAY to "play",
            PlaybackState.ACTION_PAUSE to "pause",
            PlaybackState.ACTION_PLAY_PAUSE to "play_pause",
            PlaybackState.ACTION_SEEK_TO to "seek",
        )

        fun accessText(granted: Boolean): String = if (granted) "access granted" else "access missing"

        fun short(packageName: String): String = packageName.substringAfterLast('.')

        fun secondsText(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)

        /** [text] in quotes, cut to [TITLE_CHARS], followed by its full length. */
        fun quote(text: String): String {
            val shown = if (text.length > TITLE_CHARS) text.take(TITLE_CHARS) + "…" else text
            return "\"$shown\" (${text.length})"
        }

        fun stateName(state: Int): String = when (state) {
            PlaybackState.STATE_NONE -> "NONE"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
            PlaybackState.STATE_REWINDING -> "REWINDING"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_CONNECTING -> "CONNECTING"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
            else -> "STATE_$state"
        }

        fun actions(mask: Long): String =
            ACTIONS.filter { (bit, _) -> (mask and bit) != 0L }.joinToString(" ") { it.second }

        /** E.g. "1.25×", "0×". */
        fun speed(speed: Float): String = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"

        /** [ms] as m:ss.d; "?" when unknown (negative). */
        fun clock(ms: Long): String {
            if (ms < 0) return "?"
            val tenths = ms / 100
            val wholeSeconds = tenths / 10
            return String.format(Locale.US, "%d:%02d.%d", wholeSeconds / 60, wholeSeconds % 60, tenths % 10)
        }
    }
}
