package com.faqxd.livesub.android.player

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.faqxd.livesub.android.MainActivity
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.service.LiveTranslateService
import com.faqxd.livesub.android.subtitle.CueTimeline
import com.faqxd.livesub.android.subtitle.SubtitleClock
import com.faqxd.livesub.android.subtitle.SubtitleCue
import com.faqxd.livesub.android.subtitle.VideoTime
import java.io.File

/**
 * Foreground service that plays subtitles over other apps, for videos that
 * have none: the cues loaded into [SubtitlePlayback] are shown in a
 * [SubtitlePlayerOverlay] on a clock the user starts, shifts and aligns by
 * hand (play / pause, ±0.5 s, previous / next line, progress bar, speed).
 * The clock pauses when the screen goes off, and playback of a subtitle
 * continues where it stopped last time ([SubtitlePositions]).
 *
 * With notification access it can follow the video app instead
 * (docs/plan-mediasession-sync.md): [VideoFollower] says what the video
 * does, and the clock keeps the video's position, the subtitles running
 * an offset ahead of it. Play / pause then works the video, previous /
 * next, ±0.5 s and the progress bar move the offset, and the speed is the
 * video's. The controls can also show a readout of the media sessions
 * ([MediaSessionProbe]) for measuring.
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
    // Names the subtitle playing, for SubtitlePositions; empty while none is.
    private var key = ""
    private var overlay: SubtitlePlayerOverlay? = null
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val tick = Runnable { render() }
    // The media sessions readout, while turned on (see the class).
    private var probe: MediaSessionProbe? = null
    private val probeTick = Runnable { refreshProbe() }
    // Following the video app: on while there is a follower; [video] is
    // what it follows as last told. While [tracking] such a video, the
    // clock keeps the video's position and the subtitles run [offset]
    // ahead of it; otherwise the clock keeps the subtitles' own position.
    private var follower: VideoFollower? = null
    private var video: VideoFollower.Video? = null
    private var tracking = false
    private var offset = 0L
    // A drag's wait is over: the subtitles play on with the video.
    private val dragResync = Runnable {
        video?.takeIf { tracking && it.positionKnown }?.let { syncToVideo(it, force = true) }
    }

    // The text size and background opacity apply while playing. The
    // framework holds the listener only weakly, so keep a field reference.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        overlay?.updateSettings(AppSettings.load(this))
    }

    // Most video apps pause when the screen goes off; the subtitles must
    // not run on meanwhile. A followed video tells for itself (some play on).
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!tracking && clock.isPlaying) togglePlayback()
        }
    }

    private val overlayCallbacks = object : SubtitlePlayerOverlay.Callbacks {
        override fun onPlayPauseClicked() = playOrPause()

        override fun onPreviousClicked() {
            moveTo(timeline.previousStart(subtitlePosition(), RESTART_LINE_AFTER_MS) ?: 0L)
        }

        override fun onNextClicked() {
            timeline.nextStart(subtitlePosition())?.let { moveTo(it) }
        }

        override fun onShiftClicked(deltaMs: Long) {
            if (tracking) offset += deltaMs else clock.shift(deltaMs)
            overlay?.showShift(deltaMs)
            render()
        }

        override fun onSeek(positionMs: Long) = moveTo(positionMs)

        // Following a video, the speed is the video's.
        override fun onSpeedClicked() {
            if (!tracking) setSpeed(nextSpeed(clock.rate))
        }

        override fun onSpeedReset() {
            if (!tracking) setSpeed(1.0)
        }

        override fun onFontSizeClicked(deltaSp: Int) {
            val size = AppSettings.load(this@SubtitlePlayerService).subtitleFontSize + deltaSp
            // The preference listener applies it.
            AppSettings.saveSubtitleFontSize(this@SubtitlePlayerService, size)
        }

        // Folded up, the controls only needed an update when the subtitle changed.
        override fun onControlsExpanded() {
            render()
            refreshProbe()
            // Back from granting access, say.
            follower?.refresh()
        }

        override fun onFollowClicked() = toggleFollowing()

        override fun onFollowStatusClicked() {
            val running = follower ?: return
            when {
                !running.refresh() -> openNotificationAccess()
                video?.hold == VideoFollower.Hold.OTHER_VIDEO -> running.acceptVideo()
                else -> running.cycle()
            }
        }

        override fun onSessionsClicked() = toggleProbe()

        override fun onReadoutClicked() {
            if (!MediaListenerService.isGranted(this@SubtitlePlayerService)) openNotificationAccess()
        }

        override fun onReadoutLongPressed() = copyProbeRecord()

        override fun onLockChanged(locked: Boolean) {
            // The notification offers "Unlock" while locked.
            updateNotification()
            if (locked) {
                val hint = if (tracking) R.string.player_locked_hint_following else R.string.player_locked_hint
                Toast.makeText(this@SubtitlePlayerService, hint, Toast.LENGTH_LONG).show()
            }
        }

        // The video app played or paused when the second tap went down. A
        // followed video tells for itself, and toggling it again would undo that.
        override fun onDoubleTap(sinceMs: Long) {
            if (!tracking) togglePlayback(sinceMs)
        }

        override fun onCloseClicked() = stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppSettings.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        // Sent by the system only (its UID), which a not-exported receiver still gets.
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
                ACTION_TOGGLE -> playOrPause()
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
        if (overlay != null) savePosition()
        running = false
        AppSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        unregisterReceiver(screenOffReceiver)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(dragResync)
        probe?.stop()
        probe = null
        handler.removeCallbacks(probeTick)
        follower?.stop()
        follower = null
        // Windows added through WindowManager outlive the service unless removed.
        overlay?.detach()
        overlay = null
        notificationManager.cancel(NOTIF_ID)
    }

    /**
     * Play what [SubtitlePlayback] holds: following the video app when the
     * user left that on and access is there, from the video's position;
     * else paused until the user taps play, from where it stopped last
     * time, or from the start.
     */
    private fun start() {
        // A subtitle playing until now is replaced: remember where it was.
        if (overlay != null) savePosition()
        title = SubtitlePlayback.title
        key = SubtitlePlayback.key
        timeline = CueTimeline(SubtitlePlayback.cues)
        // The offset to a video is kept for one playback only (plan, phase 1).
        offset = 0L
        // Before live translation stops: its run is kept as it ends, and
        // dropping old runs then spares the one shown here (playingRecord).
        running = true
        startForegroundWithType()
        if (stopService(Intent(this, LiveTranslateService::class.java))) {
            Toast.makeText(this, R.string.player_stopped_live, Toast.LENGTH_LONG).show()
        }
        val fresh = overlay == null
        if (fresh) {
            if (AppSettings.followVideo(this) && MediaListenerService.isGranted(this)) startFollowing()
        } else {
            // The next video each app shows is the new subtitles'.
            follower?.forgetVideos()
        }
        // Following a video, the clock keeps its position.
        val resumeAt = if (tracking) null else SubtitlePositions.get(this, key)?.takeIf { it < timeline.durationMs }
        if (!tracking) {
            clock.pause()
            clock.seekTo(resumeAt ?: 0L)
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
        // Another subtitle opened over a running player starts out as a new player would.
        shown.showNewSubtitles()
        shown.setSpeed(clock.rate)
        resumeAt?.let { shown.showResumed(it) }
        if (AppSettings.takePlayerHint(this)) {
            Toast.makeText(this, R.string.player_hint, Toast.LENGTH_LONG).show()
        }
        updateFollowUi()
        render()
        updateNotification()
    }

    /**
     * Play or pause, as of [sinceMs] ago: a double tap is only confirmed
     * a moment after the video app reacted to it.
     */
    private fun togglePlayback(sinceMs: Long = 0L) {
        val at = SystemClock.elapsedRealtime() - sinceMs
        if (clock.isPlaying) {
            clock.pause(at)
        } else {
            // Played to the end: start over.
            if (clock.position() >= timeline.durationMs) clock.seekTo(0)
            clock.play(at)
        }
        render()
        updateNotification()
    }

    /** Play or pause: the followed video, which the subtitles then follow; else the subtitles' own clock. */
    private fun playOrPause() {
        val running = follower
        val followed = video
        if (tracking && running != null && followed != null) {
            if (followed.playing) running.pause() else running.play()
        } else {
            togglePlayback()
        }
    }

    /** Put the subtitles at [positionMs]: by the offset while following a video, else by the clock. */
    private fun moveTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, timeline.durationMs)
        if (tracking) offset = target - clock.position() else clock.seekTo(target)
        render()
    }

    /** How far the subtitles run ahead of the clock: the offset while it keeps a video's position. */
    private fun lead(): Long = if (tracking) offset else 0L

    /** Where the subtitles are: before 0 while a followed video hasn't reached their start yet. */
    private fun subtitlePosition(): Long = clock.position() + lead()

    /** Whether playback shows as playing: a followed video's own state (through an ad too), else the clock's. */
    private fun showsPlaying(): Boolean = if (tracking) video?.playing ?: clock.isPlaying else clock.isPlaying

    /** Turn following the video app on or off, remembered for next time. */
    private fun toggleFollowing() {
        if (follower != null) {
            AppSettings.saveFollowVideo(this, false)
            stopFollowing()
            return
        }
        AppSettings.saveFollowVideo(this, true)
        startFollowing()
        if (follower?.hasAccess != true) openNotificationAccess()
    }

    private fun startFollowing() {
        val created = VideoFollower(this, handler, onNote = { probe?.note(it) }, onChange = { onVideoChanged() })
        // Before it reports anything: the report reads it.
        follower = created
        created.refresh()
    }

    private fun stopFollowing() {
        follower?.stop()
        follower = null
        onVideoChanged()
    }

    /** The followed video changed, or following did: move the subtitles with it, or let them go. */
    private fun onVideoChanged() {
        video = follower?.video
        val trackable = video?.takeIf { it.positionKnown }
        when {
            trackable == null -> if (tracking) stopTracking()
            !tracking -> startTracking(trackable)
            else -> syncToVideo(trackable, force = false)
        }
        updateFollowUi()
    }

    /** The clock keeps [followed]'s position from now on; until it syncs, the subtitles stay where they are. */
    private fun startTracking(followed: VideoFollower.Video) {
        val position = clock.position()
        tracking = true
        clock.seekTo(position - offset)
        syncToVideo(followed, force = true)
        // Play / pause now works the video.
        updateNotification()
    }

    /** Back to the subtitles' own clock, paused where they are (not before their start). */
    private fun stopTracking() {
        val position = subtitlePosition().coerceIn(0L, timeline.durationMs)
        tracking = false
        handler.removeCallbacks(dragResync)
        clock.pause()
        clock.seekTo(position)
        render()
        updateNotification()
    }

    /**
     * Move the clock with [followed]: to its position, speed and playing
     * or not, unless it is that close already ([SYNC_TOLERANCE_MS], or
     * [force]); through an ad or another video, let the subtitles wait
     * where they are. After a drag they wait at the new position while
     * the video loads ([VideoFollower.Video.waitsUntil]), then play on.
     */
    private fun syncToVideo(followed: VideoFollower.Video, force: Boolean) {
        handler.removeCallbacks(dragResync)
        val wasPlaying = clock.isPlaying
        if (followed.hold != null) {
            clock.pause()
        } else {
            val now = SystemClock.elapsedRealtime()
            val position = followed.positionAt(now)
            val speed = followed.speed.toDouble()
            val waitsUntil = followed.waitsUntil(now)
            val playing = followed.playing && waitsUntil == null
            if (force || VideoTime.needsSync(
                    clock.isPlaying, clock.rate, clock.position(),
                    playing, speed, position, SYNC_TOLERANCE_MS,
                )
            ) {
                // Paused or rewinding, a video may report 0 or less, which the clock can't take.
                if (speed > 0) {
                    clock.rate = speed
                    overlay?.setSpeed(speed)
                }
                clock.seekTo(position)
                if (playing) clock.play() else clock.pause()
            }
            if (waitsUntil != null) handler.postDelayed(dragResync, waitsUntil - now)
        }
        render()
        if (wasPlaying != clock.isPlaying) updateNotification()
    }

    /** Show whether and how the subtitles follow the video app. */
    private fun updateFollowUi() {
        val shown = overlay ?: return
        val running = follower
        val followed = video
        val status = when {
            running == null -> getString(R.string.player_follow_off)
            !running.hasAccess -> getString(R.string.player_follow_no_access)
            followed == null -> getString(R.string.player_follow_waiting)
            !followed.positionKnown -> getString(R.string.player_follow_no_position, followed.app)
            followed.hold == VideoFollower.Hold.AD -> getString(R.string.player_follow_ad, followed.app)
            followed.hold == VideoFollower.Hold.OTHER_VIDEO -> getString(R.string.player_follow_other_video, followed.app)
            running.sessionCount > 1 -> getString(R.string.player_follow_switchable, followed.app)
            else -> getString(R.string.player_follow_on, followed.app)
        }
        shown.showFollow(running != null, status, tracking, followed?.canPlayPause == true)
    }

    private fun setSpeed(rate: Double) {
        clock.rate = rate
        overlay?.setSpeed(rate)
        render()
    }

    /** Remember where playback of the subtitle is, unless it played to the end or had barely begun. */
    private fun savePosition() {
        if (key.isEmpty()) return
        val position = subtitlePosition()
        val worthKeeping = position >= RESUME_MIN_MS && position < timeline.durationMs
        SubtitlePositions.put(this, key, if (worthKeeping) position else null)
    }

    /**
     * Show the cues of the current position and schedule the next update:
     * when the shown cues change, and every [PROGRESS_TICK_MS] while the
     * control bar shows. Playback pauses once past the last cue, unless it
     * follows a video, which decides that for itself.
     */
    private fun render() {
        handler.removeCallbacks(tick)
        val shown = overlay ?: return
        var position = subtitlePosition()
        if (!tracking && clock.isPlaying && position >= timeline.durationMs + END_GRACE_MS) {
            clock.pause()
            clock.seekTo(timeline.durationMs)
            position = timeline.durationMs
            updateNotification()
        }
        shown.showCues(timeline.active(position))
        shown.setProgress(position.coerceAtLeast(0L), timeline.durationMs, showsPlaying())
        if (!clock.isPlaying) return
        // Past the last change a followed video plays on: only the progress needs updates.
        val nextChange = timeline.nextChange(position) ?: if (tracking) null else timeline.durationMs + END_GRACE_MS
        var delay = if (nextChange == null) Long.MAX_VALUE else clock.timeUntil(nextChange - lead())
        if (shown.showsControls) delay = minOf(delay, PROGRESS_TICK_MS)
        if (delay == Long.MAX_VALUE) return
        handler.postDelayed(tick, delay.coerceAtLeast(MIN_TICK_MS))
    }

    /**
     * Turn the media sessions readout on or off. Turned on without
     * notification access, it sends the user to that setting first.
     */
    private fun toggleProbe() {
        val running = probe
        if (running != null) {
            running.stop()
            probe = null
            handler.removeCallbacks(probeTick)
            overlay?.showReadout(null)
            return
        }
        probe = MediaSessionProbe(this, handler)
        if (!MediaListenerService.isGranted(this)) openNotificationAccess()
        refreshProbe()
    }

    /**
     * Catch the probe up, every [PROBE_TICK_MS] while it is on: it keeps
     * measuring with the controls folded, and its readout shows on the
     * expanded ones.
     */
    private fun refreshProbe() {
        handler.removeCallbacks(probeTick)
        val running = probe ?: return
        running.refresh()
        overlay?.takeIf { it.showsControls }?.showReadout(running.readout())
        handler.postDelayed(probeTick, PROBE_TICK_MS)
    }

    /** Open the notification access setting, with the controls folded out of its way. */
    private fun openNotificationAccess() {
        if (MediaListenerService.openSettings(this)) {
            Toast.makeText(this, R.string.player_follow_access, Toast.LENGTH_LONG).show()
            overlay?.foldControls()
        } else {
            Toast.makeText(this, R.string.player_follow_no_settings, Toast.LENGTH_LONG).show()
        }
    }

    /** Copy the probe's whole record, to paste it back to whoever reads it. */
    private fun copyProbeRecord() {
        val running = probe ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.player_sessions), running.report()))
        Toast.makeText(this, R.string.player_sessions_copied, Toast.LENGTH_SHORT).show()
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
        val playing = showsPlaying()
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
        // Following a video, play / pause is the video's, where its app takes it.
        if (!tracking || video?.canPlayPause == true) {
            builder.addAction(0, getString(if (playing) R.string.pause else R.string.player_play), action(ACTION_TOGGLE, 1))
        }
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
        /** Refresh rate of the media sessions readout, which extrapolates the positions it shows. */
        private const val PROBE_TICK_MS = 500L
        /** A followed video this close to the clock is left alone: reports jitter (plan, 4.1). */
        private const val SYNC_TOLERANCE_MS = 300L
        private const val MIN_TICK_MS = 16L
        /** Playback pauses this long after the last cue ended. */
        private const val END_GRACE_MS = 1_000L
        /** "Previous" restarts the current line once it has shown this long. */
        private const val RESTART_LINE_AFTER_MS = 1_000L
        /** Stopped earlier than this into a subtitle, it starts from the beginning next time. */
        private const val RESUME_MIN_MS = 10_000L
        /** The speed button steps through these, from the slowest again after the fastest. */
        private val SPEEDS = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
        private const val REQUEST_NOTIFICATIONS = 1

        // A player is up (main thread only).
        private var running = false

        /**
         * Play [cues] (HTML text) titled [title] over other apps. [key]
         * names the subtitle so that playback continues where it stopped
         * last time; [record] is the kept run they come from, if any.
         * Without the overlay permission it opens that setting instead and
         * plays once the user is back with it granted ([resumeIfPermitted]).
         */
        fun play(activity: Activity, title: String, cues: List<SubtitleCue>, key: String, record: File? = null) {
            SubtitlePlayback.load(title, cues, key, record)
            if (!Settings.canDrawOverlays(activity)) {
                Toast.makeText(activity, R.string.perm_overlay_rationale, Toast.LENGTH_LONG).show()
                try {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${activity.packageName}"),
                        )
                    )
                    SubtitlePlayback.awaitingOverlayPermission = true
                } catch (e: Exception) {
                    Log.w(TAG, "overlay permission screen unavailable: ${e.message}")
                }
                return
            }
            startPlayer(activity)
        }

        /** [activity] is back on screen: play what waited for the overlay permission, if it was granted. */
        fun resumeIfPermitted(activity: Activity) {
            if (!SubtitlePlayback.awaitingOverlayPermission) return
            SubtitlePlayback.awaitingOverlayPermission = false
            if (Settings.canDrawOverlays(activity)) startPlayer(activity)
        }

        /** The kept run the player shows, which dropping old runs must spare; null if none. */
        fun playingRecord(): File? = if (running) SubtitlePlayback.record else null

        private fun startPlayer(activity: Activity) {
            // The notification's play, unlock and stop need it on Android 13+;
            // the player runs either way, and the notification shows once allowed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS,
                )
            }
            ContextCompat.startForegroundService(
                activity,
                Intent(activity, SubtitlePlayerService::class.java).setAction(ACTION_PLAY),
            )
        }

        /** The speed after [rate] among [SPEEDS], the slowest after the fastest. */
        private fun nextSpeed(rate: Double): Double = SPEEDS.firstOrNull { it > rate + 0.01 } ?: SPEEDS.first()
    }
}
