package com.faqxd.livesub.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.faqxd.livesub.android.MainActivity
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.audio.AudioCapture
import com.faqxd.livesub.android.audio.AudioPlayer
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.gemini.GeminiClient
import com.faqxd.livesub.android.service.CaptionOverlayView.StatusKind
import com.faqxd.livesub.android.transcript.TranscriptExporter
import com.faqxd.livesub.android.transcript.TranscriptRecorder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the live-translate pipeline.
 *
 * Equivalent of [main.py:LiveBuddyApp]:
 *  - Owns [GeminiClient], [AudioCapture], [AudioPlayer], [CaptionOverlayView].
 *  - Started via [ACTION_START] from [MainActivity]; stopped via [ACTION_STOP]
 *    or system swipe-away (we re-launch the foreground notification).
 *  - Audio source is taken from [AppSettings.audioSource]:
 *      * "mic"     → AudioRecord(RECORD_AUDIO) inside [AudioCapture].
 *      * "system"  → MediaProjection loopback. The projection token is
 *                    forwarded to the service via the intent extra
 *                    [EXTRA_RESULT_CODE] / [EXTRA_RESULT_DATA].
 *
 * The HUD overlay is added as soon as the service starts, so the user sees
 * the floating panel immediately, even before the WebSocket connects.
 */
class LiveTranslateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settings: AppSettings? = null
    private var overlay: CaptionOverlayView? = null
    private var client: GeminiClient? = null
    private var capture: AudioCapture? = null
    private var player: AudioPlayer? = null
    private var mediaProjection: MediaProjection? = null
    @Volatile private var running = false
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // Caption history for export. Survives pipeline restarts, "Clear" and
    // process death (persisted to disk, see [persistTranscript]).
    private val transcriptPersistExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "transcript-persist").apply { isDaemon = true }
        }
    private val transcript = TranscriptRecorder().also {
        it.setPersistHook { lines, startedAt -> persistTranscript(lines, startedAt) }
    }

    // Auto-reconnect state for unexpected WebSocket disconnects.
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    // [SystemClock.elapsedRealtime] of the last successful connection.
    // A disconnect after the connection stayed up for RECONNECT_STABLE_MS
    // counts as healthy and resets the backoff; a connection that drops
    // sooner does not, so a server that kills sessions right after setup
    // can't keep us reconnecting forever.
    @Volatile private var connectedAtMs = 0L
    // Latest resumable-session handle issued by the server. Kept across
    // reconnects / deliberate restarts so the session context continues;
    // cleared on permanent failure and explicit stop.
    @Volatile private var resumptionHandle: String? = null

    // Cached MediaProjection grant from the most recent ACTION_START.
    // Reused by every later start path (restart, toggle, reconnect, a start
    // without a fresh consent) so the user doesn't have to re-grant
    // system-audio capture.
    private var lastResultCode: Int = 0
    private var lastResultData: Intent? = null
        set(value) {
            field = value
            projectionGrantCached = value != null
        }

    // Incremented whenever a new WebSocket session starts or the current
    // pipeline is invalidated. Late callbacks from old sessions are ignored.
    @Volatile private var currentSessionId: Int = 0
    private var pendingCaptureResultCode: Int = 0
    private var pendingCaptureResultData: Intent? = null

    /**
     * Notified when the system revokes the active [MediaProjection]
     * (e.g. screen-off policy, user dismissal). We drop the cached
     * reference so the next start re-grants instead of reusing a dead
     * instance whose token is invalid (esp. on Android 14+).
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mediaProjection = null
            lastResultCode = 0
            lastResultData = null
            Log.w(TAG, "MediaProjection stopped by system; grant cleared")
            // The loopback recorder delivers nothing from now on; say so
            // instead of showing "Connected" over a silent pipeline.
            if (running && settings?.audioSource == "system") {
                failPipeline(getString(R.string.status_system_audio_permission))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / display resize: keep the HUD reachable on the new screen.
        overlay?.onScreenChanged()
    }

    override fun onCreate() {
        super.onCreate()
        // Apply appearance / volume changes live while the pipeline runs.
        // The framework holds the listener only weakly, so keep a strong
        // field reference (see [prefsListener]).
        AppSettings.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        registerNetworkCallback()
        restoreTranscript()
    }

    // ---------- transcript persistence ----------

    private val transcriptFile: File get() = File(filesDir, "transcript.json")

    /**
     * Write the full snapshot (JSON array; the first element carries the
     * session start time) to disk on the persist thread — atomic-ish via
     * a temp file rename so a crash never truncates the good file.
     */
    private fun persistTranscript(lines: List<TranscriptRecorder.Line>, startedAt: Long) {
        if (transcriptPersistExecutor.isShutdown) return
        transcriptPersistExecutor.execute {
            try {
                val arr = org.json.JSONArray()
                arr.put(org.json.JSONObject().put("session_started_at", startedAt))
                for (line in lines) {
                    arr.put(
                        org.json.JSONObject()
                            .put("stream", line.stream.name)
                            .put("started_at", line.startedAt)
                            .put("text", line.text)
                    )
                }
                val tmp = File(filesDir, "transcript.json.tmp")
                tmp.writeText(arr.toString())
                java.nio.file.Files.move(
                    tmp.toPath(),
                    transcriptFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: Exception) {
                Log.w(TAG, "transcript persist failed: ${e.message}")
            }
        }
    }

    /** Load the previous run's lines so the next export still has them. */
    private fun restoreTranscript() {
        if (!transcriptFile.exists()) return
        try {
            val arr = org.json.JSONArray(transcriptFile.readText())
            val restored = ArrayList<TranscriptRecorder.Line>()
            var startedAt = 0L
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.has("session_started_at")) {
                    startedAt = o.optLong("session_started_at", 0L)
                    continue
                }
                restored.add(
                    TranscriptRecorder.Line(
                        TranscriptRecorder.Stream.valueOf(o.getString("stream")),
                        o.getLong("started_at"),
                        o.getString("text"),
                    )
                )
            }
            transcript.restore(restored, startedAt)
            Log.i(TAG, "restored ${restored.size} transcript lines from disk")
        } catch (e: Exception) {
            Log.w(TAG, "transcript restore failed: ${e.message}")
        }
    }

    /**
     * When a network comes back after an outage, most likely the disconnect
     * reason is gone: skip the remaining reconnect backoff and retry right
     * away. Only kicks in while a reconnect is pending — an idle/paused
     * pipeline is never auto-started.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                if (!running && reconnectJob != null) {
                    Log.i(TAG, "network available — reconnecting immediately")
                    cancelReconnect()
                    startPipelineWithCachedGrant()
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "network callback registration failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    /**
     * A preference was written (usually by [com.faqxd.livesub.android.SettingsActivity]).
     * Cosmetic changes (font size, opacity, caption mode, volume) apply
     * immediately; pipeline-affecting fields are handled separately by the
     * restart intent SettingsActivity sends, so a restart path is
     * deliberately not triggered here. Runs on the main thread.
     */
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            applyLiveSettings()
        }

    private fun applyLiveSettings() {
        val s = AppSettings.load(this).also { settings = it }
        overlay?.updateSettings(s)
        overlay?.applyStyle()
        player?.setVolume(s.playbackVolume)
        // The caption mode decides what the main screen mirrors, too.
        publishState()
    }

    // ---------- main-screen state bridge ----------

    // Last status shown on the overlay; republished to [PipelineState]
    // together with the captions whenever anything changes.
    private var lastStateStatus = ""
    private var lastStateKind: StatusKind = StatusKind.IDLE

    /** Show a status on the overlay and mirror it to the main screen. */
    private fun setOverlayStatus(text: String, kind: StatusKind) {
        overlay?.setStatus(text, kind)
        lastStateStatus = text
        lastStateKind = kind
        publishState()
    }

    /** Push the current pipeline snapshot to the main screen. */
    private fun publishState() {
        PipelineState.publish(
            PipelineState.Snapshot(
                running = running,
                kind = lastStateKind,
                status = lastStateStatus,
                input = overlay?.currentInput ?: "",
                output = overlay?.currentOutput ?: "",
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    lastResultCode = resultCode
                    lastResultData = resultData
                }
                // A start without a fresh consent (quick settings tile, or
                // the main screen while this service still holds a live
                // grant) reuses the cached one, like the overlay's Start.
                cancelReconnect()
                startPipelineWithCachedGrant()
            }
            ACTION_STOP -> shutdown()
            ACTION_TOGGLE -> togglePipeline()
            ACTION_RESTART -> restartPipelineIfNeeded()
            ACTION_EXIT_CLICK_THROUGH -> overlay?.setClickThrough(false)
        }
        // START_NOT_STICKY: the service needs RECORD_AUDIO + (optional)
        // MediaProjection consent that we cannot re-acquire after the
        // system kills and recreates us, so don't request an auto-restart
        // with a null intent we couldn't safely handle anyway.
        return START_NOT_STICKY
    }

    /**
     * Toggle Live direction (a2b ↔ b2a), persist it, and force a pipeline
     * restart so the new `translationConfig.targetLanguageCode` takes effect.
     */
    private fun toggleDirection() {
        val s = AppSettings.load(this)
        Log.i(TAG, "toggleDirection: prevDir=${s.liveDirection} running=$running")
        s.toggleDirection()
        s.save(this)
        settings = s
        overlay?.refreshDirection(s)
        Log.i(TAG, "toggleDirection: newDir=${s.liveDirection} — forcing pipeline restart")
        // Force restart regardless of running state. Re-use the cached
        // MediaProjection token if any, so system-audio mode doesn't need
        // to re-prompt the user.
        stopPipeline()
        scope.launch {
            delay(300)
            startPipelineWithCachedGrant()
        }
    }

    /**
     * Cycle the caption content from the overlay: translation → both →
     * original only. Starts from the latest persisted settings (like
     * [toggleDirection]) so values changed in SettingsActivity meanwhile are
     * never overwritten by a stale snapshot. Only the overlay style changes;
     * the pipeline keeps running.
     */
    private fun cycleCaptionMode() {
        val s = AppSettings.load(this)
        s.captionMode = s.captionMode.next()
        s.save(this)
        settings = s
        overlay?.updateSettings(s)
        overlay?.applyStyle()
        publishState()
    }

    /**
     * Export everything recorded during this service run (original and
     * translation, time-stamped) as a text file via the share sheet.
     */
    private fun exportTranscript() {
        val lines = transcript.snapshot()
        if (lines.isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val exportedAt = System.currentTimeMillis()
        val text = TranscriptExporter.format(this, lines, transcript.startedAt, exportedAt)
        scope.launch {
            try {
                TranscriptExporter.share(this@LiveTranslateService, text, exportedAt)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "transcript export failed", e)
                Toast.makeText(
                    this@LiveTranslateService,
                    getString(R.string.export_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        unregisterNetworkCallback()
        stopPipeline()
        // Forget the projection token so a later start doesn't try to use a
        // stale grant. ACTION_RESTART preserves it (see [restartPipelineIfNeeded]).
        teardownProjection()
        // Windows added through WindowManager outlive the service unless
        // removed explicitly; without this the HUD stays on screen after
        // stopping from the main screen or the notification.
        overlay?.detach()
        overlay = null
        // stopPipeline() re-posts the status notification after the system
        // already dropped the foreground one; don't leave it behind.
        notificationManager.cancel(NOTIF_ID)
        // Flush the transcript snapshot, then let the persist thread finish.
        transcript.flushPersist()
        transcriptPersistExecutor.shutdown()
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * Leave the foreground and stop the service. All teardown (pipeline,
     * projection grant, overlay, notification) happens in [onDestroy], so
     * every stop path — notification action, overlay close button, or
     * `stopService()` from [MainActivity] — cleans up the same way.
     */
    private fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- pipeline ----------

    private fun startPipeline(resultCode: Int, resultData: Intent?) {
        if (running) return
        val s = AppSettings.load(this).also { settings = it }
        if (s.apiKey.isBlank()) {
            notifyStatus(getString(R.string.err_no_api_key))
            return
        }

        // Overlay. If the system rejects the floating window, do not keep
        // capturing audio invisibly in the background.
        if (!ensureOverlay(s)) {
            failPipeline(getString(R.string.err_no_overlay_perm))
            return
        }

        if (s.audioSource == "system" && resultData == null) {
            val status = getString(R.string.status_system_audio_permission)
            overlay?.setRunningState(false)
            setOverlayStatus(status, StatusKind.ERROR)
            updateNotification(running = false)
            notifyStatus(status)
            return
        }

        // Player (translation playback). Independent of the API's
        // same-language echo flag ([AppSettings.echoTargetLanguage]).
        if (s.playTranslation) {
            try {
                val p = AudioPlayer(gain = s.playbackVolume).also { player = it }
                p.start()
            } catch (e: Exception) {
                Log.w(TAG, "AudioPlayer init failed: ${e.message}")
                player = null
            }
        }

        // Gemini client
        val sessionId = ++currentSessionId
        // Text from the new session starts new transcript lines.
        transcript.breakLines()
        pendingCaptureResultCode = resultCode
        pendingCaptureResultData = resultData

        val c = GeminiClient(listener = createClientListener(sessionId)).also { client = it }
        c.configure(
            apiKey = s.apiKey,
            targetLang = s.effectiveTargetLanguage,
            echoTargetLanguage = s.echoTargetLanguage,
            apiBase = s.apiBase,
            liveModel = s.liveModel,
            resumptionHandle = resumptionHandle,
        )

        running = true
        pipelineRunning = true
        overlay?.setRunningState(true)
        setOverlayStatus(getString(R.string.status_connecting), StatusKind.CONNECTING)
        updateNotification(running = true)
        LiveTileService.requestUpdate(this)

        try {
            c.start()
        } catch (e: Exception) {
            Log.e(TAG, "Gemini client start failed", e)
            failPipeline(getString(R.string.status_start_failed, e.message ?: e.javaClass.simpleName))
            return
        }
    }

    private fun startCaptureForSession(sessionId: Int) {
        if (!isCurrentSession(sessionId) || !running || capture != null) return

        val s = settings ?: AppSettings.load(this).also { settings = it }
        val c = client ?: return
        val resultData = pendingCaptureResultData
        if (s.audioSource == "system" && resultData == null) {
            failPipeline(getString(R.string.status_system_audio_permission))
            return
        }

        val cap = AudioCapture(
            onChunk = { pcm16 ->
                if (isCurrentSession(sessionId) && running) {
                    c.sendAudio(pcm16)
                }
            },
            onError = { reason ->
                scope.launch {
                    if (isCurrentSession(sessionId)) {
                        failPipeline(
                            status = getString(R.string.status_capture_error, reason),
                            teardownProjectionGrant = s.audioSource == "system",
                        )
                    }
                }
            }
        ).also { capture = it }

        try {
            if (s.audioSource == "system" &&
                resultData != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                startSystemCapture(cap, pendingCaptureResultCode, resultData)
            } else {
                cap.startMicrophone(context = this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioCapture start failed", e)
            failPipeline(
                getString(R.string.status_capture_error, e.message ?: e.javaClass.simpleName),
                teardownProjectionGrant = true,
            )
        }
    }

    private fun failPipeline(status: String, teardownProjectionGrant: Boolean = false) {
        currentSessionId++
        running = false
        pipelineRunning = false
        connectedAtMs = 0
        resumptionHandle = null
        cancelReconnect()
        try { capture?.stop() } catch (_: Exception) {}
        capture = null
        try { player?.stop() } catch (_: Exception) {}
        player = null
        client?.stop()
        client = null
        if (teardownProjectionGrant) teardownProjection()
        overlay?.setRunningState(false)
        setOverlayStatus(status, StatusKind.ERROR)
        updateNotification(running = false)
        notifyStatus(status)
        LiveTileService.requestUpdate(this)
    }

    private fun stopPipeline() {
        currentSessionId++
        pendingCaptureResultCode = 0
        pendingCaptureResultData = null
        connectedAtMs = 0
        resumptionHandle = null
        if (!running && client == null && capture == null) {
            pipelineRunning = false
            cancelReconnect()
            return
        }
        running = false
        pipelineRunning = false
        cancelReconnect()
        try { capture?.stop() } catch (_: Exception) {}
        capture = null
        try { player?.stop() } catch (_: Exception) {}
        player = null
        // NOTE: mediaProjection is intentionally kept alive so a
        // subsequent restart / toggle / reconnect can reuse the same
        // grant without re-prompting the user. Once a projection is
        // stopped its token becomes invalid (esp. Android 14+), so we
        // only tear it down when the service is permanently stopped
        // (see [teardownProjection]).
        client?.stop()
        client = null
        overlay?.setRunningState(false)
        setOverlayStatus(getString(R.string.status_stopped), StatusKind.IDLE)
        updateNotification(running = false)
        LiveTileService.requestUpdate(this)
    }

    /**
     * Permanently stop the cached [MediaProjection] and forget the grant
     * token. Called when the service is being destroyed (ACTION_STOP /
     * [onDestroy] / overlay close) — NOT on transient pipeline stops,
     * so that restart / toggle / reconnect can reuse the live projection.
     */
    private fun teardownProjection() {
        mediaProjection?.let {
            try { it.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            try { it.stop() } catch (_: Exception) {}
        }
        mediaProjection = null
        lastResultCode = 0
        lastResultData = null
    }

    private fun togglePipeline() {
        if (running) {
            stopPipeline()
            return
        }
        // Restart after a disconnect / user pause: reuse the cached
        // MediaProjection token so system-audio mode survives the cycle
        // instead of silently starting the wrong audio source.
        cancelReconnect()
        startPipelineWithCachedGrant()
    }

    /**
     * Hot-restart the pipeline after the user changed settings in
     * [com.faqxd.livesub.android.SettingsActivity].
     *
     * Mirrors `main.py:LiveBuddyApp.open_settings`:
     *   stop() → 300ms delay → start() with the latest [AppSettings].
     *
     * The MediaProjection grant from the last ACTION_START is reused so the
     * user doesn't have to re-grant system-audio capture when only the API
     * key / target language / echo / prompt changed. If the user switched
     * audioSource mic → system *without* an existing grant, the restart is
     * refused until the user starts from the main screen and grants capture.
     */
    private fun restartPipelineIfNeeded() {
        if (!running) {
            // Service was started (e.g. by SettingsActivity) but no pipeline
            // is active — nothing to restart. Don't auto-start either; the
            // user must press Start from the main screen to begin capture.
            return
        }
        stopPipeline()
        scope.launch {
            delay(300)
            startPipelineWithCachedGrant()
        }
    }

    /**
     * Bring the service back to the foreground (if needed) and start the
     * pipeline with the most recent settings, reusing the cached
     * MediaProjection grant when the user still wants system audio.
     *
     * Every start path — ACTION_START, direction toggle, overlay
     * pause/resume, settings hot-restart, auto-reconnect — funnels through
     * here so foreground promotion and failure handling are identical: a
     * throw from either start step permanently fails the pipeline with the
     * reason instead of crashing the surrounding coroutine.
     */
    private fun startPipelineWithCachedGrant() {
        val s = AppSettings.load(this)
        val useSystem = s.audioSource == "system" && lastResultData != null
        try {
            startForegroundIfNeeded(useSystemAudio = useSystem)
            startPipeline(lastResultCode, if (useSystem) lastResultData else null)
        } catch (e: Exception) {
            Log.e(TAG, "pipeline start failed", e)
            failPipeline(getString(R.string.status_start_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------- overlay ----------

    private fun ensureOverlay(s: AppSettings): Boolean {
        if (overlay == null) {
            overlay = CaptionOverlayView(
                context = this,
                settings = s,
                callbacks = object : CaptionOverlayView.Callbacks {
                    override fun onToggleClicked() {
                        togglePipeline()
                    }
                    override fun onClearClicked() {
                        overlay?.clear()
                        // Mirror the emptied captions to the main screen.
                        publishState()
                    }
                    override fun onSettingsClicked() {
                        startActivity(
                            Intent(this@LiveTranslateService, com.faqxd.livesub.android.SettingsActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    override fun onCloseClicked() {
                        shutdown()
                    }
                    override fun onToggleDirectionClicked() {
                        toggleDirection()
                    }
                    override fun onCaptionModeClicked() {
                        cycleCaptionMode()
                    }
                    override fun onExportClicked() {
                        exportTranscript()
                    }
                    override fun onClickThroughChanged(enabled: Boolean) {
                        // Re-post so the "exit click-through" action follows the mode.
                        updateNotification(running)
                        if (enabled) {
                            Toast.makeText(
                                this@LiveTranslateService,
                                R.string.overlay_click_through_hint,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            )
        }
        overlay?.init()
        // Update the overlay's settings snapshot before applying style so
        // font size / opacity / caption mode pick up the latest values
        // without re-creating the overlay.
        overlay?.updateSettings(s)
        overlay?.applyStyle()
        // refreshDirection(s) applies the latest target/direction after a
        // hot restart without re-creating the overlay.
        overlay?.refreshDirection(s)
        try {
            overlay?.attach()
        } catch (e: Exception) {
            Log.e(TAG, "overlay attach failed: ${e.message}")
            notifyStatus(getString(R.string.err_no_overlay_perm))
            return false
        }
        return true
    }

    // ---------- media projection ----------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startSystemCapture(cap: AudioCapture, resultCode: Int, data: Intent) {
        // Reuse a still-live MediaProjection when available so a restart
        // / toggle / reconnect doesn't re-prompt the user. Once a
        // projection is stopped its token becomes invalid (esp. on
        // Android 14+), so we must never call getMediaProjection with a
        // stale token — instead we keep the instance alive across
        // transient stops (see [stopPipeline] / [teardownProjection]).
        val mp = mediaProjection ?: run {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            val newMp = mpm.getMediaProjection(resultCode, data) ?: run {
                throw RuntimeException("MediaProjection token rejected")
            }
            newMp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            newMp
        }
        mediaProjection = mp

        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val srcRate = 48000
        val srcChannels = AudioFormat.CHANNEL_IN_STEREO
        val minBuf = AudioRecord.getMinBufferSize(srcRate, srcChannels, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(8192)
        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(srcRate)
                    .setChannelMask(srcChannels)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw RuntimeException("Loopback AudioRecord not initialized")
        }
        cap.startSystemAudio(record, srcRate, /* channels = */ 2)
    }

    // ---------- gemini listener (forwards to overlay on main thread) ----------

    private fun isCurrentSession(sessionId: Int): Boolean =
        sessionId == currentSessionId

    private fun createClientListener(sessionId: Int) = object : GeminiClient.Listener {
        override fun onInputTranscript(text: String) {
            scope.launch {
                if (isCurrentSession(sessionId)) {
                    overlay?.setInput(text)
                    transcript.append(TranscriptRecorder.Stream.ORIGINAL, text)
                    publishState()
                }
            }
        }
        override fun onOutputTranscript(text: String) {
            scope.launch {
                if (isCurrentSession(sessionId)) {
                    overlay?.setOutput(text)
                    transcript.append(TranscriptRecorder.Stream.TRANSLATION, text)
                    publishState()
                }
            }
        }
        override fun onAudioChunk(pcm16: ByteArray) {
            // AudioTrack writes are blocking; do them on a dedicated thread
            // (OkHttp dispatcher in this case) to avoid stalling the WS reader.
            if (isCurrentSession(sessionId)) player?.enqueuePcm16(pcm16)
        }
        override fun onInterrupted() {
            // The user talked over the model: anything still queued is stale
            // and must not keep playing. Flush right here on the socket
            // thread (the player is thread-safe) — a flush posted to the main
            // thread could also wipe newer audio queued meanwhile.
            if (isCurrentSession(sessionId)) player?.flush()
        }
        override fun onSessionResumptionHandle(handle: String) {
            scope.launch {
                if (isCurrentSession(sessionId)) {
                    resumptionHandle = handle
                }
            }
        }
        override fun onGoAway() {
            scope.launch {
                if (!isCurrentSession(sessionId) || !running) return@launch
                Log.w(TAG, "goAway — swapping to a fresh session before server expiry")
                // The connection was healthy (the server grants ~10 minutes),
                // so reset the backoff and swap immediately. A stale handle
                // is fine: the new setup continues the session context.
                reconnectAttempts = 0
                connectedAtMs = 0
                running = false
                pipelineRunning = false
                currentSessionId++
                capture?.stop(); capture = null
                player?.stop(); player = null
                client?.stop()
                client = null
                startPipelineWithCachedGrant()
            }
        }
        override fun onSetupSent() {
            scope.launch {
                if (isCurrentSession(sessionId)) {
                    setOverlayStatus(
                        getString(R.string.status_session_setup),
                        StatusKind.CONNECTING,
                    )
                }
            }
        }
        override fun onConnected() {
            scope.launch {
                if (!isCurrentSession(sessionId)) return@launch
                // Note when the connection became healthy; the reconnect
                // counter is reset on disconnect only if the session lasted
                // long enough (see onDisconnected).
                connectedAtMs = SystemClock.elapsedRealtime()
                setOverlayStatus(getString(R.string.status_connected), StatusKind.CONNECTED)
                startCaptureForSession(sessionId)
                if (isCurrentSession(sessionId) && running) {
                    updateNotification(running = true)
                }
            }
        }
        override fun onDisconnected(reason: String, retryable: Boolean) {
            scope.launch {
                if (!isCurrentSession(sessionId)) return@launch
                // A connection that stayed up for RECONNECT_STABLE_MS counts
                // as healthy: reset the backoff so the next drop starts a
                // fresh retry cycle. A session killed right after setup does
                // not, bounding the total retry loop.
                val connectedAt = connectedAtMs
                connectedAtMs = 0
                if (connectedAt > 0 &&
                    SystemClock.elapsedRealtime() - connectedAt >= RECONNECT_STABLE_MS
                ) {
                    reconnectAttempts = 0
                }
                val status = getString(R.string.status_disconnected, reason).take(80)
                if (running) {
                    // Unexpected disconnect — stop capture but keep the
                    // overlay visible so the user sees the status.
                    currentSessionId++
                    running = false
                    pipelineRunning = false
                    capture?.stop(); capture = null
                    player?.stop(); player = null
                    overlay?.setRunningState(false)
                    if (retryable) {
                        tryReconnect(reason)
                    } else {
                        // Permanent failure (bad key, rejected setup...):
                        // stop for good instead of hammering the API.
                        failPipeline(status)
                    }
                    LiveTileService.requestUpdate(applicationContext)
                } else {
                    setOverlayStatus(status, StatusKind.ERROR)
                }
                updateNotification(running = false)
            }
        }
    }

    // ---------- auto-reconnect ----------

    /**
     * Cancel any pending reconnect coroutine and reset the attempt counter.
     * Called from [stopPipeline] and [togglePipeline] to ensure a clean
     * slate for the next user-initiated start.
     */
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
    }

    /**
     * Attempt to re-establish the WebSocket session after an unexpected
     * disconnect. Uses exponential backoff (1s, 2s, 4s, 8s, 16s) and gives
     * up after [MAX_RECONNECT_ATTEMPTS] tries — at that point the last
     * disconnect [reason] is shown and the user must manually press Start.
     *
     * The cached MediaProjection token ([lastResultData]) is reused so
     * system-audio mode survives the reconnect cycle.
     */
    private fun tryReconnect(reason: String) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            scope.launch {
                setOverlayStatus(
                    getString(R.string.status_reconnect_failed, reason),
                    StatusKind.ERROR,
                )
            }
            return
        }
        reconnectJob?.cancel()
        val delayMs = RECONNECT_BASE_DELAY_MS shl reconnectAttempts
        reconnectAttempts++
        reconnectJob = scope.launch {
            setOverlayStatus(
                getString(
                    R.string.status_reconnecting,
                    delayMs / 1000, reconnectAttempts, MAX_RECONNECT_ATTEMPTS,
                ),
                StatusKind.CONNECTING,
            )
            delay(delayMs)
            // Bail out if the user manually restarted or stopped while we
            // were waiting.
            if (running) return@launch
            val s = AppSettings.load(this@LiveTranslateService)
            if (s.apiKey.isBlank()) return@launch
            startPipelineWithCachedGrant()
        }
    }

    // ---------- notification ----------

    private fun startForegroundIfNeeded(useSystemAudio: Boolean) {
        val nm = notificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
                nm.createNotificationChannel(channel)
            }
        }
        val notif = buildNotification(running)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (useSystemAudio) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(running: Boolean) {
        notificationManager.notify(NOTIF_ID, buildNotification(running))
    }

    private fun buildNotification(
        running: Boolean,
        contentText: String? = null,
        priority: Int = NotificationCompat.PRIORITY_LOW,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LiveTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_caption_bubble)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                contentText ?: if (running) getString(R.string.notif_text_running)
                else getString(R.string.notif_text_paused)
            )
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(priority)
            .addAction(0, getString(R.string.stop), stopIntent)
        // Fallback exit from click-through, next to the on-screen unlock button.
        if (overlay?.isClickThrough == true) {
            val exitClickThroughIntent = PendingIntent.getService(
                this, 2,
                Intent(this, LiveTranslateService::class.java).setAction(ACTION_EXIT_CLICK_THROUGH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.overlay_click_through_off), exitClickThroughIntent)
        }
        return builder.build()
    }

    private fun notifyStatus(text: String) {
        notificationManager.notify(
            NOTIF_ID,
            buildNotification(
                running = running,
                contentText = text,
                priority = NotificationCompat.PRIORITY_DEFAULT,
            )
        )
    }

    companion object {
        @Volatile private var pipelineRunning = false
        @Volatile private var projectionGrantCached = false

        fun isPipelineRunning(): Boolean = pipelineRunning

        /**
         * Whether the running service still holds a usable system-audio
         * (MediaProjection) grant, so a new start needs no fresh consent.
         */
        fun hasProjectionGrant(): Boolean = projectionGrantCached

        private const val TAG = "LiveTranslateService"
        private const val CHANNEL_ID = "live_translate"
        private const val NOTIF_ID = 1
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 1000L
        /** A connection up at least this long resets the reconnect backoff. */
        private const val RECONNECT_STABLE_MS = 10_000L

        const val ACTION_START = "com.faqxd.livesub.android.START"
        const val ACTION_STOP = "com.faqxd.livesub.android.STOP"
        const val ACTION_TOGGLE = "com.faqxd.livesub.android.TOGGLE"
        const val ACTION_RESTART = "com.faqxd.livesub.android.RESTART"
        const val ACTION_EXIT_CLICK_THROUGH = "com.faqxd.livesub.android.EXIT_CLICK_THROUGH"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun startIntent(context: Context, resultCode: Int, data: Intent?): Intent =
            Intent(context, LiveTranslateService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, LiveTranslateService::class.java).setAction(ACTION_STOP)

        /**
         * Build an intent that tells the service to tear down the current
         * pipeline and start a fresh one with the latest [AppSettings].
         * No-op if the pipeline isn't running. Sent by SettingsActivity
         * after the user saves changes to a pipeline-affecting field.
         */
        fun restartIntent(context: Context): Intent =
            Intent(context, LiveTranslateService::class.java).setAction(ACTION_RESTART)
    }
}
