package com.faqxd.livesub.android.player

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler

/**
 * Keeps up with the media sessions other apps publish
 * (docs/plan-mediasession-sync.md): listens to the system's list, which
 * takes notification access ([MediaListenerService]), keeps a callback on
 * every session in it (one starting to play doesn't necessarily change
 * the list), and tells [listener] what happens. Shared by [VideoFollower]
 * and [MediaSessionProbe]. Main thread only.
 */
class MediaSessionWatcher(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {

    /** What happens to the sessions, all on the main thread. */
    interface Listener {
        /** The system reported the list ([controllers] may come null), before [sessions] catch up with it. */
        fun onListChanged(controllers: List<MediaController>?) {}
        fun onSessionAdded(session: MediaController) {}
        fun onSessionRemoved(session: MediaController) {}
        /** [sessions] changed: one came or went, or the system reordered them. */
        fun onSessionsChanged() {}
        fun onStateChanged(session: MediaController, state: PlaybackState?) {}
        fun onMetadataChanged(session: MediaController, metadata: MediaMetadata?) {}
        fun onSessionDestroyed(session: MediaController) {}
    }

    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = MediaListenerService.component(context)
    // By session, in the system's order: playing ones first, the latest to start first.
    private var watched = LinkedHashMap<MediaSession.Token, Watched>()
    // App labels by package name; null for apps this one can't see.
    private val labels = HashMap<String, String?>()

    private val listChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        listener.onListChanged(controllers)
        update(controllers.orEmpty())
    }

    /** Whether the list is listened to: access was there when last asked. */
    var isListening = false
        private set

    /** The sessions in the system's order, as of the last update. */
    val sessions: List<MediaController>
        get() = watched.values.map { it.controller }

    /**
     * Listen to the list if not yet, and re-read it, as its order changes
     * unannounced. False without notification access: then nothing is
     * watched any more, silently (the listener hears nothing of it).
     */
    fun refresh(): Boolean {
        val controllers = try {
            if (!isListening) {
                manager.addOnActiveSessionsChangedListener(listChanged, component, handler)
                isListening = true
            }
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            // No access yet, or it was taken back.
            stop()
            return false
        }
        update(controllers)
        return true
    }

    /** Stop listening and drop every session, silently. */
    fun stop() {
        if (isListening) {
            manager.removeOnActiveSessionsChangedListener(listChanged)
            isListening = false
        }
        for (session in watched.values) session.controller.unregisterCallback(session.callback)
        watched = LinkedHashMap()
    }

    /** The app's name, or null where package visibility hides the app from this one. */
    @Suppress("DEPRECATION")
    fun label(packageName: String): String? {
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

    /** Catch up with [controllers], the list in the system's order. */
    private fun update(controllers: List<MediaController>) {
        val before = watched.keys.toList()
        val next = LinkedHashMap<MediaSession.Token, Watched>()
        val added = ArrayList<MediaController>()
        for (controller in controllers) {
            val token = controller.sessionToken
            if (token in next) continue
            next[token] = watched.remove(token) ?: Watched(controller).also {
                controller.registerCallback(it.callback, handler)
                added += controller
            }
        }
        val removed = watched.values.toList()
        watched = next
        for (gone in removed) {
            gone.controller.unregisterCallback(gone.callback)
            listener.onSessionRemoved(gone.controller)
        }
        for (session in added) listener.onSessionAdded(session)
        if (next.keys.toList() != before) listener.onSessionsChanged()
    }

    private inner class Watched(val controller: MediaController) {
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = listener.onStateChanged(controller, state)

            override fun onMetadataChanged(metadata: MediaMetadata?) = listener.onMetadataChanged(controller, metadata)

            override fun onSessionDestroyed() = listener.onSessionDestroyed(controller)
        }
    }
}
