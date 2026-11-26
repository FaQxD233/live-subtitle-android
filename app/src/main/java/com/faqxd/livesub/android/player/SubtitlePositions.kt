package com.faqxd.livesub.android.player

import android.content.Context
import androidx.core.content.edit
import com.faqxd.livesub.android.data.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where playback of each subtitle stopped, so reopening it continues
 * there: the [MAX_KEPT] most recently played, by [SubtitlePlayback.key].
 * Main thread only.
 */
object SubtitlePositions {

    private const val PREF_KEY = "player_positions"
    private const val MAX_KEPT = 20

    /** Where playback of [key] stopped last time; null to start from the beginning. */
    fun get(context: Context, key: String): Long? =
        entries(context).firstOrNull { it.first == key }?.second

    /** Remember [positionMs] for [key], or with null forget it (played to the end, or barely begun). */
    fun put(context: Context, key: String, positionMs: Long?) {
        val kept = entries(context).filterTo(ArrayList()) { it.first != key }
        if (positionMs != null) kept.add(0, key to positionMs)
        val json = JSONArray()
        for ((k, ms) in kept.take(MAX_KEPT)) json.put(JSONObject().put("key", k).put("ms", ms))
        AppSettings.prefs(context).edit { putString(PREF_KEY, json.toString()) }
    }

    /** The kept positions, most recent first. */
    private fun entries(context: Context): List<Pair<String, Long>> {
        val raw = AppSettings.prefs(context).getString(PREF_KEY, null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { i ->
                val entry = json.optJSONObject(i) ?: return@mapNotNull null
                val key = entry.optString("key")
                if (key.isEmpty()) null else key to entry.optLong("ms")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
