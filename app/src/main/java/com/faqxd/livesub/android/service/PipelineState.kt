package com.faqxd.livesub.android.service

/**
 * Main-thread state bridge between the foreground service and the main
 * screen: the service publishes every pipeline status / caption change,
 * and the activity renders the latest snapshot while it is started.
 *
 * Everything stays on the main thread — the service writes from its
 * coroutine scope (Dispatchers.Main), the activity reads in its listener.
 */
object PipelineState {

    data class Snapshot(
        val running: Boolean,
        val kind: CaptionOverlayView.StatusKind,
        val status: String,
        val input: String,
        val output: String,
    )

    @Volatile private var snapshot = Snapshot(
        running = false,
        kind = CaptionOverlayView.StatusKind.IDLE,
        status = "",
        input = "",
        output = "",
    )
    private var listener: ((Snapshot) -> Unit)? = null

    fun current(): Snapshot = snapshot

    /** Called by the service after any state change. */
    fun publish(value: Snapshot) {
        snapshot = value
        listener?.invoke(value)
    }

    /** Single main-screen listener; replaces any previous one. */
    fun setListener(value: ((Snapshot) -> Unit)?) {
        listener = value
    }
}
