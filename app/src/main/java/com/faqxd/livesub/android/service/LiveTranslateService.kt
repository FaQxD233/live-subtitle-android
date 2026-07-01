package com.faqxd.livesub.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.faqxd.livesub.android.MainActivity
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.audio.AudioCapture
import com.faqxd.livesub.android.audio.AudioPlayer
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.gemini.GeminiClient
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

    // Auto-reconnect state for unexpected WebSocket disconnects.
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    // Cached MediaProjection grant from the most recent ACTION_START.
    // Reused by ACTION_RESTART so the user can change API key / language /
    // echo / prompt without re-granting system-audio capture.
    private var lastResultCode: Int = 0
    private var lastResultData: Intent? = null

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
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
                // On Android 14+ startForeground must declare the exact
                // service types in use. If a projection token is attached,
                // we'll use mediaProjection; otherwise it's mic-only.
                startForegroundIfNeeded(useSystemAudio = resultData != null)
                startPipeline(resultCode, resultData)
            }
            ACTION_STOP -> {
                stopPipeline()
                // Forget the projection token on explicit stop so a later
                // restart doesn't try to use a stale grant. ACTION_RESTART
                // preserves it (see [restartPipelineIfNeeded]).
                teardownProjection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TOGGLE -> togglePipeline()
            ACTION_RESTART -> restartPipelineIfNeeded()
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
        s.liveDirection = if (s.liveDirection == "b2a") "a2b" else "b2a"
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
            val latest = AppSettings.load(this@LiveTranslateService)
            val useSystem = latest.audioSource == "system" && lastResultData != null
            startForegroundIfNeeded(useSystemAudio = useSystem)
            try {
                startPipeline(lastResultCode, if (useSystem) lastResultData else null)
                if (latest.audioSource == "system" && !useSystem) {
                    overlay?.setStatus("System audio permission required")
                }
            } catch (e: Exception) {
                Log.e(TAG, "restart after direction toggle failed", e)
                overlay?.setStatus("Restart failed: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPipeline()
        teardownProjection()
        scope.coroutineContext[Job]?.cancel()
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
            val status = "System audio permission required"
            overlay?.setRunningState(false)
            overlay?.setStatus(status)
            updateNotification(running = false)
            notifyStatus(status)
            return
        }

        // Player (echo).
        val wantEcho = s.echoTargetLanguage
        if (wantEcho) {
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
        pendingCaptureResultCode = resultCode
        pendingCaptureResultData = resultData

        val c = GeminiClient(listener = createClientListener(sessionId)).also { client = it }
        c.configure(
            apiKey = s.apiKey,
            targetLang = s.effectiveTargetLanguage,
            systemPrompt = s.systemPrompt,
            echoTargetLanguage = s.echoTargetLanguage,
            apiBase = s.apiBase,
            liveModel = s.liveModel,
        )

        running = true
        pipelineRunning = true
        overlay?.setRunningState(true)
        overlay?.setStatus("Connecting...")
        updateNotification(running = true)

        try {
            c.start()
        } catch (e: Exception) {
            Log.e(TAG, "Gemini client start failed", e)
            failPipeline("Gemini start error: ${e.message}")
            return
        }
    }

    private fun startCaptureForSession(sessionId: Int) {
        if (!isCurrentSession(sessionId) || !running || capture != null) return

        val s = settings ?: AppSettings.load(this).also { settings = it }
        val c = client ?: return
        val resultData = pendingCaptureResultData
        if (s.audioSource == "system" && resultData == null) {
            failPipeline("System audio permission required")
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
                            status = "Capture error: $reason",
                            teardownProjectionGrant = s.audioSource == "system",
                        )
                    }
                }
            }
        ).also { capture = it }

        try {
            if (s.audioSource == "system" && resultData != null) {
                startSystemCapture(cap, pendingCaptureResultCode, resultData)
            } else {
                cap.startMicrophone(context = this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioCapture start failed", e)
            failPipeline("Capture error: ${e.message}", teardownProjectionGrant = true)
        }
    }

    private fun failPipeline(status: String, teardownProjectionGrant: Boolean = false) {
        currentSessionId++
        running = false
        pipelineRunning = false
        cancelReconnect()
        try { capture?.stop() } catch (_: Exception) {}
        capture = null
        try { player?.stop() } catch (_: Exception) {}
        player = null
        client?.stop()
        client = null
        if (teardownProjectionGrant) teardownProjection()
        overlay?.setRunningState(false)
        overlay?.setStatus(status)
        updateNotification(running = false)
        notifyStatus(status)
    }

    private fun stopPipeline() {
        currentSessionId++
        pendingCaptureResultCode = 0
        pendingCaptureResultData = null
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
        overlay?.setStatus("Stopped")
        updateNotification(running = false)
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
        val s = AppSettings.load(this)
        val useSystem = s.audioSource == "system" && lastResultData != null
        startForegroundIfNeeded(useSystemAudio = useSystem)
        try {
            startPipeline(lastResultCode, if (useSystem) lastResultData else null)
            if (s.audioSource == "system" && !useSystem) {
                overlay?.setStatus("System audio permission required")
            }
        } catch (e: Exception) {
            Log.e(TAG, "restart after toggle failed", e)
            overlay?.setStatus("Restart failed: ${e.message}")
        }
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
            val s = AppSettings.load(this@LiveTranslateService)
            val useSystem = s.audioSource == "system" && lastResultData != null
            startForegroundIfNeeded(useSystemAudio = useSystem)
            try {
                startPipeline(lastResultCode, if (useSystem) lastResultData else null)
                if (s.audioSource == "system" && !useSystem) {
                    overlay?.setStatus("System audio permission required")
                }
            } catch (e: Exception) {
                Log.e(TAG, "restart failed", e)
                overlay?.setStatus("Restart failed: ${e.message}")
            }
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
                    }
                    override fun onSettingsClicked() {
                        startActivity(
                            Intent(this@LiveTranslateService, com.faqxd.livesub.android.SettingsActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    override fun onCloseClicked() {
                        stopPipeline()
                        teardownProjection()
                        overlay?.detach()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    override fun onToggleDirectionClicked() {
                        toggleDirection()
                    }
                },
            )
        }
        overlay?.init()
        // Update the overlay's settings snapshot before applying style so
        // font size / opacity / showOriginal pick up the latest values
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
                if (isCurrentSession(sessionId)) overlay?.setInput(text)
            }
        }
        override fun onOutputTranscript(text: String) {
            scope.launch {
                if (isCurrentSession(sessionId)) overlay?.setOutput(text)
            }
        }
        override fun onAudioChunk(pcm16: ByteArray) {
            // AudioTrack writes are blocking; do them on a dedicated thread
            // (OkHttp dispatcher in this case) to avoid stalling the WS reader.
            if (isCurrentSession(sessionId)) player?.enqueuePcm16(pcm16)
        }
        override fun onStatus(status: String) {
            scope.launch {
                if (isCurrentSession(sessionId)) overlay?.setStatus(status)
            }
        }
        override fun onConnected() {
            scope.launch {
                if (!isCurrentSession(sessionId)) return@launch
                reconnectAttempts = 0
                overlay?.setStatus("Connected")
                startCaptureForSession(sessionId)
                if (isCurrentSession(sessionId) && running) {
                    updateNotification(running = true)
                }
            }
        }
        override fun onDisconnected(reason: String) {
            scope.launch {
                if (!isCurrentSession(sessionId)) return@launch
                overlay?.setStatus("Disconnected: $reason".take(80))
                if (running) {
                    // Unexpected disconnect — stop capture but keep the
                    // overlay visible so the user sees the status.
                    currentSessionId++
                    running = false
                    pipelineRunning = false
                    capture?.stop(); capture = null
                    player?.stop(); player = null
                    overlay?.setRunningState(false)
                    tryReconnect()
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
     * up after [MAX_RECONNECT_ATTEMPTS] tries — at that point the user
     * must manually press Start.
     *
     * The cached MediaProjection token ([lastResultData]) is reused so
     * system-audio mode survives the reconnect cycle.
     */
    private fun tryReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            scope.launch {
                overlay?.setStatus("Reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts — tap Start to retry")
            }
            return
        }
        reconnectJob?.cancel()
        val delayMs = RECONNECT_BASE_DELAY_MS shl reconnectAttempts
        reconnectAttempts++
        reconnectJob = scope.launch {
            overlay?.setStatus("Reconnecting in ${delayMs}ms (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")
            delay(delayMs)
            // Bail out if the user manually restarted or stopped while we
            // were waiting.
            if (running) return@launch
            val s = AppSettings.load(this@LiveTranslateService)
            if (s.apiKey.isBlank()) return@launch
            val useSystem = s.audioSource == "system" && lastResultData != null
            startForegroundIfNeeded(useSystemAudio = useSystem)
            try {
                startPipeline(lastResultCode, if (useSystem) lastResultData else null)
            } catch (e: Exception) {
                Log.e(TAG, "reconnect attempt $reconnectAttempts failed", e)
                tryReconnect()
            }
        }
    }

    // ---------- notification ----------

    private fun startForegroundIfNeeded(useSystemAudio: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(running))
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                contentText ?: if (running) getString(R.string.notif_text_running)
                else getString(R.string.notif_text_paused)
            )
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(priority)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    private fun notifyStatus(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
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

        fun isPipelineRunning(): Boolean = pipelineRunning

        private const val TAG = "LiveTranslateService"
        private const val CHANNEL_ID = "live_translate"
        private const val NOTIF_ID = 1
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 1000L

        const val ACTION_START = "com.faqxd.livesub.android.START"
        const val ACTION_STOP = "com.faqxd.livesub.android.STOP"
        const val ACTION_TOGGLE = "com.faqxd.livesub.android.TOGGLE"
        const val ACTION_RESTART = "com.faqxd.livesub.android.RESTART"
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
