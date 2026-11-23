package com.faqxd.livesub.android.player

import android.content.Context
import android.graphics.Paint
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
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
 *    the background opacity setting, nothing between subtitles. It is
 *    placed by its bottom edge (a two-line subtitle grows upwards), drags
 *    up and down, and a tap brings the controls back;
 *  - the controls, a card in the caption overlay's style (time, lock,
 *    minimize, close; progress; previous / ±0.5 s / play / next, text
 *    size). Minimized, by hand or [AUTO_HIDE_MS] into playback, it folds
 *    into a small pill that a tap expands. It drags anywhere.
 * Locking lets touches pass through the subtitle to the app below (the
 * controls fold up); the pill then shows a lock, and the card's lock
 * button unlocks.
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
        fun onFontSizeClicked(deltaSp: Int)
        /** The controls were expanded: they need progress updates now. */
        fun onControlsExpanded()
        /** Fired after the subtitle was locked (touches pass through) or unlocked. */
        fun onLockChanged(locked: Boolean)
        fun onCloseClicked()
    }

    private val density = context.resources.displayMetrics.density
    private val sideMarginPx = (SIDE_MARGIN_DP * density).toInt()

    // ---- The subtitle window ----
    private val textRoot: View = View.inflate(context, R.layout.overlay_subtitle_text, null)
    private val textWindow = OverlayWindow(context, textRoot, textLayoutParams())
    private val box: View = textRoot.findViewById(R.id.subtitleBox)
    private val outline: TextView = textRoot.findViewById(R.id.subtitleOutline)
    private val text: TextView = textRoot.findViewById(R.id.subtitleText)

    // ---- The control window ----
    private val controlsRoot: View = View.inflate(context, R.layout.overlay_subtitle_controls, null)
    private val controlsWindow = OverlayWindow(context, controlsRoot, controlsLayoutParams())
    private val pill: View = controlsRoot.findViewById(R.id.playerPill)
    private val pillState: ImageView = controlsRoot.findViewById(R.id.playerPillState)
    private val pillLock: View = controlsRoot.findViewById(R.id.playerPillLock)
    private val card: View = controlsRoot.findViewById(R.id.playerCard)
    private val time: TextView = controlsRoot.findViewById(R.id.playerTime)
    private val shiftLabel: TextView = controlsRoot.findViewById(R.id.playerShift)
    private val seekBar: SeekBar = controlsRoot.findViewById(R.id.playerSeek)
    private val playBtn: ImageButton = controlsRoot.findViewById(R.id.playerPlayBtn)
    private val lockBtn: ImageButton = controlsRoot.findViewById(R.id.playerLockBtn)

    private var expanded = true
    private var playing = false
    // The progress bar is being dragged: don't move it from the clock meanwhile.
    private var seeking = false
    // The active cues as HTML; empty between subtitles.
    private var cuesHtml = ""
    // What the subtitle window shows: the cues, the placeholder or nothing.
    private var shownKey = ""
    private val hideShift = Runnable { shiftLabel.text = "" }
    private val autoMinimize = Runnable { setExpanded(false) }

    /** True while touches pass through the subtitle to the app below. */
    val isLocked: Boolean
        get() = textWindow.isClickThrough

    /** Whether the expanded controls show, which need progress updates. */
    val showsControls: Boolean
        get() = expanded

    init {
        // The subtitle drags up and down; a tap brings the controls back.
        box.setOnTouchListener(
            textWindow.dragListener(clickOnTap = true, verticalOnly = true) {
                AppSettings.saveWindowGeometry(
                    context, AppSettings.WINDOW_SUBTITLE, textWindow.params.x, textWindow.params.y,
                )
            }
        )
        box.setOnClickListener { setExpanded(true) }

        // The control window drags by the pill and the card's empty parts.
        val saveControls = {
            AppSettings.saveWindowGeometry(
                context, AppSettings.WINDOW_PLAYER_CONTROLS, controlsWindow.params.x, controlsWindow.params.y,
            )
        }
        pill.setOnTouchListener(controlsWindow.dragListener(clickOnTap = true, onDragEnd = saveControls))
        pill.setOnClickListener { setExpanded(true) }
        card.setOnTouchListener(controlsWindow.dragListener(onDragEnd = saveControls))

        control(R.id.playerPrevBtn) { callbacks.onPreviousClicked() }
        control(R.id.playerNextBtn) { callbacks.onNextClicked() }
        control(R.id.playerBackBtn) { callbacks.onShiftClicked(-SHIFT_MS) }
        control(R.id.playerForwardBtn) { callbacks.onShiftClicked(SHIFT_MS) }
        control(R.id.playerSmallerBtn) { callbacks.onFontSizeClicked(-FONT_STEP_SP) }
        control(R.id.playerLargerBtn) { callbacks.onFontSizeClicked(FONT_STEP_SP) }
        control(R.id.playerPlayBtn) { callbacks.onPlayPauseClicked() }
        control(R.id.playerLockBtn) { setLocked(!isLocked) }
        control(R.id.playerMinimizeBtn) { setExpanded(false) }
        control(R.id.playerCloseBtn) { callbacks.onCloseClicked() }
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
        shiftLabel.removeCallbacks(hideShift)
        textWindow.detach()
        controlsWindow.detach()
    }

    /** The screen size changed: rewrap the subtitle, keep both windows on screen. */
    fun onScreenChanged() {
        applyMaxWidth()
        textWindow.onScreenChanged()
        controlsWindow.onScreenChanged()
    }

    /** Apply a new settings snapshot (text size, background opacity). */
    fun updateSettings(s: AppSettings) {
        settings = s
        applyStyle()
    }

    /** Show the cues active now as one subtitle (their text is HTML); nothing when there are none. */
    fun showCues(cues: List<SubtitleCue>) {
        cuesHtml = cues.take(MAX_SHOWN_CUES).joinToString("<br>") { it.text.replace("\n", "<br>") }
        refreshText()
    }

    fun setProgress(positionMs: Long, durationMs: Long, playing: Boolean) {
        time.text = context.getString(R.string.player_time, clockText(positionMs), clockText(durationMs))
        if (!seeking) {
            seekBar.max = (durationMs / SEEK_STEP_MS).toInt().coerceAtLeast(1)
            seekBar.progress = (positionMs / SEEK_STEP_MS).toInt()
        }
        setPlaying(playing)
    }

    /** Briefly name a shift of the subtitles by [deltaMs] of playback position. */
    fun showShift(deltaMs: Long) {
        val seconds = String.format(Locale.US, "%.1f", abs(deltaMs) / 1000.0)
        // Moving the position back makes the subtitles come later.
        shiftLabel.text = context.getString(
            if (deltaMs < 0) R.string.player_shifted_later else R.string.player_shifted_earlier,
            seconds,
        )
        shiftLabel.removeCallbacks(hideShift)
        shiftLabel.postDelayed(hideShift, SHIFT_LABEL_MS)
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
        if (locked) setExpanded(false)
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
            // The card is bigger than the pill it replaces: keep it on screen once laid out.
            controlsRoot.doOnPreDraw {
                controlsWindow.keepOnScreen()
                controlsWindow.update()
            }
            refreshText()
            if (expand) callbacks.onControlsExpanded()
        }
        touched()
    }

    private fun setPlaying(playing: Boolean) {
        if (playing == this.playing) return
        this.playing = playing
        val icon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        playBtn.setImageResource(icon)
        playBtn.contentDescription = context.getString(if (playing) R.string.pause else R.string.player_play)
        pillState.setImageResource(icon)
        touched()
    }

    /** The controls were used: restart the countdown to folding them up (playing only). */
    private fun touched() {
        controlsRoot.removeCallbacks(autoMinimize)
        if (expanded && playing) controlsRoot.postDelayed(autoMinimize, AUTO_HIDE_MS)
    }

    /** A button of the card: runs [action] and counts as using the controls. */
    private fun control(id: Int, action: () -> Unit) {
        controlsRoot.findViewById<View>(id).setOnClickListener {
            action()
            touched()
        }
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
            box.visibility = View.GONE
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
        box.visibility = View.VISIBLE
    }

    private fun applyStyle() {
        val size = settings.subtitleFontSize.toFloat()
        outline.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        outline.paint.strokeWidth = outline.textSize * OUTLINE_WIDTH_RATIO
        outline.invalidate()
        box.background?.mutate()?.alpha = (settings.bgOpacity.coerceIn(0f, 1f) * 255).roundToInt()
        applyMaxWidth()
    }

    /** Long subtitles wrap within the screen width minus the side margins. */
    private fun applyMaxWidth() {
        val width = context.resources.displayMetrics.widthPixels - 2 * sideMarginPx -
            box.paddingLeft - box.paddingRight
        outline.maxWidth = width
        text.maxWidth = width
    }

    /** Centered, by its bottom edge: at the saved height or low on the screen. */
    private fun textLayoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        return OverlayWindow.layoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = if (settings.subtitleBottom != Int.MIN_VALUE) {
                settings.subtitleBottom
            } else {
                (metrics.heightPixels * DEFAULT_BOTTOM_FRACTION).toInt()
            }
        }
    }

    /** At the saved place, else near the left edge, a little above the middle. */
    private fun controlsLayoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        return OverlayWindow.layoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            x = if (settings.playerControlsX != Int.MIN_VALUE) settings.playerControlsX else sideMarginPx
            y = if (settings.playerControlsY != Int.MIN_VALUE) {
                settings.playerControlsY
            } else {
                (metrics.heightPixels * DEFAULT_CONTROLS_Y_FRACTION).toInt()
            }
        }
    }

    private fun clockText(ms: Long): String = DateUtils.formatElapsedTime(ms / 1000)

    private companion object {
        const val SIDE_MARGIN_DP = 16
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
        const val SHIFT_LABEL_MS = 1_500L
        /** Stroke width relative to the text size; half of it shows around the glyphs. */
        const val OUTLINE_WIDTH_RATIO = 0.12f
        /** Overlapping cues (signs and dialogue) shown at once. */
        const val MAX_SHOWN_CUES = 3
        const val PLACEHOLDER_KEY = "\u0000placeholder"
        const val PLACEHOLDER_ALPHA = 0.6f
    }
}
