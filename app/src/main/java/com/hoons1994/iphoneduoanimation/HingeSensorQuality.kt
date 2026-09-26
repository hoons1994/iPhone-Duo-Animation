package com.hoons1994.iphoneduoanimation

import kotlin.math.abs

/** Pure classification for public hinge sensors that expose only fixed stops. */
object HingeSensorQuality {
    fun isCoarse(
        declaredResolution: Float,
        eventCount: Int,
        distinctRoundedAngles: Collection<Int>,
    ): Boolean = declaredResolution.isFinite() && declaredResolution >= COARSE_RESOLUTION_DEGREES ||
        eventCount >= MIN_EVENTS && distinctRoundedAngles.size <= 3 &&
        distinctRoundedAngles.all { value -> STOPS.any { abs(value - it) <= STOP_SLOP_DEGREES } }

    const val COARSE_RESOLUTION_DEGREES = 45f
    private const val MIN_EVENTS = 6
    private const val STOP_SLOP_DEGREES = 2
    private val STOPS = intArrayOf(0, 90, 180)
}
