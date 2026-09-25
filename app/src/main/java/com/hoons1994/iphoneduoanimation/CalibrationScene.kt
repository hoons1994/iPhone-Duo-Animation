package com.hoons1994.iphoneduoanimation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** One shared atlas; its grid, labels and fine lines expose coordinate errors. */
object CalibrationScene {
    fun create(): Bitmap {
        val size = 1200
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(15, 23, 39))
        val palette = intArrayOf(0xFF47C4C0.toInt(), 0xFFEFAB54.toInt(), 0xFF8295F5.toInt(), 0xFFEB759E.toInt())
        for (row in 0 until 6) for (column in 0 until 8) {
            val x = column * 150f
            val y = row * 200f
            paint.color = if ((row + column) % 2 == 0) 0xFF20304B.toInt() else 0xFF142239.toInt()
            canvas.drawRect(x, y, x + 150, y + 200, paint)
            paint.color = palette[(row + column) % palette.size]
            canvas.drawRoundRect(x + 35, y + 50, x + 115, y + 130, 19f, 19f, paint)
            paint.color = Color.WHITE
            paint.textSize = 27f
            paint.typeface = Typeface.MONOSPACE
            canvas.drawText("${column + 1}:${row + 1}", x + 43, y + 170, paint)
        }
        paint.color = Color.WHITE
        paint.strokeWidth = 2f
        for (i in 0..8) canvas.drawLine(i * 150f, 0f, i * 150f, 1200f, paint)
        for (i in 0..6) canvas.drawLine(0f, i * 200f, 1200f, i * 200f, paint)
        paint.color = 0xFFFFDF71.toInt()
        paint.strokeWidth = 5f
        canvas.drawLine(600f, 0f, 600f, 1200f, paint)
        return bitmap
    }
}
