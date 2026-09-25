package com.hoons1994.iphoneduoanimation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.WindowManager

class SnapshotTransitionView(context: Context) : View(context) {
    private val shaderResult = runCatching { RuntimeShader(SnapshotTransitionShader.SOURCE) }
    private val runtimeShader = shaderResult.getOrNull()
    val rendererError: String? = shaderResult.exceptionOrNull()?.message
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val reveal = FoldReveal()
    private var coverBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
    private var innerBitmap = coverBitmap
    private var progress = 1f
    private var actualCoverSurface = false
    private var previewSurfaceOverride: Boolean? = null
    private var surfaceReported = false
    private var handoffProgress = FoldProjection.DEFAULT_HANDOFF
    private var linkedScene = false
    private var effectEnabled = true
    private var naturalMovingFromEnd = true
    private var revealEnabled = false
    private var firstFramePending = true
    private var lastDiagnosticMs = Long.MIN_VALUE

    var onSurfaceChanged: ((Boolean) -> Unit)? = null
    var onFrameDiagnostic: ((String) -> Unit)? = null
    var renderedTiltDegrees = 0f
        private set
    var revealCompensating = false
        private set

    init {
        val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        actualCoverSurface = SurfaceClassifier.classify(bounds.width(), bounds.height()) == SurfaceClassifier.Surface.COVER
        setLayerType(LAYER_TYPE_HARDWARE, null)
        bindSnapshots()
    }

    fun setSnapshots(cover: Bitmap, inner: Bitmap) {
        coverBitmap = cover
        innerBitmap = inner
        bindSnapshots()
        invalidate()
    }

    @Suppress("UNUSED_PARAMETER")
    fun updateProgress(value: Float, isOpening: Boolean) {
        // Geometry is reversible and depends on angle, not a one-way animator.
        if (!value.isFinite()) return
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun setHandoffProgress(value: Float) {
        if (value.isFinite()) handoffProgress = value.coerceIn(0.1f, 0.95f)
        invalidate()
    }

    fun setLinkedScene(enabled: Boolean) { linkedScene = enabled; invalidate() }
    fun setEffectEnabled(enabled: Boolean) { effectEnabled = enabled; invalidate() }
    fun setMovingFromEnd(enabled: Boolean) { naturalMovingFromEnd = enabled; invalidate() }
    fun setRevealEnabled(enabled: Boolean) {
        revealEnabled = enabled
        if (!enabled) reveal.reset()
    }
    fun resetVisibilityCompensation() { reveal.reset(); revealCompensating = false }

    fun setPreviewSurfaceOverride(isCover: Boolean?) {
        previewSurfaceOverride = isCover
        reveal.reset()
        firstFramePending = true
        invalidate()
    }

    fun currentProgress(): Float = progress
    fun isCoverSurface(): Boolean = actualCoverSurface
    fun effectiveCoverSurface(): Boolean = previewSurfaceOverride ?: actualCoverSurface

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val previous = if (actualCoverSurface) SurfaceClassifier.Surface.COVER else SurfaceClassifier.Surface.INNER
        val classification = SurfaceClassifier.classify(w, h, previous)
        if (classification == SurfaceClassifier.Surface.UNKNOWN) return
        val nextCover = classification == SurfaceClassifier.Surface.COVER
        val changed = nextCover != actualCoverSurface
        if (surfaceReported && changed && previewSurfaceOverride == null && revealEnabled) reveal.arm()
        actualCoverSurface = nextCover
        firstFramePending = true
        if (!surfaceReported || changed) {
            surfaceReported = true
            onSurfaceChanged?.invoke(actualCoverSurface)
        }
    }

    override fun onDetachedFromWindow() {
        reveal.reset()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val shader = runtimeShader
        if (shader == null) {
            // A failed shader must be obvious in the HUD, not passed off as success.
            canvas.drawBitmap(if (effectiveCoverSurface()) coverBitmap else innerBitmap, null,
                Rect(0, 0, width, height), bitmapPaint)
            if (firstFramePending) { firstFramePending = false; onFrameDiagnostic?.invoke("SHADER_ERROR:$rendererError") }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val drawable = isShown && windowVisibility == VISIBLE && display?.state == Display.STATE_ON
        val baseTilt = FoldProjection.tiltDegrees(progress * 180f, effectiveCoverSurface(), handoffProgress)
        val tilt = if (revealEnabled && previewSurfaceOverride == null) reveal.sample(baseTilt, now, drawable) else baseTilt
        renderedTiltDegrees = if (effectEnabled) tilt else 0f
        revealCompensating = effectEnabled && reveal.compensating
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val axisY = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val reversed = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_180
        shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform("tiltRadians", Math.toRadians(renderedTiltDegrees.toDouble()).toFloat())
        shader.setFloatUniform("coverSurface", if (effectiveCoverSurface()) 1f else 0f)
        shader.setFloatUniform("hingeAxisY", if (axisY) 1f else 0f)
        shader.setFloatUniform("coverHingeFromEnd", if (reversed) 1f else 0f)
        shader.setFloatUniform("movingFromEnd", if (naturalMovingFromEnd != reversed) 1f else 0f)
        shader.setFloatUniform("linkedScene", if (linkedScene) 1f else 0f)
        shader.setFloatUniform("maxBlurPx", (12f * resources.displayMetrics.density).coerceAtMost(28f))
        shader.setFloatUniform("shadeStrength", 0.16f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        if (drawable && (firstFramePending || lastDiagnosticMs == Long.MIN_VALUE || now - lastDiagnosticMs >= 500L)) {
            val event = if (firstFramePending) "first_draw" else "frame"
            firstFramePending = false
            lastDiagnosticMs = now
            onFrameDiagnostic?.invoke("$event,size=${width}x$height,display=${display?.displayId},state=${display?.state},hardware=${canvas.isHardwareAccelerated},cover=${effectiveCoverSurface()},angle=${progress * 180f},tilt=$renderedTiltDegrees,reveal=$revealCompensating")
        }
        if (drawable && reveal.needsFrame()) postInvalidateOnAnimation()
    }

    private fun bindSnapshots() {
        runtimeShader?.apply {
            setInputShader("coverSnapshot", BitmapShader(coverBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setInputShader("innerSnapshot", BitmapShader(innerBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setFloatUniform("coverSize", coverBitmap.width.toFloat(), coverBitmap.height.toFloat())
            setFloatUniform("innerSize", innerBitmap.width.toFloat(), innerBitmap.height.toFloat())
        }
    }
}
