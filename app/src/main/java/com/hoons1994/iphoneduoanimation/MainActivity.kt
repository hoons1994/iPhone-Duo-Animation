package com.hoons1994.iphoneduoanimation

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

/** A foreground visual lab. It does not replace One UI or capture other apps. */
class MainActivity : Activity() {
    private lateinit var transitionView: SnapshotTransitionView
    private lateinit var snapshotStore: SnapshotStore
    private lateinit var hingeMonitor: HingeAngleMonitor
    private lateinit var presentation: DuoPresentationController
    private lateinit var scene: Bitmap
    private lateinit var cover: Bitmap
    private lateinit var inner: Bitmap
    private lateinit var hud: TextView
    private lateinit var note: TextView
    private lateinit var controls: ScrollView
    private lateinit var slider: SeekBar
    private lateinit var sensorButton: Button
    private lateinit var demoButton: Button
    private lateinit var surfaceButton: Button
    private lateinit var effectButton: Button
    private lateinit var sideButton: Button
    private lateinit var sceneButton: Button
    private lateinit var revealButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val trace = TransitionTrace()
    private var pendingTrace: String? = null
    private var imageGeneration = 0
    private var started = false
    private var sensorMode = false
    private var linkedScene = true
    private var effectEnabled = true
    private var movingFromEnd = true
    private var revealEnabled = true
    private var previewCover = false
    private var fullScreen = false
    private var angle = 120f
    private var rawAngle = Float.NaN
    private var filteredAngle = Float.NaN
    private var opening = true
    private var lastSampleMs: Long? = null
    private var lastLoggedSampleMs = 0L
    private var sampleCount = 0
    private var animator: ValueAnimator? = null

    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { displayEvent("added", displayId) }
        override fun onDisplayRemoved(displayId: Int) { displayEvent("removed", displayId) }
        override fun onDisplayChanged(displayId: Int) { displayEvent("changed", displayId) }
    }
    private val statusTick = object : Runnable {
        override fun run() {
            if (!started) return
            updateHud()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            angle = it.getFloat("angle", 120f)
            sensorMode = it.getBoolean("sensor", false)
            linkedScene = it.getBoolean("linked", true)
            previewCover = it.getBoolean("cover", false)
            effectEnabled = it.getBoolean("effect", true)
            movingFromEnd = it.getBoolean("movingEnd", true)
            revealEnabled = it.getBoolean("reveal", true)
        }
        trace.add("create", "v12,model=${Build.MODEL},sdk=${Build.VERSION.SDK_INT}")
        snapshotStore = SnapshotStore(this)
        scene = CalibrationScene.create()
        cover = scene
        inner = scene
        presentation = DuoPresentationController(this)
        hingeMonitor = HingeAngleMonitor(this) { sample ->
            if (!sensorMode || !started) return@HingeAngleMonitor
            val now = SystemClock.elapsedRealtime()
            rawAngle = sample.rawAngleDegrees
            filteredAngle = sample.filteredAngleDegrees
            lastSampleMs = now
            sampleCount++
            if (now - lastLoggedSampleMs >= 50L) {
                lastLoggedSampleMs = now
                trace.add("hinge", "raw=$rawAngle,filtered=$filteredAngle,opening=${sample.opening}")
            }
            applyAngle(filteredAngle, sample.opening)
        }
        sensorMode = sensorMode && hingeMonitor.isAvailable
        setContentView(buildUi())
        applyMode()
        applyStyle()
        if (!linkedScene) loadSnapshots()
    }

    override fun onStart() {
        super.onStart()
        started = true
        displayManager.registerDisplayListener(displayListener, handler)
        if (sensorMode) hingeMonitor.start()
        handler.post(statusTick)
        trace.add("start", presentation.describeDisplays())
    }

    override fun onResume() {
        super.onResume()
        trace.add("resume")
    }

    override fun onPause() {
        trace.add("pause")
        // A transient focus loss must not reset the hinge filter mid-transition.
        // Sampling still stops in onStop: this is not a background service.
        super.onPause()
    }

    override fun onStop() {
        started = false
        stopDemo()
        hingeMonitor.stop()
        transitionView.resetVisibilityCompensation()
        displayManager.unregisterDisplayListener(displayListener)
        handler.removeCallbacks(statusTick)
        trace.add("stop")
        super.onStop()
    }

    override fun onDestroy() {
        presentation.dismiss()
        hingeMonitor.stop()
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        trace.add("configuration", "rotation=${display?.rotation},widthDp=${newConfig.screenWidthDp},heightDp=${newConfig.screenHeightDp}")
        transitionView.invalidate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat("angle", angle)
        outState.putBoolean("sensor", sensorMode)
        outState.putBoolean("linked", linkedScene)
        outState.putBoolean("cover", previewCover)
        outState.putBoolean("effect", effectEnabled)
        outState.putBoolean("movingEnd", movingFromEnd)
        outState.putBoolean("reveal", revealEnabled)
        super.onSaveInstanceState(outState)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                if (fullScreen) view.setPadding(0, 0, 0, 0)
                else view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        transitionView = SnapshotTransitionView(this).apply {
            setSnapshots(scene, scene)
            onSurfaceChanged = { trace.add("surface", "cover=$it,raw=$rawAngle,age=${sampleAge()}") }
            onFrameDiagnostic = { trace.add("render", it) }
            setOnClickListener { fullScreen = !fullScreen; applyFullscreen() }
        }
        root.addView(transitionView, FrameLayout.LayoutParams(-1, -1))
        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xDC101827.toInt())
            textSize = 12f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(hud, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
            setBackgroundColor(0xEF101827.toInt())
        }
        note = TextView(this).apply {
            text = "수동 120° 기준 장면입니다. 화면을 탭하면 조작부를 숨깁니다."
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }
        panel.addView(note)
        slider = SeekBar(this).apply {
            max = 1800
            progress = (angle * 10).toInt()
            contentDescription = "힌지 각도 수동 조절"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { enterManual(); applyAngle(value / 10f) }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { enterManual() }
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        panel.addView(slider)
        fun row(vararg buttons: Button) {
            val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.forEach { layout.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            panel.addView(layout)
        }
        row(*intArrayOf(90, 120, 150, 180).map { degrees ->
            button("${degrees}°") { enterManual(); applyAngle(degrees.toFloat()) }
        }.toTypedArray())
        sensorButton = button("센서 연결") {
            if (sensorMode) enterManual() else if (hingeMonitor.isAvailable) {
                stopDemo(); sensorMode = true; lastSampleMs = null; sampleCount = 0
                applyMode(); if (started) hingeMonitor.start()
                note.text = "실제 화면 비율로 면을 판별합니다. 접힘 중 원시 각도와 첫 그리기를 기록합니다."
            } else note.text = "힌지 센서가 없습니다. 수동·자동 시연은 사용할 수 있습니다."
        }
        demoButton = button("자동 시연") { if (animator == null) startDemo() else stopDemo() }
        row(sensorButton, demoButton)
        surfaceButton = button("면: 내부") { enterManual(); previewCover = !previewCover; applyMode(); applyAngle(if (previewCover) 50f else 120f) }
        effectButton = button("효과: 켜짐") { effectEnabled = !effectEnabled; applyStyle() }
        row(surfaceButton, effectButton)
        sideButton = button("움직이는 면: 오른쪽") { movingFromEnd = !movingFromEnd; applyStyle() }
        revealButton = button("점등 보정: 켜짐") { revealEnabled = !revealEnabled; applyStyle() }
        row(sideButton, revealButton)
        sceneButton = button("장면: 기준 격자") {
            linkedScene = !linkedScene
            imageGeneration++
            if (linkedScene) { cover = scene; inner = scene; setSnapshots(); applyStyle() } else loadSnapshots()
        }
        row(sceneButton)
        row(button("커버 사진") { pickSnapshot(REQUEST_COVER) }, button("내부 사진") { pickSnapshot(REQUEST_INNER) })
        row(button("2화면 시도") {
            presentation.tryShow(cover, inner, angle / 180f, opening, FoldProjection.DEFAULT_HANDOFF) {
                note.text = it; trace.add("presentation", it)
            }
            presentation.updateStyle(linkedScene, effectEnabled, movingFromEnd)
        }, button("진단 저장") {
            pendingTrace = trace.csv()
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, "duo-v12-trace.csv")
            }, REQUEST_TRACE)
        })
        controls = ScrollView(this).apply { addView(panel) }
        root.addView(controls, FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM))
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val target = minOf(dp(300), ((bottom - top) * 0.52f).toInt()).coerceAtLeast(dp(80))
            if (controls.layoutParams.height != target) {
                controls.layoutParams = controls.layoutParams.apply { height = target }
            }
        }
        return root
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun enterManual() {
        stopDemo()
        if (sensorMode) {
            sensorMode = false
            hingeMonitor.stop()
            applyMode()
        }
    }

    private fun applyMode() {
        transitionView.setPreviewSurfaceOverride(if (sensorMode) null else previewCover)
        transitionView.setRevealEnabled(sensorMode && revealEnabled)
        sensorButton.text = if (sensorMode) "수동으로 전환" else "센서 연결"
        surfaceButton.text = if (sensorMode) "면: 실제 화면" else if (previewCover) "면: 커버" else "면: 내부"
        updateHud()
    }

    private fun applyStyle() {
        transitionView.setLinkedScene(linkedScene)
        transitionView.setEffectEnabled(effectEnabled)
        transitionView.setMovingFromEnd(movingFromEnd)
        transitionView.setRevealEnabled(sensorMode && revealEnabled)
        presentation.updateStyle(linkedScene, effectEnabled, movingFromEnd)
        effectButton.text = if (effectEnabled) "효과: 켜짐" else "효과: 원본"
        sideButton.text = if (movingFromEnd) "움직이는 면: 오른쪽" else "움직이는 면: 왼쪽"
        sceneButton.text = if (linkedScene) "장면: 기준 격자" else "장면: 가져온 사진"
        revealButton.text = if (revealEnabled) "점등 보정: 켜짐" else "점등 보정: 꺼짐"
        applyAngle(angle, opening)
    }

    private fun applyAngle(value: Float, isOpening: Boolean = value >= angle) {
        if (!value.isFinite()) return
        angle = value.coerceIn(0f, 180f)
        opening = isOpening
        transitionView.updateProgress(angle / 180f, opening)
        presentation.update(angle / 180f, opening)
        slider.progress = (angle * 10).toInt()
    }

    private fun startDemo() {
        enterManual()
        val values = if (previewCover) floatArrayOf(0f, 85f, 0f) else floatArrayOf(180f, 95f, 180f)
        animator = ValueAnimator.ofFloat(*values).apply {
            duration = 4200L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { applyAngle(it.animatedValue as Float) }
            start()
        }
        demoButton.text = "시연 정지"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        trace.add("demo", "cover=$previewCover")
        note.text = "센서와 분리된 투영 시연입니다. 화면 탭으로 전체 화면을 볼 수 있습니다."
    }

    private fun stopDemo() {
        animator?.cancel()
        animator = null
        if (::demoButton.isInitialized) demoButton.text = "자동 시연"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun applyFullscreen() {
        (transitionView.parent as? View)?.requestApplyInsets()
        controls.visibility = if (fullScreen) View.GONE else View.VISIBLE
        hud.visibility = if (fullScreen) View.GONE else View.VISIBLE
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullScreen) hide(WindowInsets.Type.systemBars()) else show(WindowInsets.Type.systemBars())
        }
    }

    private fun sampleAge(): Long? = lastSampleMs?.let { SystemClock.elapsedRealtime() - it }
    private fun updateHud() {
        if (!::hud.isInitialized) return
        fun number(value: Float) = if (value.isFinite()) String.format(Locale.US, "%.1f", value) else "--"
        val mode = if (sensorMode) "센서" else if (animator != null) "자동 시연" else "수동"
        val age = sampleAge()
        val delivery = if (!sensorMode) "센서 분리" else if (age == null) "수신 대기" else "${age}ms / ${sampleCount}회"
        val error = transitionView.rendererError
        hud.text = if (error != null) "v12 셰이더 실패: $error" else {
            "v12 고정 평면 투영 | $mode | ${if (transitionView.effectiveCoverSurface()) "커버" else "내부"}\n" +
                "각도 ${number(angle)}° · 투영 ${number(transitionView.renderedTiltDegrees)}° · 보정 ${transitionView.revealCompensating}\n" +
                "raw ${number(rawAngle)}° / filtered ${number(filteredAngle)}° · $delivery"
        }
    }

    private fun displayEvent(event: String, id: Int) {
        trace.add("display_$event", "id=$id,state=${displayManager.getDisplay(id)?.state},raw=$rawAngle,age=${sampleAge()}")
        transitionView.invalidate()
    }

    private fun pickSnapshot(request: Int) {
        stopDemo()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, request)
    }

    @Deprecated("Legacy result API keeps this foreground lab dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { if (requestCode == REQUEST_TRACE) pendingTrace = null; return }
        val uri = data?.data ?: return
        if (requestCode == REQUEST_TRACE) {
            val csv = pendingTrace ?: trace.csv()
            pendingTrace = null
            io.execute {
                val result = runCatching {
                    val stream = contentResolver.openOutputStream(uri, "wt") ?: error("출력 파일을 열지 못했습니다")
                    stream.bufferedWriter().use { it.write(csv) }
                }
                runOnUiThread {
                    if (!isDestroyed) Toast.makeText(this, if (result.isSuccess) "진단을 저장했습니다" else "저장 실패: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val kind = when (requestCode) {
            REQUEST_COVER -> SnapshotStore.Kind.COVER
            REQUEST_INNER -> SnapshotStore.Kind.INNER
            else -> return
        }
        snapshotStore.persist(kind, uri)
        linkedScene = false
        loadSnapshots()
    }

    private fun loadSnapshots() {
        val generation = ++imageGeneration
        note.text = "사진을 불러오는 중입니다. 별도 스크린샷의 아이콘 위치는 자동 정렬하지 않습니다."
        io.execute {
            val loadedCover = snapshotStore.loadOrFallback(SnapshotStore.Kind.COVER)
            val loadedInner = snapshotStore.loadOrFallback(SnapshotStore.Kind.INNER)
            runOnUiThread {
                if (isDestroyed || generation != imageGeneration || linkedScene) return@runOnUiThread
                cover = loadedCover; inner = loadedInner
                setSnapshots(); applyStyle()
                note.text = "사진 모드 · 대응점 자동 정렬 없음 · 읽을 수 없는 사진은 기본 이미지로 대체합니다."
            }
        }
    }

    private fun setSnapshots() {
        transitionView.setSnapshots(cover, inner)
        presentation.updateSnapshots(cover, inner)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_COVER = 21
        private const val REQUEST_INNER = 22
        private const val REQUEST_TRACE = 23
    }
}
