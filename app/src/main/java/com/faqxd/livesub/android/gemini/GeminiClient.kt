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
 * Two pipeline presets are supported via [configure]:
 *  - [AppSettings.Mode.LIVE] — `gemini-3.5-live-translate-preview` with a
 *    fixed `translationConfig.targetLanguageCode` (single-direction).
 *  - [AppSettings.Mode.BILI_ZH_EN] / [BILI_ZH_JP] — a general-purpose Live
 *    model driven by a built-in bidirectional system prompt; no
 *    `translationConfig` is sent.
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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // streaming
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false

    private var apiKey: String = ""
    private var apiBase: String = DEFAULT_API_BASE
    private var targetLang: String = "es"
    private var systemPrompt: String = ""
    private var echo: Boolean = true
    private var mode: AppSettings.Mode = AppSettings.Mode.LIVE
    private var biliModel: String = AppSettings.Mode.BILINGUAL_DEFAULT_MODEL
    private var biliDirection: String = "a2b"

    fun configure(
        apiKey: String,
        targetLang: String,
        systemPrompt: String,
        echoTargetLanguage: Boolean,
        apiBase: String = DEFAULT_API_BASE,
        mode: AppSettings.Mode = AppSettings.Mode.LIVE,
        biliModel: String = AppSettings.Mode.BILINGUAL_DEFAULT_MODEL,
        biliDirection: String = "a2b",
    ) {
        this.apiKey = apiKey.trim()
        this.apiBase = apiBase.ifBlank { DEFAULT_API_BASE }.trim()
        this.targetLang = targetLang
        // In BILI modes the system prompt is built-in and the user's
        // custom prompt is ignored — keep it stored anyway so switching
        // back to LIVE mode restores the user's preference.
        this.systemPrompt = systemPrompt
        this.echo = echoTargetLanguage
        this.mode = mode
        this.biliModel = biliModel.ifBlank { AppSettings.Mode.BILINGUAL_DEFAULT_MODEL }
        this.biliDirection = if (biliDirection == "b2a") "b2a" else "a2b"
    }

    /** Start a new session. No-op if already running. */
    fun start() {
        if (running) return
        running = true

        val request = Request.Builder().url(buildWsUrl()).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onStatus("Gemini socket opened")
                if (!sendSetup(webSocket)) {
                    running = false
                    webSocket.close(1000, "setup failed")
                    return
                }
                listener.onStatus("Gemini session ready")
                listener.onConnected()
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
                ws = null
                listener.onStatus("Gemini error: ${t.message ?: "unknown"}")
                listener.onDisconnected("Error: ${t.message ?: "unknown"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                running = false
                ws = null
                listener.onDisconnected("Closed: $reason")
            }
        })
    }

    /** Stop the current session. */
    fun stop() {
        if (!running) return
        running = false
        ws?.close(1000, "client stop")
        ws = null
    }

    /** Thread-safe: enqueue a PCM16 audio chunk for sending. */
    fun sendAudio(pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        val socket = ws ?: return
        if (!running) return
        val b64 = Base64.encodeToString(pcm16, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("data", b64)
                    put("mimeType", "audio/pcm;rate=16000")
                })
            })
        }.toString()
        socket.send(msg)
    }

    // ---------- internals ----------

    private fun buildWsUrl(): String {
        var base = apiBase.ifBlank { DEFAULT_API_BASE }.trimEnd('/')
        base = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://")  -> "ws://"  + base.removePrefix("http://")
            else -> base
        }
        return "$base$GEMINI_WS_PATH?key=$apiKey"
    }

    private fun sendSetup(socket: WebSocket): Boolean {
        val setup = when (mode) {
            AppSettings.Mode.LIVE -> buildLiveSetup()
            AppSettings.Mode.BILI_ZH_EN, AppSettings.Mode.BILI_ZH_JP -> buildBiliSetup(mode)
        }
        val envelope = JSONObject().put("setup", setup).toString()
        Log.i(TAG, "sendSetup mode=$mode model=${setup.optString("model")} modality=${
            setup.optJSONObject("generationConfig")?.optJSONArray("responseModalities")
        }")
        return socket.send(envelope)
    }

    /**
     * Original unidirectional translate setup — uses the
     * `gemini-3.5-live-translate-preview` model with a fixed
     * `translationConfig.targetLanguageCode`. The user's custom
     * `systemPrompt` is forwarded as `systemInstruction` if non-empty.
     * `responseModalities = ["AUDIO"]` so the model can echo the translated
     * speech back when `echoTargetLanguage` is true.
     */
    private fun buildLiveSetup(): JSONObject = JSONObject().apply {
        put("model", GEMINI_MODEL)
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

    /**
     * Bidirectional translate setup — uses the same model as LIVE mode
     * (`gemini-3.5-live-translate-preview`) but with a user-toggleable
     * direction. The model is single-direction by design (training bias
     * makes it default to English), so instead of fighting the bias with
     * a system prompt, we use `translationConfig.targetLanguageCode` to
     * pin the target language. The user switches direction via a button
     * in the overlay / main screen, which hot-restarts the pipeline with
     * a different `targetLanguageCode`.
     *
     * Direction mapping:
     *  - BILI_ZH_EN + "a2b" → 中→英 (targetLanguageCode = "en")
     *  - BILI_ZH_EN + "b2a" → 英→中 (targetLanguageCode = "zh")
     *  - BILI_ZH_JP + "a2b" → 中→日 (targetLanguageCode = "ja")
     *  - BILI_ZH_JP + "b2a" → 日→中 (targetLanguageCode = "zh")
     *
     * `echoTargetLanguage` is forced false in BILI mode — we don't want
     * the model to "echo" if the user happens to speak the target language
     * (e.g. user accidentally speaks English while in 中→英 mode); instead
     * the model should still translate to English (effectively a no-op).
     *
     * The audio output is silently discarded (no AudioPlayer in BILI mode);
     * the overlay reads the translation from `outputAudioTranscription`.
     */
    private fun buildBiliSetup(mode: AppSettings.Mode): JSONObject {
        val tgtCode = when (mode) {
            AppSettings.Mode.BILI_ZH_EN -> if (biliDirection == "b2a") "zh" else "en"
            AppSettings.Mode.BILI_ZH_JP -> if (biliDirection == "b2a") "zh" else "ja"
            else -> "en"
        }

        return JSONObject().apply {
            put("model", if (biliModel.startsWith("models/")) biliModel else "models/$biliModel")
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply { put("AUDIO") })
                put("translationConfig", JSONObject().apply {
                    put("targetLanguageCode", tgtCode)
                    put("echoTargetLanguage", false)
                })
            })
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            put("contextWindowCompression", JSONObject().apply {
                put("triggerTokens", "0")
                put("slidingWindow", JSONObject().apply { put("targetTokens", "0") })
            })
            // System prompt optional — translationConfig pins the target
            // language, so we keep this minimal. If the user supplied a
            // custom prompt in LIVE mode it's intentionally ignored here
            // (BILI has its own contract).
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
            return
        }

        // setupComplete — bare ack
        if (root.has("setupComplete")) {
            Log.i(TAG, "setupComplete received — pipeline ready")
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

    companion object {
        private const val TAG = "GeminiClient"
        const val DEFAULT_API_BASE = "https://generativelanguage.googleapis.com"
        private const val GEMINI_WS_PATH =
            "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val GEMINI_MODEL = "models/gemini-3.5-live-translate-preview"
    }
}
