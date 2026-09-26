package com.hoons1994.iphoneduoanimation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

/** Exercises the actual RenderEffect graph, not a BitmapShader stand-in. */
@RunWith(AndroidJUnit4::class)
class LiveFoldRenderTest {
    @Test
    fun liveGraph_keepsFixedPanelSharp_andFrostsMovingPanel_inEveryRotation() {
        GpuScene().use { scene ->
            val effects = LiveFoldEffects()
            for (rotation in 0..3) {
                val geometry = geometry(rotation = rotation, angle = 120f)
                scene.record { stripes(it, geometry.axisY) }
                scene.effect = effects.create(geometry)
                val frame = scene.render()
                try {
                    val moving = bandStats(frame, geometry, 0.2f)
                    val fixed = bandStats(frame, geometry, 0.75f)
                    assertTrue("moving pane black at rotation $rotation", moving.first > 25.0)
                    assertTrue("moving pane not frosted at rotation $rotation: $moving", moving.second < 0.6)
                    assertTrue("fixed pane blurred at rotation $rotation: $fixed", fixed.second > 0.9)
                    // Opaque source must stay opaque across every blur weight.
                    for (axis in 1..19) {
                        val point = screenPoint(geometry, axis / 20f, 0.5f)
                        assertTrue("blur weights lost opacity at rotation $rotation, axis $axis",
                            Color.alpha(frame.getPixel(point.first, point.second)) >= 254)
                    }
                } finally {
                    frame.recycle()
                }
            }
        }
    }

    @Test
    fun changingLiveContent_atTheSameAngle_updatesWithoutRecreatingTheEffect() {
        GpuScene().use { scene ->
            scene.effect = LiveFoldEffects().create(geometry(angle = 120f))
            scene.record { it.drawColor(Color.RED) }
            val first = scene.render()
            scene.record { it.drawColor(Color.BLUE) }
            val second = scene.render()
            try {
                val x = SIZE / 5
                val y = SIZE / 2
                assertTrue(Color.red(first.getPixel(x, y)) > 100)
                assertTrue(Color.blue(first.getPixel(x, y)) < 10)
                assertTrue("old content survived live source redraw", Color.blue(second.getPixel(x, y)) > 100)
                assertTrue(Color.red(second.getPixel(x, y)) < 10)
            } finally {
                first.recycle()
                second.recycle()
            }
        }
    }

    @Test
    fun rebuildingGraphWithNewGeometry_updatesTheImage_andPreservesEarlierEffect() {
        GpuScene().use { scene ->
            scene.record { stripes(it, axisY = false) }
            val effects = LiveFoldEffects()
            val earlier = effects.create(geometry(angle = 110f))
            val later = effects.create(geometry(angle = 160f))
            scene.effect = earlier
            val first = scene.render()
            scene.effect = later
            val second = scene.render()
            scene.effect = earlier
            val restored = scene.render()
            try {
                assertTrue("changed angle retained old uniform values", meanDifference(first, second) > 3.0)
                assertTrue("creating the later effect mutated the earlier one", first.sameAs(restored))
            } finally {
                first.recycle()
                second.recycle()
                restored.recycle()
            }
        }
    }

    @Test
    fun clearingTheGraphAtResolvedEndpoints_restoresExactOriginalPixels() {
        GpuScene().use { scene ->
            scene.record { stripes(it, axisY = false) }
            val baseline = scene.render()
            try {
                val effects = LiveFoldEffects()
                for (cover in listOf(false, true)) {
                    scene.effect = effects.create(geometry(cover = cover, angle = 98f))
                    scene.render().recycle()
                    val endpoint = geometry(cover = cover, angle = if (cover) 0f else 180f)
                    assertFalse(endpoint.active)
                    scene.effect = null
                    val clear = scene.render()
                    try {
                        assertTrue("endpoint kept a filtered or stale layer", baseline.sameAs(clear))
                    } finally {
                        clear.recycle()
                    }
                }
            } finally {
                baseline.recycle()
            }
        }
    }

    private fun geometry(rotation: Int = 0, cover: Boolean = false, angle: Float) = LiveFoldGeometry(
        width = SIZE,
        height = SIZE,
        cover = cover,
        rotation = rotation,
        progress = angle / 180f,
        opening = true,
        handoff = TransitionTuning.DEFAULT_HANDOFF_PROGRESS,
        pixelsPerMm = 2f,
    )

    private fun stripes(canvas: Canvas, axisY: Boolean) {
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK }
        for (start in 0 until SIZE step 16) {
            if (axisY) canvas.drawRect(start.toFloat(), 0f, start + 8f, SIZE.toFloat(), paint)
            else canvas.drawRect(0f, start.toFloat(), SIZE.toFloat(), start + 8f, paint)
        }
    }

    private fun screenPoint(geometry: LiveFoldGeometry, axis: Float, across: Float): Pair<Int, Int> {
        val rawAxis = ((if (geometry.hingeFromEnd) 1f - axis else axis) * SIZE).toInt()
        val acrossPx = (across * SIZE).toInt()
        return if (geometry.axisY) acrossPx to rawAxis else rawAxis to acrossPx
    }

    /** Contrast normalized by brightness prevents darkening from passing as blur. */
    private fun bandStats(bitmap: Bitmap, geometry: LiveFoldGeometry, axis: Float): Pair<Double, Double> {
        val values = (SIZE / 4 until SIZE * 3 / 4).map { across ->
            val point = screenPoint(geometry, axis, across.toFloat() / SIZE)
            Color.red(bitmap.getPixel(point.first, point.second)).toDouble()
        }
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return mean to sqrt(variance) / mean.coerceAtLeast(1.0)
    }

    private fun meanDifference(first: Bitmap, second: Bitmap): Double {
        var difference = 0L
        var count = 0
        for (y in SIZE / 4 until SIZE * 3 / 4) for (x in SIZE / 10 until SIZE * 4 / 10) {
            difference += abs(Color.red(first.getPixel(x, y)) - Color.red(second.getPixel(x, y)))
            count++
        }
        return difference.toDouble() / count
    }

    /** Keeps the same RenderNode and renderer alive for redraw and stale-state checks. */
    private class GpuScene : AutoCloseable {
        private val thread = HandlerThread("live-fold-test-readback").apply { start() }
        private val pendingFrame = AtomicReference<CountDownLatch?>()
        private val reader = ImageReader.newInstance(
            SIZE, SIZE, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        ).apply {
            setOnImageAvailableListener({ pendingFrame.get()?.countDown() }, Handler(thread.looper))
        }
        private val node = RenderNode("live-fold-test").apply { setPosition(0, 0, SIZE, SIZE) }
        private val renderer = HardwareRenderer().apply {
            setOpaque(false)
            setSurface(reader.surface)
            setContentRoot(node)
        }

        var effect: RenderEffect? = null
            set(value) {
                field = value
                node.setRenderEffect(value)
            }

        fun record(draw: (Canvas) -> Unit) {
            val canvas = node.beginRecording(SIZE, SIZE)
            try {
                draw(canvas)
            } finally {
                node.endRecording()
            }
        }

        fun render(): Bitmap {
            val available = CountDownLatch(1)
            pendingFrame.set(available)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            assertTrue("GPU frame not available", available.await(5, TimeUnit.SECONDS))
            pendingFrame.set(null)
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
        }

        override fun close() {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
            thread.quitSafely()
            thread.join(1000)
        }
    }

    private companion object {
        const val SIZE = 384
    }
}
