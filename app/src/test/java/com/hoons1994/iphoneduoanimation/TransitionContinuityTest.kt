package com.hoons1994.iphoneduoanimation

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
    fun endpoints_areFullyResolved() {
        val handoff = 0.43f
        val closedCover = TransitionTuning.surfaceFocus(0f, handoff, coverSurface = true)
        val openInner = TransitionTuning.surfaceFocus(1f, handoff, coverSurface = false)

        assertTrue(closedCover <= 0.0001f)
        assertTrue(openInner <= 0.0001f)
    }
}
