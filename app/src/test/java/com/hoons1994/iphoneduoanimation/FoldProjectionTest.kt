package com.hoons1994.iphoneduoanimation

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class FoldProjectionTest {
    @Test fun resolvedEndpointsAreIdentity() {
        for (cover in listOf(false, true)) for (axis in listOf(false, true)) {
            val tilt = FoldProjection.tiltDegrees(if (cover) 0f else 180f, cover)
            for (i in 0..20) {
                val p = FoldProjection.project(i * 60f, i * 40f, 1200f, 800f, tilt, cover, axis)
                assertEquals(i * 60f, p.x, 0.001f)
                assertEquals(i * 40f, p.y, 0.001f)
            }
        }
    }
    @Test fun fixedPaneAndHingeNeverMove() {
        for (angle in 0..180) {
            val tilt = FoldProjection.tiltDegrees(angle.toFloat(), false)
            for (x in floatArrayOf(10f, 300f, 600f)) {
                val p = FoldProjection.project(x, 100f, 1200f, 800f, tilt)
                assertEquals(x, p.x, 0f); assertEquals(100f, p.y, 0f); assertEquals(0f, p.depth, 0f)
            }
        }
    }
    @Test fun movingPaneHasSubstantialProjection() {
        val p = FoldProjection.project(1050f, 200f, 1200f, 800f, 60f)
        assertTrue(abs(p.x - 1050f) > 100f)
        assertTrue(abs(p.y - 200f) > 30f)
    }
    @Test fun effectSurvivesLatePanelActivationAngles() {
        assertEquals(30f, FoldProjection.tiltDegrees(150f, false), 0f)
        assertEquals(5f, FoldProjection.tiltDegrees(175f, false), 0f)
        assertEquals(0f, FoldProjection.tiltDegrees(180f, false), 0f)
    }
    @Test fun rotationAndMirrorPreserveCoordinates() {
        for (angle in 0..180) {
            val tilt = FoldProjection.tiltDegrees(angle.toFloat(), false)
            val right = FoldProjection.project(1020f, 200f, 1200f, 800f, tilt)
            val left = FoldProjection.project(180f, 200f, 1200f, 800f, tilt, movingFromEnd = false)
            assertEquals(1200f, right.x + left.x, 0.002f)
            assertEquals(right.y, left.y, 0.002f)
            val rotated = FoldProjection.project(200f, 1020f, 800f, 1200f, tilt, axisY = true)
            assertEquals(right.x, rotated.y, 0.002f)
            assertEquals(right.y, rotated.x, 0.002f)
        }
    }
    @Test fun coordinatesStayFiniteOverEntireTravel() {
        for (cover in listOf(false, true)) for (angle in 0..180) for (i in 0..20) {
            val p = FoldProjection.project(i * 60f, i * 40f, 1200f, 800f,
                FoldProjection.tiltDegrees(angle.toFloat(), cover), cover)
            assertTrue(p.x.isFinite() && p.y.isFinite())
        }
    }
    @Test fun invalidSensorNumbersResolveSafely() {
        for (value in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, FoldProjection.tiltDegrees(value, false), 0f)
            assertEquals(0f, FoldProjection.tiltDegrees(value, true), 0f)
        }
    }
    @Test fun startupDoesNotFakeAFold() {
        assertEquals(0f, FoldReveal().sample(0f, 1000L, true), 0f)
    }
    @Test fun revealClockStartsOnFirstDrawableFrame() {
        val reveal = FoldReveal()
        reveal.arm()
        assertEquals(0f, reveal.sample(0f, 1000L, false), 0f)
        assertEquals(28f, reveal.sample(0f, 5000L, true), 0f)
        assertTrue(reveal.compensating)
        assertEquals(14f, reveal.sample(0f, 5120L, true), 0.001f)
        assertEquals(0f, reveal.sample(0f, 5240L, true), 0f)
        assertFalse(reveal.needsFrame())
    }
    @Test fun revealNeverReducesPhysicalTilt() {
        val reveal = FoldReveal(); reveal.arm()
        assertEquals(60f, reveal.sample(60f, 0L, true), 0f)
        assertFalse(reveal.compensating)
    }
    @Test fun stopOrDirectionChangeCancelsOldReveal() {
        val reveal = FoldReveal(); reveal.arm(); reveal.sample(0f, 0, true)
        reveal.reset()
        assertEquals(0f, reveal.sample(0f, 50, true), 0f)
        reveal.arm()
        assertEquals(28f, reveal.sample(0f, 100, true), 0f)
    }
}
