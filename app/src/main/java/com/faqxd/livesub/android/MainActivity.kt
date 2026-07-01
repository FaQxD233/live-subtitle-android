package com.faqxd.livesub.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.data.Languages
import com.faqxd.livesub.android.service.LiveTranslateService
import com.google.android.material.button.MaterialButton

/**
 * Entry Activity.
 *
 * Equivalent of [main.py:LiveBuddyApp.main] on Android:
 *  - Loads [AppSettings].
 *  - Renders an in-app preview of the caption (so users can verify their
 *    settings without enabling the overlay).
 *  - Requests the runtime permissions needed by [LiveTranslateService]:
 *      * RECORD_AUDIO (microphone)
 *      * POST_NOTIFICATIONS (Android 13+)
 *      * SYSTEM_ALERT_WINDOW (overlay)
 *      * MediaProjection token (only if the user chose "system" audio source)
 *  - Starts the service, which displays the floating HUD.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var langBadge: TextView
    private lateinit var outputView: TextView
    private lateinit var inputView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var hintText: TextView
    private lateinit var directionBtn: MaterialButton

    private var pendingStart = false
    private var serviceRunning = false
    private var waitingForOverlayPermission = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) continueStartFlow() else showHint(getString(R.string.perm_mic_rationale))
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Notifications are nice-to-have (service still runs without them on
        // older devices), so we proceed regardless.
        continueStartFlow()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        handleOverlayPermissionReturn()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startServiceWithProjection(result.resultCode, result.data!!)
        } else {
            showHint(getString(R.string.perm_system_audio_rationale))
            pendingStart = false
            serviceRunning = false
            toggleBtn.text = getString(R.string.start)
        }
    }

    private fun startServiceWithProjection(resultCode: Int, data: Intent?) {
        ContextCompat.startForegroundService(
            this,
            LiveTranslateService.startIntent(this, resultCode, data)
        )
        serviceRunning = true
        pendingStart = false
        toggleBtn.text = getString(R.string.stop)
        hintText.text = getString(R.string.floating_overlay_hint)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = AppSettings.load(this)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        langBadge = findViewById(R.id.langBadge)
        outputView = findViewById(R.id.outputView)
        inputView = findViewById(R.id.inputView)
        toggleBtn = findViewById(R.id.toggleBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        hintText = findViewById(R.id.hintText)
        directionBtn = findViewById(R.id.directionBtn)

        toggleBtn.setOnClickListener { onToggleClicked() }
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        directionBtn.setOnClickListener { onDirectionToggleClicked() }

        applySettingsToUi()
    }

    override fun onResume() {
        super.onResume()
        // Refresh settings in case the user changed something in SettingsActivity.
        settings = AppSettings.load(this)
        // Sync the toggle button with the actual service state — the
        // activity may have been recreated (rotation, recents) while the
        // foreground service kept running.
        serviceRunning = LiveTranslateService.isPipelineRunning()
        toggleBtn.text = if (serviceRunning) getString(R.string.stop) else getString(R.string.start)
        applySettingsToUi()
        if (waitingForOverlayPermission && pendingStart) {
            handleOverlayPermissionReturn()
        }
    }

    private fun applySettingsToUi() {
        langBadge.text = directionLabel(settings)
        directionBtn.visibility = View.VISIBLE
        inputView.visibility = if (settings.showOriginal) View.VISIBLE else View.GONE
        if (settings.apiKey.isBlank()) {
            showHint(getString(R.string.err_no_api_key))
        }
    }

    private fun onToggleClicked() {
        if (serviceRunning) {
            stopService(LiveTranslateService.stopIntent(this))
            serviceRunning = false
            toggleBtn.text = getString(R.string.start)
            return
        }
        if (settings.apiKey.isBlank()) {
            showHint(getString(R.string.err_no_api_key))
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        pendingStart = true
        // Permission chain: overlay → mic → notif → (projection if "system")
        if (!hasOverlayPermission()) {
            showHint(getString(R.string.perm_overlay_rationale))
            waitingForOverlayPermission = true
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }
        if (!hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission()) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueStartFlow()
    }

    /**
     * Flip Live direction (Chinese → target vs target → Chinese), persist it,
     * refresh the badge, and hot-restart the running pipeline so the new
     * `translationConfig.targetLanguageCode` takes effect.
     */
    private fun onDirectionToggleClicked() {
        settings.liveDirection = if (settings.liveDirection == "b2a") "a2b" else "b2a"
        settings.save(this)
        applySettingsToUi()
        if (LiveTranslateService.isPipelineRunning()) {
            ContextCompat.startForegroundService(
                this,
                LiveTranslateService.restartIntent(this)
            )
        }
    }

    private fun directionLabel(s: AppSettings): String {
        val target = s.normalizedTargetLanguage
        if (target == AppSettings.SIMPLIFIED_CHINESE) {
            return "→ ${Languages.nameFor(target)}"
        }
        val targetName = Languages.shortNameFor(target)
        return if (s.liveDirection == "b2a") "$targetName → 中" else "中 → $targetName"
    }

    private fun continueStartFlow() {
        if (!pendingStart) return
        if (!hasOverlayPermission()) {
            showHint(getString(R.string.perm_overlay_rationale))
            pendingStart = false
            return
        }
        if (!hasMicPermission()) {
            showHint(getString(R.string.perm_mic_rationale))
            pendingStart = false
            return
        }
        if (settings.audioSource == "system") {
            // Need a MediaProjection token. Prompt the user.
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            startServiceWithProjection(0, null)
        }
    }

    private fun handleOverlayPermissionReturn() {
        if (!waitingForOverlayPermission) return
        waitingForOverlayPermission = false
        if (!pendingStart) return
        if (hasOverlayPermission()) {
            continueStartFlow()
        } else {
            pendingStart = false
            showHint(getString(R.string.perm_overlay_rationale))
        }
    }

    private fun hasOverlayPermission(): Boolean =
        Settings.canDrawOverlays(this)

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotifPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    private fun showHint(text: String) {
        hintText.text = text
    }

}
