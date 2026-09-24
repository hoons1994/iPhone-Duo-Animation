package com.hoons1994.iphoneduoanimation

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotGeometryTest {

    @Test
    fun foldLikeSnapshots_matchExpectedRolesInEitherOrientation() {
        assertEquals(
            SnapshotGeometry.Assessment.MATCH,
            SnapshotGeometry.assess(SnapshotGeometry.Role.COVER, 1080, 2520),
        )
        assertEquals(
            SnapshotGeometry.Assessment.MATCH,
            SnapshotGeometry.assess(SnapshotGeometry.Role.INNER, 2208, 1840),
        )
        assertEquals(
            SnapshotGeometry.Assessment.MATCH,
            SnapshotGeometry.assess(SnapshotGeometry.Role.INNER, 1840, 2208),
        )
    }

    @Test
    fun obviousRoleSwap_isDetected() {
        assertEquals(
            SnapshotGeometry.Assessment.LOOKS_SWAPPED,
            SnapshotGeometry.assess(SnapshotGeometry.Role.COVER, 2208, 1840),
        )
        assertEquals(
            SnapshotGeometry.Assessment.LOOKS_SWAPPED,
            SnapshotGeometry.assess(SnapshotGeometry.Role.INNER, 1080, 2520),
        )
    }

    @Test
    fun middleAspectBand_isAmbiguousRatherThanRejected() {
        assertEquals(
            SnapshotGeometry.Assessment.AMBIGUOUS,
            SnapshotGeometry.assess(SnapshotGeometry.Role.COVER, 600, 1000),
        )
    }

    @Test
    fun invalidDimensions_areRejected() {
        assertEquals(
            SnapshotGeometry.Assessment.INVALID,
            SnapshotGeometry.assess(SnapshotGeometry.Role.COVER, 0, 1000),
        )
    }
}
