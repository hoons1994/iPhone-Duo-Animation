package com.hoons1994.iphoneduoanimation

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Optical glass pose, separate from the hardware hinge angle. Like duo-open,
 * each display uses its visible hinge range to drive a bounded 45-degree tilt.
 * The live renderer adds eased endpoints and a smooth strip beside the hinge.
 * Frost follows the glass-to-UI gap in DuoLikeAnimation and Atomicx7/Duo-animation.
 */
internal object DuoFoldModel {
    const val MAX_BLUR_LEVEL_COUNT = 6
    private const val MIN_NATIVE_SIGMA = 0.57735f * 0.01f + 0.5f
    // One side of the flexible hinge in iphone-duo's 3D model, relative to a pane.
    private const val HINGE_FLEX_RATIO = 0.35f / 7.89935f
    const val BLUR_SPREAD = TransitionTuning.REFERENCE_BLUR_SPREAD

    fun tiltDegrees(progress: Float, cover: Boolean, handoff: Float): Float {
        val phase = if (cover) TransitionTuning.referenceCoverBlur(progress, handoff)
            else TransitionTuning.referenceInnerPhase(progress, handoff = handoff)
        return TransitionTuning.REFERENCE_MAX_TILT_DEGREES * phase * phase * (3f - 2f * phase)
    }

    fun radians(tiltDegrees: Float): Float = tiltDegrees * PI.toFloat() / 180f

    fun pixelsPerMm(value: Float): Float =
        value.takeIf { it.isFinite() && it > 0f } ?: TransitionTuning.REFERENCE_PIXELS_PER_MM

    fun eyeDistance(paneExtent: Float, pixelsPerMm: Float): Float =
        maxOf(TransitionTuning.REFERENCE_EYE_DISTANCE_MM * pixelsPerMm, paneExtent * 2f)

    fun hingeFlex(paneExtent: Float): Float = maxOf(1f, paneExtent * HINGE_FLEX_RATIO)

    /** Integral of a smoothstep tangent: position, slope, and curvature join continuously. */
    fun bentDistance(distance: Float, flex: Float): Float {
        if (distance >= flex) return distance - flex * 0.5f
        val u = (distance / flex).coerceIn(0f, 1f)
        return flex * u * u * u * (1f - 0.5f * u)
    }

    fun darkening(pixelsPerMm: Float): Float = TransitionTuning.REFERENCE_DARKENING *
        TransitionTuning.REFERENCE_PIXELS_PER_MM / pixelsPerMm

    fun attenuation(radius: Float, darkening: Float): Float = (1f - darkening * radius).coerceAtLeast(0f)

    /** Retain the source-space filter budget of the previous live renderer. */
    fun maxBlurRadius(axisExtent: Float, cover: Boolean): Float =
        72f * axisExtent / if (cover) 774f else 1600f

    /**
     * Small source-space filters remove glyph strokes before projection can
     * stretch them. Blending sharp with R/9 directly left a strong sharp copy
     * even when the requested radius was several pixels. Keep that interval
     * below one source pixel, independent of the display's resolution.
     */
    fun blurLevels(maxBlurPx: Float): FloatArray {
        val maximum = maxBlurPx.coerceAtLeast(0.001f)
        val coarse = maximum / 9f
        val fine = minOf(1f, coarse / 3f)
        // Native RenderEffect cannot represent a positive sigma below this
        // floor. On very small viewports, use its actual radius and deduplicate
        // stops so interpolation never assumes narrower filters or zero spans.
        return listOf(0f, fine, sqrt(fine * coarse), coarse, maximum / 3f, maximum)
            .map { if (it == 0f) 0f else it.coerceAtLeast(MIN_NATIVE_SIGMA) }
            .distinct()
            .toFloatArray()
    }

    /**
     * Android converts its native blur radius to sigma = .57735 * radius + .5.
     * Treat our source-space radius as sigma consistently across blur levels.
     * This Gaussian approximation is not the original shader's Vogel disk filter.
     */
    fun nativeBlurRadius(referenceRadius: Float): Float =
        ((referenceRadius - 0.5f) / 0.57735f).coerceAtLeast(0.01f)
}
