package com.hoons1994.iphoneduoanimation

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Live launcher model, independent of the OS display-switch angle.
 *
 * Geometry follows chuspeeism/iphone-duo main.js. Frost follows the material-space
 * adaptation in joeconsorti/duo-fold-live ClassicGlassShader.kt: evaluating frost
 * at the projected texture coordinate would make it disappear near edge-on.
 * The older snapshot renderer keeps its own tuning in TransitionTuning.
 */
internal object DuoFoldModel {
    const val MAX_BLUR_LEVEL_COUNT = 6
    private const val MIN_NATIVE_SIGMA = 0.57735f * 0.01f + 0.5f
    // ClassicGlassShader limits only projection, avoiding a collapsed or mirrored
    // texture. Physical motion still reaches its full envelope at 90 degrees.
    private const val MAX_PROJECTION_DEGREES = 87.3f
    private const val INNER_EYE_RATIO = (40f - 0.24948f) / 15.7987f
    private const val COVER_EYE_RATIO = (40f - 0.825538f) / 7.73936f

    fun bendDegrees(progress: Float, cover: Boolean): Float =
        (if (cover) progress else 1f - progress).coerceIn(0f, 1f) * 180f

    fun radians(progress: Float, cover: Boolean): Float =
        bendDegrees(progress, cover).coerceAtMost(MAX_PROJECTION_DEGREES) * PI.toFloat() / 180f

    fun motion(progress: Float, cover: Boolean): Float {
        val phase = (bendDegrees(progress, cover) / 90f).coerceIn(0f, 1f)
        return phase * phase * (3f - 2f * phase)
    }

    fun eyeDistance(axisExtent: Float, cover: Boolean): Float =
        axisExtent * if (cover) COVER_EYE_RATIO else INNER_EYE_RATIO

    /** Source-space footprint of the reference's 5x5 binomial kernel. */
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

    fun attenuation(edge: Float, motion: Float): Float =
        1f - (2f * motion * ((edge - 0.2f) / 0.8f).coerceIn(0f, 1f).pow(1.35f))
            .coerceAtMost(1f)

    /**
     * The reference's binomial taps have variance radius^2. Android's native
     * blur takes a radius converted to sigma = .57735 * radius + .5; passing
     * the reference radius straight through made our frost substantially weaker.
     * Native Gaussian filtering approximates the reference kernel without its
     * 25 texture taps and mip pyramid; it is not an identical filter.
     */
    fun nativeBlurRadius(referenceRadius: Float): Float =
        ((referenceRadius - 0.5f) / 0.57735f).coerceAtLeast(0.01f)
}
