package com.hoons1994.iphoneduoanimation

import kotlin.math.abs
import kotlin.math.max

/**
 * Low-latency hinge filtering for hand-driven fold motion.
 *
 * Slow movement gets strong smoothing so sensor jitter is not visible. Fast motion raises
 * the filter alpha so the rendered transition does not visibly lag behind the physical hinge.
 * Raw angle is kept alongside the filtered angle so calibration can use the least-lagged value.
 */
class HingeSignalFilter {

    data class Output(
        val rawAngleDegrees: Float,
        val filteredAngleDegrees: Float,
        val filteredProgress: Float,
        val opening: Boolean,
    )

    private var filteredAngle = Float.NaN
    private var previousRawAngle = Float.NaN
    private var opening = true

    fun update(rawAngleDegrees: Float): Output {
        val raw = rawAngleDegrees.coerceIn(0f, 180f)

        if (filteredAngle.isNaN()) {
            filteredAngle = raw
        } else {
            val rawStep = if (previousRawAngle.isNaN()) 0f else abs(raw - previousRawAngle)
            val trackingError = abs(raw - filteredAngle)
            val motion = max(rawStep, trackingError)
            val speedFactor = (motion / FAST_MOTION_DEGREES).coerceIn(0f, 1f)
            val alpha = MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * speedFactor
            filteredAngle += (raw - filteredAngle) * alpha
        }

        if (!previousRawAngle.isNaN()) {
            val delta = raw - previousRawAngle
            if (abs(delta) >= DIRECTION_DEADBAND_DEGREES) {
                opening = delta > 0f
            }
        }
        previousRawAngle = raw

        return Output(
            rawAngleDegrees = raw,
            filteredAngleDegrees = filteredAngle,
            filteredProgress = (filteredAngle / 180f).coerceIn(0f, 1f),
            opening = opening,
        )
    }

    fun reset() {
        filteredAngle = Float.NaN
        previousRawAngle = Float.NaN
        opening = true
    }

    companion object {
        private const val MIN_ALPHA = 0.20f
        private const val MAX_ALPHA = 0.72f
        private const val FAST_MOTION_DEGREES = 24f
        private const val DIRECTION_DEADBAND_DEGREES = 0.25f
    }
}
