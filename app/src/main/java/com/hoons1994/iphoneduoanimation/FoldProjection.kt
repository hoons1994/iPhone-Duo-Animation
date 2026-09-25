package com.hoons1994.iphoneduoanimation

import kotlin.math.cos
import kotlin.math.sin

/**
 * Independent fixed-content-plane model, not Apple's implementation.
 *
 * The eye is above the hinge at (0, 0, D). A panel point (d, v, 0) rotates
 * to (d cos(theta), v, d sin(theta)). Extend its eye ray to z=0 to find
 * the content coordinate to paint there. The other inner half stays fixed.
 * Units are screen pixels; D is expressed in panel widths.
 */
object FoldProjection {
    const val MAX_TILT_DEGREES = 75f
    const val EYE_DISTANCE_PANELS = 3.2f
    const val DEFAULT_HANDOFF = 0.5f

    data class Point(val x: Float, val y: Float, val depth: Float)

    fun tiltDegrees(angle: Float, cover: Boolean, handoff: Float = DEFAULT_HANDOFF): Float {
        val a = if (angle.isFinite()) angle.coerceIn(0f, 180f) else if (cover) 0f else 180f
        val h = if (handoff.isFinite()) handoff.coerceIn(0.1f, 0.95f) else DEFAULT_HANDOFF
        return if (cover) {
            (a / (h * 180f) * MAX_TILT_DEGREES).coerceIn(0f, MAX_TILT_DEGREES)
        } else {
            // Do not resolve at handoff + 32 degrees: continue until fully open.
            (180f - a).coerceIn(0f, MAX_TILT_DEGREES)
        }
    }

    fun project(
        x: Float, y: Float, width: Float, height: Float, tiltDegrees: Float,
        cover: Boolean = false, axisY: Boolean = false,
        coverHingeFromEnd: Boolean = false, movingFromEnd: Boolean = true,
    ): Point {
        require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
        require(x.isFinite() && y.isFinite())
        val cross = if (axisY) y else x
        val along = if (axisY) x else y
        val crossSize = if (axisY) height else width
        val alongSize = if (axisY) width else height
        val hinge = if (cover) { if (coverHingeFromEnd) crossSize else 0f } else crossSize / 2f
        val sign = if (cover) { if (coverHingeFromEnd) -1f else 1f } else if (movingFromEnd) 1f else -1f
        val distance = (cross - hinge) * sign
        if (distance <= 0f) return Point(x, y, 0f)
        val panel = if (cover) crossSize else crossSize / 2f
        val tilt = if (tiltDegrees.isFinite()) tiltDegrees.coerceIn(0f, MAX_TILT_DEGREES) else 0f
        val theta = Math.toRadians(tilt.toDouble()).toFloat()
        val depth = (distance / panel).coerceIn(0f, 1f) * sin(theta)
        val denominator = 1f - depth / EYE_DISTANCE_PANELS
        val mappedCross = hinge + sign * distance * cos(theta) / denominator
        val mappedAlong = alongSize / 2f + (along - alongSize / 2f) / denominator
        return if (axisY) Point(mappedAlong, mappedCross, depth) else Point(mappedCross, mappedAlong, depth)
    }
}

/**
 * Optional, bounded visibility compensation, deliberately separate from hinge
 * physics. Arm only for an observed physical surface change, never cold start.
 * The timer begins with a drawable frame on an ON display, not while it is off.
 * A stop cancels it so reopening the app cannot replay an old transition.
 */
class FoldReveal {
    private var pending = false
    private var startMs: Long? = null
    var compensating: Boolean = false
        private set

    fun arm() { pending = true; startMs = null; compensating = false }
    fun reset() { pending = false; startMs = null; compensating = false }

    fun sample(baseTilt: Float, nowMs: Long, drawable: Boolean): Float {
        if (!drawable) return baseTilt
        if (pending) {
            pending = false
            startMs = nowMs
        }
        val start = startMs ?: return baseTilt
        val elapsed = (nowMs - start).coerceAtLeast(0L)
        if (elapsed >= DURATION_MS) { reset(); return baseTilt }
        val t = elapsed.toFloat() / DURATION_MS
        val ease = t * t * (3f - 2f * t)
        val floor = REVEAL_TILT * (1f - ease)
        compensating = floor > baseTilt
        return maxOf(baseTilt, floor)
    }

    fun needsFrame(): Boolean = startMs != null

    companion object {
        const val DURATION_MS = 240L
        const val REVEAL_TILT = 28f
    }
}
