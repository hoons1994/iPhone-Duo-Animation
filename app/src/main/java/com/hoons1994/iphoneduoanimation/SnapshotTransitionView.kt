package com.hoons1994.iphoneduoanimation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View

class SnapshotTransitionView(context: Context) : View(context) {

    private val runtimeShader = RuntimeShader(SnapshotTransitionShader.SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = runtimeShader
    }

    private var coverBitmap: Bitmap = onePixel(Color.rgb(24, 28, 38))
    private var innerBitmap: Bitmap = onePixel(Color.rgb(14, 18, 28))
    private var progress = 0f
    private var opening = true
    private var coverSurface = false

    var onSurfaceChanged: ((isCover: Boolean) -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        runtimeShader.setFloatUniform("resolution", 1080f, 2400f)
        runtimeShader.setFloatUniform("progress", progress)
        runtimeShader.setFloatUniform("opening", 1f)
        runtimeShader.setFloatUniform("coverSurface", 0f)
        runtimeShader.setFloatUniform("maxBlurPx", 22f * resources.displayMetrics.density)
        bindSnapshots()
    }

    fun setSnapshots(cover: Bitmap, inner: Bitmap) {
        coverBitmap = cover
        innerBitmap = inner
        bindSnapshots()
        invalidate()
    }

    fun updateProgress(value: Float, isOpening: Boolean) {
        progress = value.coerceIn(0f, 1f)
        opening = isOpening
        runtimeShader.setFloatUniform("progress", progress)
        runtimeShader.setFloatUniform("opening", if (opening) 1f else 0f)
        invalidate()
    }

    fun currentProgress(): Float = progress

    fun isCoverSurface(): Boolean = coverSurface

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        runtimeShader.setFloatUniform("resolution", w.toFloat(), h.toFloat())
        val shorter = minOf(w, h).toFloat()
        val longer = maxOf(w, h).toFloat()
        val newCoverSurface = (shorter / longer) < COVER_ASPECT_THRESHOLD
        if (newCoverSurface != coverSurface) {
            coverSurface = newCoverSurface
            onSurfaceChanged?.invoke(coverSurface)
        }
        runtimeShader.setFloatUniform("coverSurface", if (coverSurface) 1f else 0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun bindSnapshots() {
        runtimeShader.setInputShader(
            "coverSnapshot",
            BitmapShader(coverBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        runtimeShader.setInputShader(
            "innerSnapshot",
            BitmapShader(innerBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        runtimeShader.setFloatUniform(
            "coverSize",
            coverBitmap.width.toFloat(),
            coverBitmap.height.toFloat(),
        )
        runtimeShader.setFloatUniform(
            "innerSize",
            innerBitmap.width.toFloat(),
            innerBitmap.height.toFloat(),
        )
    }

    private fun onePixel(color: Int): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
    }

    companion object {
        private const val COVER_ASPECT_THRESHOLD = 0.62f
    }
}
