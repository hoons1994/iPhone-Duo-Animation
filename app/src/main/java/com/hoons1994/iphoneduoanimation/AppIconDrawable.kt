package com.hoons1994.iphoneduoanimation

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

/** A consistent rounded-square frame while preserving each installed app's artwork. */
internal class AppIconDrawable(private val icon: Drawable) : Drawable() {
    private val shape = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val savedBounds = Rect()
    private var opacity = 255

    override fun onBoundsChange(bounds: Rect) {
        val l = bounds.left.toFloat()
        val t = bounds.top.toFloat()
        val r = bounds.right.toFloat()
        val b = bounds.bottom.toFloat()
        val corner = minOf(bounds.width(), bounds.height()) * 0.30f
        // Longer, continuous-looking shoulders than a circular corner.
        val bend = corner * 0.23f
        shape.reset()
        shape.moveTo(l + corner, t)
        shape.lineTo(r - corner, t)
        shape.cubicTo(r - bend, t, r, t + bend, r, t + corner)
        shape.lineTo(r, b - corner)
        shape.cubicTo(r, b - bend, r - bend, b, r - corner, b)
        shape.lineTo(l + corner, b)
        shape.cubicTo(l + bend, b, l, b - bend, l, b - corner)
        shape.lineTo(l, t + corner)
        shape.cubicTo(l, t + bend, l + bend, t, l + corner, t)
        shape.close()
    }

    override fun draw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.clipPath(shape)
        paint.style = Paint.Style.FILL
        paint.color = 0xfff2f5f6.toInt()
        paint.alpha = opacity
        canvas.drawPath(shape, paint)
        if (icon is AdaptiveIconDrawable) {
            // Draw the actual layers, so an OEM's circular adaptive mask does not
            // become a circle inside the launcher's rounded-square icon frame.
            val inset = (bounds.width() * AdaptiveIconDrawable.getExtraInsetFraction()).toInt()
            drawLayer(canvas, icon.background, inset)
            drawLayer(canvas, icon.foreground, inset)
        } else drawLayer(canvas, icon, 0)
        canvas.restoreToCount(checkpoint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (bounds.width() / 64f).coerceAtLeast(0.5f)
        paint.color = 0x30ffffff
        paint.alpha = (48 * opacity / 255)
        canvas.drawPath(shape, paint)
    }

    private fun drawLayer(canvas: Canvas, drawable: Drawable?, inset: Int) {
        if (drawable == null) return
        savedBounds.set(drawable.bounds)
        val previousAlpha = drawable.alpha
        drawable.setBounds(bounds.left - inset, bounds.top - inset,
            bounds.right + inset, bounds.bottom + inset)
        drawable.alpha = previousAlpha * opacity / 255
        drawable.draw(canvas)
        drawable.bounds = savedBounds
        drawable.alpha = previousAlpha
    }

    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { icon.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = icon.intrinsicWidth
    override fun getIntrinsicHeight() = icon.intrinsicHeight
}
