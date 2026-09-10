package com.hoons1994.iphoneduoanimation

import kotlin.math.abs

/** Pure transition math shared by the UI and unit tests. */
object TransitionTuning {
    const val DEFAULT_HANDOFF_PROGRESS = 0.43f
    const val MIN_HANDOFF_PROGRESS = 0.25f
    const val MAX_HANDOFF_PROGRESS = 0.65f

    const val CALIBRATION_ALPHA = 0.30f
    const val CALIBRATION_OUTLIER_THRESHOLD = 0.075f
    const val CALIBRATION_HISTORY_LIMIT = 7
    const val MAX_SURFACE_EVENT_AGE_MS = 400L

    // Roughly 32 degrees on either side of the learned handoff. The shader
    // latches at full focus-loss if the old physical display persists past
    // the expected switch, then resolves only after the new surface appears.
    const val FOCUS_HALF_WINDOW = 0.18f

    fun clampProgress(value: Float): Float = value.coerceIn(0f, 1f)

    fun updateHandoff(current: Float, observed: Float): Float {
        val safeCurrent = current.coerceIn(MIN_HANDOFF_PROGRESS, MAX_HANDOFF_PROGRESS)
        val safeObserved = observed.coerceIn(MIN_HANDOFF_PROGRESS, MAX_HANDOFF_PROGRESS)
        return (safeCurrent + (safeObserved - safeCurrent) * CALIBRATION_ALPHA)
            .coerceIn(MIN_HANDOFF_PROGRESS, MAX_HANDOFF_PROGRESS)
    }

    /** Symmetric diagnostic envelope retained for tests and status summaries. */
    fun focusPeak(progress: Float, handoff: Float): Float {
        val distance = abs(clampProgress(progress) - handoff.coerceIn(0f, 1f))
        return 1f - smoothstep(0f, FOCUS_HALF_WINDOW, distance)
    }

    /**
     * Focus envelope for the currently active physical surface.
     *
     * Cover: sharp while closed, rising to maximum blur at the handoff and
     * staying maximally masked if One UI delays the switch.
     * Inner: maximally masked when it first appears at the handoff, then
     * resolving as the device continues opening. Reversing hinge direction
     * naturally reverses the same envelopes for closing.
     */
    fun surfaceFocus(progress: Float, handoff: Float, coverSurface: Boolean): Float {
        val t = clampProgress(progress)
        val h = handoff.coerceIn(MIN_HANDOFF_PROGRESS, MAX_HANDOFF_PROGRESS)
        return if (coverSurface) {
            smoothstep(h - FOCUS_HALF_WINDOW, h, t)
        } else {
            1f - smoothstep(h, h + FOCUS_HALF_WINDOW, t)
        }
    }

    fun coverForPreview(progress: Float, handoff: Float): Boolean =
        clampProgress(progress) < handoff.coerceIn(0f, 1f)

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
