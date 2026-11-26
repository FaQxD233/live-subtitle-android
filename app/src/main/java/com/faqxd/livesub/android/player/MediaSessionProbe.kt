package com.faqxd.livesub.android.player

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import com.faqxd.livesub.android.subtitle.VideoTime
import java.util.Locale
import kotlin.math.abs

/**
 * Phase 0 of following the video app (docs/plan-mediasession-sync.md): a
 * readout of the media sessions other apps publish, and the numbers
 * following them would rest on: how often each session reports, how old
 * a report is when it arrives, and how far it lands from where the report
 * before put the video. It only watches; the subtitles keep their own
 * clock.
 *
 * Watching needs notification access ([MediaListenerService]): each
 * [refresh] starts it once access is there and stops it once access is
 * gone. Main thread only.
 */
class MediaSessionProbe(private val context: Context, private val handler: Handler) {

    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listener = MediaListenerService.component(context)
    private val startedAt = SystemClock.elapsedRealtime()
    // The sessions watched, in the order the system lists them.
    private var watched = LinkedHashMap<MediaSession.Token, Watched>()
    private val events = ArrayDeque<String>()
    private var listening = false
    // Why the sessions can't be read; null while they can.
    private var problem: String? = null
    private var listenerState: MediaListenerService.State? = null
    // App labels by package name; null for apps this one can't see.
    private val labels = HashMap<String, String?>()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        // Documented as possibly null.
        log("list changed: " + (controllers?.joinToString(" ") { short(it.packageName) } ?: "null"))
        watch(controllers.orEmpty())
    }

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
        val controllers = try {
            if (!listening) {
                manager.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
                listening = true
                log("listening")
            }
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            // No access yet, or it was taken back.
            if (problem == null) log("refused: ${e.message}")
            problem = "session list refused (SecurityException)"
            stopWatching()
            return
        }
        if (problem != null) {
            problem = null
            log("session list allowed")
        }
        watch(controllers)
    }

    /** Stop watching, for good. */
    fun stop() = stopWatching()

    /** A few lines for the controls: access, the listener, each session now with its numbers so far. */
    fun readout(): String = buildString {
        val now = SystemClock.elapsedRealtime()
        val granted = MediaListenerService.isGranted(context)
        append(accessText(granted)).append(", listener ").append(MediaListenerService.state.name.lowercase(Locale.US))
        problem?.let { append('\n').append(it) }
        if (problem == null && watched.isEmpty()) append("\nno active session")
        watched.values.forEachIndexed { i, session ->
            append('\n').append(i + 1).append(". ").append(short(session.name))
            append(' ').append(stateText(session.state, now))
            append("\n   ").append(metaText(session.metadata))
            append("\n   ").append(session.reports).append(" reports, drift ").append(session.drifts)
        }
        append(if (granted) "\nlong-press to copy" else "\ntap to grant access, long-press to copy")
    }

    /** The whole record as text to paste: device, access, each session in full, the events. */
    fun report(): String = buildString {
        val now = SystemClock.elapsedRealtime()
        appendLine("LiveBuddy media sessions, phase 0 (docs/plan-mediasession-sync.md)")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        append(secondsText(now - startedAt)).append(" s recorded, ")
        append(accessText(MediaListenerService.isGranted(context)))
        append(", listener ").append(MediaListenerService.state.name.lowercase(Locale.US))
        problem?.let { append(", ").append(it) }
        appendLine()
        if (watched.isEmpty()) appendLine("no session watched")
        watched.values.forEachIndexed { i, session ->
            appendLine("${i + 1}. ${session.name} (label ${label(session.name) ?: "not visible"}): ${stateText(session.state, now)}")
            appendLine("   ${metaText(session.metadata)}")
            appendLine("   ${session.summary()}")
        }
        appendLine("events, last $MAX_EVENTS (steady reports only counted):")
        events.forEach { appendLine(it) }
    }

    /** Watch [controllers], the sessions now in the system's order: new ones join, gone ones leave. */
    private fun watch(controllers: List<MediaController>) {
        val before = watched.keys.toList()
        val next = LinkedHashMap<MediaSession.Token, Watched>()
        for (controller in controllers) {
            val token = controller.sessionToken
            if (token in next) continue
            next[token] = watched.remove(token) ?: Watched(controller).also {
                controller.registerCallback(it.callback, handler)
                log(
                    "${it.name} joins, label ${label(it.name) ?: "not visible"}: " +
                        "${stateText(it.state)}, ${metaText(it.metadata)}"
                )
            }
        }
        for (gone in watched.values) {
            gone.controller.unregisterCallback(gone.callback)
            log("${gone.name} leaves: ${gone.summary()}")
        }
        watched = next
        if (next.size > 1 && next.keys.toList() != before) {
            log("order: " + next.values.joinToString(" ") { short(it.name) })
        }
    }

    private fun stopWatching() {
        if (listening) {
            manager.removeOnActiveSessionsChangedListener(sessionsChanged)
            listening = false
        }
        for (session in watched.values) {
            session.controller.unregisterCallback(session.callback)
            log("${session.name} no longer watched: ${session.summary()}")
        }
        watched = LinkedHashMap()
    }

    private fun onState(session: Watched, state: PlaybackState?) {
        val now = SystemClock.elapsedRealtime()
        if (session.reports > 0) session.gaps.add(now - session.lastReportAt)
        session.reports++
        session.lastReportAt = now
        val previous = session.state
        session.state = state
        if (state == null) {
            log("${session.name} state null")
            return
        }
        val age = if (state.lastPositionUpdateTime > 0) now - state.lastPositionUpdateTime else null
        if (age != null) session.ages.add(age)
        val drift = drift(previous, state)
        if (drift != null) session.drifts.add(abs(drift))
        val changed = previous == null || previous.state != state.state ||
            previous.playbackSpeed != state.playbackSpeed
        // Steady reports only count; the rest is what following would act on.
        if (changed || drift == null || abs(drift) >= NOTABLE_DRIFT_MS) {
            log("${session.name} ${stateText(state)}, age ${age ?: "?"}" + (drift?.let { ", drift $it" } ?: ""))
        }
    }

    /**
     * How far [state]'s position lands from where [previous] put the video
     * at [state]'s update time, both playing at one speed; null otherwise
     * (not both playing, another speed, a position or time unknown).
     */
    private fun drift(previous: PlaybackState?, state: PlaybackState): Long? {
        if (previous == null || !playing(previous) || !playing(state)) return null
        if (previous.playbackSpeed != state.playbackSpeed) return null
        if (previous.position < 0 || state.position < 0) return null
        if (previous.lastPositionUpdateTime <= 0 || state.lastPositionUpdateTime <= 0) return null
        val predicted = VideoTime.positionAt(
            previous.position, previous.lastPositionUpdateTime, previous.playbackSpeed,
            playing = true, now = state.lastPositionUpdateTime,
        )
        return state.position - predicted
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

    /** [text] in quotes, cut to [TITLE_CHARS], followed by its full length. */
    private fun quote(text: String): String {
        val shown = if (text.length > TITLE_CHARS) text.take(TITLE_CHARS) + "…" else text
        return "\"$shown\" (${text.length})"
    }

    /** The app's label, or null where package visibility hides the app from this one. */
    @Suppress("DEPRECATION")
    private fun label(packageName: String): String? {
        if (packageName in labels) return labels[packageName]
        val found = try {
            val packages = context.packageManager
            packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        labels[packageName] = found
        return found
    }

    private fun log(event: String) {
        events.addLast("+${secondsText(SystemClock.elapsedRealtime() - startedAt)} $event")
        if (events.size > MAX_EVENTS) events.removeFirst()
    }

    /** A session watched: what it reported last, and how it reports. */
    private inner class Watched(val controller: MediaController) {
        val name: String = controller.packageName
        var state: PlaybackState? = controller.playbackState
        var metadata: MediaMetadata? = controller.metadata
        var reports = 0
        var lastReportAt = 0L
        /** Time between two state reports. */
        val gaps = Stat()
        /** How old a report's position was when it arrived: now − its update time. */
        val ages = Stat()
        /** How far a report lands from where the one before put the video, both playing at one speed. */
        val drifts = Stat()

        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = onState(this@Watched, state)

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                this@Watched.metadata = metadata
                log("$name meta: ${metaText(metadata)}")
            }

            override fun onSessionDestroyed() = log("$name destroyed")
        }

        fun summary(): String = "$reports reports, gap $gaps, age $ages, drift $drifts"
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
        /** A report landing this far from the predicted position is logged: the debounce threshold assumed so far (plan, 4.1). */
        const val NOTABLE_DRIFT_MS = 300L
        const val TITLE_CHARS = 24
        val ACTIONS = listOf(
            PlaybackState.ACTION_PLAY to "play",
            PlaybackState.ACTION_PAUSE to "pause",
            PlaybackState.ACTION_PLAY_PAUSE to "play_pause",
            PlaybackState.ACTION_SEEK_TO to "seek",
        )

        fun accessText(granted: Boolean): String = if (granted) "access granted" else "access missing"

        fun short(packageName: String): String = packageName.substringAfterLast('.')

        fun secondsText(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)

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
