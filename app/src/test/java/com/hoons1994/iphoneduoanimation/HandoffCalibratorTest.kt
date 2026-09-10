package com.hoons1994.iphoneduoanimation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffCalibratorTest {

    @Test
    fun opening_acceptsCoverToInner_andMovesEstimate() {
        val calibrator = HandoffCalibrator(initialOpening = 0.43f)
        val result = calibrator.observe(
            isOpening = true,
            fromCover = true,
            toCover = false,
            progress = 0.47f,
        )

        assertTrue(result.accepted)
        assertTrue(result.estimate > 0.43f)
        assertTrue(result.estimate < 0.47f)
        assertEquals(1, result.sampleCount)
    }

    @Test
    fun opening_rejectsWrongSurfaceDirection() {
        val calibrator = HandoffCalibrator()
        val result = calibrator.observe(
            isOpening = true,
            fromCover = false,
            toCover = true,
            progress = 0.43f,
        )

        assertFalse(result.accepted)
        assertEquals(HandoffCalibrator.RejectReason.WRONG_SURFACE_DIRECTION, result.rejectReason)
    }

    @Test
    fun closing_acceptsInnerToCover_independentlyFromOpening() {
        val calibrator = HandoffCalibrator(initialOpening = 0.40f, initialClosing = 0.46f)
        val result = calibrator.observe(
            isOpening = false,
            fromCover = false,
            toCover = true,
            progress = 0.50f,
        )

        assertTrue(result.accepted)
        assertEquals(0.40f, calibrator.estimate(true), 0.0001f)
        assertTrue(calibrator.estimate(false) > 0.46f)
    }

    @Test
    fun repeatedStableSamples_raiseConfidence() {
        val calibrator = HandoffCalibrator()
        val samples = listOf(0.44f, 0.445f, 0.442f, 0.444f)
        samples.forEach {
            val result = calibrator.observe(true, true, false, it)
            assertTrue(result.accepted)
        }

        assertTrue(calibrator.confidence(true) > 0.85f)
        assertEquals(4, calibrator.sampleCount(true))
    }

    @Test
    fun establishedHistory_rejectsLargeOutlier() {
        val calibrator = HandoffCalibrator()
        listOf(0.43f, 0.44f, 0.435f).forEach {
            assertTrue(calibrator.observe(true, true, false, it).accepted)
        }

        val outlier = calibrator.observe(true, true, false, 0.60f)
        assertFalse(outlier.accepted)
        assertEquals(HandoffCalibrator.RejectReason.OUTLIER, outlier.rejectReason)
        assertEquals(3, calibrator.sampleCount(true))
    }

    @Test
    fun observationOutsidePhysicalRange_isRejected() {
        val calibrator = HandoffCalibrator()
        val result = calibrator.observe(true, true, false, 0.90f)
        assertFalse(result.accepted)
        assertEquals(HandoffCalibrator.RejectReason.OUT_OF_RANGE, result.rejectReason)
    }
}
