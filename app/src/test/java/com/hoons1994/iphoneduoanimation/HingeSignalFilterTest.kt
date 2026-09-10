package com.hoons1994.iphoneduoanimation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun direction_usesRawMotionAndHonorsDeadband() {
        val filter = HingeSignalFilter()
        filter.update(80f)
        assertTrue(filter.update(82f).opening)
        assertTrue(filter.update(82.1f).opening)
        assertFalse(filter.update(80f).opening)
    }

    @Test
    fun rawAngle_isClampedToPhysicalRange() {
        val filter = HingeSignalFilter()
        assertEquals(0f, filter.update(-10f).rawAngleDegrees, 0.0001f)
        filter.reset()
        assertEquals(180f, filter.update(220f).rawAngleDegrees, 0.0001f)
    }
}
