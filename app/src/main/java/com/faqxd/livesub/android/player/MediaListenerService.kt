package com.faqxd.livesub.android.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * The app's notification listener, there for its access alone: an app
 * with notification access may list the media sessions other apps publish
 * ([android.media.session.MediaSessionManager.getActiveSessions]), which
 * the subtitle player is to follow (docs/plan-mediasession-sync.md). It
 * reads no notification.
 *
 * Once access is granted, the system keeps the listener bound, holding
 * the app's process up and passing it every notification posted. The
 * session list checks only that access was granted, so the listener asks
 * to be unbound as soon as it connects (plan, 4.7); after a reboot the
 * system binds it once more, and it unbinds again. Main thread only.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        state = State.CONNECTED
        try {
            requestUnbind()
            state = State.UNBOUND
        } catch (e: RuntimeException) {
            // Still bound then: everything works, the process just stays up.
            Log.w(TAG, "requestUnbind failed: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (state == State.CONNECTED) state = State.NONE
    }

    /** How the listener stands in this process. */
    enum class State {
        /** Not connected since the process started (unbound by an earlier one, or no access). */
        NONE,
        CONNECTED,
        /** Connected, then asked to be unbound. */
        UNBOUND,
    }

    companion object {
        private const val TAG = "MediaListener"

        var state = State.NONE
            private set

        fun component(context: Context): ComponentName = ComponentName(context, MediaListenerService::class.java)

        /** Whether the user granted the app notification access. */
        fun isGranted(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /**
         * Open the notification access setting: the app's own page where
         * the system has one (Android 11+), else the list of apps. False
         * if neither opens.
         */
        fun openSettings(context: Context): Boolean {
            val pages = mutableListOf<Intent>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pages += Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    component(context).flattenToString(),
                )
            }
            pages += Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            for (page in pages) {
                try {
                    context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return true
                } catch (e: Exception) {
                    // The docs warn a matching activity may not exist.
                    Log.w(TAG, "${page.action} unavailable: ${e.message}")
                }
            }
            return false
        }
    }
}
