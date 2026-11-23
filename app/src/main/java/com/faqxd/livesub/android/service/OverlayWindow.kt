package com.faqxd.livesub.android.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.doOnPreDraw
import com.faqxd.livesub.android.R
import kotlin.math.abs

/**
 * A floating window over other apps
 * ([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]) showing [root]:
 * adding and removing it, keeping it on screen, dragging it, and letting
 * touches pass through to the app below with a separate unlock button.
 * Shared by the caption overlay and the subtitle player, which own the
 * content and decide where the geometry is persisted. [params] is the
 * window's live layout; push changes with [update].
 *
 * Main thread only.
 */
class OverlayWindow(
    private val context: Context,
    private val root: View,
    val params: WindowManager.LayoutParams,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var isAttached = false
        private set

    /** True while touches pass through the window to the app below. */
    var isClickThrough = false
        private set

    // Separate small window over [unlockAnchor] that turns click-through off.
    private var unlockButton: View? = null
    private var unlockAnchor: View? = null
    private var onUnlock: (() -> Unit)? = null

    /** The window is placed by its bottom edge, so its y counts up from the screen bottom. */
    private val isBottomAnchored: Boolean
        get() = (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM

    /** Add the window, clamped into the current screen. Throws if the system refuses it. */
    fun attach() {
        if (isAttached) return
        keepOnScreen()
        windowManager.addView(root, params)
        isAttached = true
    }

    fun detach() {
        removeUnlockButton()
        if (!isAttached) return
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {}
        isAttached = false
    }

    /** Push [params] to the window; no-op while detached. */
    fun update() {
        if (!isAttached) return
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {}
    }

    /**
     * Clamp the window position and size so the whole window stays on
     * screen (also covers a restored geometry after rotation / resolution
     * change). A wrap-content dimension is taken from the laid-out view.
     */
    fun keepOnScreen() {
        val metrics = context.resources.displayMetrics
        if (params.width > 0) params.width = params.width.coerceAtMost(metrics.widthPixels)
        if (params.height > 0) params.height = params.height.coerceAtMost(metrics.heightPixels)
        val width = if (params.width > 0) params.width else root.width
        val height = if (params.height > 0) params.height else root.height
        val maxX = (metrics.widthPixels - width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - height).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    /**
     * The screen size changed (rotation, fold, resolution): pull the window
     * back on screen, otherwise a window placed low in portrait can end up
     * entirely off-screen in landscape, where it can't be dragged back.
     */
    fun onScreenChanged() {
        if (!isAttached) return
        keepOnScreen()
        update()
        if (isClickThrough) {
            // The unlock button sits over its anchor; re-place it once the
            // moved (and possibly resized) window has been laid out.
            removeUnlockButton()
            root.doOnPreDraw { if (isClickThrough && unlockButton == null) showUnlockButton() }
        }
    }

    /**
     * Move the window with the finger (vertically only with
     * [verticalOnly]). Movement within the touch slop is ignored so a tap
     * doesn't nudge the window; with [clickOnTap] such a tap is forwarded
     * to the view's click listener instead. [onDragEnd] runs after a drag,
     * also one the system cancels (say, the window was hidden under the
     * finger). A window anchored at the bottom ([Gravity.BOTTOM]) counts y
     * upwards.
     */
    fun dragListener(
        clickOnTap: Boolean = false,
        verticalOnly: Boolean = false,
        onDragEnd: () -> Unit,
    ): View.OnTouchListener {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragging = false

        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = if (verticalOnly) 0f else event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!dragging && abs(dx) < touchSlop && abs(dy) < touchSlop) {
                        return@OnTouchListener true
                    }
                    dragging = true
                    params.x = initialX + dx.toInt()
                    params.y = if (isBottomAnchored) initialY - dy.toInt() else initialY + dy.toInt()
                    update()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tapped = event.actionMasked == MotionEvent.ACTION_UP && !dragging
                    if (clickOnTap && tapped) view.performClick()
                    if (dragging) onDragEnd()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Let touches pass through the window to the app underneath (or stop).
     * Returns whether the mode changed.
     *
     * Android 12+ blocks touches that pass through an app overlay unless the
     * window's opacity is at most [CLICK_THROUGH_WINDOW_ALPHA], so the whole
     * window is dimmed slightly while the mode is on. The window itself
     * can't be touched then; with an [anchor], a separate small unlock
     * button is placed exactly over it, and tapping it calls [onUnlock]
     * (for a window placed by its top-left corner, as the caption overlay).
     */
    fun setClickThrough(enabled: Boolean, anchor: View? = null, onUnlock: (() -> Unit)? = null): Boolean {
        if (!isAttached || enabled == isClickThrough) return false
        isClickThrough = enabled
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (enabled) {
            unlockAnchor = anchor
            this.onUnlock = onUnlock
            params.flags = params.flags or notTouchable
            params.alpha = CLICK_THROUGH_WINDOW_ALPHA
            showUnlockButton()
        } else {
            params.flags = params.flags and notTouchable.inv()
            params.alpha = 1f
            removeUnlockButton()
        }
        update()
        return true
    }

    /**
     * Show the unlock button exactly where its anchor is. It is its own
     * (touchable) window, positioned in the same coordinate space as this
     * one, so it lines up while the non-touchable window can't move.
     */
    private fun showUnlockButton() {
        val anchor = unlockAnchor ?: return
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val padding = (6 * density).toInt()
        val button = ImageButton(context).apply {
            setImageResource(R.drawable.ic_overlay_unlock)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.hud_text_primary))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.overlay_btn_primary)
            setPadding(padding, padding, padding, padding)
            contentDescription = context.getString(R.string.overlay_click_through_off)
            setOnClickListener { onUnlock?.invoke() }
        }
        val buttonParams = WindowManager.LayoutParams(
            anchor.width,
            anchor.height,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x + location[0]
            y = params.y + location[1]
        }
        try {
            windowManager.addView(button, buttonParams)
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

    companion object {
        private const val TAG = "OverlayWindow"

        /**
         * Highest window opacity for which Android 12+ still lets touches
         * pass through an app overlay (the platform default maximum
         * obscuring opacity for touches).
         */
        const val CLICK_THROUGH_WINDOW_ALPHA = 0.8f

        fun windowType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        /** Layout of a floating, non-focusable window of [width] x [height] at the top left. */
        fun layoutParams(width: Int, height: Int): WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                width,
                height,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
    }
}
