package com.faqxd.livesub.android.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.data.Languages

/**
 * Port of `hud_window.py:HUDWindow`.
 *
 * Manages a floating overlay window added via [WindowManager] with
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]. The window:
 *  - Is always-on-top, frameless, semi-transparent.
 *  - Can be dragged by touching the header row.
 *  - Can be resized by dragging the bottom-right corner handle.
 *  - Shows: status dot + text, target-language badge, close button,
 *    translated output (primary, scrollable), original input (secondary,
 *    scrollable), control bar (Toggle / Clear / Settings).
 *
 * All public methods must be called on the main thread.
 */
class CaptionOverlayView(
    private val context: Context,
    private val settings: AppSettings,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onToggleClicked()
        fun onClearClicked()
        fun onSettingsClicked()
        fun onCloseClicked()
    }

    // ---- Caption text state (mirrors HUDWindow._out_committed / _out_draft) ----
    private var outCommitted = ""
    private var outDraft = ""
    private var inCommitted = ""
    private var inDraft = ""
    private var statusText = ""
    private var statusKind: StatusKind = StatusKind.IDLE
    private var isRunning = false

    private enum class StatusKind { IDLE, CONNECTING, CONNECTED, ERROR }

    // ---- View references ----
    private lateinit var rootView: View
    private lateinit var header: View
    private lateinit var statusDot: View
    private lateinit var statusTextView: TextView
    private lateinit var langBadge: TextView
    private lateinit var closeBtn: ImageButton
    private lateinit var outputScroll: ScrollView
    private lateinit var outputView: TextView
    private lateinit var divider: View
    private lateinit var inputScroll: ScrollView
    private lateinit var inputView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var settingsBtn: ImageButton
    private lateinit var resizeHandle: ImageView

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density: Float = context.resources.displayMetrics.density

    /** Minimum overlay dimensions in pixels. */
    private val minWidthPx: Int = (280 * density).toInt()
    private val minHeightPx: Int = (200 * density).toInt()

    private var layoutParams: WindowManager.LayoutParams = buildLayoutParams()
    private var attached = false
    private var initialized = false

    val isAttached: Boolean get() = attached

    /** Inflate the layout (does not attach to WindowManager yet). */
    fun init() {
        if (initialized) return
        rootView = View.inflate(context, R.layout.overlay_caption, null)
        header = rootView.findViewById(R.id.overlayHeader)
        statusDot = rootView.findViewById(R.id.overlayStatusDot)
        statusTextView = rootView.findViewById(R.id.overlayStatusText)
        langBadge = rootView.findViewById(R.id.overlayLangBadge)
        closeBtn = rootView.findViewById(R.id.overlayCloseBtn)
        outputScroll = rootView.findViewById(R.id.overlayOutputScroll)
        outputView = rootView.findViewById(R.id.overlayOutput)
        divider = rootView.findViewById(R.id.overlayDivider)
        inputScroll = rootView.findViewById(R.id.overlayInputScroll)
        inputView = rootView.findViewById(R.id.overlayInput)
        toggleBtn = rootView.findViewById(R.id.overlayToggleBtn)
        clearBtn = rootView.findViewById(R.id.overlayClearBtn)
        settingsBtn = rootView.findViewById(R.id.overlaySettingsBtn)
        resizeHandle = rootView.findViewById(R.id.overlayResizeHandle)

        toggleBtn.setOnClickListener { callbacks.onToggleClicked() }
        clearBtn.setOnClickListener { callbacks.onClearClicked() }
        settingsBtn.setOnClickListener { callbacks.onSettingsClicked() }
        closeBtn.setOnClickListener { callbacks.onCloseClicked() }

        installDragHandler()
        installResizeHandler()
        applyStyle()
        initialized = true
    }

    /** Add the overlay to the screen. Requires SYSTEM_ALERT_WINDOW permission. */
    fun attach() {
        if (!initialized) init()
        if (attached) return
        try {
            windowManager.addView(rootView, layoutParams)
            attached = true
        } catch (e: Exception) {
            // Most likely: SYSTEM_ALERT_WINDOW permission not granted.
            throw RuntimeException("Failed to add overlay window: ${e.message}", e)
        }
    }

    fun detach() {
        if (!attached) return
        try {
            windowManager.removeView(rootView)
        } catch (_: Exception) {}
        attached = false
    }

    // ---------- public API (mirrors HUDWindow) ----------

    fun setOutput(text: String?) {
        val t = text ?: ""
        if (outDraft.isNotEmpty() && t.startsWith(outDraft)) {
            outDraft = t
        } else {
            if (outDraft.isNotEmpty()) {
                outCommitted = (outCommitted + "\n" + outDraft).trimStart('\n')
                if (outCommitted.length > 1500) outCommitted = outCommitted.takeLast(1500)
            }
            outDraft = t
        }
        refreshOutput()
    }

    fun setInput(text: String?) {
        val t = text ?: ""
        if (inDraft.isNotEmpty() && t.startsWith(inDraft)) {
            inDraft = t
        } else {
            if (inDraft.isNotEmpty()) {
                inCommitted = (inCommitted + "\n" + inDraft).trimStart('\n')
                if (inCommitted.length > 800) inCommitted = inCommitted.takeLast(800)
            }
            inDraft = t
        }
        refreshInput()
    }

    fun setStatus(status: String?) {
        statusText = status ?: ""
        val s = statusText.lowercase()
        statusKind = when {
            listOf("connected", "ready", "live").any { it in s } -> StatusKind.CONNECTED
            listOf("connect", "starting", "loading", "init").any { it in s } -> StatusKind.CONNECTING
            listOf("error", "fail", "disconnected", "stop").any { it in s } -> StatusKind.ERROR
            else -> StatusKind.IDLE
        }
        refreshStatus()
    }

    fun clear() {
        outCommitted = ""
        outDraft = ""
        inCommitted = ""
        inDraft = ""
        refreshOutput()
        refreshInput()
    }

    fun setRunningState(running: Boolean) {
        isRunning = running
        toggleBtn.text = if (running) "Pause" else "Start"
    }

    /** Re-apply fontSize / opacity / showOriginal without re-inflating. */
    fun applyStyle() {
        // Output font
        outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSize.toFloat())
        // Input font (60% of output)
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, (settings.fontSize * 0.6f))
        // Language badge
        langBadge.text = "→ ${Languages.nameFor(settings.targetLanguage)}"
        // Original visibility
        val showOrig = settings.showOriginal
        divider.visibility = if (showOrig) View.VISIBLE else View.GONE
        inputScroll.visibility = if (showOrig) View.VISIBLE else View.GONE
        // Card opacity: alpha is applied to the whole root view.
        val alpha = (0.4f + 0.6f * settings.bgOpacity.coerceIn(0f, 1f))
        rootView.alpha = alpha
        refreshStatus()
    }

    // ---------- internals ----------

    private fun refreshOutput() {
        var text = outCommitted
        if (outDraft.isNotEmpty()) {
            text = (text + "\n" + outDraft).trim('\n')
        }
        if (text.isEmpty()) text = "—"
        if (outputView.text.toString() != text) {
            outputView.text = text
            // Auto-scroll to bottom so the latest caption is always visible.
            outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun refreshInput() {
        var text = inCommitted
        if (inDraft.isNotEmpty()) {
            text = (text + "\n" + inDraft).trim('\n')
        }
        if (inputView.text.toString() != text) {
            inputView.text = text
            inputScroll.post { inputScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun refreshStatus() {
        if (statusTextView.text.toString() != statusText) {
            statusTextView.text = statusText
        }
        val colorRes = when (statusKind) {
            StatusKind.IDLE -> R.color.status_idle
            StatusKind.CONNECTING -> R.color.status_connecting
            StatusKind.CONNECTED -> R.color.status_connected
            StatusKind.ERROR -> R.color.status_error
        }
        val color = context.getColor(colorRes)
        // Status dot is a View with a drawable background; tint it.
        statusDot.background?.setTint(color)
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Fixed initial dimensions (360dp x 320dp) so the overlay can be resized.
        val widthPx = (360 * density).toInt()
        val heightPx = (320 * density).toInt()
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }
    }

    /**
     * Drag the overlay by touching the header row. Returns true for
     * ACTION_DOWN so we receive subsequent MOVE events. Buttons inside the
     * header (close, settings) still work because they consume their own
     * touches before the parent's OnTouchListener is consulted.
     */
    private fun installDragHandler() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(rootView, layoutParams)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }

    /**
     * Resize the overlay by dragging the bottom-right corner handle.
     */
    private fun installResizeHandler() {
        var initialWidth = 0
        var initialHeight = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = layoutParams.width
                    initialHeight = layoutParams.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    layoutParams.width = (initialWidth + dx).coerceAtLeast(minWidthPx)
                    layoutParams.height = (initialHeight + dy).coerceAtLeast(minHeightPx)
                    try {
                        windowManager.updateViewLayout(rootView, layoutParams)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }
}
