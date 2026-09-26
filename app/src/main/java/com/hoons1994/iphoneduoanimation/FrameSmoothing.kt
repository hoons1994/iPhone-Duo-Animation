package com.hoons1994.iphoneduoanimation

import kotlin.math.abs
import kotlin.math.exp

/** Frame-rate-independent, causal following with no prediction or overshoot. */
object FrameSmoothing {
    fun step(
        current: Float,
        target: Float,
        dtSeconds: Float,
        timeConstantSeconds: Float,
        settleEpsilon: Float,
    ): Float {
        if (!target.isFinite()) return current
        if (!current.isFinite()) return target
        val tau = timeConstantSeconds.coerceAtLeast(0.001f)
        val dt = dtSeconds.coerceIn(0.001f, 0.05f)
        val next = current + (target - current) *
            (1f - exp((-dt / tau).toDouble()).toFloat())
        if (abs(target - next) < settleEpsilon) return target
        return next.coerceIn(minOf(current, target), maxOf(current, target))
    }
}
