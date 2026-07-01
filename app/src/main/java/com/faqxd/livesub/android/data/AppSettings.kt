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
 *  - targetLanguage      — BCP-47 code, e.g. "zh-CN", "es", "ja".
 *  - audioSource         — "mic" or "system" (loopback via MediaProjection).
 *  - fontSize            — Caption font size in sp.
 *  - bgOpacity           — 0..1 background alpha for the overlay card.
 *  - echoTargetLanguage  — Whether to play back the translated audio.
 *  - playbackVolume      — 0..1 playback volume.
 *  - systemPrompt        — Optional custom instructions for the model.
 *  - showOriginal        — Whether to display the source-language transcript.
 *  - liveModel           — Gemini Live model override.
 *  - liveDirection       — "a2b" means Chinese→target language; "b2a"
 *                          means target language→Simplified Chinese.
 */
data class AppSettings(
    var apiKey: String = "",
    var apiBase: String = DEFAULT_API_BASE,
    var targetLanguage: String = "zh-CN",
    var audioSource: String = "mic",
    var fontSize: Int = 16,
    var bgOpacity: Float = 0.6f,
    var echoTargetLanguage: Boolean = false,
    var playbackVolume: Float = 0.8f,
    var systemPrompt: String = "",
    var showOriginal: Boolean = false,
    var liveModel: String = DEFAULT_LIVE_MODEL,
    var liveDirection: String = "a2b",
) {
    val normalizedTargetLanguage: String
        get() = normalizeTargetLanguage(targetLanguage)

    val effectiveTargetLanguage: String
        get() = if (liveDirection == "b2a" && normalizedTargetLanguage != SIMPLIFIED_CHINESE) {
            SIMPLIFIED_CHINESE
        } else {
            normalizedTargetLanguage
        }

    companion object {
        const val DEFAULT_API_BASE = "https://generativelanguage.googleapis.com"
        const val SIMPLIFIED_CHINESE = "zh-CN"
        const val DEFAULT_LIVE_MODEL = "gemini-3.5-live-translate-preview"
        private const val PREFS_NAME = "livebuddy_settings"

        fun load(context: Context): AppSettings {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val oldMode = prefs.getString("mode", "live") ?: "live"
            val rawTargetLanguage = prefs.getString("target_language", SIMPLIFIED_CHINESE)
                ?: SIMPLIFIED_CHINESE
            val migratedTargetLanguage = when (oldMode) {
                "bili_zh_en" -> "en"
                "bili_zh_jp" -> "ja"
                else -> rawTargetLanguage
            }
            val oldBiliModel = prefs.getString("bili_model", null)
            val rawLiveModel = prefs.getString(
                "live_model",
                oldBiliModel ?: DEFAULT_LIVE_MODEL,
            ) ?: DEFAULT_LIVE_MODEL
            val rawDirection = prefs.getString(
                "live_direction",
                prefs.getString("bili_direction", "a2b"),
            ) ?: "a2b"
            // Migrate older defaults that may have been persisted on a
            // previous install. These names are not available on the current
            // v1beta Live API path, so silently swap to the current default.
            val liveModel = migrateLiveModel(rawLiveModel)
            return AppSettings(
                apiKey = prefs.getString("api_key", "") ?: "",
                apiBase = prefs.getString("api_base", DEFAULT_API_BASE) ?: DEFAULT_API_BASE,
                targetLanguage = normalizeTargetLanguage(migratedTargetLanguage),
                audioSource = prefs.getString("audio_source", "mic") ?: "mic",
                fontSize = prefs.getInt("font_size", 16),
                bgOpacity = prefs.getFloat("bg_opacity", 0.6f),
                echoTargetLanguage = prefs.getBoolean("echo_target", false),
                playbackVolume = prefs.getFloat("playback_volume", 0.8f),
                systemPrompt = prefs.getString("system_prompt", "") ?: "",
                showOriginal = prefs.getBoolean("show_original", false),
                liveModel = liveModel,
                liveDirection = normalizeDirection(rawDirection),
            )
        }

        /**
         * Map deprecated/legacy model strings to their current
         * equivalents. Returns the input unchanged if no migration is
         * needed.
         */
        private fun migrateLiveModel(raw: String): String {
            val model = raw.trim()
            return when (model) {
                // Model names that were temporarily shipped as defaults but
                // don't exist on the API. Migrate to the current default.
                "gemini-3-flash-live",
                "gemini-2.0-flash-live",
                "gemini-2.5-flash-live",
                "gemini-2.5-flash-live-preview",
                "gemini-3.1-flash-live-preview" -> DEFAULT_LIVE_MODEL
                "" -> DEFAULT_LIVE_MODEL
                else -> model
            }
        }

        fun normalizeTargetLanguage(raw: String): String =
            when (raw.trim()) {
                "zh", "zh-Hans" -> "zh-CN"
                else -> raw.trim().ifBlank { "zh-CN" }
            }

        fun normalizeDirection(raw: String): String =
            if (raw == "b2a") "b2a" else "a2b"
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString("api_key", apiKey)
            putString("api_base", apiBase)
            putString("target_language", normalizeTargetLanguage(targetLanguage))
            putString("audio_source", audioSource)
            putInt("font_size", fontSize)
            putFloat("bg_opacity", bgOpacity)
            putBoolean("echo_target", echoTargetLanguage)
            putFloat("playback_volume", playbackVolume)
            putString("system_prompt", systemPrompt)
            putBoolean("show_original", showOriginal)
            putString("live_model", liveModel.trim().ifBlank { DEFAULT_LIVE_MODEL })
            putString("live_direction", normalizeDirection(liveDirection))
            putString("mode", "live")
            remove("bili_model")
            remove("bili_direction")
        }
    }
}
