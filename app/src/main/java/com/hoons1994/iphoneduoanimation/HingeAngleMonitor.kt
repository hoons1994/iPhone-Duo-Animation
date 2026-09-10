package com.hoons1994.iphoneduoanimation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

class HingeAngleMonitor(
    context: Context,
    private val onAngleChanged: (angleDegrees: Float, progress: Float, opening: Boolean) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    private var smoothedAngle = Float.NaN
    private var previousAngle = Float.NaN
    private var opening = true

    val isAvailable: Boolean
        get() = hingeSensor != null

    fun start() {
        val sensor = hingeSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE || event.values.isEmpty()) return

        val raw = event.values[0].coerceIn(0f, 180f)
        smoothedAngle = if (smoothedAngle.isNaN()) {
            raw
        } else {
            smoothedAngle + (raw - smoothedAngle) * SMOOTHING_ALPHA
        }

        if (!previousAngle.isNaN()) {
            val delta = smoothedAngle - previousAngle
            if (abs(delta) >= DIRECTION_DEADBAND_DEGREES) {
                opening = delta > 0f
            }
        }
        previousAngle = smoothedAngle

        onAngleChanged(
            smoothedAngle,
            (smoothedAngle / 180f).coerceIn(0f, 1f),
            opening,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val SMOOTHING_ALPHA = 0.22f
        private const val DIRECTION_DEADBAND_DEGREES = 0.25f
    }
}
