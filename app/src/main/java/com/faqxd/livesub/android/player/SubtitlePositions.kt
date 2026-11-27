package com.faqxd.livesub.android.player

import android.content.Context
import androidx.core.content.edit
import com.faqxd.livesub.android.data.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * What is kept of each subtitle, so reopening it continues where it
 * stopped, with the offset from the video it had then
 * (docs/plan-player-sync-ui.md, 5.3): the [MAX_KEPT] most recently
 * played, by [SubtitlePlayback.key]. Main thread only.
 */
object SubtitlePositions {

    /** Where playback stopped (null: from the beginning) and the offset from the video (+ subtitles earlier). */
    data class Kept(val positionMs: Long?, val offsetMs: Long)

    private const val PREF_KEY = "player_positions"
    private const val MAX_KEPT = 20

    /** What was kept of [key] last time; null if nothing. */
    fun get(context: Context, key: String): Kept? =
        entries(context).firstOrNull { it.first == key }?.second

    /**
     * Keep [positionMs] for [key], null to start from the beginning next
     * time (played to the end, or barely begun), and [offsetMs]; with
     * neither, forget it.
     */
    fun put(context: Context, key: String, positionMs: Long?, offsetMs: Long) {
        val kept = entries(context).filterTo(ArrayList()) { it.first != key }
        if (positionMs != null || offsetMs != 0L) kept.add(0, key to Kept(positionMs, offsetMs))
        val json = JSONArray()
        for ((k, entry) in kept.take(MAX_KEPT)) {
            val item = JSONObject().put("key", k)
            entry.positionMs?.let { item.put("ms", it) }
            if (entry.offsetMs != 0L) item.put("offset", entry.offsetMs)
            json.put(item)
        }
        AppSettings.prefs(context).edit { putString(PREF_KEY, json.toString()) }
    }

    /** The kept subtitles, most recent first. */
    private fun entries(context: Context): List<Pair<String, Kept>> {
        val raw = AppSettings.prefs(context).getString(PREF_KEY, null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { i ->
                val entry = json.optJSONObject(i) ?: return@mapNotNull null
                val key = entry.optString("key")
                if (key.isEmpty()) return@mapNotNull null
                val position = if (entry.has("ms")) entry.optLong("ms") else null
                key to Kept(position, entry.optLong("offset"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
