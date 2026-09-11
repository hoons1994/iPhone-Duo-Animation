package com.hoons1994.iphoneduoanimation

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeShaderSmokeTest {

    @Test
    fun snapshotTransitionShader_compiles_andAcceptsAllInputs() {
        val runtimeShader = configuredShader(Color.RED, Color.BLUE)
        assertNotNull(runtimeShader)
    }

    @Test
    fun snapshotTransitionShader_rendersResolvedEndpointsAndMixedHandoff() {
        val runtimeShader = configuredShader(Color.RED, Color.BLUE)

        val closed = render(runtimeShader, progress = 0f, coverSurface = true)
        val open = render(runtimeShader, progress = 1f, coverSurface = false)
        val handoff = render(
            runtimeShader,
            progress = TransitionTuning.DEFAULT_HANDOFF_PROGRESS,
            coverSurface = true,
        )

        val closedPixel = closed.getPixel(closed.width / 2, closed.height / 2)
        val openPixel = open.getPixel(open.width / 2, open.height / 2)
        val handoffPixel = handoff.getPixel(handoff.width / 2, handoff.height / 2)

        assertTrue(Color.red(closedPixel) > 220)
        assertTrue(Color.blue(closedPixel) < 20)
        assertTrue(Color.blue(openPixel) > 220)
        assertTrue(Color.red(openPixel) < 20)

        val handoffRed = Color.red(handoffPixel)
        val handoffBlue = Color.blue(handoffPixel)
        assertTrue("handoff red channel was $handoffRed", handoffRed > 90)
        assertTrue("handoff blue channel was $handoffBlue", handoffBlue > 90)
        assertTrue(
            "handoff bridge was not balanced: r=$handoffRed b=$handoffBlue",
            abs(handoffRed - handoffBlue) < 20,
        )
    }

    private fun configuredShader(coverColor: Int, innerColor: Int): RuntimeShader {
        val runtimeShader = RuntimeShader(SnapshotTransitionShader.SOURCE)
        val cover = solidBitmap(8, 16, coverColor)
        val inner = solidBitmap(16, 12, innerColor)

        runtimeShader.setInputShader(
            "coverSnapshot",
            BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        runtimeShader.setInputShader(
            "innerSnapshot",
            BitmapShader(inner, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        runtimeShader.setFloatUniform("coverSize", 8f, 16f)
        runtimeShader.setFloatUniform("innerSize", 16f, 12f)
        runtimeShader.setFloatUniform("resolution", 64f, 64f)
        runtimeShader.setFloatUniform("progress", 0.43f)
        runtimeShader.setFloatUniform("opening", 1f)
        runtimeShader.setFloatUniform("coverSurface", 1f)
        runtimeShader.setFloatUniform("handoffProgress", 0.43f)
        runtimeShader.setFloatUniform("focusWindow", TransitionTuning.FOCUS_HALF_WINDOW)
        runtimeShader.setFloatUniform("maxBlurPx", 12f)
        runtimeShader.setFloatUniform("hingeAxisY", 0f)
        runtimeShader.setFloatUniform("coverHingeFromEnd", 0f)
        return runtimeShader
    }

    private fun render(
        runtimeShader: RuntimeShader,
        progress: Float,
        coverSurface: Boolean,
    ): Bitmap {
        runtimeShader.setFloatUniform("progress", progress)
        runtimeShader.setFloatUniform("coverSurface", if (coverSurface) 1f else 0f)

        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }
        canvas.drawRect(0f, 0f, 64f, 64f, paint)
        return bitmap
    }

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
}
