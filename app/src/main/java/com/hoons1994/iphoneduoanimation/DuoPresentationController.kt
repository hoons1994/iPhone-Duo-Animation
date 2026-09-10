package com.hoons1994.iphoneduoanimation

import android.app.Activity
import android.app.Presentation
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager

class DuoPresentationController(private val activity: Activity) {

    private val displayManager = activity.getSystemService(DisplayManager::class.java)
    private var presentation: SnapshotPresentation? = null
    private var statusListener: ((String) -> Unit)? = null

    fun describeDisplays(): String {
        val currentId = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        val presentationIds = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { it.displayId }
            .toSet()
        val builtInIds = runCatching {
            // Android 17 / API 37 adds this category. Use the literal so the
            // diagnostic can still run on older platform releases.
            displayManager.getDisplays(BUILT_IN_DISPLAY_CATEGORY)
                .map { it.displayId }
                .toSet()
        }.getOrDefault(emptySet())

        val all = displayManager.displays.joinToString(separator = " | ") { display ->
            describeDisplay(display, currentId, presentationIds, builtInIds)
        }.ifBlank { "none" }

        val builtIns = runCatching {
            displayManager.getDisplays(BUILT_IN_DISPLAY_CATEGORY)
                .joinToString(separator = " | ") { display ->
                    describeDisplay(display, currentId, presentationIds, builtInIds)
                }
        }.getOrDefault("").ifBlank { "none" }

        return "all[$all] · built-in[$builtIns]"
    }

    fun tryShow(
        cover: Bitmap,
        inner: Bitmap,
        progress: Float,
        opening: Boolean,
        onStatus: (String) -> Unit,
    ) {
        statusListener = onStatus
        val currentId = activity.display?.displayId ?: Display.DEFAULT_DISPLAY

        val presentationCandidates = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != currentId }

        // API 37 can expose inactive/disabled built-in displays here even when
        // getDisplays() only reports the currently active logical display.
        val builtInDisplays = runCatching {
            displayManager.getDisplays(BUILT_IN_DISPLAY_CATEGORY)
                .filter { it.displayId != currentId }
        }.getOrDefault(emptyList())

        val builtInPresentationCandidates = builtInDisplays.filter {
            (it.flags and Display.FLAG_PRESENTATION) != 0
        }

        val candidates = (presentationCandidates + builtInPresentationCandidates)
            .distinctBy { it.displayId }

        if (candidates.isEmpty()) {
            val reason = if (builtInDisplays.isNotEmpty()) {
                "other built-in display found, but it is not presentation-capable"
            } else {
                "no other built-in/presentation display exposed"
            }
            onStatus("Presentation unavailable · $reason\n${describeDisplays()}")
            return
        }

        val target = candidates.first()
        presentation?.dismiss()
        presentation = null

        val next = SnapshotPresentation(
            activity = activity,
            targetDisplay = target,
            cover = cover,
            inner = inner,
            progress = progress,
            opening = opening,
            onStopped = {
                if (presentation === it) {
                    presentation = null
                    statusListener?.invoke("Presentation stopped by system\n${describeDisplays()}")
                }
            },
        )

        try {
            next.show()
            presentation = next
            onStatus(
                "Presentation active on display ${target.displayId} · ${target.name}" +
                    " · ${stateLabel(target.state)} · flags=0x${target.flags.toString(16)}"
            )
        } catch (error: WindowManager.InvalidDisplayException) {
            onStatus(
                "Presentation rejected: ${error.message ?: "InvalidDisplay"}\n${describeDisplays()}"
            )
        } catch (error: SecurityException) {
            onStatus(
                "Presentation blocked by policy: ${error.message ?: "SecurityException"}\n${describeDisplays()}"
            )
        } catch (error: RuntimeException) {
            onStatus(
                "Presentation failed: ${error.javaClass.simpleName} · ${error.message ?: "unknown"}\n" +
                    describeDisplays()
            )
        }
    }

    fun update(progress: Float, opening: Boolean) {
        presentation?.updateProgress(progress, opening)
    }

    fun updateSnapshots(cover: Bitmap, inner: Bitmap) {
        presentation?.updateSnapshots(cover, inner)
    }

    fun dismiss() {
        val existing = presentation
        presentation = null
        existing?.dismiss()
    }

    private fun describeDisplay(
        display: Display,
        currentId: Int,
        presentationIds: Set<Int>,
        builtInIds: Set<Int>,
    ): String {
        val current = if (display.displayId == currentId) "*" else ""
        val presentationFlag = if ((display.flags and Display.FLAG_PRESENTATION) != 0) "+P" else ""
        val category = if (display.displayId in presentationIds) "+C" else ""
        val builtIn = if (display.displayId in builtInIds) "+B" else ""
        return "${display.displayId}$current$presentationFlag$category$builtIn:${display.name}" +
            "/${stateLabel(display.state)}/0x${display.flags.toString(16)}"
    }

    private fun stateLabel(state: Int): String = when (state) {
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "STATE_$state"
    }

    private class SnapshotPresentation(
        private val activity: Activity,
        targetDisplay: Display,
        private var cover: Bitmap,
        private var inner: Bitmap,
        private var progress: Float,
        private var opening: Boolean,
        private val onStopped: (SnapshotPresentation) -> Unit,
    ) : Presentation(activity, targetDisplay, android.R.style.Theme_Material_NoActionBar) {

        private lateinit var transitionView: SnapshotTransitionView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            transitionView = SnapshotTransitionView(context).apply {
                setSnapshots(cover, inner)
                updateProgress(progress, opening)
            }
            setContentView(
                transitionView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        override fun onStart() {
            super.onStart()
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        override fun onStop() {
            super.onStop()
            onStopped(this)
        }

        fun updateProgress(value: Float, isOpening: Boolean) {
            progress = value
            opening = isOpening
            if (::transitionView.isInitialized) {
                transitionView.updateProgress(progress, opening)
            }
        }

        fun updateSnapshots(newCover: Bitmap, newInner: Bitmap) {
            cover = newCover
            inner = newInner
            if (::transitionView.isInitialized) {
                transitionView.setSnapshots(cover, inner)
            }
        }
    }

    companion object {
        private const val BUILT_IN_DISPLAY_CATEGORY =
            "android.hardware.display.category.BUILT_IN_DISPLAYS"
    }
}
