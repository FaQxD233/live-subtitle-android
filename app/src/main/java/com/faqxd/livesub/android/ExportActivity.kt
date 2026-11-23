package com.faqxd.livesub.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.faqxd.livesub.android.transcript.TranscriptStore

/**
 * Transparent host for the [ExportDialog] when the overlay exports the run
 * in progress, so the dialog can show over whatever app is in front. It
 * runs in its own task (see the manifest), so the main screen isn't
 * brought up behind it, and closes once the export is done.
 */
class ExportActivity : AppCompatActivity() {

    private val exportDialog = ExportDialog(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated after the process was killed: the request is gone.
        if (savedInstanceState != null) {
            finish()
            return
        }
        exportDialog.show(TranscriptStore.snapshot()) { finish() }
    }

    companion object {
        /** Export the run in progress; works from a Service. */
        fun start(context: Context) {
            context.startActivity(
                Intent(context, ExportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
