package com.hoons1994.iphoneduoanimation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class RuntimeShaderSmokeTest {

    @Test
    fun snapshotTransitionShader_compiles_andAcceptsAllInputs() {
        val runtimeShader = configuredShader(Color.RED, Color.BLUE)
        assertNotNull(runtimeShader)
    }

    @Test
    fun snapshotTransitionShader_rendersResolvedEndpointsWithoutCrossfade() {
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
        assertTrue("handoff cover was fully black", handoffRed > 10)
        assertTrue("handoff unexpectedly mixed inner content: b=$handoffBlue", handoffBlue < 20)
    }

    private fun configuredShader(coverColor: Int, innerColor: Int): RuntimeShader {
        val cover = solidBitmap(8, 16, coverColor)
        val inner = solidBitmap(16, 12, innerColor)
        return configuredShader(cover, inner, 64)
    }

    private fun configuredShader(cover: Bitmap, inner: Bitmap, size: Int): RuntimeShader {
        val runtimeShader = RuntimeShader(SnapshotTransitionShader.SOURCE)
        SnapshotMipmaps(cover).bind(runtimeShader, "cover")
        SnapshotMipmaps(inner).bind(runtimeShader, "inner")
        runtimeShader.setFloatUniform("resolution", size.toFloat(), size.toFloat())
        runtimeShader.setFloatUniform("coverSurface", 1f)
        runtimeShader.setFloatUniform("motionAmount", 0f)
        runtimeShader.setFloatUniform("foldCos", -1f)
        runtimeShader.setFloatUniform("foldSin", 0f)
        runtimeShader.setFloatUniform("maxBlurPx", 12f)
        runtimeShader.setFloatUniform("eyeDistancePx", size * 4f)
        runtimeShader.setFloatUniform("blurSpread", TransitionTuning.REFERENCE_BLUR_SPREAD)
        runtimeShader.setFloatUniform("darkening", TransitionTuning.REFERENCE_DARKENING)
        runtimeShader.setFloatUniform("hingeAxisY", 0f)
        runtimeShader.setFloatUniform("coverHingeFromEnd", 0f)
        return runtimeShader
    }

    @Test
    fun movingPanel_staysFrostedAtHandoff_andFixedPanelStaysSharp() {
        // Bands perpendicular to the hinge remain measurable when perspective
        // compresses the other axis. Solid-color endpoint tests miss this bug.
        val size = 512
        for (axisY in listOf(false, true)) {
            val source = stripedBitmap(size, axisY)
            val shader = configuredShader(source, source, size)
            shader.setFloatUniform("hingeAxisY", if (axisY) 1f else 0f)
            shader.setFloatUniform("maxBlurPx", TransitionTuning.referenceMaxBlurPx(size, false))
            for (angle in listOf(90f, 120f)) {
                val frame = render(shader, angle / 180f, false, size, bufferScale = 2)
                val moving = bandStats(frame, 0.28f, axisY)
                val fixed = bandStats(frame, 0.75f, axisY)
                assertTrue("moving panel blacked out at $angle", moving.first > 20.0)
                assertTrue("frost lost at $angle: ${moving.second}", moving.second < 0.35)
                assertTrue("fixed panel was blurred at $angle", fixed.second > 0.85)
                frame.recycle()
            }
            val open = render(shader, 1f, false, size, bufferScale = 2)
            assertTrue("moving panel never resolved", bandStats(open, 0.28f, axisY).second > 0.85)
            open.recycle()
        }
    }

    @Test
    fun coverFrost_doesNotDisappearWhenProjectionReachesNinetyDegrees() {
        val size = 512
        val source = stripedBitmap(size, false)
        val shader = configuredShader(source, source, size)
        shader.setFloatUniform("maxBlurPx", TransitionTuning.referenceMaxBlurPx(size, true))
        for (angle in listOf(77.4f, 90f)) {
            val frame = render(shader, angle / 180f, true, size, bufferScale = 2)
            val stats = bandStats(frame, 0.45f, false)
            assertTrue("cover disappeared at $angle", stats.first > 10.0)
            assertTrue("cover frost lost at $angle: ${stats.second}", stats.second < 0.35)
            frame.recycle()
        }
    }

    private fun render(
        runtimeShader: RuntimeShader,
        progress: Float,
        coverSurface: Boolean,
        size: Int = 64,
        bufferScale: Int = 1,
    ): Bitmap {
        val motion = if (coverSurface) {
            TransitionTuning.referenceCoverBlur(
                progress,
                TransitionTuning.DEFAULT_HANDOFF_PROGRESS,
            )
        } else {
            TransitionTuning.referenceMovingPanelBlur(
                progress,
                opening = true,
                handoff = TransitionTuning.DEFAULT_HANDOFF_PROGRESS,
            )
        }
        val radians = TransitionTuning.referenceFoldRadians(
            progress,
            coverSurface,
            opening = true,
            handoff = TransitionTuning.DEFAULT_HANDOFF_PROGRESS,
        )
        runtimeShader.setFloatUniform("coverSurface", if (coverSurface) 1f else 0f)
        runtimeShader.setFloatUniform("motionAmount", motion)
        runtimeShader.setFloatUniform("foldCos", cos(radians))
        runtimeShader.setFloatUniform("foldSin", sin(radians))

        return renderHardware(runtimeShader, size, bufferScale)
    }

    private fun renderHardware(shader: RuntimeShader, size: Int, bufferScale: Int): Bitmap {
        val outputSize = size / bufferScale
        val reader = ImageReader.newInstance(
            outputSize, outputSize, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val thread = HandlerThread("shader-test-readback").apply { start() }
        val available = CountDownLatch(1)
        reader.setOnImageAvailableListener({ available.countDown() }, Handler(thread.looper))
        val node = RenderNode("shader-test").apply { setPosition(0, 0, outputSize, outputSize) }
        val canvas = node.beginRecording(outputSize, outputSize)
        canvas.scale(1f / bufferScale, 1f / bufferScale)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { this.shader = shader })
        node.endRecording()
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            assertTrue("GPU frame not available", available.await(5, TimeUnit.SECONDS))
            return requireNotNull(reader.acquireLatestImage()).use { image ->
                requireNotNull(image.hardwareBuffer).use { buffer ->
                    val hardware = requireNotNull(
                        Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)),
                    )
                    try {
                        requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                    } finally {
                        hardware.recycle()
                    }
                }
            }
        } finally {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
            thread.quitSafely()
            thread.join(1000)
        }
    }

    private fun stripedBitmap(size: Int, axisY: Boolean): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            val canvas = Canvas(this)
            val paint = Paint().apply { color = Color.BLACK }
            for (start in 0 until size step 16) {
                if (axisY) canvas.drawRect(start.toFloat(), 0f, start + 8f, size.toFloat(), paint)
                else canvas.drawRect(0f, start.toFloat(), size.toFloat(), start + 8f, paint)
            }
        }

    /** Contrast divided by brightness: darkening alone must not pass as blur. */
    private fun bandStats(bitmap: Bitmap, axisFraction: Float, axisY: Boolean): Pair<Double, Double> {
        val axis = (bitmap.width * axisFraction).toInt()
        val samples = (bitmap.height / 4 until bitmap.height * 3 / 4).map { across ->
            val pixel = if (axisY) bitmap.getPixel(across, axis) else bitmap.getPixel(axis, across)
            Color.red(pixel).toDouble()
        }
        val mean = samples.average()
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        return mean to sqrt(variance) / mean.coerceAtLeast(1.0)
    }

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
}
