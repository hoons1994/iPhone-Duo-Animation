package com.hoons1994.iphoneduoanimation

import kotlin.math.abs

/** Pure transition math shared by the UI and unit tests. */
object TransitionTuning {
    const val DEFAULT_HANDOFF_PROGRESS = 0.43f
    const val MIN_HANDOFF_PROGRESS = 0.25f
    const val MAX_HANDOFF_PROGRESS = 0.65f
    const val CALIBRATION_ALPHA = 0.30f
    const val FOCUS_HALF_WINDOW = 0.26f

    fun clampProgress(value: Float): Float = value.coerceIn(0f, 1f)

    fun updateHandoff(current: Float, observed: Float): Float {
        val safeCurrent = current.coerceIn(MIN_HANDOFF_PROGRESS, MAX_HANDOFF_PROGRESS)
        val safeObserved = observed.coerceIn(MIN_HANDOFF_PROGRESS, MAX_HANDOFF_PROGRESS)
        return (safeCurrent + (safeObserved - safeCurrent) * CALIBRATION_ALPHA)
            .coerceIn(MIN_HANDOFF_PROGRESS, MAX_HANDOFF_PROGRESS)
    }

    fun focusPeak(progress: Float, handoff: Float): Float {
        val distance = abs(clampProgress(progress) - handoff.coerceIn(0f, 1f))
        return 1f - smoothstep(0f, FOCUS_HALF_WINDOW, distance)
    }

    fun coverForPreview(progress: Float, handoff: Float): Boolean =
        clampProgress(progress) < handoff.coerceIn(0f, 1f)

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
