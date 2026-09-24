package com.hoons1994.iphoneduoanimation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionContinuityTest {

    @Test
    fun smallPhysicalSwitchError_staysStronglyMaskedOnBothSurfaces() {
        val learnedHandoff = 0.43f
        val switchOffsets = listOf(-0.03f, -0.015f, 0f, 0.015f, 0.03f)

        switchOffsets.forEach { offset ->
            val actualSwitch = learnedHandoff + offset
            val outgoingCover = TransitionTuning.surfaceFocus(
                actualSwitch,
                learnedHandoff,
                coverSurface = true,
            )
            val incomingInner = TransitionTuning.surfaceFocus(
                actualSwitch,
                learnedHandoff,
                coverSurface = false,
            )

            assertTrue("cover mask too weak at offset $offset: $outgoingCover", outgoingCover >= 0.90f)
            assertTrue("inner mask too weak at offset $offset: $incomingInner", incomingInner >= 0.90f)
        }
    }

    @Test
    fun handoffJitter_keepsBothSurfacesOnSameHalfBlend() {
        val handoff = 0.43f
        val switchOffsets = listOf(-0.03f, -0.015f, 0f, 0.015f, 0.03f)

        switchOffsets.forEach { offset ->
            val progress = handoff + offset
            val coverBlend = TransitionTuning.sourceBlend(progress, handoff, coverSurface = true)
            val innerBlend = TransitionTuning.sourceBlend(progress, handoff, coverSurface = false)

            assertEquals("cover blend at offset $offset", 0.5f, coverBlend, 0.0001f)
            assertEquals("inner blend at offset $offset", 0.5f, innerBlend, 0.0001f)
        }
    }

    @Test
    fun sourceBridge_isMonotonicAndDoesNotCrossWrongSideOfHalfBlend() {
        val handoff = 0.43f
        var previousCover = 0f
        var previousInner = 0.5f

        for (step in 0..100) {
            val progress = step / 100f
            val cover = TransitionTuning.sourceBlend(progress, handoff, coverSurface = true)
            val inner = TransitionTuning.sourceBlend(progress, handoff, coverSurface = false)

            assertTrue(cover in previousCover..0.5001f)
            assertTrue(inner + 0.0001f >= previousInner)
            assertTrue(inner in 0.4999f..1.0001f)
            previousCover = cover
            previousInner = inner
        }
    }

    @Test
    fun endpoints_areFullyResolved() {
        val handoff = 0.43f
        val closedCover = TransitionTuning.surfaceFocus(0f, handoff, coverSurface = true)
        val openInner = TransitionTuning.surfaceFocus(1f, handoff, coverSurface = false)
        val closedBlend = TransitionTuning.sourceBlend(0f, handoff, coverSurface = true)
        val openBlend = TransitionTuning.sourceBlend(1f, handoff, coverSurface = false)

        assertTrue(closedCover <= 0.0001f)
        assertTrue(openInner <= 0.0001f)
        assertEquals(0f, closedBlend, 0.0001f)
        assertEquals(1f, openBlend, 0.0001f)
    }
}
