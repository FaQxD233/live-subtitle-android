package com.faqxd.livesub.android.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.data.CaptionMode
import com.faqxd.livesub.android.transcript.latestSentences
import kotlin.math.abs

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
 *  - Shows: status dot + text, target-language badge, close button, the
 *    main caption area (primary, scrollable, **bold**: the translation,
 *    or the original in original-only mode), the original under the
 *    translation in bilingual mode (secondary, scrollable, italic), and a
 *    control bar (Toggle / Clear / Caption mode / Click-through / Export /
 *    Settings).
 *  - Can collapse into a small draggable pill ([setMinimized]); captions
 *    keep updating in the background and show up again when expanded.
 *  - Can let touches pass through to the app underneath
 *    ([setClickThrough]); a small unlock button stays tappable.
 *
 * All public methods must be called on the main thread.
 */
class CaptionOverlayView(
    private val context: Context,
    private var settings: AppSettings,
    private val callbacks: Callbacks,
) {

    /** Drives the status-dot color; the caller classifies the state. */
    enum class StatusKind { IDLE, CONNECTING, CONNECTED, ERROR }

    interface Callbacks {
        fun onToggleClicked()
        fun onClearClicked()
        fun onSettingsClicked()
        fun onCloseClicked()
        /** Fired when the user taps the Live direction-swap button. */
        fun onToggleDirectionClicked()
        /** Fired when the user taps the caption-mode button (cycles modes). */
        fun onCaptionModeClicked()
        /** Fired after click-through mode was switched on or off. */
        fun onClickThroughChanged(enabled: Boolean)
        /** Fired when the user taps the export button. */
        fun onExportClicked()
    }

    // ---- Caption text state ----
    // The Live API streams transcription as deltas that carry their own
    // leading spaces, so the history is the plain concatenation.
    private var outHistory = ""
    private var inHistory = ""
    private var statusText = ""
    private var statusKind: StatusKind = StatusKind.IDLE
    private var isRunning = false

    // ---- Minimized pill state ----
    private var isMinimized = false
    // Card size to restore when the pill is expanded again.
    private var expandedWidth = 0
    private var expandedHeight = 0

    // ---- Click-through state ----
    /** True while touches pass through the HUD to the app below. */
    var isClickThrough = false
        private set
    // Separate small window over the lock button that turns the mode off.
    private var unlockButton: View? = null

    // ---- View references ----
    private lateinit var rootView: View
    private lateinit var overlayRoot: View
    private lateinit var header: View
    private lateinit var statusDot: View
    private lateinit var statusTextView: TextView
    private lateinit var langBadge: TextView
    private lateinit var minimizeBtn: ImageButton
    private lateinit var closeBtn: ImageButton
    private lateinit var outputScroll: ScrollView
    private lateinit var outputView: TextView
    private lateinit var divider: View
    private lateinit var inputScroll: ScrollView
    private lateinit var inputView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var originalBtn: Button
    private lateinit var clickThroughBtn: ImageButton
    private lateinit var exportBtn: ImageButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var directionBtn: Button
    private lateinit var resizeTL: ImageView
    private lateinit var resizeTR: ImageView
    private lateinit var resizeBL: ImageView
    private lateinit var resizeBR: ImageView
    private lateinit var miniPill: View
    private lateinit var miniStatusDot: View
    private lateinit var miniLabel: TextView

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density: Float = context.resources.displayMetrics.density
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

    /** Minimum overlay dimensions in pixels. The width leaves enough room
     * for the control bar (298dp fixed at the previous 300dp minimum was
     * down to a 2dp margin, which large system font scales overflowed). */
    private val minWidthPx: Int = (340 * density).toInt()
    private val minHeightPx: Int = (200 * density).toInt()
    private val maxWidthPx: Int = (720 * density).toInt()
    private val maxHeightPx: Int = (640 * density).toInt()

    private var layoutParams: WindowManager.LayoutParams = buildLayoutParams()
    private var attached = false
    private var initialized = false

    val isAttached: Boolean get() = attached

    /** Latest caption texts, for the main-screen status bridge. */
    val currentOutput: String get() = if (initialized) outputView.text.toString() else ""
    val currentInput: String get() = if (initialized) inputView.text.toString() else ""

    /** Inflate the layout (does not attach to WindowManager yet). */
    fun init() {
        if (initialized) return
        rootView = View.inflate(context, R.layout.overlay_caption, null)
        overlayRoot = rootView.findViewById(R.id.overlayRoot)
        header = rootView.findViewById(R.id.overlayHeader)
        statusDot = rootView.findViewById(R.id.overlayStatusDot)
        statusTextView = rootView.findViewById(R.id.overlayStatusText)
        langBadge = rootView.findViewById(R.id.overlayLangBadge)
        minimizeBtn = rootView.findViewById(R.id.overlayMinimizeBtn)
        closeBtn = rootView.findViewById(R.id.overlayCloseBtn)
        outputScroll = rootView.findViewById(R.id.overlayOutputScroll)
        outputView = rootView.findViewById(R.id.overlayOutput)
        divider = rootView.findViewById(R.id.overlayDivider)
        inputScroll = rootView.findViewById(R.id.overlayInputScroll)
        inputView = rootView.findViewById(R.id.overlayInput)
        toggleBtn = rootView.findViewById(R.id.overlayToggleBtn)
        clearBtn = rootView.findViewById(R.id.overlayClearBtn)
        originalBtn = rootView.findViewById(R.id.overlayOriginalBtn)
        clickThroughBtn = rootView.findViewById(R.id.overlayClickThroughBtn)
        exportBtn = rootView.findViewById(R.id.overlayExportBtn)
        settingsBtn = rootView.findViewById(R.id.overlaySettingsBtn)
        directionBtn = rootView.findViewById(R.id.overlayDirectionBtn)
        resizeTL = rootView.findViewById(R.id.overlayResizeTL)
        resizeTR = rootView.findViewById(R.id.overlayResizeTR)
        resizeBL = rootView.findViewById(R.id.overlayResizeBL)
        resizeBR = rootView.findViewById(R.id.overlayResizeBR)
        miniPill = rootView.findViewById(R.id.overlayMinimized)
        miniStatusDot = rootView.findViewById(R.id.overlayMiniStatusDot)
        miniLabel = rootView.findViewById(R.id.overlayMiniLabel)

        toggleBtn.setOnClickListener { callbacks.onToggleClicked() }
        clearBtn.setOnClickListener { callbacks.onClearClicked() }
        originalBtn.setOnClickListener { callbacks.onCaptionModeClicked() }
        clickThroughBtn.setOnClickListener { setClickThrough(true) }
        exportBtn.setOnClickListener { callbacks.onExportClicked() }
        settingsBtn.setOnClickListener { callbacks.onSettingsClicked() }
        closeBtn.setOnClickListener { callbacks.onCloseClicked() }
        directionBtn.setOnClickListener {
            android.util.Log.i("CaptionOverlay", "direction swap button clicked")
            callbacks.onToggleDirectionClicked()
        }
        minimizeBtn.setOnClickListener { setMinimized(true) }
        miniPill.setOnClickListener { setMinimized(false) }

        installDragHandler()
        installResizeHandlers()
        applyStyle()
        initialized = true
    }

    /** Add the overlay to the screen. Requires SYSTEM_ALERT_WINDOW permission. */
    fun attach() {
        if (!initialized) init()
        if (attached) return
        // Clamp the (possibly restored) geometry into the current screen.
        keepOnScreen()
        try {
            windowManager.addView(rootView, layoutParams)
            attached = true
        } catch (e: Exception) {
            // Most likely: SYSTEM_ALERT_WINDOW permission not granted.
            throw RuntimeException("Failed to add overlay window: ${e.message}", e)
        }
    }

    fun detach() {
        removeUnlockButton()
        if (!attached) return
        try {
            windowManager.removeView(rootView)
        } catch (_: Exception) {}
        attached = false
    }

    // ---------- public API (mirrors HUDWindow) ----------

    fun setOutput(text: String?) {
        if (text.isNullOrEmpty()) return
        outHistory = capHistory(outHistory + text, MAX_CAPTION_CHARS)
        refreshOutput()
    }

    fun setInput(text: String?) {
        if (text.isNullOrEmpty()) return
        inHistory = capHistory(inHistory + text, MAX_CAPTION_CHARS)
        refreshInput()
        // Original-only mode shows the original in the main area.
        if (settings.captionMode == CaptionMode.ORIGINAL) refreshOutput()
    }

    /**
     * Show a status line. The caller supplies the [StatusKind] explicitly so
     * the dot color can't be fooled by the text content (e.g. a model name
     * containing "live").
     */
    fun setStatus(status: String?, kind: StatusKind = StatusKind.IDLE) {
        statusText = (status ?: "").take(MAX_STATUS_CHARS)
        statusKind = kind
        refreshStatus()
        // The caption placeholder depends on the status ("connecting…" while
        // the pipeline is spinning up), so re-evaluate it too.
        refreshOutput()
    }

    fun clear() {
        outHistory = ""
        inHistory = ""
        refreshOutput()
        refreshInput()
    }

    fun setRunningState(running: Boolean) {
        isRunning = running
        toggleBtn.text = context.getString(if (running) R.string.pause else R.string.start)
        refreshOutput()
    }

    /** Re-apply fontSize / opacity / caption mode without re-inflating. */
    fun applyStyle() {
        // Output font: bold (mirrors `hud_window.py:apply_style` which sets
        // `out_f.setBold(True)`). setTypeface preserves the bold style when
        // only the size changes via setTextSize.
        outputView.setTypeface(outputView.typeface, android.graphics.Typeface.BOLD)
        outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSize.toFloat())
        // Add subtle shadow to output text for depth (radius 4dp, offset 2dp down, 50% black)
        outputView.setShadowLayer(4f * density, 0f, 2f * density, 0x80000000.toInt())

        // Input font (60% of output, italic, slightly more muted)
        inputView.setTypeface(inputView.typeface, android.graphics.Typeface.ITALIC)
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, (settings.fontSize * 0.6f))
        inputView.setTextColor(context.getColor(R.color.hud_input_text))

        // Language badge + direction-swap button.
        updateDirectionUi(settings)
        // Caption mode: the secondary (original) area exists in bilingual
        // mode only; original-only mode moves the original to the main area.
        val mode = settings.captionMode
        val showSecondary = mode == CaptionMode.BOTH
        divider.visibility = if (showSecondary) View.VISIBLE else View.GONE
        inputScroll.visibility = if (showSecondary) View.VISIBLE else View.GONE
        // The control-bar button names the current mode and is highlighted
        // whenever the original is part of it.
        originalBtn.text = context.getString(
            when (mode) {
                CaptionMode.TRANSLATION -> R.string.overlay_mode_translation
                CaptionMode.BOTH -> R.string.overlay_mode_both
                CaptionMode.ORIGINAL -> R.string.overlay_mode_original
            }
        )
        val highlighted = mode != CaptionMode.TRANSLATION
        originalBtn.isSelected = highlighted
        originalBtn.setBackgroundResource(
            if (highlighted) R.drawable.overlay_btn_primary else R.drawable.overlay_btn_secondary
        )
        // Card opacity: alpha is applied to the whole root view. Keep it
        // high enough that the drop shadow stays visible.
        val alpha = (0.4f + 0.6f * settings.bgOpacity.coerceIn(0f, 1f))
        rootView.alpha = alpha
        refreshStatus()
        // showLatestOnly / caption mode flips must re-render the captions.
        refreshOutput()
        refreshInput()
    }

    /**
     * Update the lang badge after a direction toggle. Called by
     * [LiveTranslateService.toggleDirection] with the freshly-persisted
     * [AppSettings]. Lightweight: doesn't re-apply font sizes / opacity.
     */
    fun refreshDirection(s: AppSettings) {
        updateDirectionUi(s)
    }

    /**
     * Replace the internal settings snapshot. Call before [applyStyle] so
     * that font size / opacity / caption mode pick up the latest values
     * without re-creating the overlay.
     */
    fun updateSettings(s: AppSettings) {
        settings = s
    }

    /**
     * Collapse the HUD into the small pill, or expand it back. Captions keep
     * updating while collapsed; expanding restores the previous card size,
     * keeps the card on screen and jumps to the newest lines.
     */
    fun setMinimized(minimized: Boolean) {
        if (!initialized || minimized == isMinimized) return
        isMinimized = minimized
        if (minimized) {
            // Remember the expanded size BEFORE persisting: persistGeometry
            // reads the expanded geometry while isMinimized is already true.
            expandedWidth = layoutParams.width
            expandedHeight = layoutParams.height
            persistGeometry()
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            layoutParams.width = expandedWidth
            layoutParams.height = expandedHeight
            keepOnScreen()
            persistGeometry()
        }
        val cardVisibility = if (minimized) View.GONE else View.VISIBLE
        overlayRoot.visibility = cardVisibility
        listOf(resizeTL, resizeTR, resizeBL, resizeBR).forEach { it.visibility = cardVisibility }
        miniPill.visibility = if (minimized) View.VISIBLE else View.GONE
        updateWindowLayout()
        if (!minimized) {
            outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
            inputScroll.post { inputScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * Let touches pass through the HUD to the app underneath (or stop).
     *
     * Android 12+ blocks touches that pass through an app overlay unless the
     * window's opacity is at most [CLICK_THROUGH_WINDOW_ALPHA], so the whole
     * window is dimmed slightly while the mode is on. The HUD itself can't
     * be touched then, so a separate small unlock button is placed over the
     * lock button to switch back.
     */
    fun setClickThrough(enabled: Boolean) {
        if (!attached || enabled == isClickThrough) return
        isClickThrough = enabled
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (enabled) {
            layoutParams.flags = layoutParams.flags or notTouchable
            layoutParams.alpha = CLICK_THROUGH_WINDOW_ALPHA
            showUnlockButton()
        } else {
            layoutParams.flags = layoutParams.flags and notTouchable.inv()
            layoutParams.alpha = 1f
            removeUnlockButton()
        }
        updateWindowLayout()
        callbacks.onClickThroughChanged(enabled)
    }

    private fun updateDirectionUi(s: AppSettings) {
        langBadge.text = s.directionLabel()
        miniLabel.text = langBadge.text
        // a2b/b2a coincide when the target already is Simplified Chinese.
        directionBtn.visibility =
            if (s.canToggleDirection) View.VISIBLE else View.GONE
    }

    // ---------- internals ----------

    private fun refreshOutput() {
        val history = if (settings.captionMode == CaptionMode.ORIGINAL) inHistory else outHistory
        val text = captionText(history, MAX_CAPTION_CHARS)
        val display = text.ifEmpty {
            // No caption yet: show a connecting placeholder while the pipeline
            // is spinning up, otherwise the idle em-dash.
            if (isRunning && statusKind == StatusKind.CONNECTING) {
                context.getString(R.string.status_connecting)
            } else {
                "—"
            }
        }
        if (outputView.text.toString() != display) {
            outputView.text = display
            // Auto-scroll to bottom so the latest caption is always visible.
            outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun refreshInput() {
        val text = captionText(inHistory, MAX_CAPTION_CHARS)
        if (inputView.text.toString() != text) {
            inputView.text = text
            inputScroll.post { inputScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * Text shown for one caption stream. Latest-only mode shows just the
     * newest [AppSettings.latestSentenceCount] sentences (the last one is
     * being spoken right now), split by punctuation over the whole history
     * so it works no matter how the server chunks the transcription; a
     * newline separates them because languages differ on inter-sentence
     * spacing.
     */
    private fun captionText(history: String, maxChars: Int): String {
        val full = history.trim()
        if (!settings.showLatestOnly) return capHistory(full, maxChars)
        val latest = latestSentences(full, settings.latestSentenceCount).joinToString("\n")
        // A long run without punctuation must not turn into a wall of text.
        return capHistory(latest, LATEST_ONLY_CHARS_PER_SENTENCE * settings.latestSentenceCount)
    }

    /**
     * Keep only the newest [maxChars] characters of caption history, cutting
     * from the head. Segments are concatenated verbatim — no per-segment
     * trim — so spaces between words survive ("Hello" + " world" stays
     * "Hello world"). A cut that would split a surrogate pair advances past
     * the low half.
     */
    private fun capHistory(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var cut = text.length - maxChars
        if (Character.isHighSurrogate(text[cut - 1]) && Character.isLowSurrogate(text[cut])) {
            cut++
        }
        return text.substring(cut)
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
        miniStatusDot.background?.setTint(color)
    }

    /** Push [layoutParams] to the window; no-op while detached. */
    private fun updateWindowLayout() {
        if (!attached) return
        try {
            windowManager.updateViewLayout(rootView, layoutParams)
        } catch (_: Exception) {}
    }

    /** Clamp the window position and size so the whole card stays on screen
     * (also covers a restored geometry after rotation / resolution change). */
    private fun keepOnScreen() {
        val metrics = context.resources.displayMetrics
        // While minimized the params are WRAP_CONTENT (negative); clamp
        // explicit sizes only and measure the pill for the position bounds.
        if (layoutParams.width > 0) {
            layoutParams.width = layoutParams.width.coerceAtMost(metrics.widthPixels)
        }
        if (layoutParams.height > 0) {
            layoutParams.height = layoutParams.height.coerceAtMost(metrics.heightPixels)
        }
        val width = if (layoutParams.width > 0) layoutParams.width else rootView.width
        val height = if (layoutParams.height > 0) layoutParams.height else rootView.height
        val maxX = (metrics.widthPixels - width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - height).coerceAtLeast(0)
        layoutParams.x = layoutParams.x.coerceIn(0, maxX)
        layoutParams.y = layoutParams.y.coerceIn(0, maxY)
    }

    /**
     * The screen size changed (rotation, fold, resolution): pull the window
     * back on screen, otherwise a HUD placed low in portrait can end up
     * entirely off-screen in landscape, where it can't be dragged back. The
     * clamped geometry is not persisted, so rotating back restores the
     * user's own placement on the next start.
     */
    fun onScreenChanged() {
        if (!attached) return
        keepOnScreen()
        updateWindowLayout()
        if (isClickThrough) {
            // The unlock button sits over the lock button; re-place it once
            // the moved (and possibly narrowed) HUD has been laid out.
            removeUnlockButton()
            rootView.doOnPreDraw { if (isClickThrough && unlockButton == null) showUnlockButton() }
        }
    }

    /**
     * Show the unlock button exactly where the lock button is. It is its own
     * (touchable) window, positioned in the same coordinate space as the
     * HUD, so it lines up while the non-touchable HUD can't move.
     */
    private fun showUnlockButton() {
        val location = IntArray(2)
        clickThroughBtn.getLocationInWindow(location)
        val padding = (6 * density).toInt()
        val button = ImageButton(context).apply {
            setImageResource(R.drawable.ic_overlay_unlock)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.hud_text_primary))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.overlay_btn_primary)
            setPadding(padding, padding, padding, padding)
            contentDescription = context.getString(R.string.overlay_click_through_off)
            setOnClickListener { setClickThrough(false) }
        }
        val params = WindowManager.LayoutParams(
            clickThroughBtn.width,
            clickThroughBtn.height,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = layoutParams.x + location[0]
            y = layoutParams.y + location[1]
        }
        try {
            windowManager.addView(button, params)
            unlockButton = button
        } catch (e: Exception) {
            // The notification action still offers a way out.
            Log.w(TAG, "unlock button attach failed: ${e.message}")
        }
    }

    private fun removeUnlockButton() {
        unlockButton?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        unlockButton = null
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        // Restore persisted geometry when valid, else the 360x320 default
        // (large enough for the control bar and initial resizing).
        val widthPx = if (settings.overlayWidth >= minWidthPx) {
            settings.overlayWidth
        } else {
            (360 * density).toInt()
        }
        val heightPx = if (settings.overlayHeight >= minHeightPx) {
            settings.overlayHeight
        } else {
            (320 * density).toInt()
        }
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (settings.overlayX != Int.MIN_VALUE) settings.overlayX else 40
            y = if (settings.overlayY != Int.MIN_VALUE) settings.overlayY else 200
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
        val listener = makeDragListener()
        // Install on the whole card body + the header. ScrollView children
        // (outputScroll / inputScroll) keep their own touch handling so the
        // user can still scroll through caption history — same as the
        // Windows version where QPlainTextEdit handled its own scroll while
        // the eventFilter only kicked in on MouseButtonPress.
        overlayRoot.setOnTouchListener(listener)
        header.setOnTouchListener(listener)
        // The minimized pill drags the same way; a tap expands it.
        miniPill.setOnTouchListener(makeDragListener(clickOnTap = true))
    }

    /**
     * Move the window with the finger. Movement within the touch slop is
     * ignored so a tap doesn't nudge the window; with [clickOnTap] such a
     * tap is forwarded to the view's click listener instead.
     */
    private fun makeDragListener(clickOnTap: Boolean = false): View.OnTouchListener {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragging = false

        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!dragging && abs(dx) < touchSlop && abs(dy) < touchSlop) {
                        return@OnTouchListener true
                    }
                    dragging = true
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    updateWindowLayout()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (clickOnTap && !dragging) view.performClick()
                    if (dragging) persistGeometry()
                    true
                }
                else -> false
            }
        }
    }

    /** Write the current window geometry to prefs (drag/resize end). While
     * minimized the layout params are WRAP_CONTENT (-2), which would wipe
     * the saved card size — persist the expanded geometry instead. */
    private fun persistGeometry() {
        val width = if (isMinimized && expandedWidth > 0) expandedWidth else layoutParams.width
        val height = if (isMinimized && expandedHeight > 0) expandedHeight else layoutParams.height
        AppSettings.saveOverlayGeometry(
            context, layoutParams.x, layoutParams.y, width, height,
        )
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
                    updateWindowLayout()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (layoutParams.width != initW || layoutParams.height != initH ||
                        layoutParams.x != initX || layoutParams.y != initY
                    ) {
                        persistGeometry()
                    }
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val TAG = "CaptionOverlay"
        private const val MAX_STATUS_CHARS = 80
        /**
         * Newest-kept characters of each caption history. Equal for both
         * streams, since original-only mode shows the original in the
         * main area.
         */
        private const val MAX_CAPTION_CHARS = 3000
        /** Cap per shown sentence in latest-only mode (no punctuation). */
        private const val LATEST_ONLY_CHARS_PER_SENTENCE = 200
        /**
         * Highest window opacity for which Android 12+ still lets touches
         * pass through an app overlay (the platform default maximum
         * obscuring opacity for touches).
         */
        private const val CLICK_THROUGH_WINDOW_ALPHA = 0.8f
    }
}
