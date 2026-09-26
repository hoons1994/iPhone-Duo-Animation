package com.hoons1994.iphoneduoanimation

import android.content.Context
import android.view.Choreographer
import android.view.DragEvent
import android.view.MotionEvent
import android.view.Surface
import android.widget.FrameLayout
import kotlin.math.abs

/** The launcher itself is the shader input; child invalidations stay live at every angle. */
internal class LiveFoldLayout(context: Context) : FrameLayout(context) {
    private val effects by lazy { LiveFoldEffects() }
    private var progress = 0f
    private var targetProgress = 0f
    private var opening = true
    private var handoff = TransitionTuning.DEFAULT_HANDOFF_PROGRESS
    private var cover = SurfaceClassifier.classify(resources.displayMetrics.widthPixels,
        resources.displayMetrics.heightPixels) == SurfaceClassifier.Surface.COVER
    private var surfaceReported = false
    private var renderingActive = false
    private var framePending = false
    private var lastFrameNanos = 0L
    private var displayedGeometry: LiveFoldGeometry? = null
    private var effectActive = false
    private var rejectedGesture = false
    private var lastTouch: MotionEvent? = null
    private var systemDragActive = false

    var onSurfaceChanged: ((isCover: Boolean) -> Unit)? = null
    var onEffectActiveChanged: ((active: Boolean) -> Unit)? = null

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        framePending = false
        val dt = if (lastFrameNanos == 0L) 1f / 120f else
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNanos = frameTimeNanos
        progress = FrameSmoothing.step(progress, targetProgress, dt, 0.016f, 0.00005f)
        renderCurrentGeometry()
        if (progress != targetProgress) scheduleFrame() else lastFrameNanos = 0L
    }

    fun isCoverSurface() = cover

    fun setRenderingActive(active: Boolean) {
        if (renderingActive == active) return
        renderingActive = active
        if (active) scheduleFrame() else {
            systemDragActive = false
            cancelFrames()
            cancelTouch()
            clearEffect()
        }
    }

    fun updateProgress(value: Float, isOpening: Boolean, interpolate: Boolean = true) {
        if (!value.isFinite()) return
        val next = TransitionTuning.clampProgress(value)
        if (next == targetProgress && isOpening == opening && interpolate) return
        targetProgress = next
        opening = isOpening
        if (!interpolate) {
            progress = next
            lastFrameNanos = 0L
        }
        scheduleFrame()
    }

    fun setHandoffProgress(value: Float) {
        if (!value.isFinite()) return
        val next = value.coerceIn(TransitionTuning.MIN_HANDOFF_PROGRESS,
            TransitionTuning.MAX_HANDOFF_PROGRESS)
        if (abs(next - handoff) < 0.0001f) return
        handoff = next
        scheduleFrame()
    }

    fun refreshGeometry() {
        // Rotation can change by 180 degrees without changing the View size.
        cancelTouch()
        displayedGeometry = null
        scheduleFrame()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        cancelFrames()
        cancelTouch()
        clearEffect()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) scheduleFrame() else {
            cancelFrames()
            cancelTouch()
            clearEffect()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        cancelTouch()
        val previous = if (cover) SurfaceClassifier.Surface.COVER else SurfaceClassifier.Surface.INNER
        val next = SurfaceClassifier.classify(w, h, previous) == SurfaceClassifier.Surface.COVER
        val changed = cover != next
        cover = next
        if (!surfaceReported || changed) {
            surfaceReported = true
            onSurfaceChanged?.invoke(cover)
        }
        // Update before this size's first draw: no stale texture or surface buffer
        // can remain while Android relayouts the cover/inner launcher.
        renderCurrentGeometry()
    }

    private fun scheduleFrame() {
        if (framePending || !renderingActive || !isAttachedToWindow || windowVisibility != VISIBLE) return
        framePending = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun cancelFrames() {
        if (framePending) Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePending = false
        lastFrameNanos = 0L
    }

    private fun renderCurrentGeometry() {
        if (!renderingActive || width <= 0 || height <= 0 || windowVisibility != VISIBLE) return
        if (systemDragActive) return
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val dpi = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
            resources.displayMetrics.ydpi else resources.displayMetrics.xdpi
        val density = dpi.takeIf { it.isFinite() && it > 0f }?.div(25.4f)
            ?: TransitionTuning.REFERENCE_PIXELS_PER_MM
        val geometry = LiveFoldGeometry(width, height, cover, rotation, progress, opening, handoff, density)
        if (geometry == displayedGeometry) return
        displayedGeometry = geometry
        if (geometry.active) {
            setRenderEffect(effects.create(geometry))
            invalidate()
        } else setRenderEffect(null)
        notifyEffectActive(geometry.active)
    }

    private fun clearEffect() {
        setRenderEffect(null)
        displayedGeometry = null
        notifyEffectActive(false)
    }

    private fun notifyEffectActive(active: Boolean) {
        if (effectActive == active) return
        effectActive = active
        onEffectActiveChanged?.invoke(active)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelTouch()
            rejectedGesture = false
        }
        if (rejectedGesture) return true
        val geometry = displayedGeometry
        val mapped = if (geometry?.active == true) mapTouch(event, geometry) else MotionEvent.obtain(event)
        if (mapped == null) {
            cancelTouch()
            rejectedGesture = true
            return true
        }
        return try {
            val handled = super.dispatchTouchEvent(mapped)
            lastTouch?.recycle()
            lastTouch = if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) null else MotionEvent.obtain(mapped)
            handled
        } finally {
            mapped.recycle()
        }
    }

    override fun dispatchDragEvent(event: DragEvent): Boolean {
        // Android's system drag coordinates cannot be remapped through the
        // View RenderEffect. Resolve the glass during a drag so drop targets,
        // drag shadows and folder hover regions stay in the same coordinate space.
        if (event.action == DragEvent.ACTION_DRAG_STARTED) {
            systemDragActive = true
            clearEffect()
        }
        val handled = super.dispatchDragEvent(event)
        if (event.action == DragEvent.ACTION_DRAG_ENDED ||
            event.action == DragEvent.ACTION_DRAG_STARTED && !handled) {
            systemDragActive = false
            scheduleFrame()
        }
        return handled
    }

    private fun mapTouch(event: MotionEvent, geometry: LiveFoldGeometry): MotionEvent? {
        val properties = Array(event.pointerCount) { index ->
            MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) }
        }
        fun coordinates(history: Int = -1): Array<MotionEvent.PointerCoords>? {
            val result = Array(event.pointerCount) { MotionEvent.PointerCoords() }
            for (index in result.indices) {
                val coordinate = result[index]
                if (history < 0) event.getPointerCoords(index, coordinate)
                else event.getHistoricalPointerCoords(index, history, coordinate)
                val source = geometry.sourcePoint(coordinate.x, coordinate.y) ?: return null
                coordinate.x = source.first
                coordinate.y = source.second
            }
            return result
        }
        val current = coordinates() ?: return null
        // Preserve pointer IDs, action index, pressure, timing, and valid MOVE
        // history, so scrolling and multi-touch use the same plane as the image.
        val history = if (event.actionMasked == MotionEvent.ACTION_MOVE)
            (0 until event.historySize).mapNotNull { index ->
                coordinates(index)?.let { event.getHistoricalEventTime(index) to it }
            } else emptyList()
        val firstTime = history.firstOrNull()?.first ?: event.eventTime
        val firstCoordinates = history.firstOrNull()?.second ?: current
        val mapped = MotionEvent.obtain(event.downTime, firstTime, event.action,
            event.pointerCount, properties, firstCoordinates, event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
        if (history.isNotEmpty()) {
            history.drop(1).forEach { (time, coords) -> mapped.addBatch(time, coords, event.metaState) }
            mapped.addBatch(event.eventTime, current, event.metaState)
        }
        return mapped
    }

    private fun cancelTouch() {
        lastTouch?.let { event ->
            event.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(event)
            event.recycle()
            lastTouch = null
            rejectedGesture = true
        }
    }
}
