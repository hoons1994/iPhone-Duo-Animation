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

        val descriptions = displayManager.displays.joinToString(separator = " | ") { display ->
            val current = if (display.displayId == currentId) "*" else ""
            val presentationFlag = if ((display.flags and Display.FLAG_PRESENTATION) != 0) "+P" else ""
            val category = if (display.displayId in presentationIds) "+C" else ""
            "${display.displayId}$current$presentationFlag$category:${display.name}"
        }
        return if (descriptions.isBlank()) "no displays reported" else descriptions
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
        val candidates = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != currentId }

        if (candidates.isEmpty()) {
            onStatus("Presentation unavailable · no other presentation display · ${describeDisplays()}")
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
                    statusListener?.invoke("Presentation stopped by system · ${describeDisplays()}")
                }
            },
        )

        try {
            next.show()
            presentation = next
            onStatus("Presentation active on display ${target.displayId} · ${target.name}")
        } catch (error: WindowManager.InvalidDisplayException) {
            onStatus("Presentation rejected: ${error.message ?: "InvalidDisplay"} · ${describeDisplays()}")
        } catch (error: SecurityException) {
            onStatus("Presentation blocked by policy: ${error.message ?: "SecurityException"}")
        } catch (error: RuntimeException) {
            onStatus("Presentation failed: ${error.javaClass.simpleName} · ${error.message ?: "unknown"}")
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
}
