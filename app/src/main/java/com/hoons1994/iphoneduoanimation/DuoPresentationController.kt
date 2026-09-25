package com.hoons1994.iphoneduoanimation

import android.app.Activity
import android.app.Presentation
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup

/** Optional OS-controlled second display; this cannot force a panel to turn on. */
class DuoPresentationController(private val activity: Activity) {
    private val manager = activity.getSystemService(DisplayManager::class.java)
    private var current: SnapshotPresentation? = null
    private var linked = false
    private var effect = true
    private var moving = true

    fun describeDisplays(): String = manager.displays.joinToString(" | ") {
        "${it.displayId}:${it.name},state=${it.state},flags=0x${it.flags.toString(16)}"
    }

    fun tryShow(cover: Bitmap, inner: Bitmap, progress: Float, opening: Boolean,
                handoffProgress: Float, onStatus: (String) -> Unit) {
        val currentId = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        val builtIn = runCatching { manager.getDisplays("android.hardware.display.category.BUILT_IN_DISPLAYS").toList() }
            .getOrDefault(emptyList())
        val candidates = (builtIn.filter { it.flags and Display.FLAG_PRESENTATION != 0 } +
            manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList())
            .distinctBy { it.displayId }.filter { it.displayId != currentId }
        val target = candidates.firstOrNull()
        if (target == null) {
            onStatus("Presentation 대상이 없습니다. 두 패널 동시 출력은 확인되지 않았습니다.\n${describeDisplays()}")
            return
        }
        dismiss()
        val next = SnapshotPresentation(target, cover, inner, progress, opening, handoffProgress) {
            if (current === it) { current = null; onStatus("시스템이 Presentation을 종료했습니다.") }
        }
        try {
            next.show()
            current = next
            next.style()
            onStatus("Presentation 창 생성: ${target.displayId},state=${target.state}. 실제 패널 점등은 별도 확인이 필요합니다.")
        } catch (error: RuntimeException) {
            runCatching { next.dismiss() }
            onStatus("Presentation 실패: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    fun update(progress: Float, opening: Boolean) { current?.view?.updateProgress(progress, opening) }
    fun updateHandoffProgress(value: Float) { current?.view?.setHandoffProgress(value) }
    fun updateSnapshots(cover: Bitmap, inner: Bitmap) { current?.view?.setSnapshots(cover, inner) }
    fun updateStyle(linkedScene: Boolean, effectEnabled: Boolean, movingFromEnd: Boolean) {
        linked = linkedScene; effect = effectEnabled; moving = movingFromEnd
        current?.style()
    }
    fun dismiss() { val old = current; current = null; old?.dismiss() }

    private inner class SnapshotPresentation(
        target: Display, private val cover: Bitmap, private val inner: Bitmap,
        private val progress: Float, private val opening: Boolean, private val handoff: Float,
        private val stopped: (SnapshotPresentation) -> Unit,
    ) : Presentation(activity, target, android.R.style.Theme_Material_NoActionBar) {
        var view: SnapshotTransitionView? = null
            private set
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            view = SnapshotTransitionView(context).apply {
                setSnapshots(cover, inner)
                setHandoffProgress(handoff)
                updateProgress(progress, opening)
            }
            style()
            setContentView(requireNotNull(view), ViewGroup.LayoutParams(-1, -1))
        }
        fun style() {
            view?.setLinkedScene(linked)
            view?.setEffectEnabled(effect)
            view?.setMovingFromEnd(moving)
            // A stable Presentation does not observe the Activity's panel handoff.
            // Never synthesize a visibility pulse from that unrelated lifecycle.
            view?.setRevealEnabled(false)
        }
        override fun onStop() { super.onStop(); stopped(this) }
    }
}
