package com.faqxd.livesub.android

import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.faqxd.livesub.android.transcript.TranscriptExporter
import com.faqxd.livesub.android.transcript.TranscriptRecorder
import com.faqxd.livesub.android.transcript.TranscriptStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Caption records: the recent runs, which the user saves or discards, and
 * the saved transcripts, which can be shared or deleted. Tapping a row
 * shows the whole transcript.
 */
class TranscriptsActivity : AppCompatActivity() {

    private lateinit var recentList: LinearLayout
    private lateinit var recentEmpty: View
    private lateinit var savedList: LinearLayout
    private lateinit var savedEmpty: View
    private lateinit var savedLocation: TextView
    private val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcripts)
        recentList = findViewById(R.id.recentList)
        recentEmpty = findViewById(R.id.recentEmpty)
        savedList = findViewById(R.id.savedList)
        savedEmpty = findViewById(R.id.savedEmpty)
        savedLocation = findViewById(R.id.savedLocation)
        findViewById<TextView>(R.id.recentHint).text = getString(
            R.string.transcripts_recent_hint,
            TranscriptStore.MAX_RECENT,
            TranscriptRecorder.MIN_KEPT_SESSION_MS / 1000,
        )
    }

    override fun onStart() {
        super.onStart()
        // A run that ends meanwhile (e.g. stopped from the overlay) shows up right away.
        TranscriptStore.setOnChangedListener { reload() }
        reload()
    }

    override fun onStop() {
        super.onStop()
        TranscriptStore.setOnChangedListener(null)
    }

    private fun reload() {
        lifecycleScope.launch {
            val recent = TranscriptStore.recent()
            val saved = TranscriptStore.saved()
            val location = TranscriptStore.savedLocation()
            showRecent(recent)
            showSaved(saved)
            savedLocation.text = location
                ?.let { getString(R.string.transcripts_saved_location, it.absolutePath) }
                ?: getString(R.string.transcripts_storage_unavailable)
        }
    }

    private fun showRecent(items: List<TranscriptStore.Recent>) {
        recentList.removeAllViews()
        recentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) {
            val duration = DateUtils.formatElapsedTime((item.endedAt - item.startedAt) / 1000)
            addRow(
                list = recentList,
                record = item,
                title = "${dateTime.format(Date(item.startedAt))} · $duration",
                subtitle = item.preview,
                primaryText = R.string.save,
                onPrimary = { button ->
                    // Once is enough; the list is rebuilt afterwards.
                    button.isEnabled = false
                    save(item)
                },
                secondaryText = R.string.transcript_discard,
                onSecondary = {
                    confirm(R.string.transcript_discard_confirm, R.string.transcript_discard) {
                        delete(item)
                    }
                },
            )
        }
    }

    private fun showSaved(items: List<TranscriptStore.Saved>) {
        savedList.removeAllViews()
        savedEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) {
            addRow(
                list = savedList,
                record = item,
                title = dateTime.format(Date(item.startedAt)),
                subtitle = Formatter.formatShortFileSize(this, item.size),
                primaryText = R.string.transcript_share,
                onPrimary = { TranscriptExporter.shareFile(this, item.file) },
                secondaryText = R.string.delete,
                onSecondary = {
                    confirm(R.string.transcript_delete_confirm, R.string.delete) { delete(item) }
                },
            )
        }
    }

    /** Add a row for [record] to [list]: tap its text to read it, two actions on the right. */
    private fun addRow(
        list: LinearLayout,
        record: TranscriptStore.Record,
        title: String,
        subtitle: String,
        @StringRes primaryText: Int,
        onPrimary: (View) -> Unit,
        @StringRes secondaryText: Int,
        onSecondary: () -> Unit,
    ) {
        val row = layoutInflater.inflate(R.layout.item_transcript, list, false)
        row.findViewById<TextView>(R.id.transcriptTitle).text = title
        row.findViewById<TextView>(R.id.transcriptSubtitle).apply {
            text = subtitle
            visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        }
        row.findViewById<View>(R.id.transcriptText).setOnClickListener { showText(title, record) }
        row.findViewById<MaterialButton>(R.id.transcriptPrimaryBtn).apply {
            setText(primaryText)
            setOnClickListener { onPrimary(it) }
        }
        row.findViewById<MaterialButton>(R.id.transcriptSecondaryBtn).apply {
            setText(secondaryText)
            setOnClickListener { onSecondary() }
        }
        list.addView(row)
    }

    private fun save(item: TranscriptStore.Recent) {
        lifecycleScope.launch {
            try {
                TranscriptStore.save(this@TranscriptsActivity, item)
                Toast.makeText(this@TranscriptsActivity, R.string.transcript_save_done, Toast.LENGTH_SHORT)
                    .show()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showError(R.string.transcript_save_failed, e)
            }
            reload()
        }
    }

    private fun delete(record: TranscriptStore.Record) {
        lifecycleScope.launch {
            TranscriptStore.delete(record)
            reload()
        }
    }

    /** Show [record] in full; the text can be selected and copied. */
    private fun showText(title: String, record: TranscriptStore.Record) {
        lifecycleScope.launch {
            val text = try {
                TranscriptStore.readText(this@TranscriptsActivity, record)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showError(R.string.transcript_open_failed, e)
                // It may have been replaced by a newer run meanwhile.
                reload()
                return@launch
            }
            val dialog = MaterialAlertDialogBuilder(this@TranscriptsActivity)
                .setTitle(title)
                .setView(R.layout.dialog_transcript)
                .setPositiveButton(R.string.close, null)
                .show()
            dialog.findViewById<TextView>(R.id.transcriptBody)?.text = text
        }
    }

    private fun confirm(@StringRes message: Int, @StringRes action: Int, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(action) { _, _ -> onConfirm() }
            .show()
    }

    private fun showError(@StringRes message: Int, e: Exception) {
        Toast.makeText(
            this,
            getString(message, e.message ?: e.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
    }
}
