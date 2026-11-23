package com.faqxd.livesub.android.player

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.faqxd.livesub.android.MainActivity
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.service.LiveTranslateService
import com.faqxd.livesub.android.subtitle.CueTimeline
import com.faqxd.livesub.android.subtitle.SubtitleClock
import com.faqxd.livesub.android.subtitle.SubtitleCue

/**
 * Foreground service that plays subtitles over other apps, for videos that
 * have none: the cues loaded into [SubtitlePlayback] are shown in a
 * [SubtitlePlayerOverlay] on a clock the user starts, shifts and aligns by
 * hand (play / pause, ±0.5 s, previous / next line, progress bar).
 *
 * It plays no audio or video, so it is a special-use foreground service.
 * Live translation and the player both float over other apps, so starting
 * one stops the other. Main thread only.
 */
class SubtitlePlayerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val clock = SubtitleClock(SystemClock::elapsedRealtime)
    private var timeline = CueTimeline(emptyList())
    private var title = ""
    private var overlay: SubtitlePlayerOverlay? = null
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val tick = Runnable { render() }

    // The text size and background opacity apply while playing. The
    // framework holds the listener only weakly, so keep a field reference.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        overlay?.updateSettings(AppSettings.load(this))
    }

    private val overlayCallbacks = object : SubtitlePlayerOverlay.Callbacks {
        override fun onPlayPauseClicked() = togglePlayback()

        override fun onPreviousClicked() {
            seekTo(timeline.previousStart(clock.position(), RESTART_LINE_AFTER_MS) ?: 0L)
        }

        override fun onNextClicked() {
            timeline.nextStart(clock.position())?.let { seekTo(it) }
        }

        override fun onShiftClicked(deltaMs: Long) {
            clock.shift(deltaMs)
            overlay?.showShift(deltaMs)
            render()
        }

        override fun onSeek(positionMs: Long) = seekTo(positionMs)

        override fun onFontSizeClicked(deltaSp: Int) {
            val size = AppSettings.load(this@SubtitlePlayerService).subtitleFontSize + deltaSp
            // The preference listener applies it.
            AppSettings.saveSubtitleFontSize(this@SubtitlePlayerService, size)
        }

        // Folded up, the controls only needed an update when the subtitle changed.
        override fun onControlsExpanded() = render()

        override fun onLockChanged(locked: Boolean) {
            // The notification offers "Unlock" while locked.
            updateNotification()
            if (locked) {
                Toast.makeText(this@SubtitlePlayerService, R.string.player_locked_hint, Toast.LENGTH_LONG).show()
            }
        }

        override fun onCloseClicked() = stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppSettings.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_PLAY) {
            start()
        } else if (overlay == null) {
            // A notification action for a player that is gone.
            stopSelf()
        } else {
            when (action) {
                ACTION_TOGGLE -> togglePlayback()
                ACTION_UNLOCK -> overlay?.setLocked(false)
                ACTION_STOP -> stopSelf()
            }
        }
        // Nothing to resume after being killed: the cues were in memory.
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / display resize: span the new width, stay on screen.
        overlay?.onScreenChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacks(tick)
        // Windows added through WindowManager outlive the service unless removed.
        overlay?.detach()
        overlay = null
        notificationManager.cancel(NOTIF_ID)
    }

    /** Play what [SubtitlePlayback] holds from the start, paused until the user taps play. */
    private fun start() {
        title = SubtitlePlayback.title
        timeline = CueTimeline(SubtitlePlayback.cues)
        clock.pause()
        clock.seekTo(0)
        startForegroundWithType()
        if (stopService(Intent(this, LiveTranslateService::class.java))) {
            Toast.makeText(this, R.string.player_stopped_live, Toast.LENGTH_LONG).show()
        }
        val shown = overlay ?: SubtitlePlayerOverlay(this, AppSettings.load(this), overlayCallbacks)
            .also { overlay = it }
        try {
            shown.attach()
        } catch (e: Exception) {
            // Most likely: the overlay permission was revoked.
            Log.e(TAG, "player overlay attach failed: ${e.message}")
            Toast.makeText(this, R.string.err_no_overlay_perm, Toast.LENGTH_LONG).show()
            overlay = null
            stopSelf()
            return
        }
        if (AppSettings.takePlayerHint(this)) {
            Toast.makeText(this, R.string.player_hint, Toast.LENGTH_LONG).show()
        }
        render()
        updateNotification()
    }

    private fun togglePlayback() {
        if (clock.isPlaying) {
            clock.pause()
        } else {
            // Played to the end: start over.
            if (clock.position() >= timeline.durationMs) clock.seekTo(0)
            clock.play()
        }
        render()
        updateNotification()
    }

    private fun seekTo(positionMs: Long) {
        clock.seekTo(positionMs.coerceIn(0L, timeline.durationMs))
        render()
    }

    /**
     * Show the cues of the current position and schedule the next update:
     * when the shown cues change, and every [PROGRESS_TICK_MS] while the
     * control bar shows. Playback pauses once past the last cue.
     */
    private fun render() {
        handler.removeCallbacks(tick)
        val shown = overlay ?: return
        var position = clock.position()
        if (clock.isPlaying && position >= timeline.durationMs + END_GRACE_MS) {
            clock.pause()
            clock.seekTo(timeline.durationMs)
            position = timeline.durationMs
            updateNotification()
        }
        shown.showCues(timeline.active(position))
        shown.setProgress(position, timeline.durationMs, clock.isPlaying)
        if (!clock.isPlaying) return
        var delay = (timeline.nextChange(position) ?: (timeline.durationMs + END_GRACE_MS)) - position
        if (shown.showsControls) delay = minOf(delay, PROGRESS_TICK_MS)
        handler.postDelayed(tick, delay.coerceAtLeast(MIN_TICK_MS))
    }

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.player_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            notificationManager.createNotificationChannel(channel)
        }
        val playing = clock.isPlaying
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_caption_bubble)
            .setContentTitle(getString(R.string.player_notif_title))
            .setContentText(
                getString(if (playing) R.string.player_notif_playing else R.string.player_notif_paused, title)
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(if (playing) R.string.pause else R.string.player_play), action(ACTION_TOGGLE, 1))
        if (overlay?.isLocked == true) {
            builder.addAction(0, getString(R.string.player_unlock), action(ACTION_UNLOCK, 2))
        }
        builder.addAction(0, getString(R.string.stop), action(ACTION_STOP, 3))
        return builder.build()
    }

    private fun action(name: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, SubtitlePlayerService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val TAG = "SubtitlePlayer"
        private const val CHANNEL_ID = "subtitle_player"
        private const val NOTIF_ID = 2
        private const val ACTION_PLAY = "com.faqxd.livesub.android.player.PLAY"
        private const val ACTION_TOGGLE = "com.faqxd.livesub.android.player.TOGGLE"
        private const val ACTION_UNLOCK = "com.faqxd.livesub.android.player.UNLOCK"
        private const val ACTION_STOP = "com.faqxd.livesub.android.player.STOP"
        /** Refresh rate of the time and progress bar while the control bar shows. */
        private const val PROGRESS_TICK_MS = 250L
        private const val MIN_TICK_MS = 16L
        /** Playback pauses this long after the last cue ended. */
        private const val END_GRACE_MS = 1_000L
        /** "Previous" restarts the current line once it has shown this long. */
        private const val RESTART_LINE_AFTER_MS = 1_000L

        /**
         * Play [cues] (HTML text) titled [title] over other apps. Without the
         * overlay permission it says so and opens that setting instead.
         */
        fun play(activity: Activity, title: String, cues: List<SubtitleCue>) {
            if (!Settings.canDrawOverlays(activity)) {
                Toast.makeText(activity, R.string.perm_overlay_rationale, Toast.LENGTH_LONG).show()
                try {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${activity.packageName}"),
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "overlay permission screen unavailable: ${e.message}")
                }
                return
            }
            SubtitlePlayback.load(title, cues)
            ContextCompat.startForegroundService(
                activity,
                Intent(activity, SubtitlePlayerService::class.java).setAction(ACTION_PLAY),
            )
        }
    }
}
