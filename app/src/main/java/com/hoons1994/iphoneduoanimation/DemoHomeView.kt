package com.hoons1994.iphoneduoanimation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

class DemoHomeView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.shader = LinearGradient(
            0f,
            0f,
            w,
            h,
            Color.rgb(36, 43, 70),
            Color.rgb(14, 17, 27),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val density = resources.displayMetrics.density
        val margin = 24f * density
        val top = 56f * density

        textPaint.textSize = 22f * density
        textPaint.alpha = 235
        canvas.drawText("Duo Transition Lab", w / 2f, top, textPaint)

        val cardTop = top + 32f * density
        val cardHeight = 120f * density
        paint.color = Color.argb(170, 255, 255, 255)
        canvas.drawRoundRect(
            RectF(margin, cardTop, w - margin, cardTop + cardHeight),
            26f * density,
            26f * density,
            paint,
        )

        textPaint.color = Color.rgb(30, 34, 44)
        textPaint.textSize = 15f * density
        textPaint.alpha = 255
        canvas.drawText("HINGE-DRIVEN PROGRESSIVE FOCUS", w / 2f, cardTop + 48f * density, textPaint)
        textPaint.textSize = 28f * density
        canvas.drawText("Open  ·  Focus  ·  Settle", w / 2f, cardTop + 86f * density, textPaint)

        val columns = 4
        val rows = 4
        val gap = 18f * density
        val gridTop = cardTop + cardHeight + 44f * density
        val tileSize = (w - margin * 2f - gap * (columns - 1)) / columns
        val colors = intArrayOf(
            Color.rgb(241, 94, 94),
            Color.rgb(91, 157, 255),
            Color.rgb(94, 206, 143),
            Color.rgb(246, 181, 74),
            Color.rgb(164, 111, 255),
            Color.rgb(72, 196, 214),
            Color.rgb(238, 115, 184),
            Color.rgb(119, 132, 156),
        )

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val left = margin + column * (tileSize + gap)
                val topY = gridTop + row * (tileSize + gap)
                paint.color = colors[(row * columns + column) % colors.size]
                canvas.drawRoundRect(
                    RectF(left, topY, left + tileSize, topY + tileSize),
                    tileSize * 0.24f,
                    tileSize * 0.24f,
                    paint,
                )

                paint.color = Color.argb(110, 255, 255, 255)
                val dotRadius = tileSize * 0.12f
                canvas.drawCircle(left + tileSize * 0.5f, topY + tileSize * 0.46f, dotRadius, paint)
                canvas.drawRoundRect(
                    RectF(
                        left + tileSize * 0.28f,
                        topY + tileSize * 0.66f,
                        left + tileSize * 0.72f,
                        topY + tileSize * 0.73f,
                    ),
                    tileSize * 0.04f,
                    tileSize * 0.04f,
                    paint,
                )
            }
        }

        textPaint.color = Color.WHITE
        textPaint.alpha = 155
        textPaint.textSize = 13f * density
        canvas.drawText("The midpoint should be the blurriest frame.", w / 2f, h - 120f * density, textPaint)
    }
}
