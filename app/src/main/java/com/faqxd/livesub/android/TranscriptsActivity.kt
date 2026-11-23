package com.faqxd.livesub.android

import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Menu
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.faqxd.livesub.android.player.SubtitlePlayerService
import com.faqxd.livesub.android.transcript.TranscriptCues
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
 * the saved ones, which can be exported (as subtitles or text) or deleted,
 * plus the text transcripts earlier versions saved. Tapping a row shows
 * the whole transcript.
 */
class TranscriptsActivity : AppCompatActivity() {

    /** A row action: its label and what it does with the tapped view. */
    private class Action(@StringRes val label: Int, val perform: (View) -> Unit)

    private lateinit var recentList: LinearLayout
    private lateinit var recentEmpty: View
    private lateinit var savedList: LinearLayout
    private lateinit var savedEmpty: View
    private lateinit var savedLocation: TextView
    private val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val exportDialog = ExportDialog(this)

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
            showRecent(recent)
            showSaved(saved)
            // Only the text transcripts of earlier versions live in shared storage.
            val location = if (saved.any { it is TranscriptStore.TextFile }) {
                TranscriptStore.textLocation()
            } else {
                null
            }
            savedLocation.text = location?.let { getString(R.string.transcripts_saved_location, it.absolutePath) }
            savedLocation.visibility = if (location != null) View.VISIBLE else View.GONE
        }
    }

    private fun showRecent(items: List<TranscriptStore.Journal>) {
        recentList.removeAllViews()
        recentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) {
            addRow(
                list = recentList,
                record = item,
                title = runTitle(item),
                subtitle = item.preview,
                primary = Action(R.string.save) { button ->
                    // Once is enough; the list is rebuilt afterwards.
                    button.isEnabled = false
                    save(item)
                },
                more = listOf(
                    playAction(item),
                    exportAction(item),
                    Action(R.string.transcript_discard) {
                        confirm(R.string.transcript_discard_confirm, R.string.transcript_discard) {
                            delete(item)
                        }
                    },
                ),
            )
        }
    }

    private fun showSaved(items: List<TranscriptStore.Record>) {
        savedList.removeAllViews()
        savedEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) {
            val deleteAction = Action(R.string.delete) {
                confirm(R.string.transcript_delete_confirm, R.string.delete) { delete(item) }
            }
            when (item) {
                is TranscriptStore.Journal -> addRow(
                    list = savedList,
                    record = item,
                    title = runTitle(item),
                    subtitle = item.preview,
                    primary = playAction(item),
                    more = listOf(exportAction(item), deleteAction),
                )
                is TranscriptStore.TextFile -> addRow(
                    list = savedList,
                    record = item,
                    title = dateTime.format(Date(item.startedAt)),
                    subtitle = Formatter.formatShortFileSize(this, item.size),
                    primary = Action(R.string.transcript_share) { TranscriptExporter.shareFile(this, item.file) },
                    more = listOf(deleteAction),
                )
            }
        }
    }

    /** "Start date and time · duration" of a kept run. */
    private fun runTitle(item: TranscriptStore.Journal): String {
        val duration = DateUtils.formatElapsedTime((item.endedAt - item.startedAt) / 1000)
        return "${dateTime.format(Date(item.startedAt))} · $duration"
    }

    private fun exportAction(item: TranscriptStore.Journal) = Action(R.string.transcript_export) { export(item) }

    private fun playAction(item: TranscriptStore.Journal) = Action(R.string.player_play) { play(item) }

    /**
     * Add a row for [record] to [list]: tap its text to read it; on the
     * right the [primary] action and a menu with the [more] ones.
     */
    private fun addRow(
        list: LinearLayout,
        record: TranscriptStore.Record,
        title: String,
        subtitle: String,
        primary: Action,
        more: List<Action>,
    ) {
        val row = layoutInflater.inflate(R.layout.item_transcript, list, false)
        row.findViewById<TextView>(R.id.transcriptTitle).text = title
        row.findViewById<TextView>(R.id.transcriptSubtitle).apply {
            text = subtitle
            visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        }
        row.findViewById<View>(R.id.transcriptText).setOnClickListener { showText(title, record) }
        row.findViewById<MaterialButton>(R.id.transcriptPrimaryBtn).apply {
            setText(primary.label)
            setOnClickListener { primary.perform(it) }
        }
        row.findViewById<View>(R.id.transcriptMoreBtn).apply {
            visibility = if (more.isEmpty()) View.GONE else View.VISIBLE
            setOnClickListener { anchor ->
                val popup = PopupMenu(this@TranscriptsActivity, anchor)
                more.forEachIndexed { index, action -> popup.menu.add(Menu.NONE, index, index, action.label) }
                popup.setOnMenuItemClickListener { menuItem ->
                    more[menuItem.itemId].perform(anchor)
                    true
                }
                popup.show()
            }
        }
        list.addView(row)
    }

    private fun save(item: TranscriptStore.Journal) {
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

    /** Offer the formats to export [item] as. */
    private fun export(item: TranscriptStore.Journal) {
        lifecycleScope.launch {
            val entries = readEntries(item) ?: return@launch
            exportDialog.show(entries)
        }
    }

    /**
     * Play [item] as subtitles over other apps: its translation or its
     * original, asking which when it has both.
     */
    private fun play(item: TranscriptStore.Journal) {
        lifecycleScope.launch {
            val entries = readEntries(item) ?: return@launch
            val streams = listOf(TranscriptRecorder.Stream.TRANSLATION, TranscriptRecorder.Stream.ORIGINAL)
                .filter { stream -> entries.any { it is TranscriptRecorder.Line && it.stream == stream } }
            when (streams.size) {
                // A kept run always has captions.
                0 -> {}
                1 -> playStream(item, entries, streams[0])
                else -> MaterialAlertDialogBuilder(this@TranscriptsActivity)
                    .setTitle(R.string.player_choose_stream)
                    .setItems(
                        arrayOf(
                            getString(R.string.transcript_label_translation),
                            getString(R.string.transcript_label_original),
                        )
                    ) { _, which -> playStream(item, entries, streams[which]) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun playStream(
        item: TranscriptStore.Journal,
        entries: List<TranscriptRecorder.Entry>,
        stream: TranscriptRecorder.Stream,
    ) {
        // Recorded captions are plain text; the player renders HTML.
        val cues = TranscriptCues.build(entries, stream).map { it.copy(text = TextUtils.htmlEncode(it.text)) }
        SubtitlePlayerService.play(this, runTitle(item), cues)
    }

    /** The entries of [item], or null (with a toast) if it can't be read any more. */
    private suspend fun readEntries(item: TranscriptStore.Journal): List<TranscriptRecorder.Entry>? =
        try {
            TranscriptStore.readEntries(this, item)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            showError(R.string.transcript_open_failed, e)
            // It may have been replaced by a newer run meanwhile.
            reload()
            null
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
