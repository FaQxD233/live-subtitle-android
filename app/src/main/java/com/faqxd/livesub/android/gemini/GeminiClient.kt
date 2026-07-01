package com.faqxd.livesub.android.gemini

import android.util.Base64
import android.util.Log
import com.faqxd.livesub.android.data.AppSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Port of `gemini_client.py:GeminiClient`.
 *
 * Wraps a single Gemini Live WebSocket session on a background OkHttp
 * dispatcher thread. The caller (LiveTranslateService) feeds PCM16 audio
 * chunks via [sendAudio]; transcript / audio / status callbacks arrive on
 * the supplied listener (already on OkHttp's worker thread — callers should
 * hop to the main thread before touching UI).
 *
 * The Live translate setup uses a configurable model and a fixed
 * `translationConfig.targetLanguageCode`. Direction swapping is implemented
 * by restarting the session with a different target language.
 */
class GeminiClient(
    private val listener: Listener,
) {

    interface Listener {
        fun onInputTranscript(text: String)
        fun onOutputTranscript(text: String)
        fun onAudioChunk(pcm16: ByteArray)
        fun onStatus(status: String)
        fun onConnected()
        fun onDisconnected(reason: String)
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var setupComplete = false
    @Volatile private var intentionalStop = false

    private var apiKey: String = ""
    private var apiBase: String = DEFAULT_API_BASE
    private var targetLang: String = "es"
    private var systemPrompt: String = ""
    private var echo: Boolean = true
    private var liveModel: String = AppSettings.DEFAULT_LIVE_MODEL

    fun configure(
        apiKey: String,
        targetLang: String,
        systemPrompt: String,
        echoTargetLanguage: Boolean,
        apiBase: String = DEFAULT_API_BASE,
        liveModel: String = AppSettings.DEFAULT_LIVE_MODEL,
    ) {
        this.apiKey = apiKey.trim()
        this.apiBase = apiBase.ifBlank { DEFAULT_API_BASE }.trim()
        this.targetLang = AppSettings.normalizeTargetLanguage(targetLang)
        this.systemPrompt = systemPrompt
        this.echo = echoTargetLanguage
        this.liveModel = liveModel.trim().ifBlank { AppSettings.DEFAULT_LIVE_MODEL }
    }

    /** Start a new session. No-op if already running. */
    fun start() {
        if (running) return
        running = true
        setupComplete = false
        intentionalStop = false

        val request = Request.Builder()
            .url(buildWsUrl())
            .header("x-goog-api-key", apiKey)
            .build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onStatus("Gemini socket opened")
                if (!sendSetup(webSocket)) {
                    running = false
                    setupComplete = false
                    ws = null
                    intentionalStop = true
                    listener.onStatus("Gemini setup send failed")
                    listener.onDisconnected("Setup send failed")
                    webSocket.close(1000, "setup failed")
                    return
                }
                listener.onStatus("Gemini setup sent")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Gemini Live uses text frames only; binary is unexpected.
                handleText(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t)
                running = false
                setupComplete = false
                ws = null
                if (intentionalStop) return
                listener.onStatus("Gemini error: ${t.message ?: "unknown"}")
                listener.onDisconnected("Error: ${t.message ?: "unknown"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasIntentional = intentionalStop
                running = false
                setupComplete = false
                ws = null
                if (!wasIntentional) {
                    listener.onDisconnected("Closed: $reason")
                }
            }
        })
    }

    /** Stop the current session. */
    fun stop() {
        if (!running && ws == null) return
        intentionalStop = true
        running = false
        setupComplete = false
        ws?.close(1000, "client stop")
        ws = null
    }

    /** Thread-safe: enqueue a PCM16 audio chunk for sending. */
    fun sendAudio(pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        val socket = ws ?: return
        if (!running) return
        if (!setupComplete) return
        val b64 = Base64.encodeToString(pcm16, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("data", b64)
                    put("mimeType", "audio/pcm;rate=16000")
                })
            })
        }.toString()
        if (!socket.send(msg)) {
            Log.w(TAG, "sendAudio failed: websocket queue rejected audio chunk")
            running = false
            setupComplete = false
            ws = null
            listener.onStatus("Gemini audio send failed")
            listener.onDisconnected("Audio send failed")
        }
    }

    // ---------- internals ----------

    private fun buildWsUrl(): String {
        var base = apiBase.ifBlank { DEFAULT_API_BASE }.trimEnd('/')
        base = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://")  -> "ws://"  + base.removePrefix("http://")
            else -> base
        }
        // API key is sent via the `x-goog-api-key` header in [start] to
        // avoid leaking it in proxy / server access logs.
        return "$base$GEMINI_WS_PATH"
    }

    private fun sendSetup(socket: WebSocket): Boolean {
        val setup = buildLiveSetup()
        val envelope = JSONObject().put("setup", setup).toString()
        Log.i(TAG, "sendSetup model=${setup.optString("model")} target=$targetLang modality=${
            setup.optJSONObject("generationConfig")?.optJSONArray("responseModalities")
        }")
        return socket.send(envelope)
    }

    /**
     * Live translate setup with a fixed `translationConfig.targetLanguageCode`.
     * The user's custom `systemPrompt` is forwarded as `systemInstruction`
     * if non-empty. `responseModalities = ["AUDIO"]` so the model can echo
     * the translated speech back when `echoTargetLanguage` is true.
     */
    private fun buildLiveSetup(): JSONObject = JSONObject().apply {
        put("model", modelResourceName(liveModel))
        put("generationConfig", JSONObject().apply {
            put("responseModalities", JSONArray().apply { put("AUDIO") })
            put("translationConfig", JSONObject().apply {
                put("targetLanguageCode", targetLang)
                put("echoTargetLanguage", echo)
            })
        })
        put("inputAudioTranscription", JSONObject())
        put("outputAudioTranscription", JSONObject())
        put("contextWindowCompression", JSONObject().apply {
            put("triggerTokens", "0")
            put("slidingWindow", JSONObject().apply { put("targetTokens", "0") })
        })
        val instruction = systemPrompt.trim()
        if (instruction.isNotEmpty()) {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().put("text", instruction)) })
            })
        }
    }

    private fun handleText(raw: String) {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            Log.w(TAG, "parse failed: ${e.message}")
            listener.onStatus("Parse failed: ${e.message}")
            return
        }

        // Error
        root.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "Unknown")
            Log.w(TAG, "server error: $msg  full=$raw")
            listener.onStatus("Gemini error: $msg")
            running = false
            ws?.close(1000, "server error")
            ws = null
            listener.onDisconnected("Server error: $msg")
            return
        }

        // setupComplete — bare ack
        if (root.has("setupComplete")) {
            Log.i(TAG, "setupComplete received — pipeline ready")
            if (running && !setupComplete) {
                setupComplete = true
                listener.onStatus("Gemini session ready")
                listener.onConnected()
            }
            return
        }

        val content = root.optJSONObject("serverContent") ?: run {
            Log.v(TAG, "unhandled message: ${raw.take(200)}")
            return
        }

        content.optJSONObject("inputTranscription")?.let { tr ->
            val t = tr.optString("text", "")
            if (t.isNotEmpty()) listener.onInputTranscript(t)
        }

        content.optJSONObject("outputTranscription")?.let { tr ->
            val t = tr.optString("text", "")
            if (t.isNotEmpty()) listener.onOutputTranscript(t)
        }

        content.optJSONObject("modelTurn")?.let { turn ->
            val parts = turn.optJSONArray("parts") ?: return@let
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                part.optJSONObject("inlineData")?.let { inline ->
                    val data = inline.optString("data", "")
                    if (data.isNotEmpty()) {
                        try {
                            val audio = Base64.decode(data, Base64.DEFAULT)
                            listener.onAudioChunk(audio)
                        } catch (_: Exception) { /* ignore malformed */ }
                    }
                }
                val text = part.optString("text", "")
                if (text.isNotEmpty()) listener.onOutputTranscript(text)
            }
        }

        // Log interruption / turn-complete events for debugging latency.
        if (content.optBoolean("turnComplete", false)) Log.v(TAG, "turnComplete")
        if (content.optBoolean("interrupted", false)) Log.v(TAG, "interrupted")
    }

    private fun modelResourceName(model: String): String =
        if (model.startsWith("models/")) model else "models/$model"

    companion object {
        private const val TAG = "GeminiClient"
        private val client: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // streaming
            .build()
        const val DEFAULT_API_BASE = "https://generativelanguage.googleapis.com"
        private const val GEMINI_WS_PATH =
            "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
