package com.hoons1994.iphoneduoanimation

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign

/**
 * Low-latency hinge filtering for hand-driven fold motion.
 *
 * Slow movement gets strong smoothing so sensor jitter is not visible. Fast motion raises
 * the response using angular velocity and tracking error, while a hard residual-lag cap keeps
 * the rendered transition close to the physical hinge after a dropped or delayed sample.
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
    private var directionAnchorAngle = Float.NaN
    private var previousTimestampNanos = Long.MIN_VALUE
    private var opening = true

    fun update(
        rawAngleDegrees: Float,
        timestampNanos: Long = Long.MIN_VALUE,
    ): Output {
        val raw = rawAngleDegrees.coerceIn(0f, 180f)

        if (filteredAngle.isNaN()) {
            filteredAngle = raw
        } else {
            val dtSeconds = elapsedSeconds(timestampNanos)
            val rawStep = if (previousRawAngle.isNaN()) 0f else abs(raw - previousRawAngle)
            val trackingError = abs(raw - filteredAngle)
            val angularVelocity = rawStep / dtSeconds.coerceAtLeast(MIN_DT_SECONDS)

            val speedFactor = max(
                (angularVelocity / FAST_MOTION_DEGREES_PER_SECOND).coerceIn(0f, 1f),
                (trackingError / FAST_TRACKING_ERROR_DEGREES).coerceIn(0f, 1f),
            )
            val timeConstant = SLOW_TIME_CONSTANT_SECONDS +
                (FAST_TIME_CONSTANT_SECONDS - SLOW_TIME_CONSTANT_SECONDS) * speedFactor
            val alpha = 1f - exp((-dtSeconds / timeConstant).toDouble()).toFloat()

            var next = filteredAngle + (raw - filteredAngle) * alpha
            val residual = raw - next
            if (abs(residual) > MAX_VISUAL_LAG_DEGREES) {
                next = raw - sign(residual) * MAX_VISUAL_LAG_DEGREES
            }
            filteredAngle = next.coerceIn(0f, 180f)
        }

        updateDirection(raw)
        previousRawAngle = raw
        if (timestampNanos != Long.MIN_VALUE) {
            previousTimestampNanos = timestampNanos
        }

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
        directionAnchorAngle = Float.NaN
        previousTimestampNanos = Long.MIN_VALUE
        opening = true
    }

    private fun updateDirection(raw: Float) {
        if (directionAnchorAngle.isNaN()) {
            directionAnchorAngle = raw
            return
        }

        // Per-sample deadbands fail at high sensor rates: a slow 10 deg/s fold
        // only moves ~0.08 degrees per 120 Hz sample and would never change
        // direction. Accumulate displacement from an anchor so genuine slow
        // reversals eventually cross the threshold while sub-threshold jitter
        // still cannot flip the state.
        val displacement = raw - directionAnchorAngle
        if (abs(displacement) >= DIRECTION_DEADBAND_DEGREES) {
            opening = displacement > 0f
            directionAnchorAngle = raw
        }
    }

    private fun elapsedSeconds(timestampNanos: Long): Float {
        if (
            timestampNanos == Long.MIN_VALUE ||
            previousTimestampNanos == Long.MIN_VALUE ||
            timestampNanos <= previousTimestampNanos
        ) {
            return NOMINAL_DT_SECONDS
        }

        return ((timestampNanos - previousTimestampNanos) / NANOS_PER_SECOND)
            .toFloat()
            .coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
    }

    companion object {
        private const val SLOW_TIME_CONSTANT_SECONDS = 0.055f
        private const val FAST_TIME_CONSTANT_SECONDS = 0.008f
        private const val FAST_MOTION_DEGREES_PER_SECOND = 360f
        private const val FAST_TRACKING_ERROR_DEGREES = 12f
        private const val MAX_VISUAL_LAG_DEGREES = 5f
        private const val DIRECTION_DEADBAND_DEGREES = 0.25f

        private const val NOMINAL_DT_SECONDS = 1f / 60f
        private const val MIN_DT_SECONDS = 1f / 240f
        private const val MAX_DT_SECONDS = 0.05f
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
