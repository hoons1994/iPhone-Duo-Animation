package com.hoons1994.iphoneduoanimation

import kotlin.math.abs
import kotlin.math.PI

/** Pure transition math shared by the UI and unit tests. */
object TransitionTuning {
    // duo-fold-live's device-tested Galaxy Fold handoff is 98 degrees. The
    // adaptive calibrator replaces this default with the current device's value.
    const val DEFAULT_HANDOFF_PROGRESS = 0.54444444f
    const val MIN_HANDOFF_PROGRESS = 0.25f
    const val MAX_HANDOFF_PROGRESS = 0.65f

    const val CALIBRATION_ALPHA = 0.30f
    const val CALIBRATION_OUTLIER_THRESHOLD = 0.075f
    const val CALIBRATION_HISTORY_LIMIT = 7

    // Hinge delivery is requested unbatched at ~120 Hz. If the main thread has
    // not seen a hinge update for more than this, skipping one calibration
    // sample is safer than permanently learning an angle from a stale value.
    const val MAX_SURFACE_EVENT_AGE_MS = 120L

    // Roughly 32 degrees on either side of the learned handoff. The shader
    // latches at full focus-loss if the old physical display persists past
    // the expected switch, then resolves only after the new surface appears.
    const val FOCUS_HALF_WINDOW = 0.18f

    // Retained for compatibility with the earlier transition-math checks. The
    // reference renderer no longer blends cover and inner snapshots.
    const val SOURCE_BRIDGE_HALF_WINDOW = 0.10f
    const val SOURCE_BRIDGE_LATCH_HALF_WIDTH = 0.035f

    const val REFERENCE_CLOSED_HINGE_DEGREES = 6f
    const val REFERENCE_INNER_CLEAR_OPENING_DEGREES = 172f
    // The same physical angle must render the same glass on a direction reversal.
    // Opening and closing may retain separate learned panel-handoff positions.
    const val REFERENCE_INNER_CLEAR_CLOSING_DEGREES = REFERENCE_INNER_CLEAR_OPENING_DEGREES
    const val REFERENCE_BLUR_RADIUS_PX = 72f
    const val REFERENCE_INNER_WIDTH_PX = 1600f
    const val REFERENCE_COVER_WIDTH_PX = 774f
    const val REFERENCE_MAX_TILT_DEGREES = 45f
    // The high-interest Duo ports place the fixed UI plane about 30-32 cm
    // from the viewer; keep the ray-plane perspective close to that reference.
    const val REFERENCE_EYE_DISTANCE_MM = 320f
    const val REFERENCE_BLUR_SPREAD = 0.12f
    const val REFERENCE_DARKENING = 0.015f
    const val REFERENCE_PIXELS_PER_MM = 6f

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

    /**
     * Blend amount from cover snapshot (0) to inner snapshot (1).
     *
     * The cover surface never advances beyond 50%, and the inner surface never
     * falls below 50%. Around the expected handoff both therefore render the
     * identical 50/50 mixture, hiding source-image discontinuity at the physical
     * screen switch without leaving a long-lived ghosted cross-fade.
     */
    fun sourceBlend(progress: Float, handoff: Float, coverSurface: Boolean): Float {
        val t = clampProgress(progress)
        val h = handoff.coerceIn(MIN_HANDOFF_PROGRESS, MAX_HANDOFF_PROGRESS)
        val leftEdge = h - SOURCE_BRIDGE_HALF_WINDOW
        val leftLatch = h - SOURCE_BRIDGE_LATCH_HALF_WIDTH
        val rightLatch = h + SOURCE_BRIDGE_LATCH_HALF_WIDTH
        val rightEdge = h + SOURCE_BRIDGE_HALF_WINDOW

        return if (coverSurface) {
            0.5f * smoothstep(leftEdge, leftLatch, t)
        } else {
            0.5f + 0.5f * smoothstep(rightLatch, rightEdge, t)
        }
    }

    fun coverForPreview(progress: Float, handoff: Float): Boolean =
        clampProgress(progress) < handoff.coerceIn(0f, 1f)

    fun referenceCoverBlur(progress: Float, handoff: Float): Float {
        val t = clampProgress(progress)
        val h = handoff.coerceIn(MIN_HANDOFF_PROGRESS, MAX_HANDOFF_PROGRESS)
        val closed = REFERENCE_CLOSED_HINGE_DEGREES / 180f
        return ((t - closed) / (h - closed).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
    }

    fun referenceInnerPhase(
        progress: Float,
        opening: Boolean = true,
        handoff: Float = DEFAULT_HANDOFF_PROGRESS,
    ): Float {
        val clearAngle = if (opening) {
            REFERENCE_INNER_CLEAR_OPENING_DEGREES
        } else {
            REFERENCE_INNER_CLEAR_CLOSING_DEGREES
        }
        val angle = clampProgress(progress) * 180f
        val handoffAngle = handoff.coerceIn(MIN_HANDOFF_PROGRESS, MAX_HANDOFF_PROGRESS) * 180f
        val phase = ((clearAngle - angle) /
            (clearAngle - handoffAngle).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        return phase
    }

    fun referenceMovingPanelBlur(
        progress: Float,
        opening: Boolean = true,
        handoff: Float = DEFAULT_HANDOFF_PROGRESS,
    ): Float = referenceInnerPhase(progress, opening, handoff)

    fun referenceExpandedLayout(progress: Float, opening: Boolean = true): Float =
        1f - referenceMovingPanelBlur(progress, opening)

    fun referenceMaxBlurPx(axisExtentPx: Int, coverSurface: Boolean): Float {
        val referenceWidth = if (coverSurface) {
            REFERENCE_COVER_WIDTH_PX
        } else {
            REFERENCE_INNER_WIDTH_PX
        }
        return REFERENCE_BLUR_RADIUS_PX * axisExtentPx.coerceAtLeast(1) / referenceWidth
    }

    fun referenceFoldRadians(
        progress: Float,
        coverSurface: Boolean,
        opening: Boolean,
        handoff: Float = DEFAULT_HANDOFF_PROGRESS,
    ): Float = if (coverSurface) {
        referenceCoverBlur(progress, handoff) *
            (REFERENCE_MAX_TILT_DEGREES / 180f * PI.toFloat())
    } else {
        referenceInnerPhase(progress, opening, handoff) *
            (REFERENCE_MAX_TILT_DEGREES / 180f * PI.toFloat())
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
