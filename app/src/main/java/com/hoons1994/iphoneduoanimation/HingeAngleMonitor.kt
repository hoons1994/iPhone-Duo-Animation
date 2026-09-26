package com.hoons1994.iphoneduoanimation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Choreographer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Selects the finest reporting hinge-angle sensor and converts it to display frames.
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
        var events = 0
        val distinct = LinkedHashSet<Int>()
        val resolution: Float
            get() = sensor.resolution.takeIf { it.isFinite() && it > 0f } ?: 1f
        val standard: Boolean
            get() = sensor.type == Sensor.TYPE_HINGE_ANGLE
        val coarse: Boolean
            get() = HingeSensorQuality.isCoarse(resolution, events, distinct)

        fun observe(value: Float) {
            events++
            if (distinct.size < MAX_DISTINCT_ANGLES) distinct += value.roundToInt()
        }

        fun resetSession() {
            registered = false
            events = 0
            distinct.clear()
        }
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
    private var coarseOpening = true
    private var lastFrameNanos = 0L

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
        candidate.observe(value)

        val previousActive = active
        val best = candidates.asSequence()
            .filter { it.registered && it.events > 0 }
            .minWithOrNull(compareBy<Candidate> { it.resolution }.thenBy { !it.standard })
            ?: return
        active = best
        if (active !== candidate) return
        if (previousActive !== active) {
            signalFilter.reset()
            resetCoarseFollower()
        }

        val angle = value.coerceIn(0f, 180f)
        if (candidate.coarse) {
            followCoarseAngle(angle)
        } else {
            onAngleChanged(signalFilter.update(angle, event.timestamp))
            scheduleFollowFrame()
        }
    }

    private fun followCoarseAngle(angle: Float) {
        if (!coarseCurrent.isFinite()) {
            coarseCurrent = angle
            coarseTarget = angle
            emitCoarse(isSensorSample = true)
            return
        }
        if (angle != coarseTarget) {
            coarseOpening = angle > coarseTarget
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
        if (active?.coarse != true) {
            // SensorEvent.timestamp uses elapsedRealtimeNanos. Choreographer's
            // frame clock has a different sleep basis, so do not mix the two.
            signalFilter.settle(SystemClock.elapsedRealtimeNanos())?.let(onAngleChanged)
            scheduleFollowFrame()
            return
        }
        if (!coarseCurrent.isFinite() || !coarseTarget.isFinite()) return
        val dt = if (lastFrameNanos == 0L) 1f / 60f else
            (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = frameTimeNanos
        coarseCurrent = FrameSmoothing.step(
            coarseCurrent,
            coarseTarget,
            dt,
            COARSE_TIME_CONSTANT_SECONDS,
            COARSE_SETTLE_DEGREES,
        )
        emitCoarse(isSensorSample = false)
        if (coarseCurrent != coarseTarget) scheduleFollowFrame() else lastFrameNanos = 0L
    }

    private fun emitCoarse(isSensorSample: Boolean) {
        onAngleChanged(
            HingeSignalFilter.Output(
                rawAngleDegrees = coarseTarget,
                filteredAngleDegrees = coarseCurrent,
                filteredProgress = (coarseCurrent / 180f).coerceIn(0f, 1f),
                opening = coarseOpening,
                isSensorSample = isSensorSample,
            ),
        )
    }

    private fun scheduleFollowFrame() {
        if (framePending || !started) return
        val needsFrame = if (active?.coarse == true) {
            coarseCurrent.isFinite() && coarseTarget.isFinite() && coarseCurrent != coarseTarget
        } else signalFilter.hasPendingSettle
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
        coarseOpening = true
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
        const val MAX_DISTINCT_ANGLES = 8
        const val COARSE_TIME_CONSTANT_SECONDS = 0.12f
        const val COARSE_SETTLE_DEGREES = 0.02f
    }
}
