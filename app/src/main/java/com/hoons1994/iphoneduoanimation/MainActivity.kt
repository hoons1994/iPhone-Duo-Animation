package com.hoons1994.iphoneduoanimation

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
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

    private lateinit var controlsPanel: LinearLayout
    private lateinit var progressSeekBar: SeekBar
    private lateinit var stateText: TextView
    private lateinit var snapshotStatusText: TextView
    private lateinit var presentationStatusText: TextView
    private lateinit var modeButton: Button
    private lateinit var demoButton: Button

    private var sensorMode = false
    private var userDragging = false
    private var fullScreenPreview = false
    private var lastProgress = 0f
    private var lastOpening = true
    private var lastRawAngle = Float.NaN
    private var lastFilteredAngle = Float.NaN
    private var lastSource = "startup"

    private var latestRawProgress = Float.NaN
    private var latestSensorOpening = true
    private var lastHingeSampleElapsedMs = Long.MIN_VALUE

    private var physicalSurfaceInitialized = false
    private var lastPhysicalCover: Boolean? = null
    private var autoAnimator: ValueAnimator? = null

    private val tuningPrefs by lazy {
        getSharedPreferences("duo_transition_tuning", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val bounds = windowManager.currentWindowMetrics.bounds
            val startupSurface = SurfaceClassifier.classify(bounds.width(), bounds.height())
            lastProgress = if (startupSurface == SurfaceClassifier.Surface.INNER) 1f else 0f
            lastOpening = startupSurface != SurfaceClassifier.Surface.COVER
            lastSource = when (startupSurface) {
                SurfaceClassifier.Surface.COVER -> "startup cover seed"
                SurfaceClassifier.Surface.INNER -> "startup inner seed"
                SurfaceClassifier.Surface.UNKNOWN -> "startup"
            }
        } else {
            lastProgress = savedInstanceState.getFloat(KEY_STATE_PROGRESS, 0f)
            lastOpening = savedInstanceState.getBoolean(KEY_STATE_OPENING, true)
            lastRawAngle = savedInstanceState.getFloat(KEY_STATE_RAW_ANGLE, Float.NaN)
            lastFilteredAngle = savedInstanceState.getFloat(KEY_STATE_FILTERED_ANGLE, Float.NaN)
            fullScreenPreview = savedInstanceState.getBoolean(KEY_STATE_FULLSCREEN, false)
        }
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
            initialOpeningHistory = readCalibrationHistory(KEY_OPENING_HISTORY),
            initialClosingHistory = readCalibrationHistory(KEY_CLOSING_HISTORY),
            initialOpeningAcceptedCount = tuningPrefs.getInt(KEY_OPENING_COUNT, 0),
            initialClosingAcceptedCount = tuningPrefs.getInt(KEY_CLOSING_COUNT, 0),
        )

        snapshotStore = SnapshotStore(this)
        coverBitmap = snapshotStore.loadOrFallback(SnapshotStore.Kind.COVER)
        innerBitmap = snapshotStore.loadOrFallback(SnapshotStore.Kind.INNER)
        presentationController = DuoPresentationController(this)

        hingeMonitor = HingeAngleMonitor(this) { sample ->
            if (!sensorMode || userDragging || autoAnimator != null) return@HingeAngleMonitor

            // Keep the raw sample immediately available to surface-change calibration.
            // Rendering uses the filtered value below; calibration must not inherit filter lag.
            lastHingeSampleElapsedMs = SystemClock.elapsedRealtime()
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
        applyControlsVisibility()
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
        applySystemBarsVisibility()
    }

    override fun onPause() {
        hingeMonitor.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarsVisibility()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat(KEY_STATE_PROGRESS, lastProgress)
        outState.putBoolean(KEY_STATE_OPENING, lastOpening)
        outState.putFloat(KEY_STATE_RAW_ANGLE, lastRawAngle)
        outState.putFloat(KEY_STATE_FILTERED_ANGLE, lastFilteredAngle)
        outState.putBoolean(KEY_STATE_SENSOR_MODE, sensorMode)
        outState.putBoolean(KEY_STATE_FULLSCREEN, fullScreenPreview)
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
            setOnClickListener {
                if (fullScreenPreview) {
                    fullScreenPreview = false
                    applyControlsVisibility()
                }
            }
        }
        root.addView(
            transitionView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val horizontalPadding = (14f * density).toInt()
        val topPadding = (10f * density).toInt()
        val bottomPadding = (14f * density).toInt()

        controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
            setBackgroundColor(Color.argb(226, 10, 12, 18))
            setOnApplyWindowInsetsListener { view, insets ->
                val safeInsets = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                view.setPadding(
                    horizontalPadding + safeInsets.left,
                    topPadding,
                    horizontalPadding + safeInsets.right,
                    bottomPadding + safeInsets.bottom,
                )
                insets
            }
        }

        stateText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "v11 adaptive handoff engine · waiting for surface"
            maxLines = 3
        }
        controlsPanel.addView(stateText)

        snapshotStatusText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
        }
        controlsPanel.addView(snapshotStatusText)

        presentationStatusText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 10f
            maxLines = 5
        }
        controlsPanel.addView(presentationStatusText)

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
        controlsPanel.addView(
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
        controlsPanel.addView(importRow)

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
        controlsPanel.addView(previewRow)

        controlsPanel.addView(
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
        controlsPanel.addView(
            modeButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        controlsPanel.addView(
            Button(this).apply {
                isAllCaps = false
                text = "Full-screen preview · tap image to exit"
                setOnClickListener {
                    fullScreenPreview = true
                    applyControlsVisibility()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(
            controlsPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        controlsPanel.requestApplyInsets()

        updateStateText()
        return root
    }

    private fun applyControlsVisibility() {
        if (!::controlsPanel.isInitialized) return
        controlsPanel.visibility = if (fullScreenPreview) View.GONE else View.VISIBLE
        applySystemBarsVisibility()
        if (!fullScreenPreview) controlsPanel.requestApplyInsets()
    }

    private fun applySystemBarsVisibility() {
        val controller = window.insetsController ?: return
        if (fullScreenPreview) {
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.systemBars())
        } else {
            controller.show(WindowInsets.Type.systemBars())
        }
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
            val sampleAge = if (lastHingeSampleElapsedMs == Long.MIN_VALUE) {
                Long.MAX_VALUE
            } else {
                SystemClock.elapsedRealtime() - lastHingeSampleElapsedMs
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
        val openingState = handoffCalibrator.state(true)
        val closingState = handoffCalibrator.state(false)
        tuningPrefs.edit()
            .putFloat(KEY_OPENING_HANDOFF, openingState.estimate)
            .putFloat(KEY_CLOSING_HANDOFF, closingState.estimate)
            .putString(KEY_OPENING_HISTORY, encodeCalibrationHistory(openingState.recentSamples))
            .putString(KEY_CLOSING_HISTORY, encodeCalibrationHistory(closingState.recentSamples))
            .putInt(KEY_OPENING_COUNT, openingState.acceptedCount)
            .putInt(KEY_CLOSING_COUNT, closingState.acceptedCount)
            .apply()
    }

    private fun readCalibrationHistory(key: String): List<Float> =
        tuningPrefs.getString(key, null)
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.filter { it in TransitionTuning.MIN_HANDOFF_PROGRESS..TransitionTuning.MAX_HANDOFF_PROGRESS }
            ?.takeLast(TransitionTuning.CALIBRATION_HISTORY_LIMIT)
            .orEmpty()

    private fun encodeCalibrationHistory(values: List<Float>): String =
        values.joinToString(separator = ",") { String.format(Locale.US, "%.6f", it) }

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
        val bridge = TransitionTuning.sourceBlend(lastProgress, handoff, renderedCover)
        val confidence = handoffCalibrator.confidence(lastOpening)
        val samples = handoffCalibrator.sampleCount(lastOpening)

        stateText.text = String.format(
            Locale.US,
            "v11 · hinge raw %.1f° / filtered %.1f° · p %.3f\n" +
                "handoff %.1f° · focus %.0f%% · bridge %.0f%% · cal %.0f%% (n=%d)\n" +
                "%s · %s",
            rawAngle,
            filteredAngle,
            lastProgress,
            handoff * 180f,
            focus * 100f,
            bridge * 100f,
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
        private const val KEY_OPENING_HISTORY = "opening_handoff_history"
        private const val KEY_CLOSING_HISTORY = "closing_handoff_history"
        private const val KEY_OPENING_COUNT = "opening_handoff_count"
        private const val KEY_CLOSING_COUNT = "closing_handoff_count"

        private const val KEY_STATE_PROGRESS = "state_progress"
        private const val KEY_STATE_OPENING = "state_opening"
        private const val KEY_STATE_RAW_ANGLE = "state_raw_angle"
        private const val KEY_STATE_FILTERED_ANGLE = "state_filtered_angle"
        private const val KEY_STATE_SENSOR_MODE = "state_sensor_mode"
        private const val KEY_STATE_FULLSCREEN = "state_fullscreen_preview"
    }
}
