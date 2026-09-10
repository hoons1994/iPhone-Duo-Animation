package com.hoons1994.iphoneduoanimation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class HingeAngleMonitor(
    context: Context,
    private val onAngleChanged: (HingeSignalFilter.Output) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private val signalFilter = HingeSignalFilter()
    private var registered = false

    val isAvailable: Boolean
        get() = hingeSensor != null

    fun start() {
        if (registered) return
        val sensor = hingeSensor ?: return
        signalFilter.reset()
        registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE || event.values.isEmpty()) return
        onAngleChanged(signalFilter.update(event.values[0]))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
