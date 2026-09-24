package com.hoons1994.iphoneduoanimation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionTuningTest {

    @Test
    fun focusPeak_isMaxAtHandoff_andZeroOutsideWindow() {
        val h = 0.43f
        assertEquals(1f, TransitionTuning.focusPeak(h, h), 0.0001f)
        assertEquals(0f, TransitionTuning.focusPeak(0f, h), 0.0001f)
        assertEquals(0f, TransitionTuning.focusPeak(1f, h), 0.0001f)
    }

    @Test
    fun focusPeak_isSymmetricAroundHandoff() {
        val h = 0.43f
        assertEquals(
            TransitionTuning.focusPeak(h - 0.1f, h),
            TransitionTuning.focusPeak(h + 0.1f, h),
            0.0001f,
        )
    }

    @Test
    fun surfaceFocus_coverRisesIntoHandoff_andLatchesAfterIt() {
        val h = 0.43f
        assertEquals(0f, TransitionTuning.surfaceFocus(0f, h, coverSurface = true), 0.0001f)
        assertEquals(1f, TransitionTuning.surfaceFocus(h, h, coverSurface = true), 0.0001f)
        assertEquals(1f, TransitionTuning.surfaceFocus(h + 0.08f, h, coverSurface = true), 0.0001f)
    }

    @Test
    fun surfaceFocus_innerStartsMasked_thenResolvesOpen() {
        val h = 0.43f
        assertEquals(1f, TransitionTuning.surfaceFocus(h, h, coverSurface = false), 0.0001f)
        assertEquals(1f, TransitionTuning.surfaceFocus(h - 0.08f, h, coverSurface = false), 0.0001f)
        assertEquals(0f, TransitionTuning.surfaceFocus(1f, h, coverSurface = false), 0.0001f)
    }

    @Test
    fun updateHandoff_movesTowardObservedWithoutJumping() {
        val updated = TransitionTuning.updateHandoff(0.43f, 0.50f)
        assertTrue(updated > 0.43f)
        assertTrue(updated < 0.50f)
    }

    @Test
    fun updateHandoff_clampsImplausibleObservations() {
        val low = TransitionTuning.updateHandoff(0.43f, -1f)
        val high = TransitionTuning.updateHandoff(0.43f, 2f)
        assertTrue(low >= TransitionTuning.MIN_HANDOFF_PROGRESS)
        assertTrue(high <= TransitionTuning.MAX_HANDOFF_PROGRESS)
    }

    @Test
    fun previewSurface_switchesAtHandoff() {
        assertTrue(TransitionTuning.coverForPreview(0.42f, 0.43f))
        assertFalse(TransitionTuning.coverForPreview(0.43f, 0.43f))
        assertFalse(TransitionTuning.coverForPreview(0.80f, 0.43f))
    }
}
