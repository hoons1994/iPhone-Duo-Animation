package com.hoons1994.iphoneduoanimation

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceClassifierTest {

    @Test
    fun galaxyFoldLikeCoverRatio_isCoverInEitherOrientation() {
        assertEquals(
            SurfaceClassifier.Surface.COVER,
            SurfaceClassifier.classify(1080, 2520),
        )
        assertEquals(
            SurfaceClassifier.Surface.COVER,
            SurfaceClassifier.classify(2520, 1080),
        )
    }

    @Test
    fun galaxyFoldLikeInnerRatio_isInnerInEitherOrientation() {
        assertEquals(
            SurfaceClassifier.Surface.INNER,
            SurfaceClassifier.classify(2208, 1840),
        )
        assertEquals(
            SurfaceClassifier.Surface.INNER,
            SurfaceClassifier.classify(1840, 2208),
        )
    }

    @Test
    fun invalidDimensions_areUnknown() {
        assertEquals(SurfaceClassifier.Surface.UNKNOWN, SurfaceClassifier.classify(0, 2400))
        assertEquals(SurfaceClassifier.Surface.UNKNOWN, SurfaceClassifier.classify(1080, 0))
    }
}
