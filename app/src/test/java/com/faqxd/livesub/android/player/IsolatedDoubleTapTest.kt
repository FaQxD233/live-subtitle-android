package com.faqxd.livesub.android.player

import org.junit.Assert.assertEquals
import org.junit.Test

class IsolatedDoubleTapTest {

    private val windowMs = 400L
    private val detector = IsolatedDoubleTap(windowMs)

    /**
     * The double taps among touches going down at [downs], as the overlay
     * confirms them: a completed pair that no touch follows within the window.
     */
    private fun doubleTaps(vararg downs: Long): List<Long> =
        downs.indices.filter { i ->
            detector.onDown(downs[i]) && (i == downs.lastIndex || downs[i + 1] - downs[i] > windowMs)
        }.map { downs[it] }

    @Test
    fun aQuickPairIsADoubleTap() {
        assertEquals(listOf(1_250L), doubleTaps(1_000, 1_250))
    }

    @Test
    fun touchesTooFarApartAreNone() {
        assertEquals(emptyList<Long>(), doubleTaps(1_000, 1_500, 2_100))
    }

    @Test
    fun typingIsNone() {
        assertEquals(emptyList<Long>(), doubleTaps(1_000, 1_200, 1_400, 1_600, 1_800, 2_000))
    }

    @Test
    fun aPairRightAfterAnotherTouchIsNone() {
        // A single tap, then two quick ones too soon after it.
        assertEquals(emptyList<Long>(), doubleTaps(1_000, 1_300, 1_500))
    }

    @Test
    fun aPairAfterAQuietSpellCountsAgain() {
        assertEquals(listOf(1_200L, 3_200L), doubleTaps(1_000, 1_200, 3_000, 3_200))
    }
}
