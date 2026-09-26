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
    // Direction and display handoff are observations, never a new optical pose.
    // The same physical angle must have the same image on opening and closing.
    val motion = DuoFoldModel.motion(progress, cover)
    private val radians = DuoFoldModel.radians(progress, cover)
    val foldCos = cos(radians)
    val foldSin = sin(radians)
    val eyeDistancePx = DuoFoldModel.eyeDistance(axisExtent, cover)
    val maxBlurPx = DuoFoldModel.maxBlurRadius(axisExtent, cover)
    // Motion is eased quadratically, but perspective starts linearly. Checking
    // the eased frost here would drop the last half-degree of visible movement.
    val active get() = DuoFoldModel.bendDegrees(progress, cover) > 0.02f && width > 0 && height > 0

    /** Inverse visual mapping: a point on the glass maps into the live View tree. */
    fun sourcePoint(x: Float, y: Float): Pair<Float, Float>? {
        if (!active) return x to y
        val rawAxis = if (axisY) y else x
        val axis = if (hingeFromEnd) axisExtent - rawAxis else rawAxis
        if (!cover && axis >= axisExtent * 0.5f) return x to y
        val across = if (axisY) x else y
        val hinge = if (cover) 0f else axisExtent * 0.5f
        val distance = if (cover) axis else hinge - axis
        val paneExtent = if (cover) axisExtent else axisExtent * 0.5f
        // A fully occluded part of the glass must not activate an invisible app.
        if (DuoFoldModel.attenuation(distance / paneExtent, motion) <= 0.001f) return null
        val glassAxis = if (cover) distance * foldCos else hinge - distance * foldCos
        val gap = (distance * foldSin).coerceAtLeast(0f)
        val depth = eyeDistancePx - gap
        if (depth <= 0.001f) return null
        val perspective = eyeDistancePx / depth
        // Both surfaces share the reference eye through the hinge. On the
        // cover, subtracting the projected hinge anchors the image at its spine.
        val eyeAxis = hinge
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
