package com.hoons1994.iphoneduoanimation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.view.Surface
import android.view.Choreographer
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class SnapshotTransitionView @JvmOverloads constructor(
    context: Context,
    private val overlayMode: Boolean = false,
) :
    SurfaceView(context), SurfaceHolder.Callback {

    private val runtimeShader = RuntimeShader(SnapshotTransitionShader.SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = runtimeShader
    }

    private var coverBitmap: Bitmap = onePixel(Color.rgb(24, 28, 38))
    private var innerBitmap: Bitmap = onePixel(Color.rgb(14, 18, 28))
    private var coverMipmaps = SnapshotMipmaps(coverBitmap)
    private var innerMipmaps = SnapshotMipmaps(innerBitmap)
    private var progress = 0f
    private var opening = true
    private var actualCoverSurface = false
    private var capturedSurfaceOverride: Boolean? = null
    private var surfaceReported = false
    private var handoffProgress = TransitionTuning.DEFAULT_HANDOFF_PROGRESS
    private var transitionUniformsDirty = true
    private var targetProgress = 0f
    private var framePending = false
    private var lastFrameNanos = 0L
    private var surfaceReady = false
    private var overlayEnabled = !overlayMode
    private var capturedWidth = 0
    private var capturedHeight = 0
    private var appliedFrameRate = 0f
    var onOverlayFrame: ((clear: Boolean) -> Unit)? = null
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        framePending = false
        val dt = if (lastFrameNanos == 0L) 1f / 120f else
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNanos = frameTimeNanos
        progress = FrameSmoothing.step(
            progress,
            targetProgress,
            dt,
            FRAME_TIME_CONSTANT_SECONDS,
            FRAME_SETTLE_PROGRESS,
        )
        transitionUniformsDirty = true
        drawSurfaceFrame()
        if (overlayEnabled && progress != targetProgress) scheduleFrame() else lastFrameNanos = 0L
    }

    var onSurfaceChanged: ((isCover: Boolean) -> Unit)? = null

    init {
        // Like duo-fold-live, rasterize into a smaller hardware buffer instead
        // of running the fragment shader at the full physical display size.
        holder.addCallback(this)
        if (overlayMode) {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
        }

        val initialBounds = runCatching {
            context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        }.getOrNull()
        val metrics = resources.displayMetrics
        val initialWidth = (initialBounds?.width() ?: metrics.widthPixels).coerceAtLeast(1)
        val initialHeight = (initialBounds?.height() ?: metrics.heightPixels).coerceAtLeast(1)
        actualCoverSurface = SurfaceClassifier.classify(initialWidth, initialHeight) ==
            SurfaceClassifier.Surface.COVER

        runtimeShader.setFloatUniform(
            "resolution",
            initialWidth.toFloat(),
            initialHeight.toFloat(),
        )
        runtimeShader.setFloatUniform("coverSurface", if (actualCoverSurface) 1f else 0f)
        runtimeShader.setFloatUniform("motionAmount", 0f)
        runtimeShader.setFloatUniform("foldCos", 1f)
        runtimeShader.setFloatUniform("foldSin", 0f)
        runtimeShader.setFloatUniform("maxBlurPx", TransitionTuning.REFERENCE_BLUR_RADIUS_PX)
        val density = resources.displayMetrics.xdpi
            .takeIf { it.isFinite() && it > 0f }
            ?.div(25.4f)
            ?: TransitionTuning.REFERENCE_PIXELS_PER_MM
        runtimeShader.setFloatUniform(
            "eyeDistancePx",
            TransitionTuning.REFERENCE_EYE_DISTANCE_MM * density,
        )
        runtimeShader.setFloatUniform("blurSpread", TransitionTuning.REFERENCE_BLUR_SPREAD)
        runtimeShader.setFloatUniform(
            "darkening",
            TransitionTuning.REFERENCE_DARKENING *
                TransitionTuning.REFERENCE_PIXELS_PER_MM / density,
        )
        runtimeShader.setFloatUniform("hingeAxisY", 0f)
        runtimeShader.setFloatUniform("coverHingeFromEnd", 0f)
        runtimeShader.setFloatUniform("overlayMode", if (overlayMode) 1f else 0f)
        runtimeShader.setFloatUniform("overlayContentBounds", 0f, 0f,
            initialWidth.toFloat(), initialHeight.toFloat())
        bindSnapshots()
        pushTransitionUniforms()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateHingeGeometry()
        if (progress != targetProgress) scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePending = false
        lastFrameNanos = 0L
        appliedFrameRate = 0f
        super.onDetachedFromWindow()
    }

    private fun scheduleFrame() {
        if (framePending || !isAttachedToWindow || !surfaceReady) return
        framePending = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun setSnapshots(cover: Bitmap, inner: Bitmap) {
        coverBitmap = cover
        innerBitmap = inner
        coverMipmaps = SnapshotMipmaps(cover)
        innerMipmaps = SnapshotMipmaps(inner)
        bindSnapshots()
        scheduleFrame()
    }

    internal fun setCapturedHome(frame: CapturedHomeFrame) {
        // The active physical surface always uses its own actual launcher capture.
        // Mips have already been prepared by the screenshot worker.
        frame.mipmaps.bind(runtimeShader, "cover")
        frame.mipmaps.bind(runtimeShader, "inner")
        capturedWidth = frame.geometry.width
        capturedHeight = frame.geometry.height
        scheduleFrame()
    }

    fun setOverlayContentBounds(bounds: Rect) {
        runtimeShader.setFloatUniform("overlayContentBounds", bounds.left.toFloat(),
            bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        scheduleFrame()
    }

    fun setOverlayEnabled(enabled: Boolean) {
        if (overlayEnabled == enabled) return
        overlayEnabled = enabled
        scheduleFrame()
    }

    fun updateProgress(value: Float, isOpening: Boolean, interpolate: Boolean = false) {
        targetProgress = TransitionTuning.clampProgress(value)
        opening = isOpening
        if (interpolate && isAttachedToWindow) {
            scheduleFrame()
            return
        }
        lastFrameNanos = 0L
        progress = targetProgress
        transitionUniformsDirty = true
        scheduleFrame()
    }

    fun setHandoffProgress(value: Float) {
        val next = value.coerceIn(
            TransitionTuning.MIN_HANDOFF_PROGRESS,
            TransitionTuning.MAX_HANDOFF_PROGRESS,
        )
        if (abs(next - handoffProgress) < 0.0001f) return
        handoffProgress = next
        transitionUniformsDirty = true
        scheduleFrame()
    }

    /** Match the renderer to the capture's physical surface until it is replaced. */
    fun setCapturedSurface(isCover: Boolean?) {
        if (capturedSurfaceOverride == isCover) return
        capturedSurfaceOverride = isCover
        pushEffectiveSurface()
        scheduleFrame()
    }

    fun currentProgress(): Float = progress

    fun isCoverSurface(): Boolean = actualCoverSurface

    fun effectiveCoverSurface(): Boolean = capturedSurfaceOverride ?: actualCoverSurface

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        holder.setFixedSize((w / 2).coerceAtLeast(1), (h / 2).coerceAtLeast(1))

        runtimeShader.setFloatUniform("resolution", w.toFloat(), h.toFloat())
        updateHingeGeometry()

        val previousClassification = when {
            actualCoverSurface -> SurfaceClassifier.Surface.COVER
            else -> SurfaceClassifier.Surface.INNER
        }
        val classified = SurfaceClassifier.classify(w, h, previousClassification)
        if (classified == SurfaceClassifier.Surface.UNKNOWN) return

        val newCoverSurface = classified == SurfaceClassifier.Surface.COVER
        val changed = newCoverSurface != actualCoverSurface
        actualCoverSurface = newCoverSurface
        pushEffectiveSurface()

        if (!surfaceReported || changed) {
            surfaceReported = true
            onSurfaceChanged?.invoke(actualCoverSurface)
        }
        scheduleFrame()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        preferFastRefresh()
        scheduleFrame()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        preferFastRefresh()
        scheduleFrame()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePending = false
        lastFrameNanos = 0L
        appliedFrameRate = 0f
    }

    private fun drawSurfaceFrame() {
        if (!surfaceReady || !holder.surface.isValid || width <= 0 || height <= 0) return
        val canvas = try {
            holder.lockHardwareCanvas()
        } catch (_: IllegalStateException) {
            return // The physical display may disappear between the validity check and lock.
        }
        val motion = if (effectiveCoverSurface()) {
            TransitionTuning.referenceCoverBlur(progress, handoffProgress)
        } else TransitionTuning.referenceMovingPanelBlur(progress, opening, handoffProgress)
        // Keep the last valid launcher frame visible while Android changes the
        // activity's dimensions during a fold handoff. The shader maps it with
        // aspectFillUv until HomeActivity supplies a fresh capture; clearing on
        // a size mismatch made the closing transition disappear at that point.
        val clear = overlayMode && (!overlayEnabled || motion < 0.0001f ||
            capturedWidth <= 0 || capturedHeight <= 0)
        try {
            // Keep hinge classification, texture coordinates, and blur radius in
            // logical View pixels. Only rasterization uses the half-size buffer.
            canvas.drawColor(if (overlayMode) Color.TRANSPARENT else Color.BLACK, PorterDuff.Mode.CLEAR)
            canvas.scale(canvas.width.toFloat() / width, canvas.height.toFloat() / height)
            pushTransitionUniforms()
            if (!clear) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        if (overlayMode) onOverlayFrame?.invoke(clear)
    }

    private fun updateHingeGeometry() {
        val rotation = display?.rotation ?: runCatching { context.display?.rotation }.getOrNull()
            ?: Surface.ROTATION_0

        val axisY = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

        // Galaxy Fold book-style geometry has the cover hinge on the natural
        // left edge. Rotating the device moves that edge to bottom/right/top.
        // If a future device reports a different natural hinge edge this should
        // become a device-profile setting rather than silently guessing.
        val hingeFromEnd = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_180

        runtimeShader.setFloatUniform("hingeAxisY", if (axisY) 1f else 0f)
        runtimeShader.setFloatUniform("coverHingeFromEnd", if (hingeFromEnd) 1f else 0f)
        updateBlurRadius(axisY)
    }

    private fun pushEffectiveSurface() {
        runtimeShader.setFloatUniform(
            "coverSurface",
            if (effectiveCoverSurface()) 1f else 0f,
        )
        transitionUniformsDirty = true
        updateHingeGeometry()
    }

    private fun pushTransitionUniforms() {
        if (!transitionUniformsDirty) return
        val cover = effectiveCoverSurface()
        val motion = if (cover) {
            TransitionTuning.referenceCoverBlur(progress, handoffProgress)
        } else {
            TransitionTuning.referenceMovingPanelBlur(progress, opening, handoffProgress)
        }
        val radians = TransitionTuning.referenceFoldRadians(
            progress,
            cover,
            opening,
            handoffProgress,
        )
        runtimeShader.setFloatUniform("motionAmount", motion)
        runtimeShader.setFloatUniform("foldCos", cos(radians))
        runtimeShader.setFloatUniform("foldSin", sin(radians))
        transitionUniformsDirty = false
    }

    private fun bindSnapshots() {
        coverMipmaps.bind(runtimeShader, "cover")
        innerMipmaps.bind(runtimeShader, "inner")
    }

    private fun updateBlurRadius(axisY: Boolean) {
        val extent = if (axisY) height else width
        if (extent <= 0) return
        runtimeShader.setFloatUniform(
            "maxBlurPx",
            TransitionTuning.referenceMaxBlurPx(extent, effectiveCoverSurface()),
        )
    }

    private fun preferFastRefresh() {
        if (!holder.surface.isValid) return
        val currentMode = display?.mode
        val rate = display?.supportedModes
            ?.asSequence()
            ?.filter { mode ->
                currentMode == null || mode.physicalWidth == currentMode.physicalWidth &&
                    mode.physicalHeight == currentMode.physicalHeight
            }
            ?.maxOfOrNull { it.refreshRate }
            ?.coerceAtMost(120f)
            ?: 60f
        if (abs(rate - appliedFrameRate) < 0.1f) return
        runCatching {
            holder.surface.setFrameRate(
                rate,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
            appliedFrameRate = rate
        }
    }

    private fun onePixel(color: Int): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
    }

    private companion object {
        // HingeAngleMonitor already filters sensor jitter. Keep only a short
        // render follower to bridge samples without stacking a sluggish second filter.
        const val FRAME_TIME_CONSTANT_SECONDS = 0.016f
        const val FRAME_SETTLE_PROGRESS = 0.00005f
    }

}
