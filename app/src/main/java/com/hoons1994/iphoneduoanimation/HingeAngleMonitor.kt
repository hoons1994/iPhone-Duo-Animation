package com.hoons1994.iphoneduoanimation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Choreographer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Selects the best observed hinge-angle stream and converts it to display frames.
 *
 * Samsung devices can expose permission-gated vendor sensors and a public sensor
 * that reports only 0/90/180. Permission failures are ignored. A proven coarse
 * stream is eased between stops instead of drawing three abrupt frames.
 */
class HingeAngleMonitor(
    context: Context,
    private val onAngleChanged: (HingeSignalFilter.Output) -> Unit,
) : SensorEventListener, Choreographer.FrameCallback {

    private class Candidate(val sensor: Sensor) {
        var registered = false
        var sessionEvents = 0
        private var previousSessionAngle = Float.NaN
        var hasIntermediateAngles = false
            private set
        private var hasStopTransition = false
        val resolution: Float
            get() = sensor.resolution.takeIf { it.isFinite() && it > 0f } ?: 1f
        val standard: Boolean
            get() = sensor.type == Sensor.TYPE_HINGE_ANGLE
        val coarse: Boolean
            get() = !hasIntermediateAngles &&
                (resolution >= HingeSensorQuality.COARSE_RESOLUTION_DEGREES || hasStopTransition)
        val qualityRank: Int
            get() = when {
                hasIntermediateAngles -> 0
                coarse -> 2
                else -> 1
            }

        fun observe(value: Float) {
            if (sessionEvents < Int.MAX_VALUE) sessionEvents++
            if (!isStop(value)) hasIntermediateAngles = true
            if (previousSessionAngle.isFinite() && isStop(previousSessionAngle) && isStop(value) &&
                abs(value - previousSessionAngle) >= MIN_STOP_JUMP_DEGREES) {
                hasStopTransition = true
            }
            previousSessionAngle = value
        }

        fun resetSession() {
            registered = false
            sessionEvents = 0
            previousSessionAngle = Float.NaN
            // Stream quality survives a launcher stop/start. Repeated samples
            // at a stationary endpoint alone are not evidence of quantization.
        }

        private fun isStop(value: Float) = STOPS.any { abs(value - it) <= STOP_SLOP_DEGREES }
    }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val candidates = discover().map(::Candidate)
    private val signalFilter = HingeSignalFilter()
    private var active: Candidate? = null
    private var started = false
    private var registrationSucceeded: Boolean? = null
    private var framePending = false
    private var coarseCurrent = Float.NaN
    private var coarseTarget = Float.NaN
    private var coarseSampleTimestampNanos = Long.MIN_VALUE
    private var usingCoarseFollower = false
    private var fineBridgeCurrent = Float.NaN
    private var latestFineOutput: HingeSignalFilter.Output? = null
    private var lastOutput: HingeSignalFilter.Output? = null
    private var directionAnchorAngle = Float.NaN
    private var opening = true
    private var lastFrameNanos = 0L
    private val candidateOrder = compareBy<Candidate> { it.qualityRank }
        .thenBy { it.resolution }.thenBy { !it.standard }

    val isAvailable: Boolean
        get() = registrationSucceeded ?: candidates.isNotEmpty()

    val isCoarse: Boolean
        get() = (active ?: candidates.firstOrNull())?.coarse == true

    fun description(): String {
        val selected = active ?: candidates.firstOrNull() ?: return "hinge sensor unavailable"
        val resolution = if (selected.resolution >= 1f) {
            "${selected.resolution.roundToInt()}°"
        } else String.format(Locale.US, "%.2f°", selected.resolution)
        return "${selected.sensor.name.trim()} · $resolution${if (selected.coarse) " · stepped" else ""}"
    }

    fun start() {
        if (started) return
        started = true
        active = null
        candidates.forEach(Candidate::resetSession)
        signalFilter.reset()
        resetCoarseFollower()
        usingCoarseFollower = false
        fineBridgeCurrent = Float.NaN
        latestFineOutput = null
        lastOutput = null
        directionAnchorAngle = Float.NaN
        opening = true
        for (candidate in candidates) {
            candidate.registered = try {
                sensorManager.registerListener(
                    this,
                    candidate.sensor,
                    SAMPLING_PERIOD_US,
                    MAX_REPORT_LATENCY_US,
                )
            } catch (_: SecurityException) {
                false
            }
        }
        registrationSucceeded = candidates.any { it.registered }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
        candidates.forEach { it.registered = false }
        cancelFollowFrame()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started || event.values.isEmpty()) return
        val value = event.values[0]
        if (!value.isFinite() || value !in -PLAUSIBLE_SLACK_DEGREES..(180f + PLAUSIBLE_SLACK_DEGREES)) {
            return
        }
        val candidate = candidates.firstOrNull { it.sensor == event.sensor } ?: return
        if (!candidate.registered) return
        val angle = value.coerceIn(0f, 180f)
        candidate.observe(angle)

        val previousActive = active
        if (previousActive != null && previousActive !== candidate &&
            candidateOrder.compare(candidate, previousActive) >= 0) return
        // A source may take over only when it supplies a sample this session.
        // Equal ranks retain the active source instead of alternating events.
        active = candidate
        updateDirection(angle)
        if (previousActive !== candidate || usingCoarseFollower != candidate.coarse) {
            val previousPose = lastOutput
            signalFilter.reset()
            resetCoarseFollower()
            fineBridgeCurrent = Float.NaN
            latestFineOutput = null
            usingCoarseFollower = candidate.coarse
            if (usingCoarseFollower) {
                coarseCurrent = previousPose?.filteredAngleDegrees ?: angle
                coarseTarget = previousPose?.rawAngleDegrees ?: angle
            } else {
                val output = signalFilter.update(angle, event.timestamp).copy(opening = opening)
                latestFineOutput = output
                if (previousPose != null && abs(previousPose.filteredAngleDegrees - angle) > COARSE_SETTLE_DEGREES) {
                    // Keep this exact displayed pose when a stepped stream
                    // reveals continuous data or a better sensor takes over.
                    // The short bridge exists only for this source/mode switch.
                    fineBridgeCurrent = previousPose.filteredAngleDegrees
                }
                emitFine(output)
                scheduleFollowFrame()
                return
            }
        }

        if (usingCoarseFollower) {
            followCoarseAngle(angle, event.timestamp)
        } else {
            emitFine(signalFilter.update(angle, event.timestamp).copy(opening = opening))
            scheduleFollowFrame()
        }
    }

    private fun followCoarseAngle(angle: Float, sampleTimestampNanos: Long) {
        coarseSampleTimestampNanos = sampleTimestampNanos
        if (!coarseCurrent.isFinite()) {
            coarseCurrent = angle
            coarseTarget = angle
            emitCoarse(isSensorSample = true)
            return
        }
        if (angle != coarseTarget) {
            coarseTarget = angle
        }
        // Deliver the real raw angle immediately, even while the visual follower
        // is still between stops, so handoff calibration uses fresh sensor data.
        emitCoarse(isSensorSample = true)
        scheduleFollowFrame()
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePending = false
        if (!started) return
        if (!usingCoarseFollower) {
            // SensorEvent.timestamp uses elapsedRealtimeNanos. Choreographer's
            // frame clock has a different sleep basis, so do not mix the two.
            val settled = signalFilter.settle(SystemClock.elapsedRealtimeNanos())?.copy(opening = opening)
            if (settled != null) latestFineOutput = settled
            if (fineBridgeCurrent.isFinite()) {
                val output = latestFineOutput ?: return
                val dt = frameDeltaSeconds(frameTimeNanos)
                if (dt > 0f) {
                    fineBridgeCurrent = FrameSmoothing.step(fineBridgeCurrent, output.filteredAngleDegrees,
                        dt, FINE_BRIDGE_TIME_CONSTANT_SECONDS, COARSE_SETTLE_DEGREES)
                }
                emitFine(output.copy(isSensorSample = false))
                if (fineBridgeCurrent == output.filteredAngleDegrees) {
                    fineBridgeCurrent = Float.NaN
                    lastFrameNanos = 0L
                }
            } else if (settled != null) emitOutput(settled)
            scheduleFollowFrame()
            return
        }
        if (!coarseCurrent.isFinite() || !coarseTarget.isFinite()) return
        val dt = frameDeltaSeconds(frameTimeNanos)
        if (dt > 0f) {
            coarseCurrent = FrameSmoothing.step(
                coarseCurrent,
                coarseTarget,
                dt,
                COARSE_TIME_CONSTANT_SECONDS,
                COARSE_SETTLE_DEGREES,
            )
        }
        emitCoarse(isSensorSample = false)
        if (coarseCurrent != coarseTarget) scheduleFollowFrame() else lastFrameNanos = 0L
    }

    private fun emitCoarse(isSensorSample: Boolean) {
        emitOutput(
            HingeSignalFilter.Output(
                rawAngleDegrees = coarseTarget,
                filteredAngleDegrees = coarseCurrent,
                filteredProgress = (coarseCurrent / 180f).coerceIn(0f, 1f),
                opening = opening,
                isSensorSample = isSensorSample,
                sampleTimestampNanos = coarseSampleTimestampNanos,
            ),
        )
    }

    private fun emitFine(output: HingeSignalFilter.Output) {
        latestFineOutput = output
        val visual = fineBridgeCurrent.takeIf { it.isFinite() } ?: output.filteredAngleDegrees
        emitOutput(output.copy(filteredAngleDegrees = visual, filteredProgress = visual / 180f))
    }

    private fun emitOutput(output: HingeSignalFilter.Output) {
        lastOutput = output
        onAngleChanged(output)
    }

    private fun updateDirection(angle: Float) {
        if (!directionAnchorAngle.isFinite()) directionAnchorAngle = angle
        val displacement = angle - directionAnchorAngle
        if (abs(displacement) >= DIRECTION_DEADBAND_DEGREES) {
            opening = displacement > 0f
            directionAnchorAngle = angle
        }
    }

    private fun frameDeltaSeconds(frameTimeNanos: Long): Float {
        if (lastFrameNanos == 0L) {
            // Establish this follower's frame clock without inventing a 60 Hz
            // first step. The next callback advances by its actual interval.
            lastFrameNanos = frameTimeNanos
            return 0f
        }
        val dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = maxOf(lastFrameNanos, frameTimeNanos)
        // Callers hold at zero instead of invoking FrameSmoothing, whose
        // minimum timestep would otherwise turn a held frame into movement.
        return dt.coerceAtLeast(0f)
    }

    private fun scheduleFollowFrame() {
        if (framePending || !started) return
        val needsFrame = if (usingCoarseFollower) {
            coarseCurrent.isFinite() && coarseTarget.isFinite() && coarseCurrent != coarseTarget
        } else signalFilter.hasPendingSettle || fineBridgeCurrent.isFinite()
        if (!needsFrame) return
        framePending = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun cancelFollowFrame() {
        if (framePending) Choreographer.getInstance().removeFrameCallback(this)
        framePending = false
        lastFrameNanos = 0L
    }

    private fun resetCoarseFollower() {
        cancelFollowFrame()
        coarseCurrent = Float.NaN
        coarseTarget = Float.NaN
        coarseSampleTimestampNanos = Long.MIN_VALUE
    }

    private fun discover(): List<Sensor> {
        val all = runCatching { sensorManager.getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList())
        val standard = all.filter { it.type == Sensor.TYPE_HINGE_ANGLE }
            .ifEmpty { listOfNotNull(sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)) }
            .sortedBy { it.isWakeUpSensor }
        val vendor = all.filter { sensor ->
            if (sensor.type < Sensor.TYPE_DEVICE_PRIVATE_BASE) return@filter false
            val label = "${sensor.name} ${sensor.stringType}".lowercase()
            val foldRelated = label.contains("hinge") || label.contains("fold")
            foldRelated && sensor.maximumRange.isFinite() && sensor.maximumRange in 150f..360f &&
                (sensor.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS ||
                    sensor.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE)
        }
        return (standard + vendor).distinctBy { "${it.type}|${it.name}|${it.isWakeUpSensor}" }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SAMPLING_PERIOD_US = 8_000
        const val MAX_REPORT_LATENCY_US = 0
        const val PLAUSIBLE_SLACK_DEGREES = 5f
        const val MIN_STOP_JUMP_DEGREES = 45f
        const val STOP_SLOP_DEGREES = 2f
        val STOPS = floatArrayOf(0f, 90f, 180f)
        const val DIRECTION_DEADBAND_DEGREES = 0.25f
        const val COARSE_TIME_CONSTANT_SECONDS = 0.12f
        const val COARSE_SETTLE_DEGREES = 0.02f
        const val FINE_BRIDGE_TIME_CONSTANT_SECONDS = 0.024f
    }
}
