package com.faqxd.livesub.android.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.subtitle.VideoTime

/**
 * Which video app the subtitle player follows, and how its video stands
 * (docs/plan-mediasession-sync.md, 4.2 and 4.4).
 *
 * It follows the session the system lists first and keeps to it while it
 * is listed, unless it doesn't play and another one does; one the user
 * picked ([cycle]) it keeps to whatever plays. The subtitles wait
 * ([Video.hold]) through an ad, a session without seeking whose app has
 * offered seeking before, and through another video, a title other than
 * the first one its app showed for these subtitles.
 *
 * [onChange] runs whenever any of it may have changed. Main thread only.
 */
class VideoFollower(
    private val context: Context,
    handler: Handler,
    private val onChange: () -> Unit,
) {

    /** Why the subtitles wait although a video is followed. */
    enum class Hold { AD, OTHER_VIDEO }

    /** The followed session as it stands: its app, its last report, and whether the subtitles wait. */
    class Video(
        /** The app's name, or its package name where the app isn't visible to this one. */
        val app: String,
        private val state: PlaybackState?,
        val hold: Hold?,
    ) {
        val playing: Boolean = state != null && VideoTime.isPlaying(state.state, state.playbackSpeed)

        /** The speed it reports; 0 or below while paused or rewinding. */
        val speed: Float = state?.playbackSpeed ?: 0f

        /** Whether it says where the video is: without that it can't be followed. */
        val positionKnown: Boolean = state != null && state.position >= 0

        /** Whether its app takes play and pause. */
        val canPlayPause: Boolean = state != null &&
            (state.actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE)) != 0L

        /** The video's position at [now] (elapsedRealtime). */
        fun positionAt(now: Long): Long =
            if (state == null) 0L
            else VideoTime.positionAt(state.position, state.lastPositionUpdateTime, state.playbackSpeed, playing, now)
    }

    private val watcher = MediaSessionWatcher(context, handler, Events())
    private var current: MediaController? = null
    // Picked by the user: kept while it is listed, whatever plays.
    private var pinned = false
    // By package: the title of the video the subtitles are for.
    private val titles = HashMap<String, String>()
    // Packages whose sessions offered seeking (kept across runs); there, a session without it shows an ad.
    private val seekableApps = AppSettings.seekableApps(context).toHashSet()

    /** Whether notification access was there when last asked ([refresh]). */
    var hasAccess = false
        private set

    /** The video followed now; null while there is none (no access, no session). */
    var video: Video? = null
        private set

    /** How many sessions [cycle] goes through. */
    val sessionCount: Int
        get() = watcher.sessions.size

    /** Start following, or catch up, e.g. once access was granted. False without access. */
    fun refresh(): Boolean {
        hasAccess = watcher.refresh()
        evaluate()
        return hasAccess
    }

    /** Stop following, for good. */
    fun stop() {
        watcher.stop()
        current = null
        video = null
    }

    fun play() {
        current?.transportControls?.play()
    }

    fun pause() {
        current?.transportControls?.pause()
    }

    /** Follow the next session in the list instead, and keep to it. */
    fun cycle() {
        val sessions = watcher.sessions
        if (sessions.size < 2) return
        val index = sessions.indexOfFirst { it.sessionToken == current?.sessionToken }
        current = sessions[(index + 1) % sessions.size]
        pinned = true
        evaluate()
    }

    /** The followed app shows another video: the subtitles are for that one now. */
    fun acceptVideo() {
        val session = current ?: return
        title(session)?.let { titles[session.packageName] = it }
        evaluate()
    }

    /** New subtitles: the next video each app shows is theirs. */
    fun forgetVideos() {
        titles.clear()
        evaluate()
    }

    /** Choose the session to follow and size up its video, then tell [onChange]. */
    private fun evaluate() {
        val sessions = watcher.sessions
        val kept = current?.let { followed -> sessions.firstOrNull { it.sessionToken == followed.sessionToken } }
        if (kept == null) pinned = false
        current = when {
            kept == null -> sessions.firstOrNull()
            pinned || isPlaying(kept) -> kept
            else -> sessions.firstOrNull { isPlaying(it) } ?: kept
        }
        video = current?.let { describe(it) }
        onChange()
    }

    private fun describe(session: MediaController): Video {
        val app = session.packageName
        val state = session.playbackState
        learnSeeking(app, state)
        val showsAd = state != null && state.state != PlaybackState.STATE_NONE &&
            (state.actions and PlaybackState.ACTION_SEEK_TO) == 0L && app in seekableApps
        val hold = if (showsAd) Hold.AD else otherVideo(app, title(session))
        return Video(watcher.label(app) ?: app, state, hold)
    }

    /**
     * Whether [title] is another video than the one [app] showed first for
     * these subtitles; the first one it shows becomes theirs. No title
     * (not loaded yet) is no change.
     */
    private fun otherVideo(app: String, title: String?): Hold? {
        if (title == null) return null
        val expected = titles.getOrPut(app) { title }
        return if (title == expected) null else Hold.OTHER_VIDEO
    }

    /** Remember that [app] offers seeking, if [state] does. */
    private fun learnSeeking(app: String, state: PlaybackState?) {
        if (state == null || (state.actions and PlaybackState.ACTION_SEEK_TO) == 0L) return
        if (seekableApps.add(app)) AppSettings.saveSeekableApps(context, seekableApps)
    }

    private fun title(session: MediaController): String? =
        session.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }

    private fun isPlaying(session: MediaController): Boolean =
        session.playbackState?.let { VideoTime.isPlaying(it.state, it.playbackSpeed) } ?: false

    private inner class Events : MediaSessionWatcher.Listener {
        override fun onSessionAdded(session: MediaController) = learnSeeking(session.packageName, session.playbackState)

        override fun onSessionsChanged() = evaluate()

        override fun onStateChanged(session: MediaController, state: PlaybackState?) {
            learnSeeking(session.packageName, state)
            evaluate()
        }

        override fun onMetadataChanged(session: MediaController, metadata: MediaMetadata?) {
            if (session.sessionToken == current?.sessionToken) evaluate()
        }

        override fun onSessionDestroyed(session: MediaController) {
            // The list catches up a moment later; stop following it now.
            if (session.sessionToken == current?.sessionToken) refresh()
        }
    }
}
