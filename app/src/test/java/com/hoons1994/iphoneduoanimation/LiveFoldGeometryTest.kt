package com.hoons1994.iphoneduoanimation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFoldGeometryTest {
    @Test
    fun resolvedEndpoints_preserveTouchCoordinates_inEveryRotationAndDirection() {
        for (rotation in 0..3) for (opening in listOf(false, true)) {
            for (cover in listOf(false, true)) {
                val geometry = geometry(rotation, cover, if (cover) 0f else 180f, opening)
                assertFalse("endpoint remained active: $geometry", geometry.active)
                for ((x, y) in listOf(0.5f to 0.5f, 1199.5f to 1799.5f,
                        170f to 1450f, 600f to 900f)) {
                    assertPoint(x, y, geometry.sourcePoint(x, y))
                }
            }
        }
    }

    @Test
    fun fixedInnerPanel_preservesTouchCoordinates_whileMovingPanelIsProjected() {
        for (rotation in 0..3) for (angle in listOf(98f, 120f, 160f)) {
            val geometry = geometry(rotation, cover = false, angle = angle)
            assertTrue(geometry.active)
            for (across in listOf(0.1f, 0.5f, 0.9f)) {
                val point = screenPoint(geometry, 0.8f, across)
                assertPoint(point.first, point.second, geometry.sourcePoint(point.first, point.second))
            }
        }
    }

    @Test
    fun projectedTouchCoordinates_meetTheFixedPanelContinuouslyAtTheHinge() {
        for (rotation in 0..3) {
            val geometry = geometry(rotation, cover = false, angle = 110f)
            val hinge = screenPoint(geometry, 0.5f, 0.7f)
            val moving = screenPoint(geometry, 0.5f - 0.001f / geometry.axisExtent, 0.7f)
            val fixed = screenPoint(geometry, 0.5f + 0.001f / geometry.axisExtent, 0.7f)
            assertPoint(hinge.first, hinge.second, geometry.sourcePoint(hinge.first, hinge.second))
            assertPoint(hinge.first, hinge.second, geometry.sourcePoint(moving.first, moving.second), 0.01f)
            assertPoint(hinge.first, hinge.second, geometry.sourcePoint(fixed.first, fixed.second), 0.01f)
        }
    }

    @Test
    fun glassPointsWhoseRaysMissContent_doNotBecomeClickableEdgePixels() {
        for (rotation in 0..3) for (cover in listOf(false, true)) {
            val geometry = geometry(rotation, cover, angle = 98f)
            // Perspective expands away from the across-axis center, so the
            // top edge of the moving pane projects beyond the content plane.
            val point = screenPoint(geometry, if (cover) 0.6f else 0.1f, 0f)
            assertNull("outside projection accepted: $geometry at $point",
                geometry.sourcePoint(point.first, point.second))
        }
    }

    @Test
    fun fullyDarkGlass_doesNotActivateHiddenContent() {
        val geometry = geometry(rotation = 0, cover = true, angle = 98f)
        assertNull(geometry.sourcePoint(1000f, 900f))
    }

    private fun geometry(
        rotation: Int,
        cover: Boolean,
        angle: Float,
        opening: Boolean = true,
    ) = LiveFoldGeometry(
        width = 1200,
        height = 1800,
        cover = cover,
        rotation = rotation,
        progress = angle / 180f,
        opening = opening,
        handoff = TransitionTuning.DEFAULT_HANDOFF_PROGRESS,
        pixelsPerMm = TransitionTuning.REFERENCE_PIXELS_PER_MM,
    )

    private fun screenPoint(geometry: LiveFoldGeometry, axis: Float, across: Float): Pair<Float, Float> {
        val rawAxis = (if (geometry.hingeFromEnd) 1f - axis else axis) * geometry.axisExtent
        val acrossPx = across * geometry.acrossExtent
        return if (geometry.axisY) acrossPx to rawAxis else rawAxis to acrossPx
    }

    private fun assertPoint(x: Float, y: Float, actual: Pair<Float, Float>?, tolerance: Float = 0.001f) {
        assertNotNull("valid point was rejected", actual)
        assertEquals("source x", x, requireNotNull(actual).first, tolerance)
        assertEquals("source y", y, actual.second, tolerance)
    }
}
