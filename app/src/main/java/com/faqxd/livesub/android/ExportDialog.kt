package com.faqxd.livesub.android

import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.transcript.ExportFormat
import com.faqxd.livesub.android.transcript.SRT_MIME_TYPE
import com.faqxd.livesub.android.transcript.TranscriptExporter
import com.faqxd.livesub.android.transcript.TranscriptRecorder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Asks how to export a run's captions: as translation or original
 * subtitles (SRT) or as the bilingual text transcript, shared through the
 * share sheet or saved to a document the user picks. Only formats with
 * content are offered; the last choice is preselected next time.
 *
 * Create it while [activity] is being created (it registers for the file
 * picker's result).
 */
class ExportDialog(private val activity: AppCompatActivity) {

    private class Pending(
        val entries: List<TranscriptRecorder.Entry>,
        val kind: ExportFormat,
        val onDone: () -> Unit,
    )

    // The export waiting for the file picker; lost if the activity is recreated meanwhile.
    private var pending: Pending? = null

    private val saveSrt = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument(SRT_MIME_TYPE)
    ) { uri -> onSaveTarget(uri) }

    private val saveText = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> onSaveTarget(uri) }

    /** Offer the export of [entries]; [onDone] runs once it is finished or cancelled. */
    fun show(entries: List<TranscriptRecorder.Entry>, onDone: () -> Unit = {}) {
        val kinds = ExportFormat.values().filter { it.isAvailableFor(entries) }
        if (kinds.isEmpty()) {
            Toast.makeText(activity, R.string.export_empty, Toast.LENGTH_SHORT).show()
            onDone()
            return
        }
        val last = ExportFormat.fromKey(AppSettings.prefs(activity).getString(KEY_LAST_FORMAT, null))
        var selected = kinds.indexOf(last ?: kinds[0]).coerceAtLeast(0)
        // A button took over; dismissing the dialog then doesn't end the export.
        var handled = false
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.export_chooser_title)
            .setSingleChoiceItems(kinds.map { activity.getString(it.label) }.toTypedArray(), selected) { _, which ->
                selected = which
            }
            .setPositiveButton(R.string.transcript_share) { _, _ ->
                handled = true
                val kind = remember(kinds[selected])
                activity.lifecycleScope.launch {
                    TranscriptExporter.shareExport(activity, entries, kind)
                    onDone()
                }
            }
            .setNeutralButton(R.string.export_save_as) { _, _ ->
                handled = true
                val kind = remember(kinds[selected])
                pending = Pending(entries, kind, onDone)
                val name = TranscriptExporter.exportName(entries, kind, System.currentTimeMillis())
                try {
                    (if (kind.stream == null) saveText else saveSrt).launch(name)
                } catch (e: Exception) {
                    // No document picker on this device.
                    pending = null
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.export_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                    onDone()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (!handled) onDone() }
            .show()
    }

    private fun remember(kind: ExportFormat): ExportFormat {
        AppSettings.prefs(activity).edit { putString(KEY_LAST_FORMAT, kind.key) }
        return kind
    }

    private fun onSaveTarget(uri: Uri?) {
        val request = pending
        pending = null
        if (request == null) {
            // The activity was recreated while the picker was open, so the
            // captions to write are gone: remove the empty document again.
            if (uri != null) {
                try {
                    DocumentsContract.deleteDocument(activity.contentResolver, uri)
                } catch (_: Exception) {}
                Toast.makeText(activity, R.string.export_interrupted, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (uri == null) {
            request.onDone()
            return
        }
        activity.lifecycleScope.launch {
            TranscriptExporter.saveExport(activity, request.entries, request.kind, uri)
            request.onDone()
        }
    }

    private companion object {
        const val KEY_LAST_FORMAT = "export_format"
    }
}
