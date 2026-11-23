package com.faqxd.livesub.android.player

import android.content.Context
import android.graphics.Paint
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.service.OverlayWindow
import com.faqxd.livesub.android.subtitle.SubtitleCue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The subtitle player's floating window, styled like video captions: the
 * current subtitle only, centered near the bottom of the screen, white
 * with a black outline on a box whose opacity follows the background
 * opacity setting, and nothing between subtitles. While unlocked a control
 * bar shows below it; locked, touches pass through to the app below and a
 * small unlock button stays tappable ([setLocked]). It spans the screen
 * width and can be dragged up and down.
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
        /** Fired after the window was locked (click-through) or unlocked. */
        fun onLockChanged(locked: Boolean)
        fun onCloseClicked()
    }

    private val density = context.resources.displayMetrics.density
    private val sideMarginPx = (SIDE_MARGIN_DP * density).toInt()

    private val root: View = View.inflate(context, R.layout.overlay_subtitle, null)
    private val window = OverlayWindow(context, root, buildLayoutParams())
    private val box: View = root.findViewById(R.id.subtitleBox)
    private val outline: TextView = root.findViewById(R.id.subtitleOutline)
    private val text: TextView = root.findViewById(R.id.subtitleText)
    private val controls: View = root.findViewById(R.id.subtitleControls)
    private val time: TextView = root.findViewById(R.id.subtitleTime)
    private val seekBar: SeekBar = root.findViewById(R.id.subtitleSeek)
    private val shiftLabel: TextView = root.findViewById(R.id.subtitleShift)
    private val playBtn: ImageButton = root.findViewById(R.id.subtitlePlayBtn)
    private val lockBtn: ImageButton = root.findViewById(R.id.subtitleLockBtn)
    private val closeBtn: ImageButton = root.findViewById(R.id.subtitleCloseBtn)

    // The progress bar is being dragged: don't move it from the clock meanwhile.
    private var seeking = false
    private var shownHtml = ""
    private var shownPlaying: Boolean? = null
    private val hideShift = Runnable { shiftLabel.visibility = View.GONE }

    /** True while touches pass through to the app below. */
    val isLocked: Boolean
        get() = window.isClickThrough

    /** Whether the control bar shows, which needs progress updates. */
    val showsControls: Boolean
        get() = controls.visibility == View.VISIBLE

    init {
        root.findViewById<View>(R.id.subtitlePrevBtn).setOnClickListener { callbacks.onPreviousClicked() }
        root.findViewById<View>(R.id.subtitleNextBtn).setOnClickListener { callbacks.onNextClicked() }
        root.findViewById<View>(R.id.subtitleBackBtn).setOnClickListener { callbacks.onShiftClicked(-SHIFT_MS) }
        root.findViewById<View>(R.id.subtitleForwardBtn).setOnClickListener { callbacks.onShiftClicked(SHIFT_MS) }
        root.findViewById<View>(R.id.subtitleSmallerBtn).setOnClickListener {
            callbacks.onFontSizeClicked(-FONT_STEP_SP)
        }
        root.findViewById<View>(R.id.subtitleLargerBtn).setOnClickListener {
            callbacks.onFontSizeClicked(FONT_STEP_SP)
        }
        playBtn.setOnClickListener { callbacks.onPlayPauseClicked() }
        lockBtn.setOnClickListener { setLocked(true) }
        closeBtn.setOnClickListener { callbacks.onCloseClicked() }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) callbacks.onSeek(progress * SEEK_STEP_MS)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
            }
        })
        // The outline layer strokes the glyphs; the fill layer on top has the same layout.
        outline.paint.style = Paint.Style.STROKE
        outline.paint.strokeJoin = Paint.Join.ROUND
        // Drag by the subtitle or the empty parts of the bars; buttons keep their taps.
        val drag = window.dragListener(verticalOnly = true) {
            AppSettings.saveWindowGeometry(
                context, AppSettings.WINDOW_SUBTITLE, window.params.x, window.params.y,
            )
        }
        listOf(box, controls, root.findViewById<View>(R.id.subtitleTopRow)).forEach {
            it.setOnTouchListener(drag)
        }
        applyStyle()
    }

    /** Show the window. Throws if the system refuses it (no overlay permission). */
    fun attach() = window.attach()

    fun detach() {
        shiftLabel.removeCallbacks(hideShift)
        window.detach()
    }

    /** The screen size changed: span the new width and stay on screen. */
    fun onScreenChanged() {
        window.params.width = context.resources.displayMetrics.widthPixels - 2 * sideMarginPx
        window.params.x = sideMarginPx
        window.onScreenChanged()
    }

    /** Apply a new settings snapshot (text size, background opacity). */
    fun updateSettings(s: AppSettings) {
        settings = s
        applyStyle()
    }

    /** Show the cues active now as one subtitle (their text is HTML); nothing when there are none. */
    fun showCues(cues: List<SubtitleCue>) {
        val html = cues.take(MAX_SHOWN_CUES).joinToString("<br>") { it.text.replace("\n", "<br>") }
        if (html == shownHtml) return
        shownHtml = html
        if (html.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        val styled = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        outline.text = styled
        text.text = styled
        box.visibility = View.VISIBLE
    }

    fun setProgress(positionMs: Long, durationMs: Long, playing: Boolean) {
        time.text = context.getString(R.string.player_time, clockText(positionMs), clockText(durationMs))
        if (!seeking) {
            seekBar.max = (durationMs / SEEK_STEP_MS).toInt().coerceAtLeast(1)
            seekBar.progress = (positionMs / SEEK_STEP_MS).toInt()
        }
        if (shownPlaying != playing) {
            shownPlaying = playing
            playBtn.setImageResource(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            playBtn.contentDescription = context.getString(if (playing) R.string.pause else R.string.player_play)
        }
    }

    /** Briefly name a shift of the subtitles by [deltaMs] of playback position. */
    fun showShift(deltaMs: Long) {
        val seconds = String.format(Locale.US, "%.1f", abs(deltaMs) / 1000.0)
        // Moving the position back makes the subtitles come later.
        shiftLabel.text = context.getString(
            if (deltaMs < 0) R.string.player_shifted_later else R.string.player_shifted_earlier,
            seconds,
        )
        shiftLabel.visibility = View.VISIBLE
        shiftLabel.removeCallbacks(hideShift)
        shiftLabel.postDelayed(hideShift, SHIFT_LABEL_MS)
    }

    /**
     * Lock (touches pass through to the app below, controls hidden) or
     * unlock. The window is dimmed slightly while locked (an Android 12+
     * requirement, see [OverlayWindow.setClickThrough]); the unlock button
     * sits where the lock button is.
     */
    fun setLocked(locked: Boolean) {
        if (!window.setClickThrough(locked, lockBtn) { setLocked(false) }) return
        // The top row stays laid out, so the lock button keeps its place under the unlock button.
        val topVisibility = if (locked) View.INVISIBLE else View.VISIBLE
        lockBtn.visibility = topVisibility
        closeBtn.visibility = topVisibility
        controls.visibility = if (locked) View.GONE else View.VISIBLE
        callbacks.onLockChanged(locked)
    }

    private fun applyStyle() {
        val size = settings.subtitleFontSize.toFloat()
        outline.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        outline.paint.strokeWidth = outline.textSize * OUTLINE_WIDTH_RATIO
        outline.invalidate()
        box.background?.mutate()?.alpha = (settings.bgOpacity.coerceIn(0f, 1f) * 255).roundToInt()
    }

    /** Full width minus the side margins, at the saved height or low on the screen. */
    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        return OverlayWindow.layoutParams(
            metrics.widthPixels - 2 * sideMarginPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            x = sideMarginPx
            y = if (settings.subtitleY != Int.MIN_VALUE) {
                settings.subtitleY
            } else {
                (metrics.heightPixels * DEFAULT_Y_FRACTION).toInt()
            }
        }
    }

    private fun clockText(ms: Long): String = DateUtils.formatElapsedTime(ms / 1000)

    private companion object {
        const val SIDE_MARGIN_DP = 16
        /** Where the window starts until it is dragged, as a fraction of the screen height. */
        const val DEFAULT_Y_FRACTION = 0.72f
        const val SHIFT_MS = 500L
        const val FONT_STEP_SP = 2
        /** Resolution of the progress bar. */
        const val SEEK_STEP_MS = 100L
        const val SHIFT_LABEL_MS = 1_500L
        /** Stroke width relative to the text size; half of it shows around the glyphs. */
        const val OUTLINE_WIDTH_RATIO = 0.12f
        /** Overlapping cues (signs and dialogue) shown at once. */
        const val MAX_SHOWN_CUES = 3
    }
}
