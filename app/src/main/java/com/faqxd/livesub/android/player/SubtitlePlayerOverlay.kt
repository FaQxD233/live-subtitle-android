package com.faqxd.livesub.android.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.doOnPreDraw
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.service.OverlayWindow
import com.faqxd.livesub.android.subtitle.SubtitleCue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The subtitle player's two floating windows, apart so they never push
 * each other around:
 *  - the subtitle, styled like video captions: only the current one,
 *    centered, white with a black outline on a box whose opacity follows
 *    the background opacity setting. Its window spans the screen width
 *    (long subtitles wrap within it) and is placed by its bottom edge (a
 *    two-line subtitle grows upwards), at a height kept for portrait and
 *    landscape apart; it is hidden between subtitles ([showText]). Its row
 *    drags up and down, and a tap brings the controls back;
 *  - the controls, a card in the caption overlay's style, of a fixed
 *    width (docs/plan-player-sync-ui.md, 4.2): a status chip and more,
 *    lock, minimize, close; progress and speed, the times under it;
 *    previous / ±0.5 s / play / next; an info line; a panel for longer
 *    text ([showPanel]); behind more, following on / off, the text size
 *    and the diagnostics. Minimized, by hand or [AUTO_HIDE_MS] into
 *    playback, it folds into a small pill that a tap expands. It drags
 *    anywhere.
 * Locking lets touches pass through the subtitle to the app below (the
 * controls fold up); the pill then shows a lock, and the card's lock
 * button unlocks. Locked, a double tap anywhere plays or pauses
 * ([onOutsideTouch]).
 *
 * The chip says whether and how the subtitles follow the video app
 * ([showFollow]); the info line under the buttons shows a brief note
 * ([showShift], [showResumed]), else what following needs, else the
 * offset from the video with a reset ([showOffset]). The diagnostics
 * button turns on a readout of other apps' media sessions at the bottom
 * of the card ([showReadout]), for measuring.
 *
 * Main thread only.
 */
