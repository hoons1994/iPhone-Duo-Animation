package com.hoons1994.iphoneduoanimation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Wallpaper-derived glass: its background is blurred, while child content stays crisp. */
@SuppressLint("ViewConstructor") // Created with a live wallpaper source; never inflated from XML.
internal class LiquidGlassPanel(
    context: Context,
    private val wallpaper: ImageView,
    cornerRadiusDp: Float = 30f,
    private val strong: Boolean = false,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val radius = cornerRadiusDp * density
    private val blurRadius = (if (strong) 26f else 20f) * density
    private val blurPadding = ceil(blurRadius * 2f).toInt()
    private val sourceLocation = IntArray(2)
    private val backdropLocation = IntArray(2)
    private var cachedSource: Drawable? = null
    private var backdropDrawable: Drawable? = null
    private var alignment: List<Int>? = null
    private var observedTree: ViewTreeObserver? = null
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        syncBackdrop()
        true
    }

    private val backdrop = object : View(context) {
        private val previousBounds = Rect()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val drawable = backdropDrawable ?: return
            val contentWidth = wallpaper.width - wallpaper.paddingLeft - wallpaper.paddingRight
            val contentHeight = wallpaper.height - wallpaper.paddingTop - wallpaper.paddingBottom
            if (contentWidth <= 0 || contentHeight <= 0) return
            val drawableWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: contentWidth
            val drawableHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: contentHeight
            val scale = max(contentWidth.toFloat() / drawableWidth,
                contentHeight.toFloat() / drawableHeight)
            val cropX = ((contentWidth - drawableWidth * scale) * 0.5f).roundToInt()
            val cropY = ((contentHeight - drawableHeight * scale) * 0.5f).roundToInt()
            val checkpoint = canvas.save()
            val shared = drawable === cachedSource
            val previousCallback = drawable.callback
            drawable.copyBounds(previousBounds)
            try {
                // Some custom wallpaper Drawables cannot be cloned. Temporarily
                // suppress invalidation callbacks while restoring their bounds;
                // otherwise sharing one can create a redraw feedback loop.
                if (shared) drawable.callback = null
                drawable.setBounds(0, 0, drawableWidth, drawableHeight)
                canvas.translate(
                    (sourceLocation[0] - backdropLocation[0] + wallpaper.paddingLeft + cropX).toFloat(),
                    (sourceLocation[1] - backdropLocation[1] + wallpaper.paddingTop + cropY).toFloat(),
                )
                canvas.scale(scale, scale)
                drawable.draw(canvas)
            } finally {
                drawable.bounds = previousBounds
                if (shared) drawable.callback = previousCallback
                canvas.restoreToCount(checkpoint)
            }
        }
    }.apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
    }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            // Read wallpaper beyond the panel before blurring, so panel edges
            // do not stretch the last row of pixels into a flat border.
            setMargins(-blurPadding, -blurPadding, -blurPadding, -blurPadding)
        })
        addView(View(context).apply {
            background = GlassFinish(borderOnly = false)
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        foreground = GlassFinish(borderOnly = true)
        refreshBackdrop()
    }

    /** Call when replacing or changing the wallpaper in place. */
    fun refreshBackdrop() {
        val source = wallpaper.drawable
        cachedSource = source
        backdropDrawable = source?.let {
            runCatching { it.constantState?.newDrawable(resources)?.mutate() }.getOrNull() ?: it
        }
        alignment = null
        syncBackdrop()
        backdrop.invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observedTree = viewTreeObserver.also { it.addOnPreDrawListener(preDrawListener) }
        syncBackdrop()
    }

    override fun onDetachedFromWindow() {
        observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        observedTree = null
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) invalidateOutline()
        syncBackdrop()
    }

    private fun syncBackdrop() {
        if (wallpaper.drawable !== cachedSource) {
            refreshBackdrop()
            return
        }
        wallpaper.getLocationInWindow(sourceLocation)
        backdrop.getLocationInWindow(backdropLocation)
        val next = listOf(sourceLocation[0] - backdropLocation[0], sourceLocation[1] - backdropLocation[1],
            wallpaper.width, wallpaper.height, wallpaper.paddingLeft, wallpaper.paddingTop,
            wallpaper.paddingRight, wallpaper.paddingBottom, width, height)
        if (next == alignment) return
        alignment = next
        backdrop.invalidate()
    }

    private inner class GlassFinish(private val borderOnly: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shape = RectF()

        override fun onBoundsChange(bounds: Rect) {
            shape.set(bounds)
            if (borderOnly) {
                val inset = density * 0.65f
                shape.inset(inset, inset)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density * 1.15f
                paint.shader = LinearGradient(shape.left, shape.top, shape.right, shape.bottom,
                    intArrayOf(0x99ffffff.toInt(), 0x30ffffff, 0x12ffffff, 0x55ffffff),
                    floatArrayOf(0f, 0.32f, 0.72f, 1f), Shader.TileMode.CLAMP)
            } else {
                paint.style = Paint.Style.FILL
                val colors = if (strong) intArrayOf(
                    Color.argb(198, 23, 31, 46), Color.argb(212, 12, 19, 32), Color.argb(220, 9, 14, 25),
                ) else intArrayOf(
                    Color.argb(146, 37, 48, 66), Color.argb(156, 20, 31, 47), Color.argb(170, 12, 23, 38),
                )
                paint.shader = LinearGradient(shape.left, shape.top, shape.left, shape.bottom,
                    colors, floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            }
        }

        override fun draw(canvas: Canvas) {
            val inset = if (borderOnly) density * 0.65f else 0f
            canvas.drawRoundRect(shape, (radius - inset).coerceAtLeast(0f),
                (radius - inset).coerceAtLeast(0f), paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
