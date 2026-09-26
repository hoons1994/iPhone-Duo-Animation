package com.hoons1994.iphoneduoanimation

import kotlin.math.cos
import kotlin.math.sin

/** The displayed glass geometry, shared by rendering and touch hit testing. */
internal data class LiveFoldGeometry(
    val width: Int,
    val height: Int,
    val cover: Boolean,
    val rotation: Int,
    val progress: Float,
    val opening: Boolean,
    val handoff: Float,
    val pixelsPerMm: Float,
) {
    val axisY = rotation == 1 || rotation == 3
    val hingeFromEnd = rotation == 1 || rotation == 2
    val axisExtent = (if (axisY) height else width).toFloat()
    val acrossExtent = (if (axisY) width else height).toFloat()
    val paneExtent = if (cover) axisExtent else axisExtent * 0.5f
    // LiveFoldLayout latches handoff for a gesture. Reversing its direction does
    // not select a different optical pose or change the calibration mid-fold.
    private val tiltDegrees = DuoFoldModel.tiltDegrees(progress, cover, handoff)
    private val radians = DuoFoldModel.radians(tiltDegrees)
    val foldCos = cos(radians)
    val foldSin = sin(radians)
    private val density = DuoFoldModel.pixelsPerMm(pixelsPerMm)
    val eyeDistancePx = DuoFoldModel.eyeDistance(paneExtent, density)
    val hingeFlexPx = DuoFoldModel.hingeFlex(paneExtent)
    val maxBlurPx = DuoFoldModel.maxBlurRadius(axisExtent, cover)
    val darkening = DuoFoldModel.darkening(density)
    val maxFrostRadiusPx = frostRadius(DuoFoldModel.bentDistance(paneExtent, hingeFlexPx) * foldSin)
    val active get() = tiltDegrees > 0.02f && width > 0 && height > 0

    private fun frostRadius(gap: Float): Float = minOf(maxBlurPx, DuoFoldModel.BLUR_SPREAD * gap)

    /** Inverse visual mapping: a point on the glass maps into the live View tree. */
    fun sourcePoint(x: Float, y: Float): Pair<Float, Float>? {
        if (!active) return x to y
        val rawAxis = if (axisY) y else x
        val axis = if (hingeFromEnd) axisExtent - rawAxis else rawAxis
        if (!cover && axis >= axisExtent * 0.5f) return x to y
        val across = if (axisY) x else y
        val hinge = if (cover) 0f else axisExtent * 0.5f
        val distance = if (cover) axis else hinge - axis
        val bentDistance = DuoFoldModel.bentDistance(distance, hingeFlexPx)
        val glassDistance = distance - (1f - foldCos) * bentDistance
        val gap = (bentDistance * foldSin).coerceAtLeast(0f)
        // A fully occluded part of the glass must not activate an invisible app.
        if (DuoFoldModel.attenuation(frostRadius(gap), darkening) <= 0.001f) return null
        val glassAxis = if (cover) glassDistance else hinge - glassDistance
        val depth = eyeDistancePx - gap
        if (depth <= 0.001f) return null
        val perspective = eyeDistancePx / depth
        val eyeAxis = axisExtent * 0.5f
        val hitAxis = eyeAxis + (glassAxis - eyeAxis) * perspective
        val hitAcross = acrossExtent * 0.5f + (across - acrossExtent * 0.5f) * perspective
        val rawHit = if (hingeFromEnd) axisExtent - hitAxis else hitAxis
        val sourceX = if (axisY) hitAcross else rawHit
        val sourceY = if (axisY) rawHit else hitAcross
        return if (sourceX.isFinite() && sourceY.isFinite() &&
            sourceX >= 0f && sourceX < width && sourceY >= 0f && sourceY < height) {
            sourceX to sourceY
        } else null
    }
}
