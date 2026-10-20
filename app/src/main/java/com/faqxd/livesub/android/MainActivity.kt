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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.data.CaptionMode
import com.faqxd.livesub.android.service.CaptionOverlayView.StatusKind
import com.faqxd.livesub.android.service.LiveTranslateService
import com.faqxd.livesub.android.service.PipelineState
import com.faqxd.livesub.android.transcript.TranscriptExporter
import com.faqxd.livesub.android.transcript.TranscriptStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

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
    private lateinit var outputScroll: ScrollView
    private lateinit var outputView: TextView
    private lateinit var inputDivider: View
    private lateinit var inputScroll: ScrollView
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
        outputScroll = findViewById(R.id.outputScroll)
        outputView = findViewById(R.id.outputView)
        inputDivider = findViewById(R.id.inputDivider)
        inputScroll = findViewById(R.id.inputScroll)
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
        findViewById<View>(R.id.exportBtn).setOnClickListener {
            lifecycleScope.launch { TranscriptExporter.shareHistory(this@MainActivity) }
        }
        findViewById<View>(R.id.clearHistoryBtn).setOnClickListener { confirmClearHistory() }

        applySettingsToUi()
        // A recreated activity (rotation, process restore) must not start again.
        if (savedInstanceState == null) handleAutoStart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoStart(intent)
    }

    /**
     * The quick settings tile opens this screen with [EXTRA_AUTO_START] when
     * starting needs the app (API key, permissions, system-audio consent):
     * run the normal start flow once.
     */
    private fun handleAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) != true) return
        intent.removeExtra(EXTRA_AUTO_START)
        if (!LiveTranslateService.isPipelineRunning()) onToggleClicked()
    }

    override fun onStart() {
        super.onStart()
        // Live pipeline state from the foreground service (status dot, text,
        // current captions). Re-rendered immediately and on every publish.
        PipelineState.setListener { snapshot -> renderPipelineState(snapshot) }
        renderPipelineState(PipelineState.current())
    }

    override fun onStop() {
        super.onStop()
        PipelineState.setListener(null)
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
        langBadge.text = settings.directionLabel()
        // a2b/b2a coincide when the target already is Simplified Chinese.
        directionBtn.visibility =
            if (settings.canToggleDirection) View.VISIBLE else View.GONE
        // Only bilingual mode has a second (original) area; otherwise the
        // main caption — the original itself in original-only mode, as on
        // the overlay — takes the whole card.
        val originalVisibility =
            if (settings.captionMode == CaptionMode.BOTH) View.VISIBLE else View.GONE
        inputDivider.visibility = originalVisibility
        inputScroll.visibility = originalVisibility
        val noKeyHint = getString(R.string.err_no_api_key)
        if (settings.apiKey.isBlank()) {
            showHint(noKeyHint)
        } else if (hintText.text.toString() == noKeyHint) {
            // The key was just saved in Settings; drop the stale prompt.
            showHint(
                getString(
                    if (serviceRunning) R.string.floating_overlay_hint
                    else R.string.floating_overlay_idle_hint
                )
            )
        }
    }

    /** Mirror the service-published pipeline snapshot into the status card. */
    private fun renderPipelineState(s: PipelineState.Snapshot) {
        val colorRes = when (s.kind) {
            StatusKind.IDLE -> R.color.status_idle
            StatusKind.CONNECTING -> R.color.status_connecting
            StatusKind.CONNECTED -> R.color.status_connected
            StatusKind.ERROR -> R.color.status_error
        }
        statusDot.background?.setTint(ContextCompat.getColor(this, colorRes))
        statusText.text = s.status.ifEmpty { getString(R.string.status_idle) }
        val output = s.output.ifEmpty { "—" }
        if (outputView.text.toString() != output) {
            outputView.text = output
            scrollToEnd(outputScroll, outputView)
        }
        if (inputView.text.toString() != s.input) {
            inputView.text = s.input
            scrollToEnd(inputScroll, inputView)
        }
        if (serviceRunning != s.running) {
            serviceRunning = s.running
            toggleBtn.text = getString(if (s.running) R.string.stop else R.string.start)
        }
    }

    /** Delete the recorded caption history after an explicit confirmation. */
    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.clear_history_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                TranscriptStore.clear()
                Toast.makeText(this, R.string.clear_history_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Show the newest caption line once the new text has been laid out. */
    private fun scrollToEnd(scroll: ScrollView, content: View) {
        scroll.post { scroll.scrollTo(0, content.bottom) }
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
        // Toggle the latest persisted settings rather than the onResume
        // snapshot, so changes made meanwhile (e.g. from the overlay) stay.
        settings = AppSettings.load(this).also {
            it.toggleDirection()
            it.save(this)
        }
        applySettingsToUi()
        if (LiveTranslateService.isPipelineRunning()) {
            ContextCompat.startForegroundService(
                this,
                LiveTranslateService.restartIntent(this)
            )
        }
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
        if (settings.audioSource == "system" && !LiveTranslateService.hasProjectionGrant()) {
            // Need a MediaProjection token. Prompt the user.
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            // Microphone, or the (paused) service still holds a live
            // system-audio grant that the start reuses.
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

    companion object {
        /** Run the start flow right away (sent by the quick settings tile). */
        const val EXTRA_AUTO_START = "com.faqxd.livesub.android.AUTO_START"
    }
}
