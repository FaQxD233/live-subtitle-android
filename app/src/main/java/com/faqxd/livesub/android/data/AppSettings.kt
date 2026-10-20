package com.faqxd.livesub.android.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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
 *  - playTranslation     — Whether to play the translated audio locally.
 *  - echoTargetLanguage  — Whether the model also repeats input that is
 *                          already in the target language (API field).
 *  - playbackVolume      — 0..1 playback volume.
 *  - showOriginal        — Whether to display the source-language transcript.
 *  - showLatestOnly      — Whether the overlay shows only the newest caption
 *                          (current sentence) instead of the full history.
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
    var playTranslation: Boolean = false,
    var echoTargetLanguage: Boolean = false,
    var playbackVolume: Float = 0.8f,
    var showOriginal: Boolean = false,
    var showLatestOnly: Boolean = false,
    var liveModel: String = DEFAULT_LIVE_MODEL,
    var liveDirection: String = "a2b",
    // Persisted overlay geometry in WindowManager px; MIN_VALUE = unset.
    var overlayX: Int = Int.MIN_VALUE,
    var overlayY: Int = Int.MIN_VALUE,
    var overlayWidth: Int = Int.MIN_VALUE,
    var overlayHeight: Int = Int.MIN_VALUE,
) {
    val normalizedTargetLanguage: String
        get() = normalizeTargetLanguage(targetLanguage)

    val effectiveTargetLanguage: String
        get() = if (liveDirection == "b2a" && normalizedTargetLanguage != SIMPLIFIED_CHINESE) {
            SIMPLIFIED_CHINESE
        } else {
            normalizedTargetLanguage
        }

    /**
     * Whether a direction toggle makes sense: when the target already is
     * Simplified Chinese, a2b and b2a coincide and the swap button hides.
     */
    val canToggleDirection: Boolean
        get() = normalizedTargetLanguage != SIMPLIFIED_CHINESE

    /** Flip Live direction (a2b ↔ b2a) in place. */
    fun toggleDirection() {
        liveDirection = if (liveDirection == "b2a") "a2b" else "b2a"
    }

    /**
     * Human-readable direction badge shared by the main screen, the overlay
     * and the minimized pill: "中 → EN" (or reversed), or just "→ 中" when
     * the target is Simplified Chinese regardless of the source language.
     */
    fun directionLabel(): String {
        val target = normalizedTargetLanguage
        if (target == SIMPLIFIED_CHINESE) {
            return "→ ${Languages.shortNameFor(target)}"
        }
        val targetName = Languages.shortNameFor(target)
        return if (liveDirection == "b2a") "$targetName → 中" else "中 → $targetName"
    }

    companion object {
        const val DEFAULT_API_BASE = "https://generativelanguage.googleapis.com"
        const val SIMPLIFIED_CHINESE = "zh-CN"
        const val DEFAULT_LIVE_MODEL = "gemini-3.5-live-translate-preview"
        private const val PREFS_NAME = "livebuddy_settings"

        /** Backing preferences file for [load] / [save]; also for change listeners. */
        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun load(context: Context): AppSettings {
            val prefs = prefs(context)
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
            // AudioPlaybackCapture exists only on Android 10+ (API 29); an
            // old persisted "system" value would crash the pipeline, so
            // normalize to the microphone on older systems.
            val audioSource = prefs.getString("audio_source", "mic") ?: "mic"
            return AppSettings(
                apiKey = prefs.getString("api_key", "") ?: "",
                apiBase = prefs.getString("api_base", DEFAULT_API_BASE) ?: DEFAULT_API_BASE,
                targetLanguage = normalizeTargetLanguage(migratedTargetLanguage),
                audioSource = if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && audioSource == "system"
                ) "mic" else audioSource,
                fontSize = prefs.getInt("font_size", 16),
                bgOpacity = prefs.getFloat("bg_opacity", 0.6f),
                // "echo_target" historically controlled both local playback
                // and the API's same-language echo. It now feeds
                // playTranslation only; the API flag moved to its own key.
                playTranslation = prefs.getBoolean("echo_target", false),
                echoTargetLanguage = prefs.getBoolean("echo_same_language", false),
                playbackVolume = prefs.getFloat("playback_volume", 0.8f),
                showOriginal = prefs.getBoolean("show_original", false),
                showLatestOnly = prefs.getBoolean("latest_only", false),
                liveModel = liveModel,
                liveDirection = normalizeDirection(rawDirection),
                overlayX = prefs.getInt("overlay_x", Int.MIN_VALUE),
                overlayY = prefs.getInt("overlay_y", Int.MIN_VALUE),
                overlayWidth = prefs.getInt("overlay_width", Int.MIN_VALUE),
                overlayHeight = prefs.getInt("overlay_height", Int.MIN_VALUE),
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
                // Simplified Chinese keeps the legacy app code (documented
                // spelling zh-Hans maps onto it); bare "pt" predates the
                // official split into pt-BR / pt-PT.
                "zh", "zh-Hans" -> "zh-CN"
                "pt" -> "pt-BR"
                else -> raw.trim().ifBlank { "zh-CN" }
            }

        fun normalizeDirection(raw: String): String =
            if (raw == "b2a") "b2a" else "a2b"

        /**
         * Persist only the overlay geometry keys. The overlay holds its own
         * settings snapshot which may lag behind unrelated changes, so it
         * must not write the whole [AppSettings] back.
         */
        fun saveOverlayGeometry(context: Context, x: Int, y: Int, width: Int, height: Int) {
            prefs(context).edit {
                putInt("overlay_x", x)
                putInt("overlay_y", y)
                putInt("overlay_width", width)
                putInt("overlay_height", height)
            }
        }
    }

    /**
     * Persist the user-facing settings. The overlay geometry is owned by
     * [saveOverlayGeometry] and deliberately not written here, so a screen
     * saving an older snapshot can't move the window back. Callers should
     * start from a fresh [load] so other fields changed meanwhile survive.
     */
    fun save(context: Context) {
        prefs(context).edit {
            putString("api_key", apiKey)
            putString("api_base", apiBase)
            putString("target_language", normalizeTargetLanguage(targetLanguage))
            putString("audio_source", audioSource)
            putInt("font_size", fontSize)
            putFloat("bg_opacity", bgOpacity)
            putBoolean("echo_target", playTranslation)
            putBoolean("echo_same_language", echoTargetLanguage)
            putFloat("playback_volume", playbackVolume)
            putBoolean("show_original", showOriginal)
            putBoolean("latest_only", showLatestOnly)
            putString("live_model", liveModel.trim().ifBlank { DEFAULT_LIVE_MODEL })
            putString("live_direction", normalizeDirection(liveDirection))
            putString("mode", "live")
            remove("bili_model")
            remove("bili_direction")
        }
    }
}
