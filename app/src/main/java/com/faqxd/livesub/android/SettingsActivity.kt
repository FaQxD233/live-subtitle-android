package com.faqxd.livesub.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.data.Languages
import com.faqxd.livesub.android.service.LiveTranslateService
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/**
 * Port of `settings_window.py:SettingsDialog`.
 *
 * MVP: a single scrollable form with the same sections as the Windows version
 * (Connection / Audio / Appearance / Advanced).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    /** Language picked in the picker but not yet saved; null = unchanged. */
    private var pickedLanguage: String? = null

    private lateinit var apiKeyEdit: EditText
    private lateinit var showKeyBtn: Button
    private lateinit var apiBaseEdit: EditText
    private lateinit var langPicker: MaterialAutoCompleteTextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var volumeSlider: SeekBar
    private lateinit var echoCheck: CheckBox
    private lateinit var echoSameLangCheck: CheckBox
    private lateinit var fontSlider: SeekBar
    private lateinit var opacitySlider: SeekBar
    private lateinit var showOriginalCheck: CheckBox
    private lateinit var latestOnlyCheck: CheckBox
    private lateinit var latestCountLabel: TextView
    private lateinit var latestCountSpinner: Spinner
    private lateinit var modelLabel: TextView
    private lateinit var modelEdit: EditText
    private lateinit var saveBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var testConnBtn: Button

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
        langPicker = findViewById(R.id.langPicker)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        volumeSlider = findViewById(R.id.volumeSlider)
        echoCheck = findViewById(R.id.echoCheck)
        echoSameLangCheck = findViewById(R.id.echoSameLangCheck)
        fontSlider = findViewById(R.id.fontSlider)
        opacitySlider = findViewById(R.id.opacitySlider)
        showOriginalCheck = findViewById(R.id.showOriginalCheck)
        latestOnlyCheck = findViewById(R.id.latestOnlyCheck)
        latestCountLabel = findViewById(R.id.latestCountLabel)
        latestCountSpinner = findViewById(R.id.latestCountSpinner)
        modelLabel = findViewById(R.id.modelLabel)
        modelEdit = findViewById(R.id.modelEdit)
        saveBtn = findViewById(R.id.saveBtn)
        cancelBtn = findViewById(R.id.cancelBtn)
        testConnBtn = findViewById(R.id.testConnBtn)

        showKeyBtn.setOnClickListener {
            val showing = apiKeyEdit.inputType and InputType.TYPE_MASK_VARIATION !=
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (showing) {
                apiKeyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                showKeyBtn.text = getString(R.string.show)
            } else {
                apiKeyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                showKeyBtn.text = getString(R.string.hide)
            }
        }

        saveBtn.setOnClickListener {
            // Reject a bad base URL before touching anything else.
            val apiBaseError = validateApiBase(apiBaseEdit.text.toString())
            if (apiBaseError != null) {
                apiBaseEdit.error = apiBaseError
                return@setOnClickListener
            }
            apiBaseEdit.error = null
            // Start from the latest persisted values, not the onCreate
            // snapshot: fields this form doesn't edit (direction, toggled from
            // the overlay meanwhile) must not be reverted. The same baseline
            // decides whether the running pipeline needs a hot restart
            // (mirrors `settings_window.py:SettingsDialog` →
            // `main.py:open_settings`).
            val prev = AppSettings.load(this)
            settings = prev.copy()
            applyFromUi()
            settings.save(this)

            val affectsPipeline = prev.apiKey != settings.apiKey ||
                prev.apiBase != settings.apiBase ||
                prev.targetLanguage != settings.targetLanguage ||
                prev.audioSource != settings.audioSource ||
                prev.playTranslation != settings.playTranslation ||
                prev.echoTargetLanguage != settings.echoTargetLanguage ||
                prev.liveModel != settings.liveModel

            if (affectsPipeline && LiveTranslateService.isPipelineRunning()) {
                // Forward a restart intent to the service. The service is
                // already in the foreground while the pipeline is active.
                // Do not start it only to deliver a no-op restart command:
                // Android 8+ requires startForegroundService callers to
                // promote the service quickly, which ACTION_RESTART does
                // not do when no pipeline is running.
                ContextCompat.startForegroundService(
                    this,
                    LiveTranslateService.restartIntent(this)
                )
            }

            setResult(RESULT_OK)
            finish()
        }
        cancelBtn.setOnClickListener { finish() }

        testConnBtn.setOnClickListener {
            val apiKey = apiKeyEdit.text.toString().trim()
            if (apiKey.isBlank()) {
                apiKeyEdit.error = getString(R.string.err_no_api_key)
                return@setOnClickListener
            }
            apiKeyEdit.error = null
            val base = apiBaseEdit.text.toString().trim().ifBlank { AppSettings.DEFAULT_API_BASE }
            val model = modelEdit.text.toString().trim().ifBlank { AppSettings.DEFAULT_LIVE_MODEL }
            testConnBtn.isEnabled = false
            testConnBtn.text = getString(R.string.testing_connection)
            // Probe on a worker thread; toast the outcome on the main thread.
            Thread {
                val result = ConnectionTester.test(apiKey, base, model)
                runOnUiThread {
                    testConnBtn.isEnabled = true
                    testConnBtn.text = getString(R.string.test_connection)
                    val message = when (result) {
                        is ConnectionTester.Result.Ok -> getString(R.string.test_ok)
                        is ConnectionTester.Result.Failed ->
                            getString(R.string.test_failed, result.detail)
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    /**
     * Validate the API base URL. Blank means "use the default". Otherwise
     * only https:// or wss:// is accepted (Android's cleartext policy blocks
     * plain http/ws anyway), and the host part must be non-empty.
     *
     * @return an error message, or null when the value is acceptable.
     */
    private fun validateApiBase(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val hasScheme = value.startsWith("https://") || value.startsWith("wss://")
        val host = value
            .substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore('?')
            .trim()
        return if (hasScheme && host.isNotEmpty()) null else getString(R.string.api_base_invalid)
    }

    private fun populateViews() {
        apiKeyEdit.setText(settings.apiKey)
        apiBaseEdit.setText(settings.apiBase)
        if (apiBaseEdit.text.isBlank()) apiBaseEdit.hint = AppSettings.DEFAULT_API_BASE

        // Searchable language picker. Tapping the field shows the FULL
        // list (the filter is reset to empty); typing filters by name or
        // code — the code is embedded in each row so "pt-BR" finds
        // Portuguese (Brazil).
        val langDisplay = Languages.ALL.map { "${it.name}  (${it.code})" }
        val langAdapter = LanguageAdapter(this, langDisplay)
        langPicker.setAdapter(langAdapter)
        langPicker.setText(Languages.nameFor(settings.targetLanguage), false)
        // Tap = open the full list; free typing filters as usual. The first
        // tap on an unfocused editable only grants focus (and opens the
        // IME) without firing onClick, so focus opens the list as well.
        val showFullList = android.widget.Filter.FilterListener { _ ->
            langPicker.showDropDown()
        }
        langPicker.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) langAdapter.filter.filter("", showFullList)
        }
        langPicker.setOnClickListener {
            langAdapter.filter.filter("", showFullList)
        }
        // Leaving without picking restores the current selection label.
        langPicker.setOnDismissListener {
            langPicker.setText(Languages.nameFor(pickedLanguage ?: settings.targetLanguage), false)
        }
        langPicker.setOnItemClickListener { _, _, position, _ ->
            val item = langPicker.adapter.getItem(position) as? String ?: return@setOnItemClickListener
            val code = item.substringAfterLast('(').removeSuffix(")").trim()
            if (Languages.ALL.any { it.code == code }) {
                pickedLanguage = code
                langPicker.setText(Languages.nameFor(code), false)
            }
        }

        // Loopback capture exists only on Android 10+; don't offer it on
        // older systems (AppSettings also normalizes a stale "system").
        val supportsSystemAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        sourceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            if (supportsSystemAudio) {
                listOf(getString(R.string.audio_source_mic), getString(R.string.audio_source_system))
            } else {
                listOf(getString(R.string.audio_source_mic))
            }
        )
        sourceSpinner.setSelection(
            if (supportsSystemAudio && settings.audioSource == "system") 1 else 0
        )

        volumeSlider.progress = (settings.playbackVolume * 100).toInt()
        echoCheck.isChecked = settings.playTranslation
        echoSameLangCheck.isChecked = settings.echoTargetLanguage
        // Range 14..60, so shift by 14
        fontSlider.progress = (settings.fontSize - 14).coerceIn(0, 46)
        opacitySlider.progress = (settings.bgOpacity * 100).toInt()
        showOriginalCheck.isChecked = settings.showOriginal
        latestOnlyCheck.isChecked = settings.showLatestOnly
        latestCountSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            (1..AppSettings.MAX_LATEST_SENTENCES).map { it.toString() },
        )
        latestCountSpinner.setSelection(settings.latestSentenceCount - 1)
        // The count only matters in latest-only mode.
        setLatestCountEnabled(settings.showLatestOnly)
        latestOnlyCheck.setOnCheckedChangeListener { _, checked -> setLatestCountEnabled(checked) }
        modelEdit.setText(settings.liveModel)
        modelLabel.text = "Live Model"
    }

    private fun applyFromUi() {
        settings.apiKey = apiKeyEdit.text.toString().trim()
        settings.apiBase = apiBaseEdit.text.toString().trim().ifBlank { AppSettings.DEFAULT_API_BASE }
        // targetLanguage only changes when a picker row was selected;
        // free-typed text that matches nothing is ignored.
        pickedLanguage?.let { settings.targetLanguage = it }
        settings.audioSource = if (sourceSpinner.selectedItemPosition == 1) "system" else "mic"
        settings.playbackVolume = volumeSlider.progress / 100f
        settings.playTranslation = echoCheck.isChecked
        settings.echoTargetLanguage = echoSameLangCheck.isChecked
        settings.fontSize = (fontSlider.progress + 14).coerceIn(14, 60)
        settings.bgOpacity = opacitySlider.progress / 100f
        settings.showOriginal = showOriginalCheck.isChecked
        settings.showLatestOnly = latestOnlyCheck.isChecked
        settings.latestSentenceCount = latestCountSpinner.selectedItemPosition + 1
        settings.liveModel = modelEdit.text.toString().trim()
            .ifBlank { AppSettings.DEFAULT_LIVE_MODEL }
    }

    private fun setLatestCountEnabled(enabled: Boolean) {
        latestCountLabel.isEnabled = enabled
        latestCountSpinner.isEnabled = enabled
        latestCountLabel.alpha = if (enabled) 1f else 0.4f
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * Dropdown model for the language picker: an empty query lists every
     * language; a non-empty one filters by name or code (case-insensitive).
     */
    private class LanguageAdapter(
        context: Context,
        private val items: List<String>,
    ) : ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line) {

        private val filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString()?.trim()?.lowercase().orEmpty()
                val matches = if (query.isEmpty()) {
                    items
                } else {
                    items.filter { it.lowercase().contains(query) }
                }
                return FilterResults().apply { values = matches; count = matches.size }
            }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                @Suppress("UNCHECKED_CAST")
                val matches = results?.values as? List<String> ?: emptyList()
                setNotifyOnChange(false)
                clear()
                addAll(matches)
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = filter
    }
}