class SubtitlePlayerOverlay(
    private val context: Context,
    private var settings: AppSettings,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onPlayPauseClicked()
        fun onPreviousClicked()
        fun onNextClicked()
        /** Move the playback position by [deltaMs] (back delays the subtitles). */
        fun onShiftClicked(deltaMs: Long)
        /** The user dragged the progress bar to [positionMs]. */
        fun onSeek(positionMs: Long)
        /** Step to the next playback speed. */
        fun onSpeedClicked()
        /** Back to normal speed. */
        fun onSpeedReset()
        fun onFontSizeClicked(deltaSp: Int)
        /** The controls were expanded: they need progress updates now. */
        fun onControlsExpanded()
        /** Fired after the subtitle was locked (touches pass through) or unlocked. */
        fun onLockChanged(locked: Boolean)
        /**
         * Locked, a double tap went down in the app below [sinceMs] ago (it
         * is confirmed only once no third touch followed): play or pause as
         * of then, as the video app did.
         */
        fun onDoubleTap(sinceMs: Long)
        fun onCloseClicked()
        /** Turn following the video app on or off. */
        fun onFollowClicked()
        /** The status chip was tapped: do what following needs now. */
        fun onChipClicked()
        /** Bring the offset from the followed video back to 0. */
        fun onResetOffsetClicked()
        /** Turn the media sessions readout on or off. */
        fun onSessionsClicked()
        /** The readout was tapped: it offers the notification access setting while that is missing. */
        fun onReadoutClicked()
        /** The readout was long-pressed: copy its whole record. */
        fun onReadoutLongPressed()
    }

    /** How following goes, by the chip's dot: following a video, waiting, or by hand. */
    enum class Tone(val colorRes: Int) {
        FOLLOWING(R.color.status_connected),
        WAITING(R.color.status_connecting),
        BY_HAND(R.color.status_idle),
    }

    private val density = context.resources.displayMetrics.density
    private val sideMarginPx = (SIDE_MARGIN_DP * density).toInt()

    // ---- The subtitle window ----
    private val textRoot: View = View.inflate(context, R.layout.overlay_subtitle_text, null)
    private val textWindow = OverlayWindow(context, textRoot, textLayoutParams())
    private val box: View = textRoot.findViewById(R.id.subtitleBox)
    private val outline: TextView = textRoot.findViewById(R.id.subtitleOutline)
    private val text: TextView = textRoot.findViewById(R.id.subtitleText)
    // The orientation the subtitle's height was taken for (each has its own).
    private var textLandscape = isLandscape()

    // ---- The control window ----
    private val controlsRoot: View = View.inflate(context, R.layout.overlay_subtitle_controls, null)
    private val controlsWindow = OverlayWindow(context, controlsRoot, controlsLayoutParams())
    private val pill: View = controlsRoot.findViewById(R.id.playerPill)
    private val pillFollow: View = controlsRoot.findViewById(R.id.playerPillFollow)
    private val pillState: ImageView = controlsRoot.findViewById(R.id.playerPillState)
    private val pillLock: View = controlsRoot.findViewById(R.id.playerPillLock)
    private val card: View = controlsRoot.findViewById(R.id.playerCard)
    private val chip: TextView = controlsRoot.findViewById(R.id.playerChip)
    private val seekBar: SeekBar = controlsRoot.findViewById(R.id.playerSeek)
    private val elapsedLabel: TextView = controlsRoot.findViewById(R.id.playerElapsed)
    private val durationLabel: TextView = controlsRoot.findViewById(R.id.playerDuration)
    private val speedBtn: Button = controlsRoot.findViewById(R.id.playerSpeedBtn)
    private val playBtn: ImageButton = controlsRoot.findViewById(R.id.playerPlayBtn)
    private val lockBtn: ImageButton = controlsRoot.findViewById(R.id.playerLockBtn)
    private val infoLabel: TextView = controlsRoot.findViewById(R.id.playerInfo)
    private val resetBtn: View = controlsRoot.findViewById(R.id.playerResetBtn)
    private val panel: View = controlsRoot.findViewById(R.id.playerPanel)
    private val panelText: TextView = controlsRoot.findViewById(R.id.playerPanelText)
    private val panelPrimary: Button = controlsRoot.findViewById(R.id.playerPanelPrimary)
    private val panelSecondary: Button = controlsRoot.findViewById(R.id.playerPanelSecondary)
    private val moreRow: View = controlsRoot.findViewById(R.id.playerMoreRow)
    private val followBtn: Button = controlsRoot.findViewById(R.id.playerFollowBtn)
    private val readout: TextView = controlsRoot.findViewById(R.id.playerReadout)

    private var expanded = true
    private var playing = false
    // The progress bar is being dragged: don't move it from the clock meanwhile.
    private var seeking = false
    // The active cues as HTML; empty between subtitles.
    private var cuesHtml = ""
    // What the subtitle window shows: the cues, the placeholder or nothing.
    private var shownKey = ""
    // The subtitle window is hidden to grow until [reveal] shows it again.
    private var revealPending = false
    private val reveal = Runnable {
        revealPending = false
        if (shownKey.isNotEmpty()) textRoot.visibility = View.VISIBLE
    }
    // The info line shows the first there is of: a brief note, what following
    // needs, the offset (or by hand, how to align).
    private var note: CharSequence? = null
    private var followDetail: CharSequence? = null
    private var offsetText: CharSequence = ""
    // The offset shown, null while not following; nothing shown yet at first.
    private var shownOffset: Long? = Long.MIN_VALUE
    private val hideNote = Runnable {
        note = null
        refreshInfo()
    }
    private val autoMinimize = Runnable { setExpanded(false) }

    // Locked, double taps anywhere play or pause (see onOutsideTouch).
    private val doubleTapWindowMs =
        (ViewConfiguration.getTapTimeout() + ViewConfiguration.getDoubleTapTimeout()).toLong()
    private val doubleTap = IsolatedDoubleTap(doubleTapWindowMs)
    // When the second touch of the double tap waiting to be confirmed went down (uptime).
    private var doubleTapDown = 0L
    private val confirmDoubleTap = Runnable {
        callbacks.onDoubleTap(SystemClock.uptimeMillis() - doubleTapDown)
    }

    /** True while touches pass through the subtitle to the app below. */
    val isLocked: Boolean
        get() = textWindow.isClickThrough

    /** Whether the expanded controls show, which need progress updates. */
    val showsControls: Boolean
        get() = expanded

    init {
        // The subtitle's row, the whole window, drags up and down; a tap
        // brings the controls back. Its empty parts would take touches anyway.
        textRoot.setOnTouchListener(
            textWindow.dragListener(clickOnTap = true, verticalOnly = true) {
                AppSettings.saveWindowGeometry(
                    context, subtitleWindowKey(textLandscape), textWindow.params.x, textWindow.params.y,
                )
            }
        )
        textRoot.setOnClickListener { setExpanded(true) }

        // The control window drags by the pill and the card's empty parts.
        val saveControls = {
            AppSettings.saveWindowGeometry(
                context, AppSettings.WINDOW_PLAYER_CONTROLS, controlsWindow.params.x, controlsWindow.params.y,
            )
        }
        pill.setOnTouchListener(controlsWindow.dragListener(clickOnTap = true, onDragEnd = saveControls))
        pill.setOnClickListener { setExpanded(true) }
        card.setOnTouchListener(controlsWindow.dragListener(onDragEnd = saveControls))
        // Touches going down in the app below arrive here as ACTION_OUTSIDE.
        controlsRoot.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch(event.eventTime)
            false
        }

        control(R.id.playerPrevBtn) { callbacks.onPreviousClicked() }
        control(R.id.playerNextBtn) { callbacks.onNextClicked() }
        control(R.id.playerBackBtn) { callbacks.onShiftClicked(-SHIFT_MS) }
        control(R.id.playerForwardBtn) { callbacks.onShiftClicked(SHIFT_MS) }
        control(R.id.playerSmallerBtn) { callbacks.onFontSizeClicked(-FONT_STEP_SP) }
        control(R.id.playerLargerBtn) { callbacks.onFontSizeClicked(FONT_STEP_SP) }
        control(R.id.playerPlayBtn) { callbacks.onPlayPauseClicked() }
        control(R.id.playerSpeedBtn) { callbacks.onSpeedClicked() }
        speedBtn.setOnLongClickListener {
            callbacks.onSpeedReset()
            touched()
            true
        }
        control(R.id.playerLockBtn) { setLocked(!isLocked) }
        control(R.id.playerMinimizeBtn) { setExpanded(false) }
        control(R.id.playerCloseBtn) { callbacks.onCloseClicked() }
        control(R.id.playerFollowBtn) { callbacks.onFollowClicked() }
        control(R.id.playerChip) { callbacks.onChipClicked() }
        control(R.id.playerMoreBtn) { showMore(moreRow.visibility != View.VISIBLE) }
        control(R.id.playerResetBtn) { callbacks.onResetOffsetClicked() }
        control(R.id.playerSessionsBtn) { callbacks.onSessionsClicked() }
        control(R.id.playerReadout) { callbacks.onReadoutClicked() }
        readout.setOnLongClickListener {
            callbacks.onReadoutLongPressed()
            touched()
            true
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) callbacks.onSeek(progress * SEEK_STEP_MS)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
                touched()
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                touched()
            }
        })
        // The outline layer strokes the glyphs; the fill layer on top has the same layout.
        outline.paint.style = Paint.Style.STROKE
        outline.paint.strokeJoin = Paint.Join.ROUND
        setSpeed(1.0)
        setCardWidth()
        applyStyle()
        refreshText()
    }

    /** Show both windows. Throws if the system refuses one (no overlay permission). */
    fun attach() {
        try {
            textWindow.attach()
            controlsWindow.attach()
        } catch (e: Exception) {
            detach()
            throw e
        }
    }

    fun detach() {
        controlsRoot.removeCallbacks(autoMinimize)
        controlsRoot.removeCallbacks(confirmDoubleTap)
        infoLabel.removeCallbacks(hideNote)
        textRoot.removeCallbacks(reveal)
        textWindow.detach()
        controlsWindow.detach()
    }

    /**
     * The screen size changed: keep both windows on screen (the subtitle
     * rewraps by itself, the card narrows on a narrow screen), and after a
     * rotation put the subtitle at the height kept for the new
     * orientation, since a portrait video usually sits high on the screen
     * and a landscape one fills it.
     */
    fun onScreenChanged() {
        val landscape = isLandscape()
        if (landscape != textLandscape) {
            textLandscape = landscape
            textWindow.params.y = subtitleBottom(landscape)
        }
        setCardWidth()
        textWindow.onScreenChanged()
        controlsWindow.onScreenChanged()
    }

    /** Apply a new settings snapshot (text size, background opacity, saved heights). */
    fun updateSettings(s: AppSettings) {
        settings = s
        applyStyle()
    }

    /** A newly loaded subtitle opens like the player does: unlocked, with the controls out. */
    fun showNewSubtitles() {
        setLocked(false)
        setExpanded(true)
    }

    /** Show the cues active now as one subtitle (their text is HTML); nothing when there are none. */
    fun showCues(cues: List<SubtitleCue>) {
        cuesHtml = cues.take(MAX_SHOWN_CUES).joinToString("<br>") { it.text.replace("\n", "<br>") }
        refreshText()
    }

    fun setProgress(positionMs: Long, durationMs: Long, playing: Boolean) {
        elapsedLabel.text = clockText(positionMs)
        durationLabel.text = clockText(durationMs)
        if (!seeking) {
            seekBar.max = (durationMs / SEEK_STEP_MS).toInt().coerceAtLeast(1)
            seekBar.progress = (positionMs / SEEK_STEP_MS).toInt()
        }
        setPlaying(playing)
    }

    /** Name the playback speed on its button, e.g. "1.25×". */
    fun setSpeed(rate: Double) {
        val label = String.format(Locale.US, "%.2f", rate).trimEnd('0').trimEnd('.') + "×"
        speedBtn.text = label
        speedBtn.contentDescription = context.getString(R.string.player_speed, label)
    }

    /** Briefly name a shift of the subtitles by [deltaMs] of playback position. */
    fun showShift(deltaMs: Long) {
        // Moving the position back makes the subtitles come later.
        showNote(
            context.getString(
                if (deltaMs < 0) R.string.player_shifted_later else R.string.player_shifted_earlier,
                secondsText(deltaMs),
            ),
            SHIFT_NOTE_MS,
        )
    }

    /**
     * Show on the info line how far the subtitles run ahead of the
     * followed video ([offsetMs], + earlier), with reset beside it unless
     * 0; null while they follow none, which shows how to align by hand.
     */
    fun showOffset(offsetMs: Long?) {
        if (offsetMs == shownOffset) return
        shownOffset = offsetMs
        offsetText = when {
            offsetMs == null -> context.getString(R.string.player_detail_off)
            offsetMs == 0L -> context.getString(R.string.player_offset_none)
            offsetMs > 0 -> context.getString(R.string.player_offset_early, secondsText(offsetMs))
            else -> context.getString(R.string.player_offset_late, secondsText(offsetMs))
        }
        resetBtn.visibility = if (offsetMs != null && offsetMs != 0L) View.VISIBLE else View.GONE
        refreshInfo()
    }

    /**
     * Show [text] in the panel under the info line, [primary] on its main
     * button and [secondary], if given, beside it; either button hides the
     * panel, then runs its action. The card comes out for it and doesn't
     * fold up by itself meanwhile.
     */
    fun showPanel(
        text: CharSequence,
        primary: CharSequence,
        onPrimary: () -> Unit = {},
        secondary: CharSequence? = null,
        onSecondary: () -> Unit = {},
    ) {
        panelText.text = text
        panelPrimary.text = primary
        panelPrimary.setOnClickListener {
            hidePanel()
            onPrimary()
        }
        panelSecondary.visibility = if (secondary != null) View.VISIBLE else View.GONE
        panelSecondary.text = secondary
        panelSecondary.setOnClickListener {
            hidePanel()
            onSecondary()
        }
        panel.visibility = View.VISIBLE
        refit()
        setExpanded(true)
    }

    /** Show the row behind more (following on / off, text size, diagnostics), or with false hide it. */
    fun showMore(show: Boolean = true) {
        moreRow.visibility = if (show) View.VISIBLE else View.GONE
        refit()
        touched()
    }

    /** Briefly say that playback continues at [positionMs], where it stopped last time. */
    fun showResumed(positionMs: Long) =
        showNote(context.getString(R.string.player_resumed, clockText(positionMs)), RESUMED_NOTE_MS)

    /** Briefly say that the offset from the video is the one these subtitles had last time. */
    fun showOffsetKept() = showNote(context.getString(R.string.player_offset_kept), RESUMED_NOTE_MS)

    /**
     * Show [text] as the media sessions readout at the bottom of the card,
     * or with null hide it. While it shows, the card doesn't fold up by
     * itself.
     */
    fun showReadout(text: CharSequence?) {
        val wasShown = readout.visibility == View.VISIBLE
        if (text != null) readout.text = text
        readout.visibility = if (text != null) View.VISIBLE else View.GONE
        // The card grows and shrinks with it.
        if (text != null || wasShown) refit()
        if ((text != null) != wasShown) touched()
    }

    /** Fold the controls into the pill, e.g. out of the way of a setting the user is sent to. */
    fun foldControls() = setExpanded(false)

    /**
     * Show whether following the video app is on ([on]) and how it goes:
     * [chipText] and [tone] on the chip, and on the info line [detail],
     * what following needs, if anything. While the subtitles follow a
     * video ([followsVideo]), the pill has a dot, the speed is the
     * video's, so its button is off, and play / pause works only where the
     * video app takes it ([canPlayPause]).
     */
    fun showFollow(
        on: Boolean,
        chipText: CharSequence,
        tone: Tone,
        detail: CharSequence?,
        followsVideo: Boolean,
        canPlayPause: Boolean,
    ) {
        followBtn.setBackgroundResource(if (on) R.drawable.overlay_btn_primary else R.drawable.overlay_btn_secondary)
        followBtn.contentDescription = context.getString(if (on) R.string.player_follow_stop else R.string.player_follow_start)
        chip.text = chipText
        chip.compoundDrawableTintList = ColorStateList.valueOf(ContextCompat.getColor(context, tone.colorRes))
        pillFollow.visibility = if (followsVideo) View.VISIBLE else View.GONE
        followDetail = detail
        refreshInfo()
        setUsable(speedBtn, !followsVideo)
        setUsable(playBtn, !followsVideo || canPlayPause)
    }

    /**
     * Lock: touches pass through the subtitle to the app below (the window
     * is dimmed slightly, an Android 12+ requirement, see
     * [OverlayWindow.setClickThrough]) and the controls fold up. Unlock: the
     * subtitle can be dragged and tapped again.
     */
    fun setLocked(locked: Boolean) {
        if (!textWindow.setClickThrough(locked)) return
        lockBtn.setImageResource(if (locked) R.drawable.ic_overlay_unlock else R.drawable.ic_overlay_lock)
        lockBtn.contentDescription = context.getString(if (locked) R.string.player_unlock else R.string.player_lock)
        pillLock.visibility = if (locked) View.VISIBLE else View.GONE
        if (locked) setExpanded(false) else controlsRoot.removeCallbacks(confirmDoubleTap)
        refreshText()
        callbacks.onLockChanged(locked)
    }

    /**
     * Expand the controls into the card, or fold them into the pill. While
     * playing, the card folds up by itself [AUTO_HIDE_MS] after it was last
     * touched.
     */
    private fun setExpanded(expand: Boolean) {
        if (expand != expanded) {
            expanded = expand
            card.visibility = if (expand) View.VISIBLE else View.GONE
            pill.visibility = if (expand) View.GONE else View.VISIBLE
            // The card is bigger than the pill it replaces.
            refit()
            refreshText()
            if (expand) callbacks.onControlsExpanded()
        }
        touched()
    }

    private fun setPlaying(playing: Boolean) {
        if (playing == this.playing) return
        this.playing = playing
        val icon = if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play
        playBtn.setImageResource(icon)
        playBtn.contentDescription = context.getString(if (playing) R.string.pause else R.string.player_play)
        pillState.setImageResource(icon)
        touched()
    }

    /**
     * A touch went down outside the controls, in the app below. Locked, a
     * double tap plays or pauses, as it does in many video apps. Android
     * tells an overlay only that a touch that goes to another app went
     * down, not where (the coordinates come zeroed) and not whether it was
     * a keyboard, so this is a double tap anywhere on the screen, timed
     * from down to down (a tap's length plus the double-tap timeout). To
     * leave typing and quick flicks out, it counts only when no other
     * touch came shortly before or after it ([IsolatedDoubleTap]): so it
     * is confirmed one window later, and reported with how long ago it
     * happened.
     */
    private fun onOutsideTouch(downTime: Long) {
        if (!isLocked) return
        // Another touch soon after a pair makes it a burst.
        controlsRoot.removeCallbacks(confirmDoubleTap)
        if (doubleTap.onDown(downTime)) {
            doubleTapDown = downTime
            controlsRoot.postDelayed(confirmDoubleTap, doubleTapWindowMs)
        }
    }

    /** The controls were used: restart the countdown to folding them up (playing, with nothing to read). */
    private fun touched() {
        controlsRoot.removeCallbacks(autoMinimize)
        if (expanded && playing && readout.visibility != View.VISIBLE && panel.visibility != View.VISIBLE) {
            controlsRoot.postDelayed(autoMinimize, AUTO_HIDE_MS)
        }
    }

    /** Let [view] be used or not, dimmed while not. */
    private fun setUsable(view: View, usable: Boolean) {
        view.isEnabled = usable
        view.alpha = if (usable) 1f else UNUSABLE_ALPHA
    }

    /** The control window changed size: keep it on screen once laid out. */
    private fun refit() {
        controlsRoot.doOnPreDraw {
            controlsWindow.keepOnScreen()
            controlsWindow.update()
        }
    }

    /** A button of the card: runs [action] and counts as using the controls. */
    private fun control(id: Int, action: () -> Unit) {
        controlsRoot.findViewById<View>(id).setOnClickListener {
            action()
            touched()
        }
    }

    /** Show [text] on the info line for [durationMs]. */
    private fun showNote(text: CharSequence, durationMs: Long) {
        note = text
        refreshInfo()
        infoLabel.removeCallbacks(hideNote)
        infoLabel.postDelayed(hideNote, durationMs)
    }

    private fun refreshInfo() {
        infoLabel.text = note ?: followDetail ?: offsetText
    }

    private fun hidePanel() {
        panel.visibility = View.GONE
        refit()
        touched()
    }

    /** The card's width: [CARD_WIDTH_DP], or less on a narrower screen. */
    private fun setCardWidth() {
        val screen = context.resources.displayMetrics.widthPixels
        val margins = 2 * (sideMarginPx + (CARD_MARGIN_DP * density).toInt())
        val cardWidth = minOf((CARD_WIDTH_DP * density).toInt(), screen - margins)
        card.layoutParams = card.layoutParams.apply { width = cardWidth }
    }

    /**
     * Show the cues, or between subtitles, while the controls are out and
     * the subtitle unlocked, a dimmed placeholder to position it by.
     */
    private fun refreshText() {
        val placeholder = cuesHtml.isEmpty() && expanded && !isLocked
        val key = if (placeholder) PLACEHOLDER_KEY else cuesHtml
        if (key == shownKey) return
        shownKey = key
        if (key.isEmpty()) {
            // Hidden between subtitles, the window neither shows nor takes touches.
            textRoot.visibility = View.INVISIBLE
            return
        }
        val content: CharSequence = if (placeholder) {
            context.getString(R.string.player_placeholder)
        } else {
            HtmlCompat.fromHtml(cuesHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
        outline.text = content
        text.text = content
        box.alpha = if (placeholder) PLACEHOLDER_ALPHA else 1f
        showText()
    }

    /**
     * Show the subtitle window after its subtitle (or text size) changed.
     * The window is placed by its bottom edge, so it moves whenever its
     * height changes, and some devices then draw the new subtitle at the
     * old place for a while: from a window shrunk to nothing between
     * subtitles, that was low and to the right, half off the screen. A
     * window that comes up is drawn before it shows, though. So the window
     * is hidden between subtitles and takes the next one's height unseen;
     * on screen, it never shrinks (a shorter subtitle sits at its bottom),
     * and to grow it is hidden and shown again once that layout went
     * through, two or three frames later.
     */
    private fun showText() {
        // On screen as of the window's last layout: a gap between two
        // subtitles shorter than a frame never hid it.
        val onScreen = textRoot.windowVisibility == View.VISIBLE && textRoot.isLaidOut
        when {
            // Hidden to grow: it comes back with whatever is current then.
            revealPending -> Unit
            !onScreen -> {
                textRoot.minimumHeight = 0
                textRoot.visibility = View.VISIBLE
            }
            growsText() -> {
                textRoot.visibility = View.INVISIBLE
                revealPending = true
                textRoot.doOnPreDraw { textRoot.post(reveal) }
            }
            else -> textRoot.visibility = View.VISIBLE
        }
    }

    /**
     * Whether the subtitle needs a taller window than the shown one. The
     * window keeps at least its shown height either way.
     */
    private fun growsText(): Boolean {
        val shownHeight = textRoot.height
        box.measure(
            View.MeasureSpec.makeMeasureSpec(
                textRoot.width - textRoot.paddingLeft - textRoot.paddingRight,
                View.MeasureSpec.AT_MOST,
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        textRoot.minimumHeight = maxOf(shownHeight, box.measuredHeight)
        return box.measuredHeight > shownHeight
    }

    private fun applyStyle() {
        val size = settings.subtitleFontSize.toFloat()
        outline.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        outline.paint.strokeWidth = outline.textSize * OUTLINE_WIDTH_RATIO
        outline.invalidate()
        box.background?.mutate()?.alpha = (settings.bgOpacity.coerceIn(0f, 1f) * 255).roundToInt()
        // A larger text size may need a taller window, as a longer subtitle does.
        if (textRoot.isShown) showText()
    }

    /** Across the screen, by its bottom edge, at the height kept for the orientation. */
    private fun textLayoutParams(): WindowManager.LayoutParams =
        OverlayWindow.layoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = subtitleBottom(isLandscape())
        }

    /** The subtitle's distance from the screen bottom in [landscape] or portrait: as saved, else low on the screen. */
    private fun subtitleBottom(landscape: Boolean): Int {
        val saved = if (landscape) settings.subtitleBottomLandscape else settings.subtitleBottomPortrait
        if (saved != Int.MIN_VALUE) return saved
        return (context.resources.displayMetrics.heightPixels * DEFAULT_BOTTOM_FRACTION).toInt()
    }

    private fun subtitleWindowKey(landscape: Boolean): String =
        if (landscape) AppSettings.WINDOW_SUBTITLE_LANDSCAPE else AppSettings.WINDOW_SUBTITLE

    private fun isLandscape(): Boolean =
        context.resources.displayMetrics.let { it.widthPixels > it.heightPixels }

    /** At the saved place, else near the left edge, a little above the middle. */
    private fun controlsLayoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        return OverlayWindow.layoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            // Learns when a touch goes down in the app below (see onOutsideTouch).
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            x = if (settings.playerControlsX != Int.MIN_VALUE) settings.playerControlsX else sideMarginPx
            y = if (settings.playerControlsY != Int.MIN_VALUE) {
                settings.playerControlsY
            } else {
                (metrics.heightPixels * DEFAULT_CONTROLS_Y_FRACTION).toInt()
            }
        }
    }

    private fun clockText(ms: Long): String = DateUtils.formatElapsedTime(ms / 1000)

    /** [ms] as seconds, without sign or trailing zeros: e.g. "0.5", "1.37", "2". */
    private fun secondsText(ms: Long): String =
        String.format(Locale.US, "%.2f", abs(ms) / 1000.0).trimEnd('0').trimEnd('.')

    private companion object {
        /** The controls' distance from the left edge until they are dragged. */
        const val SIDE_MARGIN_DP = 16
        /** The card's width, on a screen wide enough. */
        const val CARD_WIDTH_DP = 300
        /** The card's margin in its window (room for the shadow). */
        const val CARD_MARGIN_DP = 6
        /** Where the subtitle's bottom edge starts, as a fraction of the screen height from the bottom. */
        const val DEFAULT_BOTTOM_FRACTION = 0.12f
        /** Where the controls start, as a fraction of the screen height from the top. */
        const val DEFAULT_CONTROLS_Y_FRACTION = 0.3f
        /** The controls fold up this long after they were last used, while playing. */
        const val AUTO_HIDE_MS = 4_000L
        const val SHIFT_MS = 500L
        const val FONT_STEP_SP = 2
        /** Resolution of the progress bar. */
        const val SEEK_STEP_MS = 100L
        const val SHIFT_NOTE_MS = 1_500L
        const val RESUMED_NOTE_MS = 4_000L
        /** Stroke width relative to the text size; half of it shows around the glyphs. */
        const val OUTLINE_WIDTH_RATIO = 0.12f
        /** Overlapping cues (signs and dialogue) shown at once. */
        const val MAX_SHOWN_CUES = 3
        const val PLACEHOLDER_KEY = "\u0000placeholder"
        const val PLACEHOLDER_ALPHA = 0.6f
        /** Opacity of a control that can't be used now. */
        const val UNUSABLE_ALPHA = 0.4f
    }
}
