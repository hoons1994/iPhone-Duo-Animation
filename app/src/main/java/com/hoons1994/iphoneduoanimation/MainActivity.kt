package com.hoons1994.iphoneduoanimation

import android.app.Activity
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.pow

class MainActivity : Activity() {

    private lateinit var shader: RuntimeShader
    private lateinit var demoView: DemoHomeView
    private lateinit var hingeMonitor: HingeAngleMonitor
    private lateinit var progressSeekBar: SeekBar
    private lateinit var stateText: TextView
    private lateinit var modeButton: Button
    private lateinit var effectTestButton: Button

    private var sensorMode = false
    private var userDragging = false
    private var lastProgress = 0f
    private var lastOpening = true
    private var coverSurface = false
    private var lastSource = "startup"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shader = RuntimeShader(DuoShader.SOURCE)
        shader.setFloatUniform("resolution", 1080f, 2400f)
        shader.setFloatUniform("progress", 0f)
        shader.setFloatUniform("opening", 1f)
        shader.setFloatUniform("coverSurface", 0f)
        shader.setFloatUniform("maxBlurPx", 20f * resources.displayMetrics.density)
        shader.setFloatUniform("scaleDip", 0.010f)

        hingeMonitor = HingeAngleMonitor(this) { angle, progress, opening ->
            if (!sensorMode || userDragging) return@HingeAngleMonitor
            runOnUiThread {
                applyProgress(progress, opening, "hinge ${formatAngle(angle)}°")
                progressSeekBar.progress = (progress * SEEK_MAX).toInt()
            }
        }

        sensorMode = hingeMonitor.isAvailable
        setContentView(buildUi())
        updateModeButton()
    }

    override fun onResume() {
        super.onResume()
        if (sensorMode) hingeMonitor.start()
    }

    override fun onPause() {
        hingeMonitor.stop()
        super.onPause()
    }

    private fun buildUi(): FrameLayout {
        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        demoView = DemoHomeView(this).apply {
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (width > 0 && height > 0) {
                    shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())

                    val shorter = minOf(width, height).toFloat()
                    val longer = maxOf(width, height).toFloat()
                    coverSurface = (shorter / longer) < COVER_ASPECT_THRESHOLD
                    shader.setFloatUniform("coverSurface", if (coverSurface) 1f else 0f)

                    refreshRenderEffect()
                    updateStateText()
                }
            }
        }
        root.addView(
            demoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (18f * density).toInt(),
                (12f * density).toInt(),
                (18f * density).toInt(),
                (18f * density).toInt(),
            )
            setBackgroundColor(Color.argb(220, 12, 14, 20))
        }

        stateText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "v5 · progress 0.000 · fx -- · detecting surface"
        }
        controls.addView(
            stateText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        progressSeekBar = SeekBar(this).apply {
            max = SEEK_MAX
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val progress = value.toFloat() / SEEK_MAX.toFloat()
                    val opening = progress >= lastProgress
                    applyProgress(progress, opening, "manual")
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userDragging = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userDragging = false
                }
            })
        }
        controls.addView(
            progressSeekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        effectTestButton = Button(this).apply {
            isAllCaps = false
            text = "Force 50% spatial FX · diagnostic"
            setOnClickListener {
                sensorMode = false
                hingeMonitor.stop()
                progressSeekBar.progress = SEEK_MAX / 2
                applyProgress(0.5f, true, "forced 90°")
                updateModeButton()
            }
        }
        controls.addView(
            effectTestButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        modeButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                sensorMode = hingeMonitor.isAvailable && !sensorMode
                hingeMonitor.stop()
                if (sensorMode) hingeMonitor.start()
                updateModeButton()
            }
        }
        controls.addView(
            modeButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(
            controls,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        return root
    }

    private fun applyProgress(progress: Float, opening: Boolean, source: String) {
        val clamped = progress.coerceIn(0f, 1f)
        lastProgress = clamped
        lastOpening = opening
        lastSource = source

        shader.setFloatUniform("progress", clamped)
        shader.setFloatUniform("opening", if (opening) 1f else 0f)
        shader.setFloatUniform("coverSurface", if (coverSurface) 1f else 0f)
        refreshRenderEffect()
        updateStateText()
    }

    private fun refreshRenderEffect() {
        // Recreate the shader effect to explicitly dirty the RenderNode on each
        // hinge update. There is intentionally no chained global blur in v5.
        demoView.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        demoView.invalidate()
    }

    private fun transitionAmount(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        val linear = if (coverSurface) t else 1f - t
        return linear.coerceIn(0f, 1f).pow(0.82f)
    }

    private fun updateStateText() {
        if (!::stateText.isInitialized) return
        val amount = transitionAmount(lastProgress)
        stateText.text = String.format(
            Locale.US,
            "v5 · progress %.3f  ·  fx %.0f%%  ·  %s  ·  %s  ·  %s",
            lastProgress,
            amount * 100f,
            if (coverSurface) "cover" else "inner",
            if (lastOpening) "opening" else "closing",
            lastSource,
        )
    }

    private fun updateModeButton() {
        modeButton.text = when {
            !hingeMonitor.isAvailable -> "Hinge sensor unavailable · manual mode"
            sensorMode -> "Sensor mode · tap for manual"
            else -> "Manual mode · tap for hinge sensor"
        }
        modeButton.isEnabled = hingeMonitor.isAvailable
    }

    private fun formatAngle(value: Float): String = String.format(Locale.US, "%.1f", value)

    companion object {
        private const val SEEK_MAX = 1000
        private const val COVER_ASPECT_THRESHOLD = 0.62f
    }
}
