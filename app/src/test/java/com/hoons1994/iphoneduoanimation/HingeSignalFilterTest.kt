package com.hoons1994.iphoneduoanimation

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HingeSignalFilterTest {

    @Test
    fun firstSample_passesThroughWithoutLag() {
        val filter = HingeSignalFilter()
        val output = filter.update(72f)

        assertEquals(72f, output.rawAngleDegrees, 0.0001f)
        assertEquals(72f, output.filteredAngleDegrees, 0.0001f)
        assertEquals(0.4f, output.filteredProgress, 0.0001f)
    }

    @Test
    fun slowJitter_isSmoothed() {
        val filter = HingeSignalFilter()
        filter.update(90f)
        val output = filter.update(91f)

        assertTrue(output.filteredAngleDegrees > 90f)
        assertTrue(output.filteredAngleDegrees < 91f)
    }

    @Test
    fun fastMotion_tracksMoreAggressivelyThanSlowMotion() {
        val slow = HingeSignalFilter()
        slow.update(60f)
        val slowOutput = slow.update(62f)
        val slowFraction = (slowOutput.filteredAngleDegrees - 60f) / 2f

        val fast = HingeSignalFilter()
        fast.update(60f)
        val fastOutput = fast.update(90f)
        val fastFraction = (fastOutput.filteredAngleDegrees - 60f) / 30f

        assertTrue(fastFraction > slowFraction)
    }

    @Test
    fun thirtyDegreeHandMotion_keepsVisualLagUnderFiveDegrees() {
        val filter = HingeSignalFilter()
        filter.update(60f)
        val output = filter.update(90f)
        val lag = output.rawAngleDegrees - output.filteredAngleDegrees

        assertTrue("visual lag was $lag degrees", lag in 0f..5f)
    }

    @Test
    fun repeatedTenDegreeSteps_doNotAccumulateLargeLag() {
        val filter = HingeSignalFilter()
        var output = filter.update(40f)
        listOf(50f, 60f, 70f, 80f, 90f, 100f).forEach {
            output = filter.update(it)
        }
        val lag = output.rawAngleDegrees - output.filteredAngleDegrees

        assertTrue("accumulated visual lag was $lag degrees", lag in 0f..5f)
    }

    @Test
    fun equivalentMotion_isStableAcrossSixtyAndOneTwentyHertz() {
        val sixtyHz = runRamp(stepMillis = 16L)
        val oneTwentyHz = runRamp(stepMillis = 8L)
        val difference = abs(sixtyHz - oneTwentyHz)

        assertTrue("sampling-rate response differed by $difference degrees", difference < 1.5f)
    }

    @Test
    fun delayedSample_stillCapsVisualLag() {
        val filter = HingeSignalFilter()
        val base = 1_000_000_000L
        filter.update(50f, base)
        val output = filter.update(95f, base + 80_000_000L)
        val lag = abs(output.rawAngleDegrees - output.filteredAngleDegrees)

        assertTrue("visual lag was $lag degrees", lag <= 5.0001f)
    }

    @Test
    fun direction_usesCumulativeRawMotionAndHonorsDeadband() {
        val filter = HingeSignalFilter()
        filter.update(80f)
        assertTrue(filter.update(82f).opening)
        assertTrue(filter.update(82.1f).opening)
        assertFalse(filter.update(80f).opening)
    }

    @Test
    fun slowSubDeadbandSamples_eventuallyDetectDirectionReversal() {
        val filter = HingeSignalFilter()
        filter.update(80f)
        assertFalse(filter.update(79f).opening)

        assertFalse(filter.update(79.10f).opening)
        assertFalse(filter.update(79.20f).opening)
        assertTrue(filter.update(79.30f).opening)
    }

    @Test
    fun tinyJitter_doesNotFlipEstablishedDirection() {
        val filter = HingeSignalFilter()
        filter.update(80f)
        assertFalse(filter.update(79f).opening)

        listOf(79.08f, 78.94f, 79.11f, 78.97f).forEach { angle ->
            assertFalse(filter.update(angle).opening)
        }
    }

    @Test
    fun rawAngle_isClampedToPhysicalRange() {
        val filter = HingeSignalFilter()
        assertEquals(0f, filter.update(-10f).rawAngleDegrees, 0.0001f)
        filter.reset()
        assertEquals(180f, filter.update(220f).rawAngleDegrees, 0.0001f)
    }

    @Test
    fun onChangeSensor_whenEventsStop_finitelySettlesAtTheActualHeldAngle() {
        val filter = HingeSignalFilter()
        val base = 1_000_000_000L
        filter.update(90f, base)
        var output = filter.update(120f, base + 8_000_000L)
        assertTrue(output.isSensorSample)
        assertTrue(output.filteredAngleDegrees < 120f)
        var frames = 0
        var lastAngle = output.filteredAngleDegrees
        while (filter.hasPendingSettle && frames < 120) {
            frames++
            output = requireNotNull(filter.settle(base + (frames + 1) * 8_000_000L))
            assertFalse("display frame reported a new sensor sample", output.isSensorSample)
            assertEquals(120f, output.rawAngleDegrees, 0f)
            assertTrue(output.filteredAngleDegrees in lastAngle..120f)
            lastAngle = output.filteredAngleDegrees
        }
        assertTrue("held-angle response never settled", frames in 1 until 120)
        assertEquals(120f, output.filteredAngleDegrees, 0f)
        assertFalse(filter.hasPendingSettle)
        assertNull(filter.settle(base + 2_000_000_000L))
    }

    @Test
    fun syntheticFrames_preserveClosingDirectionAndRawDirectionAnchor() {
        val filter = HingeSignalFilter()
        val base = 2_000_000_000L
        filter.update(80f, base)
        assertFalse(filter.update(79f, base + 8_000_000L).opening)
        assertFalse(filter.update(79.1f, base + 16_000_000L).opening)
        for (frame in 1..60) {
            filter.settle(base + 16_000_000L + frame * 8_000_000L)?.let {
                assertFalse("settling changed the physical direction", it.opening)
                assertEquals(79.1f, it.rawAngleDegrees, 0f)
            }
        }
        assertFalse(filter.update(79.2f, base + 600_000_000L).opening)
        // Cumulative displacement is measured from the last real 79-degree
        // direction anchor, not from synthetic 79.1-degree settle emissions.
        assertTrue(filter.update(79.3f, base + 608_000_000L).opening)
    }

    @Test
    fun sensorSampleAtLastSettleTime_doesNotAdvanceTheSameElapsedTimeTwice() {
        val filter = HingeSignalFilter()
        val base = 3_000_000_000L
        filter.update(90f, base)
        filter.update(120f, base + 8_000_000L)
        val settled = requireNotNull(filter.settle(base + 16_000_000L))
        val sampled = filter.update(120f, base + 16_000_000L)
        assertEquals(settled.filteredAngleDegrees, sampled.filteredAngleDegrees, 0f)
        assertTrue(sampled.isSensorSample)
    }

    @Test
    fun settledResponse_isIndependentOfDisplayRefreshRate() {
        fun follow(frameMillis: Long): Float {
            val filter = HingeSignalFilter()
            val base = 4_000_000_000L
            filter.update(90f, base)
            var output = filter.update(120f, base + 8_000_000L)
            for (elapsed in frameMillis..128L step frameMillis) {
                output = requireNotNull(filter.settle(base + 8_000_000L + elapsed * 1_000_000L))
            }
            return output.filteredAngleDegrees
        }
        assertEquals(follow(16L), follow(8L), 0.0001f)
    }

    @Test
    fun staleFrameTimestamps_doNotReverseOrAdvanceSettling() {
        val filter = HingeSignalFilter()
        val base = 5_000_000_000L
        filter.update(90f, base)
        filter.update(120f, base + 8_000_000L)
        assertNull(filter.settle(base))
        assertNull(filter.settle(base + 8_000_000L))
        val actual = requireNotNull(filter.settle(base + 16_000_000L))

        val control = HingeSignalFilter()
        control.update(90f, base)
        control.update(120f, base + 8_000_000L)
        val expected = requireNotNull(control.settle(base + 16_000_000L))
        assertEquals(expected.filteredAngleDegrees, actual.filteredAngleDegrees, 0f)
    }

    @Test
    fun resettingSensorSession_discardsPendingSettleAndOldDirection() {
        val filter = HingeSignalFilter()
        val base = 6_000_000_000L
        filter.update(120f, base)
        assertFalse(filter.update(90f, base + 8_000_000L).opening)
        assertTrue(filter.hasPendingSettle)
        filter.reset()
        assertFalse(filter.hasPendingSettle)
        assertNull(filter.settle(base + 16_000_000L))
        val first = filter.update(70f, base + 24_000_000L)
        assertTrue(first.isSensorSample)
        assertTrue(first.opening)
        assertEquals(70f, first.filteredAngleDegrees, 0f)
    }

    private fun runRamp(stepMillis: Long): Float {
        val filter = HingeSignalFilter()
        val base = 2_000_000_000L
        val durationMillis = 128L
        filter.update(60f, base)

        var output = filter.update(60f, base)
        var elapsed = stepMillis
        while (elapsed <= durationMillis) {
            val fraction = elapsed / durationMillis.toFloat()
            val angle = 60f + 30f * fraction
            output = filter.update(angle, base + elapsed * 1_000_000L)
            elapsed += stepMillis
        }
        return output.filteredAngleDegrees
    }
}
