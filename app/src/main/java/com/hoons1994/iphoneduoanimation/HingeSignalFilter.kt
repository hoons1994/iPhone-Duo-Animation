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
        /** False for display-frame settling; only real samples refresh calibration age. */
        val isSensorSample: Boolean = true,
    )

    private var filteredAngle = Float.NaN
    private var previousRawAngle = Float.NaN
    private var directionAnchorAngle = Float.NaN
    private var previousTimestampNanos = Long.MIN_VALUE
    private var lastFilterTimestampNanos = Long.MIN_VALUE
    private var responseTimeConstant = SLOW_TIME_CONSTANT_SECONDS
    private var opening = true

    val hasPendingSettle: Boolean
        get() = filteredAngle.isFinite() && previousRawAngle.isFinite() &&
            filteredAngle != previousRawAngle

    fun update(
        rawAngleDegrees: Float,
        timestampNanos: Long = Long.MIN_VALUE,
    ): Output {
        val raw = rawAngleDegrees.coerceIn(0f, 180f)

        if (filteredAngle.isNaN()) {
            filteredAngle = raw
        } else {
            val sampleDtSeconds = elapsedSeconds(timestampNanos)
            val filterDtSeconds = elapsedFilterSeconds(timestampNanos)
            val rawStep = if (previousRawAngle.isNaN()) 0f else abs(raw - previousRawAngle)
            val trackingError = abs(raw - filteredAngle)
            val angularVelocity = rawStep / sampleDtSeconds.coerceAtLeast(MIN_DT_SECONDS)

            val speedFactor = max(
                (angularVelocity / FAST_MOTION_DEGREES_PER_SECOND).coerceIn(0f, 1f),
                (trackingError / FAST_TRACKING_ERROR_DEGREES).coerceIn(0f, 1f),
            )
            responseTimeConstant = SLOW_TIME_CONSTANT_SECONDS +
                (FAST_TIME_CONSTANT_SECONDS - SLOW_TIME_CONSTANT_SECONDS) * speedFactor
            val alpha = 1f - exp((-filterDtSeconds / responseTimeConstant).toDouble()).toFloat()

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
            lastFilterTimestampNanos = maxOf(lastFilterTimestampNanos, timestampNanos)
        }

        return Output(
            rawAngleDegrees = raw,
            filteredAngleDegrees = filteredAngle,
            filteredProgress = (filteredAngle / 180f).coerceIn(0f, 1f),
            opening = opening,
        )
    }

    /**
     * Finish the response when an ON_CHANGE sensor stops sending events.
     * This advances only rendered state. The actual sample timestamp, raw
     * velocity baseline, and direction anchor remain untouched.
     */
    fun settle(timestampNanos: Long): Output? {
        if (!hasPendingSettle || timestampNanos == Long.MIN_VALUE) return null
        val elapsed = elapsedFilterSeconds(timestampNanos)
        if (elapsed <= 0f) return null
        filteredAngle = FrameSmoothing.step(
            filteredAngle, previousRawAngle, elapsed,
            // Continue the same response between sensor samples. Switching to
            // 55 ms on every display frame after an 8 ms sensor update produced
            // alternating fast/slow motion even at a steady physical fold speed.
            responseTimeConstant, SETTLE_DEGREES,
        )
        lastFilterTimestampNanos = maxOf(lastFilterTimestampNanos, timestampNanos)
        return Output(previousRawAngle, filteredAngle, filteredAngle / 180f, opening,
            isSensorSample = false)
    }

    fun reset() {
        filteredAngle = Float.NaN
        previousRawAngle = Float.NaN
        directionAnchorAngle = Float.NaN
        previousTimestampNanos = Long.MIN_VALUE
        lastFilterTimestampNanos = Long.MIN_VALUE
        responseTimeConstant = SLOW_TIME_CONSTANT_SECONDS
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

    private fun elapsedFilterSeconds(timestampNanos: Long): Float {
        if (timestampNanos == Long.MIN_VALUE || lastFilterTimestampNanos == Long.MIN_VALUE) {
            return NOMINAL_DT_SECONDS
        }
        if (timestampNanos <= lastFilterTimestampNanos) return 0f
        return ((timestampNanos - lastFilterTimestampNanos) / NANOS_PER_SECOND)
            .toFloat().coerceAtMost(MAX_DT_SECONDS)
    }

    companion object {
        private const val SLOW_TIME_CONSTANT_SECONDS = 0.055f
        private const val FAST_TIME_CONSTANT_SECONDS = 0.008f
        private const val FAST_MOTION_DEGREES_PER_SECOND = 360f
        private const val FAST_TRACKING_ERROR_DEGREES = 12f
        private const val MAX_VISUAL_LAG_DEGREES = 5f
        private const val DIRECTION_DEADBAND_DEGREES = 0.25f
        private const val SETTLE_DEGREES = 0.02f

        private const val NOMINAL_DT_SECONDS = 1f / 60f
        private const val MIN_DT_SECONDS = 1f / 240f
        private const val MAX_DT_SECONDS = 0.05f
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
