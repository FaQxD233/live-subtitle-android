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
import android.os.IBinder
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

    // Cached MediaProjection grant from the most recent ACTION_START.
    // Reused by ACTION_RESTART so the user can change API key / language /
    // echo / prompt without re-granting system-audio capture.
    private var lastResultCode: Int = 0
    private var lastResultData: Intent? = null

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
                lastResultCode = 0
                lastResultData = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TOGGLE -> togglePipeline()
            ACTION_RESTART -> restartPipelineIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPipeline()
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

        // Overlay
        ensureOverlay(s)

        // Player (echo). BILI modes use responseModalities=["TEXT"], so the
        // model never returns audio and there's nothing to play back.
        val wantEcho = s.echoTargetLanguage && !s.isBilingual
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
        val c = GeminiClient(listener = createClientListener()).also { client = it }
        c.configure(
            apiKey = s.apiKey,
            targetLang = s.targetLanguage,
            systemPrompt = s.systemPrompt,
            echoTargetLanguage = s.echoTargetLanguage,
            apiBase = s.apiBase,
            mode = s.modeEnum,
            biliModel = s.biliModel,
        )

        // Audio capture
        val cap = AudioCapture(onChunk = { pcm16 -> c.sendAudio(pcm16) }).also { capture = it }
        try {
            if (s.audioSource == "system" && resultData != null) {
                startSystemCapture(cap, resultCode, resultData)
            } else {
                cap.startMicrophone(context = this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioCapture start failed", e)
            notifyStatus("Capture error: ${e.message}")
            player?.stop(); player = null
            return
        }

        c.start()
        running = true
        overlay?.setRunningState(true)
        overlay?.setStatus("Connecting...")
        updateNotification(running = true)
    }

    private fun stopPipeline() {
        if (!running && client == null && capture == null) return
        running = false
        try { capture?.stop() } catch (_: Exception) {}
        capture = null
        try { player?.stop() } catch (_: Exception) {}
        player = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        client?.stop()
        client = null
        overlay?.setRunningState(false)
        overlay?.setStatus("Stopped")
        updateNotification(running = false)
    }

    private fun togglePipeline() {
        if (running) stopPipeline() else startPipeline(0, null)
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
     * audioSource mic → system *without* an existing grant, we fall back to
     * the microphone path and surface a notice in the overlay status line.
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
                    overlay?.setStatus("System audio needs re-grant; using mic")
                }
            } catch (e: Exception) {
                Log.e(TAG, "restart failed", e)
                overlay?.setStatus("Restart failed: ${e.message}")
            }
        }
    }

    // ---------- overlay ----------

    private fun ensureOverlay(s: AppSettings) {
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
                        overlay?.detach()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                },
            )
        }
        overlay?.init()
        overlay?.applyStyle()
        try {
            overlay?.attach()
        } catch (e: Exception) {
            Log.e(TAG, "overlay attach failed: ${e.message}")
            notifyStatus(getString(R.string.err_no_overlay_perm))
        }
    }

    // ---------- media projection ----------

    private fun startSystemCapture(cap: AudioCapture, resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        val mp = mpm.getMediaProjection(resultCode, data) ?: run {
            throw RuntimeException("MediaProjection token rejected")
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

    private fun createClientListener() = object : GeminiClient.Listener {
        override fun onInputTranscript(text: String) {
            scope.launch { overlay?.setInput(text) }
        }
        override fun onOutputTranscript(text: String) {
            scope.launch { overlay?.setOutput(text) }
        }
        override fun onAudioChunk(pcm16: ByteArray) {
            // AudioTrack writes are blocking; do them on a dedicated thread
            // (OkHttp dispatcher in this case) to avoid stalling the WS reader.
            player?.enqueuePcm16(pcm16)
        }
        override fun onStatus(status: String) {
            scope.launch { overlay?.setStatus(status) }
        }
        override fun onConnected() {
            scope.launch {
                overlay?.setStatus("Connected")
                updateNotification(running = true)
            }
        }
        override fun onDisconnected(reason: String) {
            scope.launch {
                overlay?.setStatus("Disconnected: $reason".take(80))
                if (running) {
                    running = false
                    capture?.stop(); capture = null
                    player?.stop(); player = null
                    overlay?.setRunningState(false)
                }
                updateNotification(running = false)
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

    private fun buildNotification(running: Boolean): Notification {
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
                if (running) getString(R.string.notif_text_running)
                else getString(R.string.notif_text_paused)
            )
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    private fun notifyStatus(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    companion object {
        private const val TAG = "LiveTranslateService"
        private const val CHANNEL_ID = "live_translate"
        private const val NOTIF_ID = 1

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
