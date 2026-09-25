package com.hoons1994.iphoneduoanimation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeShaderSmokeTest {
    private val size = 256

    @Test fun shaderCompilesAndResolvedSurfacesUseTheCorrectSource() {
        val red = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val closed = render(configure(red, blue, 0f, cover = true))
        val open = render(configure(red, blue, 0f))
        assertEquals(Color.RED, closed.getPixel(128, 128))
        assertEquals(Color.BLUE, open.getPixel(128, 128))
    }

    @Test fun gpuProjectionMatchesCpuAndLeavesFixedPaneUnchanged() {
        val source = gradient()
        for (axis in listOf(false, true)) for (moving in listOf(false, true)) {
            val shader = configure(source, source, 60f, axis = axis, moving = moving)
            val output = render(shader)
            for (point in listOf(40 to 70, 128 to 90, 210 to 80, 190 to 170)) {
                val (x, y) = point
                val expected = FoldProjection.project(x + 0.5f, y + 0.5f, size.toFloat(), size.toFloat(),
                    60f, axisY = axis, movingFromEnd = moving)
                val pixel = output.getPixel(x, y)
                assertTrue("x projection mismatch at $point: ${Color.red(pixel)} vs ${expected.x}",
                    abs(Color.red(pixel) - expected.x) <= 3f)
                assertTrue("y projection mismatch at $point: ${Color.green(pixel)} vs ${expected.y}",
                    abs(Color.green(pixel) - expected.y) <= 3f)
            }
        }
    }

    @Test fun coverProjectionTracksEitherHingeEdgeAndAxis() {
        val source = gradient()
        for (axis in listOf(false, true)) for (end in listOf(false, true)) {
            val output = render(configure(source, source, 55f, cover = true, axis = axis, coverEnd = end))
            for ((x, y) in listOf(60 to 80, 190 to 170)) {
                val expected = FoldProjection.project(x + 0.5f, y + 0.5f, 256f, 256f, 55f,
                    cover = true, axisY = axis, coverHingeFromEnd = end)
                val pixel = output.getPixel(x, y)
                assertTrue(abs(Color.red(pixel) - expected.x) <= 3f)
                assertTrue(abs(Color.green(pixel) - expected.y) <= 3f)
            }
        }
    }

    @Test fun sharedAtlasHasActualCrossSurfaceCorrespondence() {
        val source = gradient()
        val inner = render(configure(source, source, 0f, linked = true))
        val cover = render(configure(source, source, 0f, cover = true, linked = true))
        assertTrue(abs(Color.red(inner.getPixel(64, 100)) - Color.red(cover.getPixel(128, 100))) <= 2)
        assertTrue(abs(Color.green(inner.getPixel(64, 100)) - Color.green(cover.getPixel(128, 100))) <= 2)
    }

    @Test fun patternedFramesShowMotionAndRestoreExactEndpoint() {
        val source = CalibrationScene.create()
        val resolved = render(configure(source, source, 0f, linked = true, effects = true))
        val folded = render(configure(source, source, 60f, linked = true, effects = true))
        var fixedDifferences = 0
        var movingDifferences = 0
        for (y in 10 until size - 10) for (x in 10 until size - 10) {
            if (folded.getPixel(x, y) != resolved.getPixel(x, y)) {
                if (x < size / 2) fixedDifferences++ else movingDifferences++
            }
        }
        assertEquals("fixed pane changed", 0, fixedDifferences)
        assertTrue("moving pane barely changed: $movingDifferences", movingDifferences > 8000)
        val restored = render(configure(source, source, 0f, linked = true, effects = true))
        assertTrue("fully open frame failed to restore", resolved.sameAs(restored))
        for (angle in intArrayOf(90, 120, 150, 175, 180)) {
            save(render(configure(source, source, FoldProjection.tiltDegrees(angle.toFloat(), false),
                linked = true, effects = true)), "inner-$angle.png")
        }
        println("PROJECTION_PIXELS fixedDifferences=$fixedDifferences movingDifferences=$movingDifferences")
    }

    @Test fun activityStartsWithVisibleManualProjectionAndAutoDemoMoves() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            lateinit var view: SnapshotTransitionView
            instrumentation.runOnMainSync {
                view = requireNotNull(findView(activity.window.decorView) { it is SnapshotTransitionView }) as SnapshotTransitionView
                assertNull("renderer startup failed", view.rendererError)
                assertEquals(120f / 180f, view.currentProgress(), 0.001f)
                assertFalse(view.effectiveCoverSurface())
            }
            awaitFrame(view)
            instrumentation.runOnMainSync { assertEquals(60f, view.renderedTiltDegrees, 0.01f) }
            instrumentation.uiAutomation.takeScreenshot()?.let { save(it, "activity-manual-120.png") }

            fun click(label: String) {
                instrumentation.runOnMainSync {
                    val button = requireNotNull(findView(activity.window.decorView) { it is Button && it.text.toString() == label })
                    assertTrue("button did not click: $label", button.performClick())
                }
                awaitFrame(view)
            }
            click("180°")
            instrumentation.runOnMainSync { assertEquals(0f, view.renderedTiltDegrees, 0.01f) }
            click("120°")
            click("효과: 켜짐")
            instrumentation.runOnMainSync { assertEquals(0f, view.renderedTiltDegrees, 0.01f) }
            click("효과: 원본")
            instrumentation.runOnMainSync { assertEquals(60f, view.renderedTiltDegrees, 0.01f) }

            click("180°")
            val advanced = CountDownLatch(1)
            lateinit var listener: ViewTreeObserver.OnDrawListener
            instrumentation.runOnMainSync {
                listener = ViewTreeObserver.OnDrawListener {
                    if (view.currentProgress() < 0.90f && view.currentProgress() > 0.50f) advanced.countDown()
                }
                view.viewTreeObserver.addOnDrawListener(listener)
            }
            try {
                click("자동 시연")
                assertTrue("automatic demo did not advance across frames", advanced.await(10, TimeUnit.SECONDS))
                instrumentation.uiAutomation.takeScreenshot()?.let { save(it, "activity-auto.png") }
            } finally {
                instrumentation.runOnMainSync { view.viewTreeObserver.removeOnDrawListener(listener) }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun findView(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findView(view.getChildAt(i), predicate)?.let { return it }
        }
        return null
    }

    private fun awaitFrame(view: View) {
        val committed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.invalidate()
        }
        assertTrue("Activity did not commit a hardware frame", committed.await(10, TimeUnit.SECONDS))
    }

    private fun gradient() = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until size) for (x in 0 until size) setPixel(x, y, Color.rgb(x, y, 80))
    }

    private fun configure(coverBitmap: Bitmap, innerBitmap: Bitmap, tilt: Float,
                          cover: Boolean = false, axis: Boolean = false, moving: Boolean = true,
                          coverEnd: Boolean = false, linked: Boolean = false, effects: Boolean = false): RuntimeShader {
        return RuntimeShader(SnapshotTransitionShader.SOURCE).apply {
            setInputShader("coverSnapshot", BitmapShader(coverBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setInputShader("innerSnapshot", BitmapShader(innerBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setFloatUniform("coverSize", coverBitmap.width.toFloat(), coverBitmap.height.toFloat())
            setFloatUniform("innerSize", innerBitmap.width.toFloat(), innerBitmap.height.toFloat())
            setFloatUniform("resolution", size.toFloat(), size.toFloat())
            setFloatUniform("tiltRadians", Math.toRadians(tilt.toDouble()).toFloat())
            setFloatUniform("coverSurface", if (cover) 1f else 0f)
            setFloatUniform("hingeAxisY", if (axis) 1f else 0f)
            setFloatUniform("coverHingeFromEnd", if (coverEnd) 1f else 0f)
            setFloatUniform("movingFromEnd", if (moving) 1f else 0f)
            setFloatUniform("linkedScene", if (linked) 1f else 0f)
            setFloatUniform("maxBlurPx", if (effects) 12f else 0f)
            setFloatUniform("shadeStrength", if (effects) 0.16f else 0f)
        }
    }

    /** Hardware path, not Canvas(Bitmap), so it tests the app's AGSL backend. */
    private fun render(shader: RuntimeShader): Bitmap {
        val reader = ImageReader.newInstance(size, size, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = RenderNode("ProjectionTest").apply { setPosition(0, 0, size, size) }
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            val canvas = node.beginRecording(size, size)
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { this.shader = shader })
            node.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage() ?: error("HardwareRenderer produced no image")
            try {
                val buffer = image.hardwareBuffer ?: error("Missing hardware buffer")
                try {
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, null) ?: error("Cannot wrap render output")
                    return try { requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) } finally { bitmap.recycle() }
                } finally { buffer.close() }
            } finally { image.close() }
        } finally {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        // AGP collects this directory before uninstalling the tested app. The
        // old internal-only path disappeared during normal test cleanup.
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val base = output?.let(::File) ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val dir = File(base, "projection-frames")
        check(dir.isDirectory || dir.mkdirs())
        File(dir, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
