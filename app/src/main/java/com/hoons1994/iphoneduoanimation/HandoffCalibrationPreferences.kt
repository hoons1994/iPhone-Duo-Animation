package com.hoons1994.iphoneduoanimation

import android.content.Context

/** Reuses the calibration learned by previous in-app builds. No screenshots are persisted. */
internal class HandoffCalibrationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("duo_transition_tuning", Context.MODE_PRIVATE)

    fun load() = HandoffCalibrator(
        initialOpening = preferences.getFloat("opening_handoff_progress", TransitionTuning.DEFAULT_HANDOFF_PROGRESS),
        initialClosing = preferences.getFloat("closing_handoff_progress", TransitionTuning.DEFAULT_HANDOFF_PROGRESS),
        initialOpeningHistory = history("opening_handoff_history"),
        initialClosingHistory = history("closing_handoff_history"),
        initialOpeningAcceptedCount = preferences.getInt("opening_handoff_count", 0),
        initialClosingAcceptedCount = preferences.getInt("closing_handoff_count", 0),
    )

    fun save(calibrator: HandoffCalibrator) {
        val edit = preferences.edit()
        for ((prefix, opening) in listOf("opening" to true, "closing" to false)) {
            val state = calibrator.state(opening)
            edit.putFloat("${prefix}_handoff_progress", state.estimate)
                .putString("${prefix}_handoff_history", state.recentSamples.joinToString(","))
                .putInt("${prefix}_handoff_count", state.acceptedCount)
        }
        edit.apply()
    }

    private fun history(key: String): List<Float> = preferences.getString(key, null)
        ?.split(',')?.mapNotNull { it.toFloatOrNull() }.orEmpty()
}
