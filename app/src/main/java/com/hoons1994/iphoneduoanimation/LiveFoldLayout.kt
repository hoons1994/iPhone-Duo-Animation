package com.hoons1994.iphoneduoanimation

import android.content.Context
import android.view.DragEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/** The launcher itself is the shader input; child invalidations stay live at every angle. */
internal class LiveFoldLayout(context: Context) : FrameLayout(context) {
    private val effects by lazy { LiveFoldEffects() }
    private var progress = 0f
    private var opening = true
    private var handoff = TransitionTuning.DEFAULT_HANDOFF_PROGRESS
    private var pendingHandoff = handoff
    private var cover = SurfaceClassifier.classify(resources.displayMetrics.widthPixels,
        resources.displayMetrics.heightPixels) == SurfaceClassifier.Surface.COVER
    private var surfaceReported = false
    private var renderingActive = false
    private var firstPoseReady = true
    private var renderPending = true
    private var observedTree: ViewTreeObserver? = null
    private var displayedGeometry: LiveFoldGeometry? = null
    private var effectActive = false
    private var rejectedGesture = false
    private var lastTouch: MotionEvent? = null
    private var systemDragActive = false

    var onSurfaceChanged: ((isCover: Boolean) -> Unit)? = null
    var onEffectActiveChanged: ((active: Boolean) -> Unit)? = null

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        // The hinge monitor owns filtering and its vsync settle callback. Read
        // its latest pose after animation callbacks, once before this traversal
        // draws; a second independent follower adds latency and uneven pacing.
        if (!firstPoseReady) {
            false
        } else {
            if (renderPending) {
                renderPending = false
                renderCurrentGeometry()
            }
            true
        }
    }

    fun isCoverSurface() = cover

    fun setFirstPoseReady(ready: Boolean) {
        if (firstPoseReady == ready) return
        firstPoseReady = ready
        if (ready) requestRender() else {
            cancelTouch()
            clearEffect()
            invalidate()
        }
    }

    fun setRenderingActive(active: Boolean) {
        if (renderingActive == active) return
        renderingActive = active
        if (active) requestRender() else {
            systemDragActive = false
            cancelTouch()
            clearEffect()
        }
    }

    fun updateProgress(value: Float, isOpening: Boolean) {
        if (!value.isFinite()) return
        val next = TransitionTuning.clampProgress(value)
        if (next == progress && isOpening == opening) return
        progress = next
        opening = isOpening
        if (canAdoptHandoff()) handoff = pendingHandoff
        requestRender()
    }

    fun setHandoffProgress(value: Float) {
        if (!value.isFinite()) return
        pendingHandoff = value.coerceIn(TransitionTuning.MIN_HANDOFF_PROGRESS,
            TransitionTuning.MAX_HANDOFF_PROGRESS)
        if (pendingHandoff == handoff || !canAdoptHandoff()) return
        handoff = pendingHandoff
        requestRender()
    }

    // Keep one optical mapping through a reversal and across the display
    // handoff. Learn calibration changes at a resolved endpoint or fresh start.
    private fun canAdoptHandoff(): Boolean = !renderingActive ||
        if (cover) progress * 180f <= TransitionTuning.REFERENCE_CLOSED_HINGE_DEGREES
        else progress * 180f >= TransitionTuning.REFERENCE_INNER_CLEAR_OPENING_DEGREES

    fun refreshGeometry() {
        // Rotation can change by 180 degrees without changing the View size.
        cancelTouch()
        displayedGeometry = null
        requestRender()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observedTree = viewTreeObserver.also { it.addOnPreDrawListener(preDrawListener) }
        requestRender()
    }

    override fun onDetachedFromWindow() {
        observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        observedTree = null
        cancelTouch()
        clearEffect()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) requestRender() else {
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
        // Commit the new size and latest sensor pose together at pre-draw.
        requestRender()
    }

    private fun requestRender() {
        renderPending = true
        if (renderingActive && isAttachedToWindow && windowVisibility == VISIBLE) invalidate()
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
        renderPending = true
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
            requestRender()
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
