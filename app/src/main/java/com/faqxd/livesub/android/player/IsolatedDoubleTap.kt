package com.faqxd.livesub.android.player

/**
 * Tells deliberate double taps from bursts of touches, by the times touches
 * went down: a double tap is two touches at most [windowMs] apart with no
 * other touch within [windowMs] before the first or after the second.
 * Typing on a keyboard or flicking through a list goes down in runs of
 * more than two, so it doesn't count.
 *
 * [onDown] says whether a touch completes such a pair so far; the caller
 * confirms it once [windowMs] pass without another touch, and drops it if
 * one comes. Not thread-safe.
 */
class IsolatedDoubleTap(private val windowMs: Long) {

    private var last = NONE
    private var beforeLast = NONE

    /** A touch went down at [time] (ms): whether it completes a pair, to confirm after a quiet [windowMs]. */
    fun onDown(time: Long): Boolean {
        val pair = time - last <= windowMs && last - beforeLast > windowMs
        beforeLast = last
        last = time
        return pair
    }

    private companion object {
        /** No touch yet: far enough back to be outside any window, near enough not to overflow. */
        const val NONE = Long.MIN_VALUE / 4
    }
}
