package com.hoons1994.iphoneduoanimation

import kotlin.math.PI

/**
 * Optical glass pose, separate from the hardware hinge angle. Like duo-open,
 * each display uses its visible hinge range to drive a bounded 45-degree tilt.
 * The live renderer adds eased endpoints and a smooth strip beside the hinge.
 * Frost follows the glass-to-UI gap in DuoLikeAnimation and Atomicx7/Duo-animation.
 */
internal object DuoFoldModel {
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

    /** Source-space disk radius ceiling, retained across the renderer change. */
    fun maxBlurRadius(axisExtent: Float, cover: Boolean): Float =
        72f * axisExtent / if (cover) 774f else 1600f
}
