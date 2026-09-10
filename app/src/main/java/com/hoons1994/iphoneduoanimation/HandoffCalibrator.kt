package com.hoons1994.iphoneduoanimation

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min

/**
 * Learns the physical cover/inner display switch separately for opening and closing.
 *
 * Surface changes are noisy signals: configuration changes can happen late, and a single
 * misclassified resize must not permanently move the handoff point. Keep a short rolling
 * history, reject implausible jumps, and move the estimate toward the median observation.
 */
class HandoffCalibrator(
    initialOpening: Float = TransitionTuning.DEFAULT_HANDOFF_PROGRESS,
    initialClosing: Float = TransitionTuning.DEFAULT_HANDOFF_PROGRESS,
) {

    enum class RejectReason {
        WRONG_SURFACE_DIRECTION,
        OUT_OF_RANGE,
        OUTLIER,
    }

    data class ObservationResult(
        val accepted: Boolean,
        val estimate: Float,
        val confidence: Float,
        val sampleCount: Int,
        val rejectReason: RejectReason? = null,
    )

    private data class Channel(
        var estimate: Float,
        val history: ArrayDeque<Float> = ArrayDeque(),
        var acceptedCount: Int = 0,
    )

    private val opening = Channel(clampEstimate(initialOpening))
    private val closing = Channel(clampEstimate(initialClosing))

    fun estimate(isOpening: Boolean): Float = channel(isOpening).estimate

    fun sampleCount(isOpening: Boolean): Int = channel(isOpening).acceptedCount

    fun confidence(isOpening: Boolean): Float = confidence(channel(isOpening))

    fun observe(
        isOpening: Boolean,
        fromCover: Boolean,
        toCover: Boolean,
        progress: Float,
    ): ObservationResult {
        val target = channel(isOpening)

        val expectedSurfaceDirection = if (isOpening) {
            fromCover && !toCover
        } else {
            !fromCover && toCover
        }
        if (!expectedSurfaceDirection) {
            return rejected(target, RejectReason.WRONG_SURFACE_DIRECTION)
        }

        if (progress !in TransitionTuning.MIN_HANDOFF_PROGRESS..TransitionTuning.MAX_HANDOFF_PROGRESS) {
            return rejected(target, RejectReason.OUT_OF_RANGE)
        }

        if (target.history.size >= MIN_HISTORY_FOR_OUTLIER_REJECTION) {
            val center = median(target.history)
            if (abs(progress - center) > TransitionTuning.CALIBRATION_OUTLIER_THRESHOLD) {
                return rejected(target, RejectReason.OUTLIER)
            }
        }

        target.history.addLast(progress)
        while (target.history.size > TransitionTuning.CALIBRATION_HISTORY_LIMIT) {
            target.history.removeFirst()
        }

        val robustTarget = median(target.history)
        target.estimate = TransitionTuning.updateHandoff(target.estimate, robustTarget)
        target.acceptedCount += 1

        return ObservationResult(
            accepted = true,
            estimate = target.estimate,
            confidence = confidence(target),
            sampleCount = target.acceptedCount,
        )
    }

    private fun rejected(channel: Channel, reason: RejectReason): ObservationResult =
        ObservationResult(
            accepted = false,
            estimate = channel.estimate,
            confidence = confidence(channel),
            sampleCount = channel.acceptedCount,
            rejectReason = reason,
        )

    private fun channel(isOpening: Boolean): Channel = if (isOpening) opening else closing

    private fun clampEstimate(value: Float): Float = value.coerceIn(
        TransitionTuning.MIN_HANDOFF_PROGRESS,
        TransitionTuning.MAX_HANDOFF_PROGRESS,
    )

    private fun confidence(channel: Channel): Float {
        if (channel.acceptedCount <= 0 || channel.history.isEmpty()) return 0f
        val sampleScore = min(channel.acceptedCount / 4f, 1f)
        val center = median(channel.history)
        val deviations = channel.history.map { abs(it - center) }
        val mad = median(deviations)
        val spreadScore = 1f - (mad / TransitionTuning.CALIBRATION_OUTLIER_THRESHOLD)
            .coerceIn(0f, 1f)
        return sampleScore * spreadScore
    }

    private fun median(values: Iterable<Float>): Float {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return TransitionTuning.DEFAULT_HANDOFF_PROGRESS
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) * 0.5f
        }
    }

    companion object {
        private const val MIN_HISTORY_FOR_OUTLIER_REJECTION = 3
    }
}
