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
 *  - Is always-on-top, frameless, semi-transparent, with a drop shadow.
 *  - Can be dragged by pressing anywhere on the card body (header,
 *    divider area, output/input caption text) — mirroring the Windows
 *    `eventFilter` installed on `card + output_edit + input_edit`.
 *  - Can be resized from **any** of the four corner handles — mirroring
 *    `hud_window.py:_TransparentSizeGrip` (4 QSizeGrips, one per corner).
 *  - Shows: status dot + text, target-language badge, close button,
 *    translated output (primary, scrollable, **bold**), original input
 *    (secondary, scrollable, italic), control bar (Toggle / Clear / Settings).
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
    private lateinit var overlayRoot: View
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
    private lateinit var resizeTL: ImageView
    private lateinit var resizeTR: ImageView
    private lateinit var resizeBL: ImageView
    private lateinit var resizeBR: ImageView

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density: Float = context.resources.displayMetrics.density

    /** Minimum overlay dimensions in pixels. */
    private val minWidthPx: Int = (280 * density).toInt()
    private val minHeightPx: Int = (200 * density).toInt()
    private val maxWidthPx: Int = (720 * density).toInt()
    private val maxHeightPx: Int = (640 * density).toInt()

    private var layoutParams: WindowManager.LayoutParams = buildLayoutParams()
    private var attached = false
    private var initialized = false

    val isAttached: Boolean get() = attached

    /** Inflate the layout (does not attach to WindowManager yet). */
    fun init() {
        if (initialized) return
        rootView = View.inflate(context, R.layout.overlay_caption, null)
        overlayRoot = rootView.findViewById(R.id.overlayRoot)
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
        resizeTL = rootView.findViewById(R.id.overlayResizeTL)
        resizeTR = rootView.findViewById(R.id.overlayResizeTR)
        resizeBL = rootView.findViewById(R.id.overlayResizeBL)
        resizeBR = rootView.findViewById(R.id.overlayResizeBR)

        toggleBtn.setOnClickListener { callbacks.onToggleClicked() }
        clearBtn.setOnClickListener { callbacks.onClearClicked() }
        settingsBtn.setOnClickListener { callbacks.onSettingsClicked() }
        closeBtn.setOnClickListener { callbacks.onCloseClicked() }

        installDragHandler()
        installResizeHandlers()
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
        // Output font: bold (mirrors `hud_window.py:apply_style` which sets
        // `out_f.setBold(True)`). setTypeface preserves the bold style when
        // only the size changes via setTextSize.
        outputView.setTypeface(outputView.typeface, android.graphics.Typeface.BOLD)
        outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSize.toFloat())
        // Input font (60% of output, italic)
        inputView.setTypeface(inputView.typeface, android.graphics.Typeface.ITALIC)
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, (settings.fontSize * 0.6f))
        // Language badge — mode-dependent. In LIVE mode it's the
        // unidirectional arrow `→ <target>`; in BILI modes it's the pair
        // symbol `中 ↔ EN` / `中 ↔ JP` so the user can tell at a glance
        // which preset is active.
        langBadge.text = when (settings.modeEnum) {
            AppSettings.Mode.LIVE -> "→ ${Languages.nameFor(settings.targetLanguage)}"
            AppSettings.Mode.BILI_ZH_EN -> "中 ↔ EN"
            AppSettings.Mode.BILI_ZH_JP -> "中 ↔ JP"
        }
        // Original visibility
        val showOrig = settings.showOriginal
        divider.visibility = if (showOrig) View.VISIBLE else View.GONE
        inputScroll.visibility = if (showOrig) View.VISIBLE else View.GONE
        // Card opacity: alpha is applied to the whole root view. Keep it
        // high enough that the drop shadow stays visible.
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
     * Drag the overlay by pressing anywhere on the card body. Child buttons
     * (Toggle / Clear / Settings / Close) and the four corner resize handles
     * consume their own touches first, so this listener only fires when the
     * user presses empty card area — same behavior as `hud_window.py`'s
     * eventFilter on `card + output_edit + input_edit`.
     */
    private fun installDragHandler() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val listener = View.OnTouchListener { _, event ->
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
        // Install on the whole card body + the header. ScrollView children
        // (outputScroll / inputScroll) keep their own touch handling so the
        // user can still scroll through caption history — same as the
        // Windows version where QPlainTextEdit handled its own scroll while
        // the eventFilter only kicked in on MouseButtonPress.
        overlayRoot.setOnTouchListener(listener)
        header.setOnTouchListener(listener)
    }

    /**
     * Wire up the four corner resize handles. Each handle computes its own
     * edge deltas based on which corner it represents (top-left: move xy +
     * shrink wh; top-right: move y + grow w / shrink h; etc.) so the
     * opposite corner stays anchored.
     */
    private fun installResizeHandlers() {
        resizeTL.setOnTouchListener(makeResizeListener(corner = Corner.TOP_LEFT))
        resizeTR.setOnTouchListener(makeResizeListener(corner = Corner.TOP_RIGHT))
        resizeBL.setOnTouchListener(makeResizeListener(corner = Corner.BOTTOM_LEFT))
        resizeBR.setOnTouchListener(makeResizeListener(corner = Corner.BOTTOM_RIGHT))
    }

    private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private fun makeResizeListener(corner: Corner): View.OnTouchListener {
        var initX = 0; var initY = 0
        var initW = 0; var initH = 0
        var startRawX = 0f; var startRawY = 0f

        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = layoutParams.x
                    initY = layoutParams.y
                    initW = layoutParams.width
                    initH = layoutParams.height
                    startRawX = event.rawX
                    startRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    var newX = initX
                    var newY = initY
                    var newW = initW
                    var newH = initH
                    when (corner) {
                        Corner.TOP_LEFT -> {
                            // Anchor bottom-right: width/height shrink, x/y move.
                            newW = (initW - dx).coerceIn(minWidthPx, maxWidthPx)
                            newH = (initH - dy).coerceIn(minHeightPx, maxHeightPx)
                            newX = initX + (initW - newW)
                            newY = initY + (initH - newH)
                        }
                        Corner.TOP_RIGHT -> {
                            // Anchor bottom-left: width grows, height shrinks, y moves.
                            newW = (initW + dx).coerceIn(minWidthPx, maxWidthPx)
                            newH = (initH - dy).coerceIn(minHeightPx, maxHeightPx)
                            newY = initY + (initH - newH)
                        }
                        Corner.BOTTOM_LEFT -> {
                            // Anchor top-right: width shrinks, height grows, x moves.
                            newW = (initW - dx).coerceIn(minWidthPx, maxWidthPx)
                            newH = (initH + dy).coerceIn(minHeightPx, maxHeightPx)
                            newX = initX + (initW - newW)
                        }
                        Corner.BOTTOM_RIGHT -> {
                            // Anchor top-left: only width/height grow.
                            newW = (initW + dx).coerceIn(minWidthPx, maxWidthPx)
                            newH = (initH + dy).coerceIn(minHeightPx, maxHeightPx)
                        }
                    }
                    layoutParams.x = newX
                    layoutParams.y = newY
                    layoutParams.width = newW
                    layoutParams.height = newH
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
