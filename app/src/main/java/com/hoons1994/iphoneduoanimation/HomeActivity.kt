package com.hoons1994.iphoneduoanimation

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.ClipData
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.view.Gravity
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowInsets
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.sqrt

/** A real, user-selectable home screen that owns its wallpaper, shortcuts, and widgets. */
class HomeActivity : Activity() {
    private lateinit var homeContent: LiveFoldLayout
    private lateinit var horizontalPager: HorizontalScrollView
    private lateinit var pageStrip: LinearLayout
    private lateinit var dockRow: LinearLayout
    private lateinit var widgetStacks: List<LinearLayout>
    private lateinit var shortcutGrids: List<GridLayout>
    private lateinit var pageIndicators: List<TextView>
    private lateinit var editButton: Button
    private lateinit var editToolbar: LinearLayout
    private lateinit var homeColumn: LinearLayout
    private lateinit var clockLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var wallpaperView: ImageView
    private val glassPanels = ArrayList<LiquidGlassPanel>()
    private var appDrawer: FrameLayout? = null
    private var drawerGrid: GridView? = null
    private var drawerSearch: EditText? = null
    private var drawerClosing = false
    private val homeBackCallback = android.window.OnBackInvokedCallback { handleHomeBack() }

    private val widgetManager by lazy { AppWidgetManager.getInstance(this) }
    private val widgetHost by lazy { AppWidgetHost(this, WIDGET_HOST_ID) }
    private val preferences by lazy { getSharedPreferences(HOME_PREFERENCES, MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handoffPreferences by lazy { HandoffCalibrationPreferences(this) }
    private var handoffCalibrator = HandoffCalibrator()
    private var latestProgress = 0f
    private var latestRawProgress = Float.NaN
    private var latestOpening = true
    private var awaitingHingeSample = true
    private var homeStarted = false
    private var lastSignalAtMs = 0L
    private var previousCoverSurface: Boolean? = null
    private var widgetListening = false
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val menuStack = ArrayList<GlassActionOverlay>()
    private val activeMenu get() = menuStack.lastOrNull()
    private var widgetFlowPending = false
    private var pendingWidgetPage = 0
    private var currentPage = 0
    private var isEditingHome = false
    private var lastDragPageSwitchAtMs = 0L
    private var dragHoverComponent: String? = null
    private var dragHoverPage = Int.MIN_VALUE
    private var dragHoverSinceMs = 0L
    private var clockRunnable: Runnable? = null
    private val wallpaperExecutor = Executors.newSingleThreadExecutor()
    private var wallpaperGeneration = 0
    private var loadedWallpaperKey: String? = null
    private var loadingWallpaperKey: String? = null
    private var pendingWallpaperUri: Uri? = null
    private var homeReflowListener: ViewTreeObserver.OnPreDrawListener? = null
    private var homeReflowPage: Int? = null
    private val widgetOptionWidths = HashMap<Int, Int>()

    private data class PinnedShortcut(val component: String, val page: Int)
    private data class WidgetPlacement(val id: Int, val page: Int, val heightDp: Int = 0)
    private data class ShortcutDrag(val component: String)
    private data class HomeFolder(val id: String, val title: String, val components: List<String>)

    private val hingeMonitor by lazy {
        HingeAngleMonitor(this) { signal ->
            runOnUiThread {
                if (!homeStarted || awaitingHingeSample && !signal.isSensorSample) return@runOnUiThread
                latestProgress = signal.filteredProgress
                latestOpening = signal.opening
                if (signal.isSensorSample) {
                    latestRawProgress = (signal.rawAngleDegrees / 180f).coerceIn(0f, 1f)
                    lastSignalAtMs = android.os.SystemClock.elapsedRealtime()
                }
                homeContent.setHandoffProgress(handoffCalibrator.estimate(signal.opening))
                homeContent.updateProgress(signal.filteredProgress, signal.opening,
                    interpolate = !awaitingHingeSample)
                if (awaitingHingeSample) {
                    // Install the first fresh pose before allowing a frame.
                    // Resuming must never render the angle from before stop().
                    awaitingHingeSample = false
                    homeContent.setRenderingActive(true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        handoffCalibrator = handoffPreferences.load()
        pendingWidgetId = savedInstanceState?.getInt(STATE_PENDING_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        pendingWidgetPage = savedInstanceState?.getInt(STATE_PENDING_WIDGET_PAGE, 0)
            ?.coerceIn(0, HOME_PAGE_COUNT - 1) ?: 0
        widgetFlowPending = savedInstanceState?.getBoolean(STATE_WIDGET_FLOW_PENDING, false) == true &&
            pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        buildHome()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, homeBackCallback)
        renderShortcuts()
    }

    override fun onStart() {
        super.onStart()
        homeStarted = true
        awaitingHingeSample = true
        lastSignalAtMs = 0L
        latestRawProgress = Float.NaN
        homeContent.setRenderingActive(false)
        startClock()
        renderShortcuts()
        wallpaperView.post { refreshWallpaper() }
        refreshGlassPanels()
        if (!widgetListening) {
            widgetListening = runCatching { widgetHost.startListening(); true }.getOrDefault(false)
            renderWidgets()
        }
        hingeMonitor.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A fold changes the viewport while the shader is moving. Reuse the
        // displayed icons and widget hosts instead of loading/inflating them
        // again on the handoff frame.
        scheduleHomeReflow()
        drawerGrid?.numColumns = drawerColumnCount()
        wallpaperView.post { refreshWallpaper() }
        if (::homeContent.isInitialized) homeContent.refreshGeometry()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            dismissAllGlassMenus()
            closeAppDrawer(animate = false)
            setEditingHome(false)
            switchPage(0)
        }
    }

    private fun handleHomeBack() {
        when {
            activeMenu != null -> dismissGlassMenu()
            appDrawer != null -> closeAppDrawer()
            isEditingHome -> setEditingHome(false)
            currentPage != 0 -> switchPage(0)
        }
    }

    override fun onStop() {
        homeStarted = false
        dismissAllGlassMenus()
        stopClock()
        hingeMonitor.stop()
        homeContent.setRenderingActive(false)
        if (widgetListening) {
            runCatching { widgetHost.stopListening() }
            widgetListening = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopClock()
        dismissAllGlassMenus()
        homeReflowListener?.let { listener ->
            homeContent.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        homeReflowListener = null
        wallpaperGeneration++
        wallpaperExecutor.shutdownNow()
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(homeBackCallback)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PENDING_WIDGET_ID, pendingWidgetId)
        outState.putInt(STATE_PENDING_WIDGET_PAGE, pendingWidgetPage)
        outState.putBoolean(STATE_WIDGET_FLOW_PENDING, widgetFlowPending)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PICK_WALLPAPER -> {
                if (resultCode != RESULT_OK) return
                val uri = data?.data ?: return
                val granted = runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isSuccess
                if (!granted) {
                    Toast.makeText(this, R.string.home_wallpaper_read_failed, Toast.LENGTH_LONG).show()
                    return
                }
                pendingWallpaperUri = uri
                refreshWallpaper()
            }
            REQUEST_PICK_WIDGET -> {
                val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID) ?: pendingWidgetId
                if (resultCode != RESULT_OK || id == AppWidgetManager.INVALID_APPWIDGET_ID) {
                    discardPendingWidget()
                    return
                }
                pendingWidgetId = id
                widgetFlowPending = true
                val info = widgetManager.getAppWidgetInfo(id)
                if (info == null) {
                    bindSelectedWidget(id, data)
                } else {
                    configureOrFinishWidget(id, info)
                }
            }
            REQUEST_BIND_WIDGET -> {
                if (resultCode == RESULT_OK && pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    widgetManager.getAppWidgetInfo(pendingWidgetId)?.let {
                        configureOrFinishWidget(pendingWidgetId, it)
                    } ?: discardPendingWidget()
                } else discardPendingWidget()
            }
            REQUEST_CONFIGURE_WIDGET -> {
                if (resultCode == RESULT_OK && pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    saveWidget(pendingWidgetId)
                } else discardPendingWidget()
            }
        }
    }

    private fun buildHome() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(17, 20, 27)) }
        homeContent = LiveFoldLayout(this)

        wallpaperView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(HomeWallpaperDrawable())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    view.post { refreshWallpaper() }
                }
            }
        }
        homeContent.addView(wallpaperView, FrameLayout.LayoutParams(-1, -1))
        homeContent.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x28080b11, 0x08080b11, 0x70080b11),
            )
        }, FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(56), dp(22), dp(24))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(22) + bars.left, bars.top + dp(24), dp(22) + bars.right,
                    bars.bottom + dp(12))
                insets
            }
        }
        homeColumn = column
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(2), dp(16))
            setOnLongClickListener { setEditingHome(true); true }
        }
        dateLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(0xe6ffffff.toInt())
            letterSpacing = 0.01f
            setPadding(dp(3), dp(2), 0, 0)
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x88000000.toInt())
        }
        clockLabel = TextView(this).apply {
            textSize = 50f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = -0.045f
            includeFontPadding = false
            setShadowLayer(dp(5).toFloat(), 0f, dp(2).toFloat(), 0x99000000.toInt())
            setOnClickListener {
                runCatching { startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }
            }
        }
        val clockStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(clockLabel)
            addView(dateLabel)
        }
        header.addView(clockStack, LinearLayout.LayoutParams(0, -2, 1f))
        editButton = actionButton(getString(R.string.home_edit)) { setEditingHome(!isEditingHome) }.apply {
            textSize = 12f
            setPadding(dp(14), 0, dp(14), 0)
            background = glassBackground(0x26353e49, 20)
            minWidth = 0
            minimumWidth = 0
        }
        header.addView(editButton, LinearLayout.LayoutParams(dp(64), dp(40)))
        column.addView(header, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        currentPage = preferences.getInt(KEY_CURRENT_PAGE, 0).coerceIn(0, HOME_PAGE_COUNT - 1)
        horizontalPager = HomePager().apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnScrollChangeListener { _, scrollX, _, _, _ ->
                if (width > 0 && homeReflowPage == null) {
                    updatePageIndicator((scrollX + width / 2) / width)
                }
            }
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) scheduleHomeReflow()
            }
        }
        pageStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val stacks = ArrayList<LinearLayout>(HOME_PAGE_COUNT)
        val grids = ArrayList<GridLayout>(HOME_PAGE_COUNT)
        repeat(HOME_PAGE_COUNT) { page ->
            val pageScroll = ScrollView(this).apply {
                clipToPadding = false
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setPadding(0, dp(12), 0, dp(8))
                setOnLongClickListener { setEditingHome(true); true }
            }
            val pageContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setOnLongClickListener { setEditingHome(true); true }
            }
            val pageWidgetStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val pageGrid = GridLayout(this).apply {
                columnCount = drawerColumnCount()
                minimumHeight = dp(112)
                useDefaultMargins = false
                alignmentMode = GridLayout.ALIGN_BOUNDS
                setOnDragListener { _, event -> handleShortcutDrop(page, width, columnCount, event) }
                setOnLongClickListener { setEditingHome(true); true }
            }
            stacks += pageWidgetStack
            grids += pageGrid
            pageContent.addView(pageWidgetStack)
            pageContent.addView(pageGrid)
            pageScroll.addView(pageContent)
            pageStrip.addView(pageScroll, LinearLayout.LayoutParams(
                resources.displayMetrics.widthPixels, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        widgetStacks = stacks
        shortcutGrids = grids
        horizontalPager.addView(pageStrip, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        column.addView(horizontalPager, LinearLayout.LayoutParams(-1, 0, 1f))

        val indicators = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        pageIndicators = List(HOME_PAGE_COUNT) { page ->
            TextView(this).apply {
                gravity = Gravity.CENTER
                setOnClickListener { switchPage(page) }
                contentDescription = getString(R.string.home_page_number, page + 1)
                indicators.addView(this, LinearLayout.LayoutParams(dp(24), dp(28)))
            }
        }
        column.addView(indicators)

        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(8), dp(7), dp(8))
            setOnDragListener { _, event ->
                handleShortcutDrop(DOCK_PAGE, width, DOCK_SLOT_COUNT, event)
            }
        }
        val dockGlass = LiquidGlassPanel(this, wallpaperView, cornerRadiusDp = 30f).also(glassPanels::add)
        dockGlass.addView(dockRow, FrameLayout.LayoutParams(-1, -1))

        val searchPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.home_search_apps)
            setOnClickListener { showAppBrowser(pinOnSelect = false) }
            addView(ImageView(this@HomeActivity).apply { setImageDrawable(HomeGlyph("search")) },
                LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(8) })
            addView(TextView(this@HomeActivity).apply {
                text = getString(R.string.home_search_short)
                textSize = 13f
                setTextColor(0xe6ffffff.toInt())
            })
            var downY = 0f
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> downY = event.y
                    MotionEvent.ACTION_UP -> if (downY - event.y > dp(24)) {
                        view.performClick()
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }
        val searchGlass = LiquidGlassPanel(this, wallpaperView, cornerRadiusDp = 22f).also(glassPanels::add)
        searchGlass.addView(searchPill, FrameLayout.LayoutParams(-1, -1))
        column.addView(searchGlass, LinearLayout.LayoutParams(dp(112), dp(40)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(2)
            bottomMargin = dp(16)
        })
        column.addView(dockGlass, LinearLayout.LayoutParams(-1, dp(84)))
        editToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
            addView(actionButton(getString(R.string.home_add_app)) { showAppBrowser(pinOnSelect = true) }, equalWeight())
            addView(actionButton(getString(R.string.home_add_widget)) { pickWidget() }, equalWeight())
            addView(actionButton(getString(R.string.home_wallpaper)) { showWallpaperActions() }, equalWeight())
        }
        column.addView(editToolbar)
        homeContent.addView(column, FrameLayout.LayoutParams(-1, -1))
        root.addView(homeContent, FrameLayout.LayoutParams(-1, -1))

        val initialProgress = if (homeContent.isCoverSurface()) 0f else 1f
        latestProgress = initialProgress
        homeContent.setHandoffProgress(handoffCalibrator.estimate(true))
        homeContent.updateProgress(initialProgress, isOpening = true, interpolate = false)
        previousCoverSurface = homeContent.isCoverSurface()
        homeContent.onSurfaceChanged = { isCover ->
            val previous = previousCoverSurface
            if (previous != null && previous != isCover && lastSignalAtMs > 0L &&
                android.os.SystemClock.elapsedRealtime() - lastSignalAtMs <= TransitionTuning.MAX_SURFACE_EVENT_AGE_MS) {
                handoffCalibrator.observe(latestOpening, previous, isCover,
                    if (latestRawProgress.isFinite()) latestRawProgress else latestProgress)
                handoffPreferences.save(handoffCalibrator)
                homeContent.setHandoffProgress(handoffCalibrator.estimate(latestOpening))
            }
            previousCoverSurface = isCover
            if (!hingeMonitor.isAvailable) {
                latestProgress = if (isCover) 0f else 1f
                homeContent.updateProgress(latestProgress, latestOpening, interpolate = false)
            }
        }
        homeContent.onEffectActiveChanged = { active ->
            val mode = display?.mode
            val rate = if (active) display?.supportedModes?.asSequence()
                ?.filter { mode == null || it.physicalWidth == mode.physicalWidth &&
                    it.physicalHeight == mode.physicalHeight }
                ?.maxOfOrNull { it.refreshRate }?.coerceAtMost(120f) ?: 60f else 0f
            window.attributes = window.attributes.apply { preferredRefreshRate = rate }
        }
        setContentView(root)
        column.requestApplyInsets()
        scheduleHomeReflow()
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setTextColor(Color.WHITE)
        background = glassBackground(0x55303c49, 18)
        setOnClickListener { onClick() }
    }

    private fun equalWeight() = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun setEditingHome(editing: Boolean, redraw: Boolean = true) {
        isEditingHome = editing
        editButton.text = getString(if (editing) R.string.home_done else R.string.home_edit)
        editToolbar.visibility = if (editing) View.VISIBLE else View.GONE
        if (redraw) {
            renderShortcuts()
            renderWidgets()
        }
    }

    private fun drawerColumnCount() = when {
        resources.configuration.screenWidthDp >= 700 -> 6
        resources.configuration.screenWidthDp >= 500 -> 5
        else -> 4
    }

    /** Settles every gesture on a page; inertial scrolling must not leave half a home visible. */
    private inner class HomePager : HorizontalScrollView(this@HomeActivity) {
        private var startPage = 0
        private var downX = 0f
        private var downY = 0f
        private var snapPosted = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                startPage = if (width > 0) (scrollX.toFloat() / width).roundToInt() else 0
                downX = event.x
                downY = event.y
                snapPosted = false
            }
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (!snapPosted && width > 0 && abs(event.x - downX) > abs(event.y - downY)) {
                    snapPosted = true
                    val delta = event.x - downX
                    val target = if (abs(delta) > width * 0.15f) startPage + if (delta < 0) 1 else -1
                        else (scrollX.toFloat() / width).roundToInt()
                    post { smoothScrollTo(target.coerceIn(0, HOME_PAGE_COUNT - 1) * width, 0) }
                }
            }
            return handled
        }

        override fun fling(velocityX: Int) {
            if (width <= 0) return
            snapPosted = true
            val target = startPage + if (velocityX > 0) 1 else -1
            smoothScrollTo(target.coerceIn(0, HOME_PAGE_COUNT - 1) * width, 0)
        }
    }

    private fun updatePageWidths(): Boolean {
        if (!::horizontalPager.isInitialized || horizontalPager.width <= 0) return false
        var changed = false
        for (index in 0 until pageStrip.childCount) {
            val child = pageStrip.getChildAt(index)
            if (child.layoutParams.width != horizontalPager.width) {
                child.layoutParams = child.layoutParams.apply { width = horizontalPager.width }
                changed = true
            }
        }
        return changed
    }

    private fun scheduleHomeReflow() {
        if (!::shortcutGrids.isInitialized || homeReflowListener != null) return
        homeReflowPage = currentPage
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (horizontalPager.width <= 0) return true
                val pagesChanged = updatePageWidths()
                val shortcutsChanged = reflowShortcuts()
                // LayoutParams changed after measure. Draw after the next
                // layout, so a frame never contains old-width home pages.
                if (pagesChanged || shortcutsChanged) return false
                val page = homeReflowPage ?: currentPage
                horizontalPager.scrollTo(page * horizontalPager.width, 0)
                updatePageIndicator(page)
                homeContent.viewTreeObserver.removeOnPreDrawListener(this)
                homeReflowListener = null
                homeReflowPage = null
                homeContent.post { updateHostedWidgetWidths() }
                return true
            }
        }
        homeReflowListener = listener
        homeContent.viewTreeObserver.addOnPreDrawListener(listener)
        homeContent.invalidate()
    }

    private fun reflowShortcuts(): Boolean {
        val columns = drawerColumnCount()
        val cellWidth = (horizontalPager.width / columns - dp(6)).coerceAtLeast(dp(54))
        var changed = false
        shortcutGrids.forEach { grid ->
            val columnsChanged = grid.columnCount != columns
            if (columnsChanged) {
                // Clear auto-placement spans before reducing the column count.
                // The existing Views (including icons and listeners) stay attached.
                for (index in 0 until grid.childCount) {
                    val child = grid.getChildAt(index)
                    child.layoutParams = (child.layoutParams as GridLayout.LayoutParams).apply {
                        rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
                    }
                }
                grid.columnCount = columns
                changed = true
            }
            for (index in 0 until grid.childCount) {
                val child = grid.getChildAt(index)
                val emptyPage = child is TextView
                val width = if (emptyPage) 0 else cellWidth
                val params = child.layoutParams as GridLayout.LayoutParams
                if (columnsChanged || params.width != width) {
                    params.width = width
                    if (emptyPage) params.columnSpec = GridLayout.spec(0, columns, 1f)
                    child.layoutParams = params
                    changed = true
                }
            }
        }
        return changed
    }

    private fun updatePageIndicator(page: Int) {
        if (!::pageIndicators.isInitialized) return
        val selectedPage = page.coerceIn(0, HOME_PAGE_COUNT - 1)
        val changed = currentPage != selectedPage
        currentPage = selectedPage
        pageIndicators.forEachIndexed { index, indicator ->
            indicator.text = "•"
            indicator.textSize = if (index == currentPage) 24f else 19f
            indicator.setTextColor(if (index == currentPage) Color.WHITE else 0x65ffffff)
            indicator.isSelected = index == currentPage
        }
        if (changed) preferences.edit().putInt(KEY_CURRENT_PAGE, currentPage).apply()
    }

    private fun switchPage(page: Int) {
        val target = page.coerceIn(0, HOME_PAGE_COUNT - 1)
        horizontalPager.smoothScrollTo(target * horizontalPager.width, 0)
    }

    private fun renderShortcuts() {
        if (!::shortcutGrids.isInitialized) return
        val pinned = pinnedShortcuts()
        val columns = drawerColumnCount()
        val pageWidth = horizontalPager.width.takeIf { it > 0 }
            ?: dp(resources.configuration.screenWidthDp - 44)
        val cellWidth = (pageWidth / columns - dp(6)).coerceAtLeast(dp(54))
        shortcutGrids.forEachIndexed { page, grid ->
            grid.removeAllViews()
            grid.columnCount = columns
            pinned.filter { it.page == page }.forEach shortcutLoop@{ shortcut ->
                val cell = createShortcutView(shortcut, dock = false) ?: return@shortcutLoop
                grid.addView(cell, GridLayout.LayoutParams().apply {
                    width = cellWidth
                    height = dp(96)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            if (grid.childCount == 0) {
                grid.addView(TextView(this).apply {
                    text = getString(if (isEditingHome) R.string.home_empty_edit else R.string.home_empty)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(0xccffffff.toInt())
                    setPadding(dp(20), dp(24), dp(20), dp(24))
                    background = glassBackground(0x1615222f, 24)
                    setOnClickListener { showAppBrowser(pinOnSelect = true) }
                    setOnLongClickListener { setEditingHome(true); true }
                }, GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(112)
                    columnSpec = GridLayout.spec(0, columns, 1f)
                    setMargins(dp(4), dp(16), dp(4), dp(8))
                })
            }
        }
        renderDock(pinned)
    }

    private fun renderDock(pinned: List<PinnedShortcut>) {
        if (!::dockRow.isInitialized) return
        dockRow.removeAllViews()
        val docked = pinned.filter { it.page == DOCK_PAGE }.take(DOCK_SLOT_COUNT)
        docked.forEach { shortcut ->
            val cell = createShortcutView(shortcut, dock = true) ?: return@forEach
            dockRow.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
        repeat((DOCK_SLOT_COUNT - dockRow.childCount).coerceAtLeast(0)) {
            dockRow.addView(FrameLayout(this).apply {
                addView(iconButton("plus", getString(R.string.home_add_dock)) {
                    showAppBrowser(pinOnSelect = true, pinToDock = true)
                }.apply { alpha = if (isEditingHome || docked.isEmpty()) 0.8f else 0.45f },
                    FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun createShortcutView(shortcut: PinnedShortcut, dock: Boolean): View? {
        val isFolder = isFolderComponent(shortcut.component)
        val folder = if (isFolder) homeFolder(folderId(shortcut.component)) else null
        val component = if (isFolder) null else ComponentName.unflattenFromString(shortcut.component)
        val info = component?.let { runCatching { packageManager.getActivityInfo(it, 0) }.getOrNull() }
        if (!isFolder && info == null) return null

        val cell = LinearLayout(this).apply {
            tag = shortcut.component
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(if (dock) 0 else dp(4), if (dock) 0 else dp(4), if (dock) 0 else dp(4), if (dock) 0 else dp(4))
            isClickable = true
            isFocusable = true
            background = if (isEditingHome) glassBackground(0x35343d49, 18)
                else RippleDrawable(ColorStateList.valueOf(0x24ffffff), null,
                    roundedBackground(Color.WHITE, dp(18).toFloat()))
            contentDescription = if (isFolder) folder?.title ?: "폴더" else info?.loadLabel(packageManager)?.toString()
            setOnClickListener {
                if (isEditingHome) {
                    showShortcutActions(shortcut.component, this)
                } else if (isFolder) {
                    showFolderContents(folderId(shortcut.component))
                } else {
                    component?.let { launchApplication(it, this) }
                }
            }
            setOnLongClickListener {
                if (!isEditingHome) {
                    showShortcutActions(shortcut.component, this)
                    return@setOnLongClickListener true
                }
                val clip = ClipData.newPlainText("launcher-shortcut", shortcut.component)
                startDragAndDrop(clip, View.DragShadowBuilder(this), ShortcutDrag(shortcut.component), 0)
                true
            }
        }

        if (isFolder) {
            val preview = GridLayout(this).apply {
                columnCount = 2
                rowCount = 2
                background = roundedBackground(0x88333b49.toInt(), dp(13).toFloat())
                val icons = folder?.components.orEmpty().take(4)
                    .mapNotNull { name -> ComponentName.unflattenFromString(name)?.let { name to it } }
                    .mapNotNull { (name, target) ->
                        runCatching { packageManager.getActivityInfo(target, 0).loadIcon(packageManager) }.getOrNull()
                    }
                icons.forEach { drawable ->
                    addView(ImageView(this@HomeActivity).apply {
                        setImageDrawable(drawable)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, GridLayout.LayoutParams().apply {
                        width = dp(21)
                        height = dp(21)
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    })
                }
            }
            cell.addView(preview, LinearLayout.LayoutParams(if (dock) dp(46) else dp(50), if (dock) dp(46) else dp(50)))
        } else {
            val icon = ImageView(this).apply {
                setImageDrawable(info?.loadIcon(packageManager)?.let(::AppIconDrawable))
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = info?.loadLabel(packageManager)?.toString()
            }
            val iconSize = if (dock) 52 else 54
            cell.addView(icon, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
        }

        if (dock && isEditingHome) {
            cell.addView(TextView(this).apply {
                text = getString(R.string.home_edit)
                textSize = 10f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(0xe6ffffff.toInt())
            }, LinearLayout.LayoutParams(-1, dp(14)))
        } else if (!dock) {
            val label = TextView(this).apply {
                text = if (isFolder) folder?.title ?: "폴더" else info?.loadLabel(packageManager)
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(6), 0, 0)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            }
            cell.addView(label, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return cell
    }

    private fun launchApplication(component: ComponentName, source: View? = null) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching {
            if (source != null && source.width > 0 && source.height > 0) {
                val options = ActivityOptions.makeScaleUpAnimation(source, 0, 0, source.width, source.height)
                startActivity(intent, options.toBundle())
            } else {
                startActivity(intent)
            }
        }.onFailure { Toast.makeText(this, R.string.home_launch_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun handleShortcutDrop(page: Int, targetWidth: Int, columns: Int, event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                if (event.localState is ShortcutDrag) {
                    lastDragPageSwitchAtMs = android.os.SystemClock.elapsedRealtime()
                    dragHoverComponent = null
                    dragHoverPage = Int.MIN_VALUE
                    return true
                }
                return false
            }
            DragEvent.ACTION_DRAG_ENTERED -> {
                dragHoverComponent = null
                dragHoverPage = page
                return true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                if (dragHoverPage == page) {
                    dragHoverComponent = null
                    dragHoverPage = Int.MIN_VALUE
                }
                return true
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                val now = android.os.SystemClock.elapsedRealtime()
                val drag = event.localState as? ShortcutDrag
                if (drag != null) {
                    val slot = targetSlot(event, page, targetWidth, columns)
                    val hovered = pinnedShortcuts().filter { it.page == page }.getOrNull(slot)
                        ?.component?.takeIf { it != drag.component }
                    if (hovered != dragHoverComponent || dragHoverPage != page) {
                        dragHoverComponent = hovered
                        dragHoverPage = page
                        dragHoverSinceMs = now
                    } else if (hovered == null) {
                        dragHoverSinceMs = now
                    }
                }
                if (page >= 0 && now - lastDragPageSwitchAtMs >= DRAG_PAGE_SWITCH_DELAY_MS) {
                    val edge = dp(DRAG_PAGE_EDGE_DP)
                    if (event.x <= edge && page > 0) {
                        lastDragPageSwitchAtMs = now
                        switchPage(page - 1)
                    } else if (event.x >= targetWidth - edge && page < HOME_PAGE_COUNT - 1) {
                        lastDragPageSwitchAtMs = now
                        switchPage(page + 1)
                    }
                }
                return true
            }
            DragEvent.ACTION_DROP -> {
                val drag = event.localState as? ShortcutDrag ?: return false
                val originalEntries = pinnedShortcuts()
                val entries = originalEntries.toMutableList()
                val source = entries.indexOfFirst { it.component == drag.component }
                if (source < 0) return false
                val sourcePage = entries[source].page
                val sourcePageIndex = originalEntries.filter { it.page == sourcePage }
                    .indexOfFirst { it.component == drag.component }
                val targetSlot = targetSlot(event, page, targetWidth, columns)
                val originalPageEntries = originalEntries.filter { it.page == page }
                val target = originalPageEntries.getOrNull(targetSlot)
                val hovered = if (dragHoverPage == page && dragHoverComponent == target?.component &&
                    android.os.SystemClock.elapsedRealtime() - dragHoverSinceMs >= FOLDER_HOVER_DELAY_MS) target else null
                entries.removeAt(source)
                val pageEntries = entries.filter { it.page == page }

                if (hovered != null && hovered.component != drag.component) {
                    if (isFolderComponent(hovered.component)) {
                        val targetFolder = homeFolder(folderId(hovered.component))
                        if (targetFolder != null) {
                            val incoming = if (isFolderComponent(drag.component)) {
                                homeFolder(folderId(drag.component))?.components.orEmpty()
                            } else listOf(drag.component)
                            saveHomeFolder(targetFolder.copy(components = (targetFolder.components + incoming).distinct()))
                            if (isFolderComponent(drag.component)) deleteHomeFolder(folderId(drag.component))
                            savePinnedShortcuts(entries)
                            renderShortcuts()
                            return true
                        }
                    } else {
                        val incoming = if (isFolderComponent(drag.component)) {
                            homeFolder(folderId(drag.component))?.components.orEmpty()
                        } else listOf(drag.component)
                        val targetIndex = entries.indexOfFirst { it.component == hovered.component }
                        if (targetIndex >= 0 && incoming.isNotEmpty()) {
                            val sourceFolder = if (isFolderComponent(drag.component)) homeFolder(folderId(drag.component)) else null
                            val folderId = UUID.randomUUID().toString()
                            val folderTitle = sourceFolder?.title ?: "폴더"
                            saveHomeFolder(HomeFolder(folderId, folderTitle, (listOf(hovered.component) + incoming).distinct()))
                            if (sourceFolder != null) deleteHomeFolder(sourceFolder.id)
                            entries.removeAt(targetIndex)
                            entries.add(targetIndex, PinnedShortcut(folderComponent(folderId), page))
                            savePinnedShortcuts(entries)
                            renderShortcuts()
                            return true
                        }
                    }
                }

                val isCreatingFolder = hovered != null && !isFolderComponent(hovered.component)
                if (page == DOCK_PAGE && pageEntries.size >= DOCK_SLOT_COUNT && !isCreatingFolder) {
                    Toast.makeText(this, "Dock에는 앱을 ${DOCK_SLOT_COUNT}개까지 둘 수 있습니다.", Toast.LENGTH_SHORT).show()
                    return false
                }
                if (target?.component == drag.component && sourcePage == page) return true
                val targetInPage = (targetSlot - if (sourcePage == page && sourcePageIndex < targetSlot) 1 else 0)
                    .coerceIn(0, pageEntries.size)
                val targetGlobal = entries.indexOfFirst { it.page >= page }
                val insertAt = if (targetGlobal < 0) entries.size else targetGlobal + targetInPage
                entries.add(insertAt.coerceIn(0, entries.size), PinnedShortcut(drag.component, page))
                savePinnedShortcuts(entries)
                renderShortcuts()
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> return true
        }
        return true
    }

    private fun targetSlot(event: DragEvent, page: Int, targetWidth: Int, columns: Int): Int {
        val slots = if (page == DOCK_PAGE) DOCK_SLOT_COUNT else columns.coerceAtLeast(1)
        val cellWidth = (targetWidth / slots).coerceAtLeast(1)
        val column = (event.x / cellWidth).toInt().coerceIn(0, slots - 1)
        val row = if (page == DOCK_PAGE) 0 else (event.y / dp(102)).toInt().coerceAtLeast(0)
        return row * slots + column
    }

    private fun folderComponent(id: String) = "$FOLDER_PREFIX$id"

    private fun isFolderComponent(component: String) = component.startsWith(FOLDER_PREFIX)

    private fun folderId(component: String) = component.removePrefix(FOLDER_PREFIX)

    private fun homeFolder(id: String): HomeFolder? {
        val components = preferences.getString(FOLDER_APPS_PREFIX + id, null) ?: return null
        return HomeFolder(
            id = id,
            title = preferences.getString(FOLDER_NAME_PREFIX + id, null)?.takeIf { it.isNotBlank() } ?: "폴더",
            components = components.split('\n').filter(String::isNotBlank).distinct(),
        )
    }

    private fun saveHomeFolder(folder: HomeFolder) {
        preferences.edit()
            .putString(FOLDER_NAME_PREFIX + folder.id, folder.title.trim().ifBlank { "폴더" })
            .putString(FOLDER_APPS_PREFIX + folder.id, folder.components.distinct().joinToString("\n"))
            .apply()
    }

    private fun deleteHomeFolder(id: String) {
        preferences.edit()
            .remove(FOLDER_NAME_PREFIX + id)
            .remove(FOLDER_APPS_PREFIX + id)
            .apply()
    }

    private fun showFolderContents(id: String) {
        val folder = homeFolder(id) ?: return
        val appComponents = launcherActivities().associateBy {
            ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString()
        }
        val available = folder.components.mapNotNull(appComponents::get)
        if (available.size != folder.components.size) saveHomeFolder(folder.copy(
            components = available.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString() },
        ))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(4)
            verticalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) {
                    val columns = if (right - left >= dp(360)) 4 else 3
                    if (numColumns != columns) numColumns = columns
                }
            }
        }
        grid.adapter = LauncherActivityAdapter(available)
        val gridHeight = (resources.configuration.screenHeightDp / 2).coerceIn(220, 360)
        val gridFrame = FrameLayout(this)
        gridFrame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        val emptyMessage = TextView(this).apply {
            text = "폴더가 비어 있습니다"
            gravity = Gravity.CENTER
            setTextColor(0xccffffff.toInt())
        }
        gridFrame.addView(emptyMessage, FrameLayout.LayoutParams(-1, -1))
        grid.emptyView = emptyMessage
        content.addView(gridFrame, LinearLayout.LayoutParams(-1, dp(gridHeight)))
        grid.setOnItemClickListener { _, view, position, _ ->
            val item = available.getOrNull(position) ?: return@setOnItemClickListener
            launchApplication(ComponentName(item.activityInfo.packageName, item.activityInfo.name), view)
            dismissAllGlassMenus()
        }
        grid.setOnItemLongClickListener { _, view, position, _ ->
            val item = available.getOrNull(position) ?: return@setOnItemLongClickListener true
            val component = ComponentName(item.activityInfo.packageName, item.activityInfo.name)
            showGlassMenu(item.loadLabel(packageManager).toString(), anchor = view,
                icon = AppIconDrawable(item.loadIcon(packageManager)), replaceCurrent = false,
                actions = listOf(
                    GlassAction(getString(R.string.home_extract_from_folder), "home") {
                        dismissAllGlassMenus()
                        removeAppFromFolder(id, component, placeOnHome = true)
                    },
                    GlassAction(getString(R.string.home_remove_from_folder), "trash", destructive = true) {
                        dismissAllGlassMenus()
                        removeAppFromFolder(id, component, placeOnHome = false)
                    }))
            true
        }
        showGlassMenu(folder.title, icon = shortcutIcon(folderComponent(id)), content = content, actions = listOf(
            GlassAction(getString(R.string.home_edit), "edit") { showFolderActions(id) }))
    }

    private fun removeAppFromFolder(id: String, component: ComponentName, placeOnHome: Boolean) {
        val folder = homeFolder(id) ?: return
        val name = component.flattenToString()
        val remaining = folder.components.filterNot { it == name }
        val entries = pinnedShortcuts().toMutableList()
        val folderEntry = entries.firstOrNull { it.component == folderComponent(id) }
        if (remaining.isEmpty()) {
            entries.removeAll { it.component == folderComponent(id) }
            deleteHomeFolder(id)
        } else {
            saveHomeFolder(folder.copy(components = remaining))
        }
        if (placeOnHome && entries.none { it.component == name }) {
            val page = folderEntry?.page?.takeIf { it != DOCK_PAGE } ?: currentPage
            entries.add(pageInsertionIndex(entries, page), PinnedShortcut(name, page))
        }
        savePinnedShortcuts(entries)
        renderShortcuts()
    }

    private fun showFolderActions(id: String, anchor: View? = null) {
        val component = folderComponent(id)
        if (pinnedShortcuts().any { it.component == component && it.page == DOCK_PAGE }) {
            showDockActions(component, anchor)
            return
        }
        val folder = homeFolder(id) ?: return
        showGlassMenu(folder.title, icon = shortcutIcon(component),
            anchor = anchor ?: shortcutAnchor(component), actions = listOf(
            GlassAction(getString(R.string.home_folder_rename), "edit") { showRenameFolder(folder) },
            GlassAction(getString(R.string.home_edit_layout), "grid") { setEditingHome(true) },
            GlassAction(getString(R.string.home_folder_ungroup), "folder") { ungroupFolder(folder) }))
    }

    private fun showRenameFolder(folder: HomeFolder) {
        val input = EditText(this).apply {
            setText(folder.title)
            setSelection(text.length)
            hint = getString(R.string.home_folder_name)
            isSingleLine = true
            textSize = 17f
            setTextColor(Color.WHITE)
            setHintTextColor(0x99ffffff.toInt())
            background = glassBackground(0x18ffffff, 18)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))
        }
        val save: () -> Unit = {
            homeFolder(folder.id)?.let { current ->
                saveHomeFolder(current.copy(title = input.text.toString()))
                renderShortcuts()
            }
        }
        val menu = showGlassMenu(getString(R.string.home_folder_rename), content = input,
            actions = listOf(GlassAction(getString(R.string.home_save), "check", onClick = save)))
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                menu?.dismiss(afterDismiss = save)
                true
            } else false
        }
        input.post {
            if (activeMenu === menu && menu != null && input.isAttachedToWindow) {
                input.requestFocus()
                getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun ungroupFolder(folder: HomeFolder) {
        val entries = pinnedShortcuts().toMutableList()
        val folderIndex = entries.indexOfFirst { it.component == folderComponent(folder.id) }
        if (folderIndex < 0) return
        val location = entries[folderIndex].page
        entries.removeAt(folderIndex)
        val targetPage = if (location == DOCK_PAGE) 0 else location
        val components = folder.components.filter { text ->
            val component = ComponentName.unflattenFromString(text) ?: return@filter false
            runCatching { packageManager.getActivityInfo(component, 0) }.isSuccess
        }
        val insertion = if (targetPage == location) folderIndex.coerceAtMost(entries.size)
        else pageInsertionIndex(entries, targetPage)
        entries.addAll(insertion, components.map { PinnedShortcut(it, targetPage) })
        deleteHomeFolder(folder.id)
        savePinnedShortcuts(entries)
        renderShortcuts()
    }

    private fun pageInsertionIndex(entries: List<PinnedShortcut>, page: Int): Int {
        val lastOnPage = entries.indexOfLast { it.page == page }
        if (lastOnPage >= 0) return lastOnPage + 1
        val nextPage = entries.indexOfFirst { it.page > page }
        return if (nextPage >= 0) nextPage else entries.size
    }

    private fun showShortcutActions(component: String, anchor: View? = null) {
        val shortcut = pinnedShortcuts().firstOrNull { it.component == component } ?: return
        if (shortcut.page == DOCK_PAGE) {
            showDockActions(component, anchor)
            return
        }
        if (isFolderComponent(component)) {
            showFolderActions(folderId(component), anchor)
            return
        }
        val actions = ArrayList<GlassAction>()
        actions += GlassAction(getString(R.string.home_edit_layout), "grid") { setEditingHome(true) }
        ComponentName.unflattenFromString(component)?.let { target ->
            actions += GlassAction(getString(R.string.home_add_dock), "plus") { addAppToDock(target) }
        }
        actions += GlassAction(getString(R.string.home_move_to_page), "pages") {
            showGlassMenu(getString(R.string.home_move_to_page), actions = (0 until HOME_PAGE_COUNT)
                .filter { it != shortcut.page }.map { page ->
                    GlassAction(getString(R.string.home_page_number, page + 1), "home") {
                        savePinnedShortcuts(pinnedShortcuts().map {
                            if (it.component == component) it.copy(page = page) else it
                        })
                        renderShortcuts()
                    }
                }
            )
        }
        actions += GlassAction(getString(R.string.home_remove_shortcut), "trash", destructive = true) {
            savePinnedShortcuts(pinnedShortcuts().filterNot { it.component == component })
            renderShortcuts()
        }
        showGlassMenu(shortcutLabel(component), icon = shortcutIcon(component),
            anchor = anchor ?: shortcutAnchor(component), actions = actions)
    }

    private fun showDockActions(component: String, anchor: View? = null) {
        val dock = pinnedShortcuts().filter { it.page == DOCK_PAGE }
        val index = dock.indexOfFirst { it.component == component }
        if (index < 0) return
        val quickOrder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((direction, label) in listOf(-1 to R.string.home_dock_move_left, 1 to R.string.home_dock_move_right)) {
                addView(actionButton(getString(label)) {
                    activeMenu?.dismiss(afterDismiss = { reorderDock(component, direction) })
                }.apply {
                    isEnabled = index + direction in dock.indices
                    alpha = if (isEnabled) 1f else 0.32f
                    textSize = 13f
                    minWidth = 0
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = if (direction > 0) dp(4) else 0
                    marginEnd = if (direction < 0) dp(4) else 0
                })
            }
        }
        val actions = ArrayList<GlassAction>()
        actions += GlassAction(getString(R.string.home_dock_replace), "replace") {
            showAppBrowser(pinOnSelect = true, pinToDock = true, replaceDockComponent = component)
        }
        actions += GlassAction(getString(R.string.home_dock_remove_to_home), "home") {
            moveDockItemToHome(component, currentPage)
        }
        actions += GlassAction(getString(R.string.home_move_to_page), "pages") {
            showGlassMenu(getString(R.string.home_move_to_page), actions = (0 until HOME_PAGE_COUNT).map { page ->
                GlassAction(getString(R.string.home_page_number, page + 1), "home") {
                    moveDockItemToHome(component, page)
                }
            })
        }
        if (isFolderComponent(component)) {
            homeFolder(folderId(component))?.let { folder ->
                actions += GlassAction(getString(R.string.home_folder_rename), "edit") { showRenameFolder(folder) }
                actions += GlassAction(getString(R.string.home_folder_ungroup), "folder") { ungroupFolder(folder) }
            }
        } else {
            ComponentName.unflattenFromString(component)?.let { app ->
                actions += GlassAction(getString(R.string.home_app_info), "info") {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${app.packageName}"))) }
                }
            }
        }
        showGlassMenu(shortcutLabel(component), icon = shortcutIcon(component), content = quickOrder,
            anchor = anchor ?: shortcutAnchor(component), actions = actions)
    }

    private fun shortcutAnchor(component: String): View? = homeContent.findViewWithTag(component)

    private fun shortcutIcon(component: String): Drawable? {
        if (isFolderComponent(component)) {
            return android.graphics.drawable.LayerDrawable(arrayOf(
                roundedBackground(0xcc343d51.toInt(), dp(12).toFloat()),
                android.graphics.drawable.InsetDrawable(HomeGlyph("folder"), dp(9)),
            ))
        }
        val name = ComponentName.unflattenFromString(component) ?: return null
        return runCatching { AppIconDrawable(packageManager.getActivityIcon(name)) }.getOrNull()
    }

    private fun shortcutLabel(component: String): String {
        if (isFolderComponent(component)) return homeFolder(folderId(component))?.title ?: "폴더"
        val name = ComponentName.unflattenFromString(component) ?: return component
        return runCatching { packageManager.getActivityInfo(name, 0).loadLabel(packageManager).toString() }
            .getOrDefault(name.packageName)
    }

    private fun reorderDock(component: String, direction: Int) {
        val entries = pinnedShortcuts().toMutableList()
        val dockIndices = entries.indices.filter { entries[it].page == DOCK_PAGE }
        val position = dockIndices.indexOfFirst { entries[it].component == component }
        if (position < 0) return
        val target = position + direction
        if (target !in dockIndices.indices) return
        val sourceIndex = dockIndices[position]
        val targetIndex = dockIndices[target]
        val previous = entries[sourceIndex]
        entries[sourceIndex] = entries[targetIndex]
        entries[targetIndex] = previous
        savePinnedShortcuts(entries)
        renderShortcuts()
    }

    private fun moveDockItemToHome(component: String, page: Int) {
        val entries = pinnedShortcuts().toMutableList()
        val index = entries.indexOfFirst { it.component == component && it.page == DOCK_PAGE }
        if (index < 0) return
        val moved = entries.removeAt(index).copy(page = page.coerceIn(0, HOME_PAGE_COUNT - 1))
        entries.add(pageInsertionIndex(entries, moved.page), moved)
        savePinnedShortcuts(entries)
        renderShortcuts()
        Toast.makeText(this, getString(R.string.home_dock_moved_home, moved.page + 1), Toast.LENGTH_SHORT).show()
    }

    /** Keep both entries, including intact folders, when replacing a full dock slot. */
    private fun replaceDockItem(target: String, incoming: ComponentName): Boolean {
        val entries = pinnedShortcuts().toMutableList()
        val targetIndex = entries.indexOfFirst { it.component == target && it.page == DOCK_PAGE }
        if (targetIndex < 0) {
            Toast.makeText(this, R.string.home_dock_target_missing, Toast.LENGTH_SHORT).show()
            return false
        }
        val name = incoming.flattenToString()
        if (target == name) return true
        val old = entries[targetIndex]
        val incomingIndex = entries.indexOfFirst { it.component == name }
        val swapsDockSlots = incomingIndex >= 0 && entries[incomingIndex].page == DOCK_PAGE
        entries[targetIndex] = PinnedShortcut(name, DOCK_PAGE)
        if (incomingIndex >= 0) {
            // The old item takes the incoming app's previous place. Swapping
            // another dock item therefore preserves slot count and order.
            entries[incomingIndex] = old.copy(page = entries[incomingIndex].page)
        } else {
            entries.add(pageInsertionIndex(entries, currentPage), old.copy(page = currentPage))
        }
        savePinnedShortcuts(entries)
        renderShortcuts()
        (drawerGrid?.adapter as? BaseAdapter)?.notifyDataSetChanged()
        Toast.makeText(this, if (swapsDockSlots) R.string.home_dock_swapped else R.string.home_dock_replaced,
            Toast.LENGTH_SHORT).show()
        return true
    }

    private fun showDockReplacementTargets(incoming: ComponentName) {
        val dock = pinnedShortcuts().filter { it.page == DOCK_PAGE }
        if (dock.isEmpty()) return
        val slots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dock.forEachIndexed { index, shortcut ->
            val name = shortcutLabel(shortcut.component)
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(14), dp(3), dp(12))
                background = glassBackground(0x18ffffff, 20)
                isFocusable = true
                contentDescription = getString(R.string.home_dock_item_title, index + 1, name)
                addView(ImageView(this@HomeActivity).apply {
                    setImageDrawable(shortcutIcon(shortcut.component) ?: getDrawable(R.mipmap.ic_launcher))
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(TextView(this@HomeActivity).apply {
                    text = name
                    textSize = 11f
                    maxLines = 2
                    gravity = Gravity.CENTER
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(8), 0, 0)
                }, LinearLayout.LayoutParams(-1, dp(42)))
                setOnClickListener {
                    activeMenu?.dismiss(afterDismiss = { replaceDockItem(shortcut.component, incoming) })
                }
            }
            slots.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = if (index == 0) 0 else dp(4)
            })
        }
        showGlassMenu(getString(R.string.home_dock_full_choose_slot),
            subtitle = getString(R.string.home_dock_replace_preserves_items), content = slots, actions = emptyList())
    }

    private fun showAppBrowser(pinOnSelect: Boolean, pinToDock: Boolean = false,
        replaceDockComponent: String? = null) {
        if (appDrawer != null || activeMenu != null || widgetFlowPending) return
        drawerClosing = false
        val apps = launcherActivities()
        val adapter = LauncherActivityAdapter(apps)
        val overlay = LiquidGlassPanel(this, wallpaperView, cornerRadiusDp = 0f, strong = true).apply {
            isClickable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        glassPanels.add(overlay)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(56), dp(22), dp(24))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(dp(22) + bars.left, bars.top + dp(16), dp(22) + bars.right,
                    maxOf(bars.bottom, keyboard.bottom) + dp(8))
                insets
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, dp(18))
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = getString(when {
                replaceDockComponent != null -> R.string.home_dock_replace
                pinToDock -> R.string.home_add_dock
                pinOnSelect -> R.string.home_add_app
                else -> R.string.home_all_apps
            })
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }
        val count = TextView(this).apply {
            textSize = 12f
            setTextColor(0xaaffffff.toInt())
            setPadding(0, dp(5), 0, 0)
        }
        val updateCount = {
            count.text = when {
                replaceDockComponent != null -> getString(R.string.home_dock_select_replacement)
                pinOnSelect -> getString(R.string.home_select_apps_hint)
                else -> getString(R.string.home_app_count, adapter.count)
            }
        }
        updateCount()
        titleStack.addView(title)
        titleStack.addView(count)
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton("close", getString(R.string.home_close)) { closeAppDrawer() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(header)

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBackground(0x18ffffff, 20)
            setPadding(dp(16), 0, dp(6), 0)
        }
        searchRow.addView(ImageView(this).apply { setImageDrawable(HomeGlyph("search")) },
            LinearLayout.LayoutParams(dp(20), dp(20)))
        val search = EditText(this).apply {
            hint = getString(R.string.home_search_hint)
            textSize = 15f
            isSingleLine = true
            background = null
            setTextColor(Color.WHITE)
            setHintTextColor(0x99ffffff.toInt())
            setPadding(dp(12), 0, dp(8), 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        drawerSearch = search
        searchRow.addView(search, LinearLayout.LayoutParams(0, -1, 1f))
        val clear = iconButton("close", getString(R.string.home_clear_search)) { search.text.clear() }.apply {
            visibility = View.INVISIBLE
        }
        searchRow.addView(clear, LinearLayout.LayoutParams(dp(38), dp(38)))
        content.addView(searchRow, LinearLayout.LayoutParams(-1, dp(54)))
        if (pinOnSelect) {
            content.addView(TextView(this).apply {
                text = getString(when {
                    replaceDockComponent != null -> R.string.home_dock_replace_preserves_items
                    pinToDock -> R.string.home_dock_capacity_hint
                    else -> R.string.home_pin_page
                }, currentPage + 1)
                textSize = 12f
                setTextColor(0xffbad4ff.toInt())
                setPadding(dp(4), dp(14), 0, 0)
            })
        }
        val grid = GridView(this).apply {
            numColumns = drawerColumnCount()
            horizontalSpacing = dp(2)
            verticalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            setPadding(0, dp(24), 0, dp(16))
            this.adapter = adapter
        }
        drawerGrid = grid
        val gridFrame = FrameLayout(this)
        gridFrame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        val empty = TextView(this).apply {
            text = getString(R.string.home_no_search_results)
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(0xccffffff.toInt())
        }
        gridFrame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        grid.emptyView = empty
        content.addView(gridFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        overlay.addView(content, FrameLayout.LayoutParams(-1, -1))
        appDrawer = overlay
        homeColumn.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        homeContent.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.requestFocus()
        content.requestApplyInsets()
        overlay.alpha = 0f
        overlay.translationY = dp(32).toFloat()
        overlay.animate().alpha(1f).translationY(0f).setDuration(240)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
        overlay.announceForAccessibility(title.text)

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
                clear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                updateCount()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        fun selectApp(position: Int, source: View?) {
            val item = adapter.getItem(position)
            val component = ComponentName(item.activityInfo.packageName, item.activityInfo.name)
            if (pinOnSelect) {
                if (replaceDockComponent != null) {
                    if (replaceDockItem(replaceDockComponent, component)) closeAppDrawer(animate = false)
                } else if (pinToDock) addAppToDock(component) else addAppToHome(component)
                adapter.notifyDataSetChanged()
            } else {
                launchApplication(component, source)
                closeAppDrawer(animate = false)
            }
        }
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH && adapter.count == 1) {
                selectApp(0, search)
                true
            } else false
        }
        grid.setOnItemClickListener { _, view, position, _ -> selectApp(position, view) }
        grid.setOnItemLongClickListener { _, view, position, _ ->
            val item = adapter.getItem(position)
            val component = ComponentName(item.activityInfo.packageName, item.activityInfo.name)
            showGlassMenu(item.loadLabel(packageManager).toString(), anchor = view,
                icon = AppIconDrawable(item.loadIcon(packageManager)), actions = listOf(
                GlassAction(getString(R.string.home_add_app), "home") {
                    addAppToHome(component)
                    adapter.notifyDataSetChanged()
                },
                GlassAction(getString(R.string.home_add_dock), "plus") {
                    addAppToDock(component)
                    adapter.notifyDataSetChanged()
                },
                GlassAction(getString(R.string.home_app_info), "info") {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${component.packageName}"))) }
                }))
            true
        }
    }

    private fun closeAppDrawer(animate: Boolean = true) {
        dismissAllGlassMenus()
        val overlay = appDrawer ?: return
        if (drawerClosing && animate) return
        drawerClosing = true
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(overlay.windowToken, 0)
        drawerSearch?.clearFocus()
        overlay.animate().cancel()
        val remove = Runnable {
            homeContent.removeView(overlay)
            if (overlay is LiquidGlassPanel) glassPanels.remove(overlay)
            if (appDrawer === overlay) {
                appDrawer = null
                drawerGrid = null
                drawerSearch = null
                drawerClosing = false
                homeColumn.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                editButton.requestFocus()
            }
        }
        if (animate) overlay.animate().alpha(0f).translationY(dp(24).toFloat())
            .setDuration(180).withEndAction(remove).start()
        else remove.run()
    }

    private fun addAppToHome(component: ComponentName) {
        val name = component.flattenToString()
        val entries = pinnedShortcuts().toMutableList()
        if (entries.any { it.component == name }) {
            Toast.makeText(this, "이미 홈 화면에 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        entries += PinnedShortcut(name, currentPage)
        savePinnedShortcuts(entries)
        renderShortcuts()
        Toast.makeText(this, "홈 화면에 추가했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun addAppToDock(component: ComponentName) {
        val name = component.flattenToString()
        val entries = pinnedShortcuts().toMutableList()
        if (entries.any { it.component == name && it.page == DOCK_PAGE }) {
            Toast.makeText(this, "이미 독에 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (entries.count { it.page == DOCK_PAGE } >= DOCK_SLOT_COUNT) {
            showDockReplacementTargets(component)
            return
        }
        entries.removeAll { it.component == name }
        entries += PinnedShortcut(name, DOCK_PAGE)
        savePinnedShortcuts(entries)
        renderShortcuts()
        Toast.makeText(this, "Dock에 추가했습니다.", Toast.LENGTH_SHORT).show()
    }

    private inner class LauncherActivityAdapter(private val allApps: List<ResolveInfo>) : BaseAdapter() {
        private var visibleApps = allApps
        private val labels = allApps.associateWith { it.loadLabel(packageManager).toString() }
        private val icons = android.util.LruCache<String, Drawable>(72)

        fun filter(query: String) {
            val needle = query.trim().lowercase()
            visibleApps = if (needle.isEmpty()) allApps else allApps.filter { app ->
                labels.getValue(app).lowercase().contains(needle) ||
                    app.activityInfo.packageName.lowercase().contains(needle)
            }
            notifyDataSetChanged()
        }

        override fun getCount() = visibleApps.size
        override fun getItem(position: Int) = visibleApps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(4), dp(3), dp(4))
                background = RippleDrawable(ColorStateList.valueOf(0x20ffffff), null,
                    roundedBackground(Color.WHITE, dp(16).toFloat()))
                addView(FrameLayout(this@HomeActivity).apply {
                    addView(ImageView(this@HomeActivity), FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
                    addView(ImageView(this@HomeActivity).apply {
                        background = roundedBackground(0xff416d97.toInt(), dp(10).toFloat())
                        setPadding(dp(3), dp(3), dp(3), dp(3))
                        setImageDrawable(HomeGlyph("check"))
                    }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM or Gravity.END))
                }, LinearLayout.LayoutParams(dp(58), dp(58)))
                addView(TextView(this@HomeActivity).apply {
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    includeFontPadding = false
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(7), 0, 0)
                    setTextColor(Color.WHITE)
                    setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
                }, LinearLayout.LayoutParams(-1, dp(38)))
                layoutParams = android.widget.AbsListView.LayoutParams(-1, dp(104))
            }
            val app = getItem(position)
            val name = ComponentName(app.activityInfo.packageName, app.activityInfo.name).flattenToString()
            val drawable = icons.get(name) ?: app.loadIcon(packageManager).also { icons.put(name, it) }
            val iconFrame = row.getChildAt(0) as FrameLayout
            (iconFrame.getChildAt(0) as ImageView).setImageDrawable(AppIconDrawable(drawable))
            val alreadyPinned = appDrawer != null && pinnedShortcuts().any { it.component == name }
            iconFrame.getChildAt(1).visibility = if (alreadyPinned) View.VISIBLE else View.GONE
            (row.getChildAt(1) as TextView).text = labels.getValue(app)
            row.contentDescription = labels.getValue(app) + if (alreadyPinned) getString(R.string.home_pinned_description) else ""
            return row
        }
    }

    private fun launcherActivities() = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        android.content.pm.PackageManager.ResolveInfoFlags.of(0),
    ).filter { it.activityInfo != null }
        .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

    private fun showGlassMenu(
        title: String,
        actions: List<GlassAction>,
        subtitle: String? = null,
        icon: Drawable? = null,
        anchor: View? = null,
        content: View? = null,
        replaceCurrent: Boolean = true,
    ): GlassActionOverlay? {
        if (!homeStarted || isDestroyed || widgetFlowPending) return null
        if (replaceCurrent) dismissAllGlassMenus()
        lateinit var menu: GlassActionOverlay
        menu = GlassActionOverlay(this, wallpaperView, title, subtitle, icon, actions,
            anchor = anchor, content = content, onDismiss = { menuStack.remove(menu) })
        menuStack.add(menu)
        menu.showIn(homeContent)
        return menu
    }

    private fun dismissGlassMenu(animate: Boolean = true) {
        activeMenu?.dismiss(animate)
    }

    private fun dismissAllGlassMenus() {
        // Top-down removal restores each underlying layer's accessibility state
        // before that layer is removed. Callbacks only remove their own entry.
        menuStack.toList().asReversed().forEach { it.dismiss(animate = false) }
    }
    private fun pickWidget() {
        if (widgetFlowPending || pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) return
        widgetFlowPending = true
        pendingWidgetPage = currentPage
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        runCatching { startActivityForResult(intent, REQUEST_PICK_WIDGET) }
            .onFailure {
                discardPendingWidget()
                Toast.makeText(this, "이 기기에서 위젯 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun bindSelectedWidget(id: Int, data: Intent?) {
        val provider = data?.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,
            ComponentName::class.java)
        if (provider == null) {
            discardPendingWidget()
            return
        }
        val options = Bundle()
        if (widgetManager.bindAppWidgetIdIfAllowed(id, provider, options)) {
            widgetManager.getAppWidgetInfo(id)?.let { configureOrFinishWidget(id, it) }
                ?: discardPendingWidget()
        } else {
            runCatching {
                startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options), REQUEST_BIND_WIDGET)
            }.onFailure {
                discardPendingWidget()
                Toast.makeText(this, R.string.home_widget_bind_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun configureOrFinishWidget(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setComponent(info.configure)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            runCatching { startActivityForResult(intent, REQUEST_CONFIGURE_WIDGET) }
                .onFailure { saveWidget(id) }
        } else saveWidget(id)
    }

    private fun saveWidget(id: Int) {
        val placements = widgetPlacements().toMutableList()
        if (placements.none { it.id == id }) {
            placements += WidgetPlacement(id, pendingWidgetPage.coerceIn(0, HOME_PAGE_COUNT - 1))
        }
        saveWidgetPlacements(placements)
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        widgetFlowPending = false
        renderWidgets()
    }

    private fun discardPendingWidget() {
        if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            runCatching { widgetHost.deleteAppWidgetId(pendingWidgetId) }
        }
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        widgetFlowPending = false
    }

    private fun renderWidgets() {
        if (!::widgetStacks.isInitialized) return
        widgetStacks.forEach { it.removeAllViews() }
        widgetOptionWidths.clear()
        val placements = widgetPlacements()
        val validPlacements = ArrayList<WidgetPlacement>()
        for (placement in placements) {
            val info = widgetManager.getAppWidgetInfo(placement.id)
            if (info == null) {
                runCatching { widgetHost.deleteAppWidgetId(placement.id) }
                continue
            }
            validPlacements += placement
            val hostView = runCatching { widgetHost.createView(this, placement.id, info) }.getOrNull() ?: continue
            hostView.setAppWidget(placement.id, info)
            val heightDp = (placement.heightDp.takeIf { it > 0 } ?: info.minHeight.coerceAtLeast(120))
                .coerceIn(100, 600)
            val widthDp = (resources.configuration.screenWidthDp - 40).coerceAtLeast(240)
            updateWidgetWidth(placement.id, widthDp, heightDp)
            val frame = FrameLayout(this).apply {
                background = roundedBackground(0x660d1119, dp(20).toFloat())
                clipToOutline = true
                addView(hostView, FrameLayout.LayoutParams(-1, -1))
                if (isEditingHome) {
                    addView(actionButton(getString(R.string.home_widget_settings)) {
                        showWidgetActions(placement, heightDp)
                    }, FrameLayout.LayoutParams(dp(72), dp(40), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(6)
                        marginEnd = dp(6)
                    })
                }
            }
            val openActions = {
                showWidgetActions(placement, heightDp)
                true
            }
            frame.setOnLongClickListener { openActions() }
            hostView.setOnLongClickListener { openActions() }
            widgetStacks[placement.page.coerceIn(0, HOME_PAGE_COUNT - 1)].addView(
                frame,
                LinearLayout.LayoutParams(-1, dp(heightDp)).apply { bottomMargin = dp(10) },
            )
        }
        if (validPlacements.size != placements.size) saveWidgetPlacements(validPlacements)
    }

    /** Keep RemoteViews and their current content alive across cover/inner resize. */
    private fun updateHostedWidgetWidths() {
        if (isDestroyed || !::widgetStacks.isInitialized) return
        val widthDp = (resources.configuration.screenWidthDp - 40).coerceAtLeast(240)
        widgetStacks.forEach { stack ->
            for (index in 0 until stack.childCount) {
                val frame = stack.getChildAt(index) as? FrameLayout ?: continue
                val hostView = frame.getChildAt(0) as? AppWidgetHostView ?: continue
                val heightDp = (frame.layoutParams.height / resources.displayMetrics.density)
                    .roundToInt().coerceIn(100, 600)
                updateWidgetWidth(hostView.appWidgetId, widthDp, heightDp)
            }
        }
    }

    private fun updateWidgetWidth(id: Int, widthDp: Int, heightDp: Int) {
        if (widgetOptionWidths[id] == widthDp) return
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        }
        runCatching { widgetManager.updateAppWidgetOptions(id, options) }
            .onSuccess { widgetOptionWidths[id] = widthDp }
    }

    private fun showWidgetActions(placement: WidgetPlacement, heightDp: Int) {
        showGlassMenu(getString(R.string.home_widget_settings), actions = listOf(
            GlassAction(getString(R.string.home_widget_resize), "size") {
                val sizes = listOf(120 to R.string.home_size_small, 200 to R.string.home_size_medium,
                    300 to R.string.home_size_large, 420 to R.string.home_size_extra_large)
                showGlassMenu(getString(R.string.home_widget_resize), actions = sizes.map { (preset, label) ->
                    GlassAction(getString(label), if (heightDp == preset) "check" else "size") {
                        saveWidgetPlacements(widgetPlacements().map {
                            if (it.id == placement.id) it.copy(heightDp = preset) else it
                        })
                        renderWidgets()
                    }
                })
            },
            GlassAction(getString(R.string.home_move_to_page), "pages") {
                showGlassMenu(getString(R.string.home_move_to_page), actions = (0 until HOME_PAGE_COUNT)
                    .filter { it != placement.page }.map { page ->
                        GlassAction(getString(R.string.home_page_number, page + 1), "home") {
                            saveWidgetPlacements(widgetPlacements().map {
                                if (it.id == placement.id) it.copy(page = page) else it
                            })
                            renderWidgets()
                        }
                    })
            },
            GlassAction(getString(R.string.home_remove_widget), "trash", destructive = true) {
                saveWidgetPlacements(widgetPlacements().filterNot { it.id == placement.id })
                runCatching { widgetHost.deleteAppWidgetId(placement.id) }
                renderWidgets()
            }))
    }

    private fun startClock() {
        stopClock()
        val tick = object : Runnable {
            override fun run() {
                val now = java.time.ZonedDateTime.now()
                clockLabel.text = String.format(java.util.Locale.getDefault(), "%d:%02d", now.hour, now.minute)
                dateLabel.text = now.format(java.time.format.DateTimeFormatter.ofPattern("M월 d일 EEEE", java.util.Locale.KOREAN))
                val delay = 60_000L - (System.currentTimeMillis() % 60_000L)
                mainHandler.postDelayed(this, delay)
            }
        }
        clockRunnable = tick
        tick.run()
    }

    private fun stopClock() {
        clockRunnable?.let(mainHandler::removeCallbacks)
        clockRunnable = null
    }

    private fun showWallpaperActions() {
        showGlassMenu(getString(R.string.home_wallpaper_title), actions = listOf(
            GlassAction(getString(R.string.home_wallpaper_choose_photo), "image") {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }
                    runCatching { startActivityForResult(intent, REQUEST_PICK_WALLPAPER) }
                        .onFailure { Toast.makeText(this, R.string.home_wallpaper_unavailable, Toast.LENGTH_SHORT).show() }
            },
            GlassAction(getString(R.string.home_wallpaper_restore_default), "restore") {
                    val previous = preferences.getString(KEY_WALLPAPER_URI, null)
                    val pending = pendingWallpaperUri?.toString()
                    preferences.edit().remove(KEY_WALLPAPER_URI).apply()
                    pendingWallpaperUri = null
                    wallpaperGeneration++
                    loadingWallpaperKey = null
                    loadedWallpaperKey = DEFAULT_WALLPAPER_KEY
                    wallpaperView.setImageDrawable(HomeWallpaperDrawable())
                    refreshGlassPanels()
                    releaseWallpaperPermission(previous)
                    if (pending != previous) releaseWallpaperPermission(pending)
            }))
    }

    /** Decode off the UI thread and cap the decoded pixel count, even for panoramic source photos. */
    private fun refreshWallpaper() {
        if (isDestroyed || wallpaperExecutor.isShutdown || !::wallpaperView.isInitialized) return
        val selectedUri = pendingWallpaperUri
            ?: preferences.getString(KEY_WALLPAPER_URI, null)?.let(Uri::parse)
        if (selectedUri == null) {
            if (loadedWallpaperKey != DEFAULT_WALLPAPER_KEY) {
                wallpaperView.setImageDrawable(HomeWallpaperDrawable())
                refreshGlassPanels()
                loadedWallpaperKey = DEFAULT_WALLPAPER_KEY
            }
            return
        }
        val targetWidth = (wallpaperView.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels).coerceAtLeast(1)
        val targetHeight = (wallpaperView.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels).coerceAtLeast(1)
        val key = "$selectedUri|$targetWidth|$targetHeight"
        if (loadedWallpaperKey == key) {
            if (pendingWallpaperUri == selectedUri) pendingWallpaperUri = null
            return
        }
        if (loadingWallpaperKey == key) return
        val generation = ++wallpaperGeneration
        loadingWallpaperKey = key
        wallpaperExecutor.execute {
            val result = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, selectedUri)) { decoder, info, _ ->
                    val width = info.size.width.coerceAtLeast(1)
                    val height = info.size.height.coerceAtLeast(1)
                    val fillScale = maxOf(targetWidth.toDouble() / width, targetHeight.toDouble() / height)
                    val pixelScale = sqrt(MAX_WALLPAPER_PIXELS.toDouble() / (width.toDouble() * height))
                    val edgeScale = MAX_WALLPAPER_EDGE.toDouble() / maxOf(width, height)
                    val scale = minOf(1.0, fillScale, pixelScale, edgeScale)
                    decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
            mainHandler.post {
                if (isDestroyed || generation != wallpaperGeneration) {
                    result.getOrNull()?.recycle()
                    return@post
                }
                loadingWallpaperKey = null
                result.onSuccess { bitmap ->
                    wallpaperView.setImageBitmap(bitmap)
                    refreshGlassPanels()
                    loadedWallpaperKey = key
                    if (pendingWallpaperUri == selectedUri) {
                        val previous = preferences.getString(KEY_WALLPAPER_URI, null)
                        preferences.edit().putString(KEY_WALLPAPER_URI, selectedUri.toString()).apply()
                        pendingWallpaperUri = null
                        if (previous != selectedUri.toString()) releaseWallpaperPermission(previous)
                        Toast.makeText(this, R.string.home_wallpaper_applied, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    if (pendingWallpaperUri == selectedUri) {
                        pendingWallpaperUri = null
                        if (preferences.getString(KEY_WALLPAPER_URI, null) != selectedUri.toString()) {
                            releaseWallpaperPermission(selectedUri.toString())
                        }
                        Toast.makeText(this, R.string.home_wallpaper_read_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun releaseWallpaperPermission(uri: String?) {
        if (uri == null) return
        runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun refreshGlassPanels() {
        glassPanels.forEach(LiquidGlassPanel::refreshBackdrop)
    }

    private fun pinnedShortcuts(): List<PinnedShortcut> {
        val stored = preferences.getString(KEY_SHORTCUTS, null).orEmpty()
        if (stored.isBlank()) return emptyList()
        val parsed = stored.split('\n').mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val separator = line.lastIndexOf('|')
            val page = if (separator > 0) line.substring(separator + 1).toIntOrNull() else null
            val component = if (page != null) line.substring(0, separator) else line
            component.takeIf { it.isNotBlank() }?.let {
                PinnedShortcut(it, (page ?: 0).coerceIn(DOCK_PAGE, HOME_PAGE_COUNT - 1))
            }
        }.distinctBy { it.component }
        if (stored.split('\n').any { !it.contains('|') }) savePinnedShortcuts(parsed)
        return parsed
    }

    private fun savePinnedShortcuts(shortcuts: List<PinnedShortcut>) {
        val unique = shortcuts.distinctBy { it.component }
            .map { it.copy(page = it.page.coerceIn(DOCK_PAGE, HOME_PAGE_COUNT - 1)) }
        val pageOrdered = (DOCK_PAGE until HOME_PAGE_COUNT).flatMap { page -> unique.filter { it.page == page } }
        preferences.edit().putString(KEY_SHORTCUTS, pageOrdered.joinToString("\n") {
            "${it.component}|${it.page}"
        }).apply()
    }

    private fun widgetPlacements(): List<WidgetPlacement> {
        val stored = preferences.getString(KEY_WIDGETS, null).orEmpty()
        if (stored.isBlank()) return emptyList()
        val encoded = stored.contains('|')
        val parsed = if (encoded) {
            stored.split('\n').mapNotNull { row ->
                val values = row.split('|')
                val id = values.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val page = values.getOrNull(1)?.toIntOrNull() ?: 0
                val height = values.getOrNull(2)?.toIntOrNull() ?: 0
                WidgetPlacement(id, page.coerceIn(0, HOME_PAGE_COUNT - 1), height.coerceIn(0, 600))
            }
        } else {
            stored.split(',').mapNotNull { it.toIntOrNull() }
                .map { WidgetPlacement(it, page = 0) }
        }
        val unique = parsed.distinctBy { it.id }
        if (!encoded) saveWidgetPlacements(unique)
        return unique
    }

    private fun saveWidgetPlacements(placements: List<WidgetPlacement>) {
        val encoded = placements.distinctBy { it.id }.joinToString("\n") {
            "${it.id}|${it.page.coerceIn(0, HOME_PAGE_COUNT - 1)}|${it.heightDp.coerceIn(0, 600)}"
        }
        preferences.edit().putString(KEY_WIDGETS, encoded).apply()
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun glassBackground(color: Int, radiusDp: Int): Drawable {
        val shape = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), 0x20ffffff)
        }
        return RippleDrawable(ColorStateList.valueOf(0x20ffffff), shape,
            roundedBackground(Color.WHITE, dp(radiusDp).toFloat()))
    }

    private fun iconButton(glyph: String, description: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(HomeGlyph(glyph))
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = glassBackground(0x16ffffff, 24)
        setOnClickListener { onClick() }
    }

    /** Resolution-independent default art; the layers echo two folding display planes. */
    private class HomeWallpaperDrawable : Drawable() {
        private val base = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rear = Paint(Paint.ANTI_ALIAS_FLAG)
        private val front = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rearPath = Path()
        private val frontPath = Path()

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            val w = bounds.width().toFloat().coerceAtLeast(1f)
            val h = bounds.height().toFloat().coerceAtLeast(1f)
            base.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(0xff081426.toInt(), 0xff183653.toInt(), 0xff091b2c.toInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            glow.shader = RadialGradient(w * 0.82f, h * 0.32f, maxOf(w, h) * 0.62f,
                intArrayOf(0x444b86ac, 0x00385477), null, Shader.TileMode.CLAMP)
            rear.shader = LinearGradient(w * 0.10f, h * 0.94f, w, h * 0.3f,
                intArrayOf(0xff163950.toInt(), 0xff376c8b.toInt(), 0xff81acbd.toInt()),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            rearPath.reset()
            rearPath.moveTo(-w * 0.2f, h * 1.1f)
            rearPath.cubicTo(w * 0.12f, h * 0.76f, w * 0.42f, h * 0.43f, w * 1.12f, h * 0.30f)
            rearPath.lineTo(w * 1.12f, h * 0.51f)
            rearPath.cubicTo(w * 0.45f, h * 0.6f, w * 0.41f, h * 1.08f, w * 0.02f, h * 1.18f)
            rearPath.close()
            front.shader = LinearGradient(w * 0.38f, h * 0.54f, w * 0.95f, h,
                intArrayOf(0xff568dad.toInt(), 0xff1b4666.toInt(), 0xff0b2138.toInt()),
                floatArrayOf(0f, 0.34f, 1f), Shader.TileMode.CLAMP)
            frontPath.reset()
            frontPath.moveTo(w * 0.10f, h * 1.18f)
            frontPath.cubicTo(w * 0.46f, h * 0.90f, w * 0.42f, h * 0.57f, w * 1.12f, h * 0.33f)
            frontPath.lineTo(w * 1.12f, h * 0.48f)
            frontPath.cubicTo(w * 0.78f, h * 0.64f, w * 0.88f, h * 0.95f, w * 0.69f, h * 1.18f)
            frontPath.close()
        }

        override fun draw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), base)
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), glow)
            canvas.drawPath(rearPath, rear)
            canvas.drawPath(frontPath, front)
            canvas.restoreToCount(saved)
        }

        override fun setAlpha(alpha: Int) {
            listOf(base, glow, rear, front).forEach { it.alpha = alpha }
            invalidateSelf()
        }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            listOf(base, glow, rear, front).forEach { it.colorFilter = colorFilter }
            invalidateSelf()
        }
        override fun getConstantState(): ConstantState = object : ConstantState() {
            override fun newDrawable(): Drawable = HomeWallpaperDrawable()
            override fun getChangingConfigurations() = 0
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    private class HomeGlyph(private val kind: String) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            when (kind) {
                "search" -> {
                    canvas.drawCircle(10.5f, 10.5f, 6.5f, paint)
                    canvas.drawLine(15.5f, 15.5f, 21f, 21f, paint)
                }
                "close" -> {
                    canvas.drawLine(6f, 6f, 18f, 18f, paint)
                    canvas.drawLine(18f, 6f, 6f, 18f, paint)
                }
                "check" -> {
                    val path = Path().apply { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 6f) }
                    canvas.drawPath(path, paint)
                }
                "folder" -> {
                    val path = Path().apply {
                        moveTo(3f, 6f); quadTo(3f, 4f, 5f, 4f)
                        lineTo(9f, 4f); lineTo(12f, 7f); lineTo(19f, 7f)
                        quadTo(21f, 7f, 21f, 9f); lineTo(21f, 18f)
                        quadTo(21f, 20f, 19f, 20f); lineTo(5f, 20f)
                        quadTo(3f, 20f, 3f, 18f); close()
                    }
                    canvas.drawPath(path, paint)
                }
                else -> {
                    canvas.drawLine(5f, 12f, 19f, 12f, paint)
                    canvas.drawLine(12f, 5f, 12f, 19f, paint)
                }
            }
            canvas.restoreToCount(saved)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = 24
        override fun getIntrinsicHeight() = 24
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val HOME_PREFERENCES = "duo_home_layout"
        const val KEY_SHORTCUTS = "shortcuts"
        const val KEY_WIDGETS = "widgets"
        const val KEY_CURRENT_PAGE = "current_page"
        const val KEY_WALLPAPER_URI = "wallpaper_uri"
        const val DEFAULT_WALLPAPER_KEY = "duo-default"
        const val MAX_WALLPAPER_PIXELS = 4_000_000
        const val MAX_WALLPAPER_EDGE = 3072
        const val STATE_PENDING_WIDGET_ID = "pending_widget_id"
        const val STATE_PENDING_WIDGET_PAGE = "pending_widget_page"
        const val STATE_WIDGET_FLOW_PENDING = "widget_flow_pending"
        const val FOLDER_PREFIX = "folder:"
        const val FOLDER_APPS_PREFIX = "folder_apps_"
        const val FOLDER_NAME_PREFIX = "folder_name_"
        const val HOME_PAGE_COUNT = 3
        const val DOCK_PAGE = -1
        const val DOCK_SLOT_COUNT = 4
        const val DRAG_PAGE_EDGE_DP = 36
        const val DRAG_PAGE_SWITCH_DELAY_MS = 550L
        const val FOLDER_HOVER_DELAY_MS = 700L
        const val WIDGET_HOST_ID = 2048
        const val REQUEST_PICK_WIDGET = 5101
        const val REQUEST_BIND_WIDGET = 5102
        const val REQUEST_CONFIGURE_WIDGET = 5103
        const val REQUEST_PICK_WALLPAPER = 5104
    }
}
