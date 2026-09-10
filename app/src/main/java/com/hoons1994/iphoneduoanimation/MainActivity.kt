package com.hoons1994.iphoneduoanimation

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var snapshotStore: SnapshotStore
    private lateinit var transitionView: SnapshotTransitionView
    private lateinit var hingeMonitor: HingeAngleMonitor
    private lateinit var presentationController: DuoPresentationController
    private lateinit var handoffCalibrator: HandoffCalibrator

    private lateinit var coverBitmap: Bitmap
    private lateinit var innerBitmap: Bitmap

    private lateinit var progressSeekBar: SeekBar
    private lateinit var stateText: TextView
    private lateinit var snapshotStatusText: TextView
    private lateinit var presentationStatusText: TextView
    private lateinit var modeButton: Button
    private lateinit var demoButton: Button

    private var sensorMode = false
    private var userDragging = false
    private var lastProgress = 0f
    private var lastOpening = true
    private var lastRawAngle = Float.NaN
    private var lastFilteredAngle = Float.NaN
    private var lastSource = "startup"

    private var latestRawProgress = Float.NaN
    private var latestSensorOpening = true
    private var lastHingeSampleUptimeMs = Long.MIN_VALUE

    private var physicalSurfaceInitialized = false
    private var lastPhysicalCover: Boolean? = null
    private var autoAnimator: ValueAnimator? = null

    private val tuningPrefs by lazy {
        getSharedPreferences("duo_transition_tuning", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lastProgress = savedInstanceState?.getFloat(KEY_STATE_PROGRESS, 0f) ?: 0f
        lastOpening = savedInstanceState?.getBoolean(KEY_STATE_OPENING, true) ?: true
        lastRawAngle = savedInstanceState?.getFloat(KEY_STATE_RAW_ANGLE, Float.NaN) ?: Float.NaN
        lastFilteredAngle = savedInstanceState?.getFloat(KEY_STATE_FILTERED_ANGLE, Float.NaN) ?: Float.NaN
        val requestedSensorMode = savedInstanceState?.getBoolean(KEY_STATE_SENSOR_MODE, true) ?: true

        handoffCalibrator = HandoffCalibrator(
            initialOpening = tuningPrefs.getFloat(
                KEY_OPENING_HANDOFF,
                TransitionTuning.DEFAULT_HANDOFF_PROGRESS,
            ),
            initialClosing = tuningPrefs.getFloat(
                KEY_CLOSING_HANDOFF,
                TransitionTuning.DEFAULT_HANDOFF_PROGRESS,
            ),
        )

        snapshotStore = SnapshotStore(this)
        coverBitmap = snapshotStore.loadOrFallback(SnapshotStore.Kind.COVER)
        innerBitmap = snapshotStore.loadOrFallback(SnapshotStore.Kind.INNER)
        presentationController = DuoPresentationController(this)

        hingeMonitor = HingeAngleMonitor(this) { sample ->
            if (!sensorMode || userDragging || autoAnimator != null) return@HingeAngleMonitor

            // Keep the raw sample immediately available to surface-change calibration.
            // Rendering uses the filtered value below; calibration must not inherit filter lag.
            lastHingeSampleUptimeMs = SystemClock.uptimeMillis()
            lastRawAngle = sample.rawAngleDegrees
            lastFilteredAngle = sample.filteredAngleDegrees
            latestRawProgress = (sample.rawAngleDegrees / 180f).coerceIn(0f, 1f)
            latestSensorOpening = sample.opening

            runOnUiThread {
                applyProgress(sample.filteredProgress, sample.opening, "hinge")
                progressSeekBar.progress = (sample.filteredProgress * SEEK_MAX).toInt()
            }
        }

        sensorMode = requestedSensorMode && hingeMonitor.isAvailable
        setContentView(buildUi())
        updateModeButton()
        updateSnapshotStatus()
        presentationStatusText.text = "Displays · ${presentationController.describeDisplays()}"
    }

    override fun onResume() {
        super.onResume()
        if (sensorMode && autoAnimator == null) hingeMonitor.start()
        if (::presentationStatusText.isInitialized) {
            presentationStatusText.text = "Displays · ${presentationController.describeDisplays()}"
        }
    }

    override fun onPause() {
        hingeMonitor.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat(KEY_STATE_PROGRESS, lastProgress)
        outState.putBoolean(KEY_STATE_OPENING, lastOpening)
        outState.putFloat(KEY_STATE_RAW_ANGLE, lastRawAngle)
        outState.putFloat(KEY_STATE_FILTERED_ANGLE, lastFilteredAngle)
        outState.putBoolean(KEY_STATE_SENSOR_MODE, sensorMode)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        stopAutoDemo(restorePhysicalSurface = false)
        presentationController.dismiss()
        super.onDestroy()
    }

    @Deprecated("Legacy result API is sufficient for this dependency-free POC")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        val kind = when (requestCode) {
            REQUEST_COVER_SNAPSHOT -> SnapshotStore.Kind.COVER
            REQUEST_INNER_SNAPSHOT -> SnapshotStore.Kind.INNER
            else -> return
        }

        snapshotStore.persist(kind, uri)
        reloadSnapshots()
    }

    private fun buildUi(): FrameLayout {
        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        transitionView = SnapshotTransitionView(this).apply {
            setSnapshots(coverBitmap, innerBitmap)
            setHandoffProgress(currentHandoff(lastOpening))
            updateProgress(lastProgress, lastOpening)
            onSurfaceChanged = { isCover ->
                handlePhysicalSurfaceChange(isCover)
            }
        }
        root.addView(
            transitionView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (14f * density).toInt(),
                (10f * density).toInt(),
                (14f * density).toInt(),
                (14f * density).toInt(),
            )
            setBackgroundColor(Color.argb(226, 10, 12, 18))
        }

        stateText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "v9 adaptive handoff engine · waiting for surface"
            maxLines = 3
        }
        controls.addView(stateText)

        snapshotStatusText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
        }
        controls.addView(snapshotStatusText)

        presentationStatusText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 10f
            maxLines = 5
        }
        controls.addView(presentationStatusText)

        progressSeekBar = SeekBar(this).apply {
            max = SEEK_MAX
            progress = (lastProgress * SEEK_MAX).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    stopAutoDemo()
                    val progress = value.toFloat() / SEEK_MAX.toFloat()
                    val opening = progress >= lastProgress
                    lastRawAngle = progress * 180f
                    lastFilteredAngle = lastRawAngle
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

        val importRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        importRow.addView(
            Button(this).apply {
                isAllCaps = false
                text = "Pick cover shot"
                setOnClickListener { pickSnapshot(REQUEST_COVER_SNAPSHOT) }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        importRow.addView(
            Button(this).apply {
                isAllCaps = false
                text = "Pick inner shot"
                setOnClickListener { pickSnapshot(REQUEST_INNER_SNAPSHOT) }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(importRow)

        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        demoButton = Button(this).apply {
            isAllCaps = false
            text = "Auto preview"
            setOnClickListener { toggleAutoDemo() }
        }
        previewRow.addView(
            demoButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        previewRow.addView(
            Button(this).apply {
                isAllCaps = false
                text = "Force handoff"
                setOnClickListener {
                    stopAutoDemo()
                    sensorMode = false
                    hingeMonitor.stop()
                    val handoff = currentHandoff(lastOpening)
                    lastRawAngle = handoff * 180f
                    lastFilteredAngle = lastRawAngle
                    progressSeekBar.progress = (handoff * SEEK_MAX).toInt()
                    applyProgress(handoff, lastOpening, "forced handoff")
                    updateModeButton()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(previewRow)

        controls.addView(
            Button(this).apply {
                isAllCaps = false
                text = "Try 2-screen Presentation"
                setOnClickListener { startPresentation() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        modeButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                stopAutoDemo()
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

        updateStateText()
        return root
    }

    private fun pickSnapshot(requestCode: Int) {
        stopAutoDemo()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, requestCode)
    }

    private fun reloadSnapshots() {
        coverBitmap = snapshotStore.loadOrFallback(SnapshotStore.Kind.COVER)
        innerBitmap = snapshotStore.loadOrFallback(SnapshotStore.Kind.INNER)
        transitionView.setSnapshots(coverBitmap, innerBitmap)
        presentationController.updateSnapshots(coverBitmap, innerBitmap)
        updateSnapshotStatus()
    }

    private fun startPresentation() {
        val handoff = currentHandoff(lastOpening)
        presentationController.tryShow(
            cover = coverBitmap,
            inner = innerBitmap,
            progress = lastProgress,
            opening = lastOpening,
            handoffProgress = handoff,
        ) { status ->
            runOnUiThread {
                presentationStatusText.text = status
            }
        }
    }

    private fun handlePhysicalSurfaceChange(isCover: Boolean) {
        val previous = lastPhysicalCover
        lastPhysicalCover = isCover

        if (!physicalSurfaceInitialized) {
            physicalSurfaceInitialized = true
            updateStateText()
            return
        }

        if (previous != null && previous != isCover && sensorMode && autoAnimator == null) {
            val sampleAge = if (lastHingeSampleUptimeMs == Long.MIN_VALUE) {
                Long.MAX_VALUE
            } else {
                SystemClock.uptimeMillis() - lastHingeSampleUptimeMs
            }

            if (latestRawProgress.isNaN() || sampleAge !in 0..TransitionTuning.MAX_SURFACE_EVENT_AGE_MS) {
                lastSource = "surface switch · calibration skipped (stale hinge)"
            } else {
                val observation = handoffCalibrator.observe(
                    isOpening = latestSensorOpening,
                    fromCover = previous,
                    toCover = isCover,
                    progress = latestRawProgress,
                )

                if (observation.accepted) {
                    persistHandoffCalibration()
                    lastSource = "hinge + learned handoff"
                    val handoff = currentHandoff(latestSensorOpening)
                    transitionView.setHandoffProgress(handoff)
                    presentationController.updateHandoffProgress(handoff)
                } else {
                    lastSource = "surface switch ignored · ${observation.rejectReason}"
                }
            }
        }
        updateStateText()
    }

    private fun applyProgress(progress: Float, opening: Boolean, source: String) {
        val clamped = TransitionTuning.clampProgress(progress)
        lastProgress = clamped
        lastOpening = opening
        lastSource = source

        val handoff = currentHandoff(opening)
        transitionView.setHandoffProgress(handoff)
        transitionView.updateProgress(clamped, opening)
        presentationController.updateHandoffProgress(handoff)
        presentationController.update(clamped, opening)
        updateStateText()
    }

    private fun toggleAutoDemo() {
        if (autoAnimator != null) {
            stopAutoDemo()
        } else {
            startAutoDemo()
        }
    }

    private fun startAutoDemo() {
        stopAutoDemo()
        sensorMode = false
        hingeMonitor.stop()
        updateModeButton()

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { valueAnimator ->
                val progress = valueAnimator.animatedValue as Float
                val opening = progress >= lastProgress
                val handoff = currentHandoff(opening)
                transitionView.setPreviewSurfaceOverride(
                    TransitionTuning.coverForPreview(progress, handoff),
                )
                lastRawAngle = progress * 180f
                lastFilteredAngle = lastRawAngle
                progressSeekBar.progress = (progress * SEEK_MAX).toInt()
                applyProgress(progress, opening, "auto preview")
            }
        }
        autoAnimator = animator
        demoButton.text = "Stop auto preview"
        animator.start()
    }

    private fun stopAutoDemo(restorePhysicalSurface: Boolean = true) {
        val animator = autoAnimator
        autoAnimator = null
        animator?.cancel()

        if (restorePhysicalSurface && ::transitionView.isInitialized) {
            transitionView.setPreviewSurfaceOverride(null)
            updateStateText()
        }
        if (::demoButton.isInitialized) demoButton.text = "Auto preview"
    }

    private fun currentHandoff(opening: Boolean): Float = handoffCalibrator.estimate(opening)

    private fun persistHandoffCalibration() {
        tuningPrefs.edit()
            .putFloat(KEY_OPENING_HANDOFF, handoffCalibrator.estimate(true))
            .putFloat(KEY_CLOSING_HANDOFF, handoffCalibrator.estimate(false))
            .apply()
    }

    private fun updateStateText() {
        if (!::stateText.isInitialized || !::transitionView.isInitialized) return

        val filteredAngle = if (lastFilteredAngle.isNaN()) lastProgress * 180f else lastFilteredAngle
        val rawAngle = if (lastRawAngle.isNaN()) filteredAngle else lastRawAngle
        val handoff = currentHandoff(lastOpening)
        val physical = if (transitionView.isCoverSurface()) "cover" else "inner"
        val renderedCover = transitionView.effectiveCoverSurface()
        val rendered = if (renderedCover) "cover" else "inner"
        val surfaceLabel = if (physical == rendered) physical else "$physical→$rendered preview"
        val focus = TransitionTuning.surfaceFocus(lastProgress, handoff, renderedCover)
        val confidence = handoffCalibrator.confidence(lastOpening)
        val samples = handoffCalibrator.sampleCount(lastOpening)

        stateText.text = String.format(
            Locale.US,
            "v9 · hinge raw %.1f° / filtered %.1f° · p %.3f\n" +
                "handoff %.1f° · focus %.0f%% · cal %.0f%% (n=%d) · %s · %s",
            rawAngle,
            filteredAngle,
            lastProgress,
            handoff * 180f,
            focus * 100f,
            confidence * 100f,
            samples,
            surfaceLabel,
            lastSource,
        )
    }

    private fun updateSnapshotStatus() {
        if (!::snapshotStatusText.isInitialized) return
        val coverReady = snapshotStore.hasSnapshot(SnapshotStore.Kind.COVER)
        val innerReady = snapshotStore.hasSnapshot(SnapshotStore.Kind.INNER)
        snapshotStatusText.text = buildString {
            append("snapshots · cover ")
            append(if (coverReady) "imported" else "FALLBACK")
            append(" · inner ")
            append(if (innerReady) "imported" else "FALLBACK")
            if (!coverReady || !innerReady) append(" · import both for meaningful preview")
        }
    }

    private fun updateModeButton() {
        modeButton.text = when {
            !hingeMonitor.isAvailable -> "Hinge sensor unavailable · manual mode"
            sensorMode -> "Sensor mode · tap for manual"
            else -> "Manual/preview mode · tap for hinge sensor"
        }
        modeButton.isEnabled = hingeMonitor.isAvailable
    }

    companion object {
        private const val SEEK_MAX = 1000
        private const val REQUEST_COVER_SNAPSHOT = 3101
        private const val REQUEST_INNER_SNAPSHOT = 3102

        private const val KEY_OPENING_HANDOFF = "opening_handoff_progress"
        private const val KEY_CLOSING_HANDOFF = "closing_handoff_progress"

        private const val KEY_STATE_PROGRESS = "state_progress"
        private const val KEY_STATE_OPENING = "state_opening"
        private const val KEY_STATE_RAW_ANGLE = "state_raw_angle"
        private const val KEY_STATE_FILTERED_ANGLE = "state_filtered_angle"
        private const val KEY_STATE_SENSOR_MODE = "state_sensor_mode"
    }
}
