package com.faqxd.livesub.android.service

import android.content.ComponentName
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.faqxd.livesub.android.R

/**
 * Quick Settings tile: one tap starts (mic path) or stops the translation
 * pipeline. System-audio capture needs a fresh MediaProjection consent, so
 * starting that mode from the tile is refused by the pipeline with a status
 * message — grant capture from the main screen first.
 */
class LiveTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (LiveTranslateService.isPipelineRunning()) {
            startService(LiveTranslateService.stopIntent(this))
        } else {
            ContextCompat.startForegroundService(
                this,
                LiveTranslateService.startIntent(this, resultCode = 0, data = null),
            )
        }
        // The service reports the state change back via requestListeningState.
    }

    private fun updateTile() {
        val tile: Tile = qsTile ?: return
        val running = LiveTranslateService.isPipelineRunning()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.notif_title)
        tile.updateTile()
    }

    companion object {
        /** Ask the system to refresh the tile after a pipeline state change. */
        fun requestUpdate(context: android.content.Context) {
            try {
                requestListeningState(context, ComponentName(context, LiveTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
