package com.faqxd.livesub.android.service

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import com.faqxd.livesub.android.MainActivity
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.data.AppSettings

/**
 * Quick Settings tile: one tap starts or stops the translation pipeline.
 * Whatever needs the app itself — a missing API key or permission, or
 * system audio without a live capture grant (consent dialog) — opens the
 * main screen instead, which runs the full start flow; so does a start
 * the system refuses from here.
 */
class LiveTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (LiveTranslateService.isPipelineRunning()) {
            try {
                startService(LiveTranslateService.stopIntent(this))
            } catch (e: Exception) {
                Log.w(TAG, "tile stop refused: ${e.message}")
                openApp()
            }
            return
        }
        if (!canStartHere()) {
            openApp()
            return
        }
        try {
            ContextCompat.startForegroundService(
                this,
                LiveTranslateService.startIntent(this, resultCode = 0, data = null),
            )
        } catch (e: Exception) {
            // E.g. a foreground-service start the system doesn't allow here.
            Log.w(TAG, "tile start refused: ${e.message}")
            openApp()
        }
        // The service reports the state change back via requestListeningState.
    }

    /** Whether the service can start without any UI from the app. */
    private fun canStartHere(): Boolean {
        val s = AppSettings.load(this)
        val needsConsent = s.audioSource == "system" && !LiveTranslateService.hasProjectionGrant()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return s.apiKey.isNotBlank() && !needsConsent && hasMic && Settings.canDrawOverlays(this)
    }

    /** Open the main screen and let it run the start flow. */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_AUTO_START, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile: Tile = qsTile ?: return
        val running = LiveTranslateService.isPipelineRunning()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.notif_title)
        tile.updateTile()
    }

    companion object {
        private const val TAG = "LiveTileService"

        /** Ask the system to refresh the tile after a pipeline state change. */
        fun requestUpdate(context: android.content.Context) {
            try {
                requestListeningState(context, ComponentName(context, LiveTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
