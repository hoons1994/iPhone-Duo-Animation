package com.hoons1994.iphoneduoanimation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HingeSensorQualityTest {
    @Test
    fun declaredNinetyDegreeResolution_isCoarseImmediately() {
        assertTrue(HingeSensorQuality.isCoarse(90f, 0, emptySet()))
    }

    @Test
    fun repeatedStopOnlyReadings_areClassifiedCoarse() {
        assertTrue(HingeSensorQuality.isCoarse(1f, 6, setOf(0, 90, 180)))
    }

    @Test
    fun realIntermediateAngles_remainContinuous() {
        assertFalse(HingeSensorQuality.isCoarse(0.1f, 8, setOf(0, 17, 38, 64, 91, 123, 151, 180)))
    }
}
