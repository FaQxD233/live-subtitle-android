package com.faqxd.livesub.android

import android.app.Application
import com.faqxd.livesub.android.transcript.TranscriptStore

/**
 * Application entry point. Holds process-wide singletons.
 *
 * Mirrors the Windows `main.py` `QApplication` setup; we keep this lightweight
 * because all real work lives in [com.faqxd.livesub.android.service.LiveTranslateService].
 */
class LiveBuddyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before any run starts, keep what an earlier process left behind
        // (an interrupted run, the old history) in the background.
        TranscriptStore.init(this)
    }
}
