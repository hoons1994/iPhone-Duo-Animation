package com.hoons1994.iphoneduoanimation

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSmoothingTest {
    @Test
    fun equalElapsedTime_isStableAcrossRefreshRates() {
        val sixty = follow(stepSeconds = 1f / 60f, steps = 6)
        val oneTwenty = follow(stepSeconds = 1f / 120f, steps = 12)
        assertTrue(abs(sixty - oneTwenty) < 0.002f)
    }

    @Test
    fun reversal_staysBetweenCurrentAndTarget() {
        val forward = FrameSmoothing.step(0.2f, 0.9f, 1f / 120f, 0.012f, 0.00005f)
        val reversed = FrameSmoothing.step(forward, 0.1f, 1f / 120f, 0.012f, 0.00005f)
        assertTrue(forward in 0.2f..0.9f)
        assertTrue(reversed in 0.1f..forward)
    }

    @Test
    fun settledValue_landsExactlyOnTarget() {
        assertEquals(
            0.75f,
            FrameSmoothing.step(0.74999f, 0.75f, 1f / 120f, 0.012f, 0.00005f),
            0f,
        )
    }

    private fun follow(stepSeconds: Float, steps: Int): Float {
        var current = 0f
        repeat(steps) {
            current = FrameSmoothing.step(current, 1f, stepSeconds, 0.012f, 0.00005f)
        }
        return current
    }
}
