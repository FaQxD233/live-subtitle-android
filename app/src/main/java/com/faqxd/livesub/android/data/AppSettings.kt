package com.faqxd.livesub.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persisted application settings.
 *
 * Direct port of `settings.py:AppSettings`. Stored in `SharedPreferences`
 * (`gemini-live-translate.json` equivalent on Android is the prefs file
 * `livebuddy_settings`).
 *
 * Properties:
 *  - apiKey              — Google Gemini API key (stored plaintext, like the
 *                          Windows version; users on rooted devices can read it).
 *  - apiBase             — Override for the API base URL (proxy / regional mirror).
 *  - targetLanguage      — ISO-639-1 code, e.g. "zh", "es", "ja". Used only
 *                          in [Mode.LIVE] (unidirectional translate).
 *  - audioSource         — "mic" or "system" (loopback via MediaProjection).
 *  - fontSize            — Caption font size in sp.
 *  - bgOpacity           — 0..1 background alpha for the overlay card.
 *  - echoTargetLanguage  — Whether to play back the translated audio.
 *  - playbackVolume      — 0..1 playback volume.
 *  - systemPrompt        — Optional custom instructions for the model.
 *                          (LIVE mode only; BILI modes use a built-in prompt.)
 *  - showOriginal        — Whether to display the source-language transcript.
 *  - mode                — Pipeline preset, see [Mode].
 *  - biliModel           — Override model for BILI modes. Defaults to
 *                          [Mode.BILINGUAL_DEFAULT_MODEL].
 */
data class AppSettings(
    var apiKey: String = "",
    var apiBase: String = DEFAULT_API_BASE,
    var targetLanguage: String = "zh",
    var audioSource: String = "mic",
    var fontSize: Int = 16,
    var bgOpacity: Float = 0.6f,
    var echoTargetLanguage: Boolean = false,
    var playbackVolume: Float = 0.8f,
    var systemPrompt: String = "",
    var showOriginal: Boolean = false,
    var mode: String = Mode.LIVE.id,
    var biliModel: String = Mode.BILINGUAL_DEFAULT_MODEL,
) {
    /**
     * Pipeline preset selected from the main screen.
     *
     * - [LIVE] uses `models/gemini-3.5-live-translate-preview` with a
     *   fixed `translationConfig.targetLanguageCode` (single-direction
     *   translate, source auto-detected). This is the original Windows/macOS
     *   behavior and is preserved unchanged.
     * - [BILI_ZH_EN] / [BILI_ZH_JP] use a general-purpose Live model
     *   (no `translationConfig`) driven by a built-in bidirectional system
     *   prompt — the model listens, detects the source language, and
     *   translates to the *other* language in the pair.
     */
    enum class Mode(val id: String, val label: String) {
        LIVE("live", "Live Translate"),
        BILI_ZH_EN("bili_zh_en", "中英互译"),
        BILI_ZH_JP("bili_zh_jp", "中日互译");

        companion object {
            /**
             * Default model for BILI modes. `gemini-3-flash-live` is the
             * general-purpose Live API model that's available on the Google
             * AI Studio free tier as of mid-2026. Users on paid tiers can
             * override this in Settings (e.g. to `gemini-3.1-flash-live-preview`
             * for newer features, or `gemini-2.5-flash-native-audio-latest`
             * for audio echo support).
             */
            const val BILINGUAL_DEFAULT_MODEL = "gemini-3-flash-live"
            fun fromId(id: String?): Mode = entries.firstOrNull { it.id == id } ?: LIVE
        }
    }

    /** Convenience accessor; [mode] is stored as a raw string for forward compat. */
    val modeEnum: Mode get() = Mode.fromId(mode)

    /** True when the current mode is one of the bidirectional presets. */
    val isBilingual: Boolean get() = modeEnum != Mode.LIVE

    companion object {
        const val DEFAULT_API_BASE = "https://generativelanguage.googleapis.com"
        private const val PREFS_NAME = "livebuddy_settings"

        fun load(context: Context): AppSettings {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawBiliModel = prefs.getString(
                "bili_model",
                Mode.BILINGUAL_DEFAULT_MODEL,
            ) ?: Mode.BILINGUAL_DEFAULT_MODEL
            // Migrate older defaults that may have been persisted on a
            // previous install. `gemini-3.1-flash-live-preview` is paid-tier
            // only; silently swap to the free-tier `gemini-3-flash-live` so
            // existing users don't get a 404 on the next BILI session.
            val biliModel = migrateBiliModel(rawBiliModel)
            return AppSettings(
                apiKey = prefs.getString("api_key", "") ?: "",
                apiBase = prefs.getString("api_base", DEFAULT_API_BASE) ?: DEFAULT_API_BASE,
                targetLanguage = prefs.getString("target_language", "zh") ?: "zh",
                audioSource = prefs.getString("audio_source", "mic") ?: "mic",
                fontSize = prefs.getInt("font_size", 16),
                bgOpacity = prefs.getFloat("bg_opacity", 0.6f),
                echoTargetLanguage = prefs.getBoolean("echo_target", false),
                playbackVolume = prefs.getFloat("playback_volume", 0.8f),
                systemPrompt = prefs.getString("system_prompt", "") ?: "",
                showOriginal = prefs.getBoolean("show_original", false),
                mode = prefs.getString("mode", Mode.LIVE.id) ?: Mode.LIVE.id,
                biliModel = biliModel,
            )
        }

        /**
         * Map deprecated/legacy biliModel strings to their current
         * equivalents. Returns the input unchanged if no migration is
         * needed. Add new migrations here as defaults evolve.
         */
        private fun migrateBiliModel(raw: String): String {
            return when (raw) {
                "gemini-3.1-flash-live-preview" -> BILINGUAL_DEFAULT_MODEL
                "gemini-2.5-flash-live-preview" -> BILINGUAL_DEFAULT_MODEL
                "" -> BILINGUAL_DEFAULT_MODEL
                else -> raw
            }
        }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString("api_key", apiKey)
            putString("api_base", apiBase)
            putString("target_language", targetLanguage)
            putString("audio_source", audioSource)
            putInt("font_size", fontSize)
            putFloat("bg_opacity", bgOpacity)
            putBoolean("echo_target", echoTargetLanguage)
            putFloat("playback_volume", playbackVolume)
            putString("system_prompt", systemPrompt)
            putBoolean("show_original", showOriginal)
            putString("mode", mode)
            putString("bili_model", biliModel)
        }
    }
}
