package com.faqxd.livesub.android

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.data.Languages
import com.faqxd.livesub.android.service.LiveTranslateService

/**
 * Port of `settings_window.py:SettingsDialog`.
 *
 * MVP: a single scrollable form with the same sections as the Windows version
 * (Connection / Audio / Appearance / Advanced).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    private lateinit var apiKeyEdit: EditText
    private lateinit var showKeyBtn: Button
    private lateinit var apiBaseEdit: EditText
    private lateinit var langSpinner: Spinner
    private lateinit var sourceSpinner: Spinner
    private lateinit var volumeSlider: SeekBar
    private lateinit var echoCheck: CheckBox
    private lateinit var fontSlider: SeekBar
    private lateinit var opacitySlider: SeekBar
    private lateinit var showOriginalCheck: CheckBox
    private lateinit var promptLabel: TextView
    private lateinit var promptEdit: EditText
    private lateinit var biliModelLabel: TextView
    private lateinit var biliModelEdit: EditText
    private lateinit var saveBtn: Button
    private lateinit var cancelBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        settings = AppSettings.load(this)
        bindViews()
        populateViews()
    }

    private fun bindViews() {
        apiKeyEdit = findViewById(R.id.apiKeyEdit)
        showKeyBtn = findViewById(R.id.showKeyBtn)
        apiBaseEdit = findViewById(R.id.apiBaseEdit)
        langSpinner = findViewById(R.id.langSpinner)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        volumeSlider = findViewById(R.id.volumeSlider)
        echoCheck = findViewById(R.id.echoCheck)
        fontSlider = findViewById(R.id.fontSlider)
        opacitySlider = findViewById(R.id.opacitySlider)
        showOriginalCheck = findViewById(R.id.showOriginalCheck)
        promptLabel = findViewById(R.id.promptLabel)
        promptEdit = findViewById(R.id.promptEdit)
        biliModelLabel = findViewById(R.id.biliModelLabel)
        biliModelEdit = findViewById(R.id.biliModelEdit)
        saveBtn = findViewById(R.id.saveBtn)
        cancelBtn = findViewById(R.id.cancelBtn)

        showKeyBtn.setOnClickListener {
            val showing = apiKeyEdit.inputType and InputType.TYPE_MASK_VARIATION !=
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (showing) {
                apiKeyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                showKeyBtn.text = "Show"
            } else {
                apiKeyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                showKeyBtn.text = "Hide"
            }
        }

        saveBtn.setOnClickListener {
            // Snapshot the pre-edit values so we can decide whether the
            // running pipeline needs a hot restart (mirrors
            // `settings_window.py:SettingsDialog` → `main.py:open_settings`).
            val prev = settings.copy()
            applyFromUi()
            settings.save(this)

            val affectsPipeline = prev.apiKey != settings.apiKey ||
                prev.apiBase != settings.apiBase ||
                prev.targetLanguage != settings.targetLanguage ||
                prev.audioSource != settings.audioSource ||
                prev.echoTargetLanguage != settings.echoTargetLanguage ||
                prev.systemPrompt != settings.systemPrompt ||
                prev.mode != settings.mode ||
                prev.biliModel != settings.biliModel

            if (affectsPipeline) {
                // Forward a restart intent to the service. The service is
                // no-op if the pipeline isn't currently running, so it's
                // always safe to send.
                ContextCompat.startForegroundService(
                    this,
                    LiveTranslateService.restartIntent(this)
                )
            }

            setResult(RESULT_OK)
            finish()
        }
        cancelBtn.setOnClickListener { finish() }
    }

    private fun populateViews() {
        apiKeyEdit.setText(settings.apiKey)
        apiBaseEdit.setText(settings.apiBase)
        if (apiBaseEdit.text.isBlank()) apiBaseEdit.hint = AppSettings.DEFAULT_API_BASE

        langSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Languages.ALL.map { it.name }
        )
        langSpinner.setSelection(Languages.ALL.indexOfFirst { it.code == settings.targetLanguage }.coerceAtLeast(0))

        sourceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.audio_source_mic), getString(R.string.audio_source_system))
        )
        sourceSpinner.setSelection(if (settings.audioSource == "system") 1 else 0)

        volumeSlider.progress = (settings.playbackVolume * 100).toInt()
        echoCheck.isChecked = settings.echoTargetLanguage
        // Range 14..60, so shift by 14
        fontSlider.progress = (settings.fontSize - 14).coerceIn(0, 46)
        opacitySlider.progress = (settings.bgOpacity * 100).toInt()
        showOriginalCheck.isChecked = settings.showOriginal
        promptEdit.setText(settings.systemPrompt)
        biliModelEdit.setText(settings.biliModel)

        applyModeVisibility()
    }

    /**
     * In BILI modes the bidirectional system prompt is built-in and the
     * model ignores `translationConfig`, so the target-language picker,
     * echo checkbox, and custom prompt are irrelevant — disable them so
     * the user doesn't get confused. The biliModel override field is the
     * only BILI-specific knob.
     */
    private fun applyModeVisibility() {
        val bili = settings.isBilingual
        langSpinner.isEnabled = !bili
        echoCheck.isEnabled = !bili
        promptEdit.isEnabled = !bili
        promptLabel.alpha = if (bili) 0.4f else 1f
        promptLabel.text = if (bili) {
            "${getString(R.string.system_prompt)} (disabled in bilingual mode — built-in prompt)"
        } else {
            getString(R.string.system_prompt)
        }
        biliModelLabel.alpha = if (bili) 1f else 0.4f
        biliModelEdit.isEnabled = bili
        biliModelLabel.text = if (bili) {
            "Bilingual Model"
        } else {
            "Bilingual Model (used only in 中英 / 中日 modes)"
        }
    }

    private fun applyFromUi() {
        settings.apiKey = apiKeyEdit.text.toString().trim()
        settings.apiBase = apiBaseEdit.text.toString().trim().ifBlank { AppSettings.DEFAULT_API_BASE }
        val langIdx = langSpinner.selectedItemPosition
        if (langIdx in Languages.ALL.indices) {
            settings.targetLanguage = Languages.ALL[langIdx].code
        }
        settings.audioSource = if (sourceSpinner.selectedItemPosition == 1) "system" else "mic"
        settings.playbackVolume = volumeSlider.progress / 100f
        settings.echoTargetLanguage = echoCheck.isChecked
        settings.fontSize = (fontSlider.progress + 14).coerceIn(14, 60)
        settings.bgOpacity = opacitySlider.progress / 100f
        settings.showOriginal = showOriginalCheck.isChecked
        settings.systemPrompt = promptEdit.text.toString().trim()
        settings.biliModel = biliModelEdit.text.toString().trim()
            .ifBlank { AppSettings.Mode.BILINGUAL_DEFAULT_MODEL }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
