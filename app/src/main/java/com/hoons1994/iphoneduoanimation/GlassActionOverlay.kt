package com.hoons1994.iphoneduoanimation

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.IdentityHashMap
import kotlin.math.roundToInt

internal data class GlassAction(
    val label: String,
    val icon: String = "chevron",
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * An app-owned contextual menu or compact sheet inside the launcher's live View tree.
 * The host routes Back/HOME to [dismiss]; viewport and IME changes reflow the card.
 * Custom [content] must be unattached; its explicit height is retained.
 */
@SuppressLint("ViewConstructor")
internal class GlassActionOverlay(
    context: Context,
    wallpaper: ImageView,
    private val title: String,
    subtitle: String? = null,
    icon: Drawable? = null,
    actions: List<GlassAction> = emptyList(),
    private val anchor: View? = null,
    content: View? = null,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val siblingAccessibility = IdentityHashMap<View, Int>()
    private var parentBeforeDismiss: FrameLayout? = null
    private var previousFocus: View? = null
    private var safeInsets = Insets.NONE
    private var entrancePending = false
    private var shown = false
    private var closing = false
    private var finished = false
    private var pendingAction: (() -> Unit)? = null
    private var panelAboveAnchor = false
    private var anchorCenterX: Float? = null
    private val hasCustomContent = content != null

    private val scrim = View(context).apply {
        setBackgroundColor(0x4807111e)
        alpha = 0f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { dismiss() }
    }
    private val panel = LiquidGlassPanel(context, wallpaper, cornerRadiusDp = 28f, strong = true).apply {
        isClickable = true
        alpha = 0f
        elevation = dp(12).toFloat()
    }
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(6))
    }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        addView(column, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        accessibilityPaneTitle = title
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { dismiss() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(10), dp(12))
        }
        if (icon != null) {
            heading.addView(ImageView(context).apply {
                setImageDrawable(icon.constantState?.newDrawable(resources)?.mutate() ?: icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(0xb3ffffff.toInt())
                    includeFontPadding = false
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        }
        heading.addView(labels, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(ImageButton(context).apply {
            setImageDrawable(MenuGlyph("close", 0xccffffff.toInt()))
            contentDescription = context.getString(R.string.home_close)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ripple(0x12ffffff, dp(22).toFloat())
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        column.addView(heading)

        if (content != null) {
            val suppliedHeight = content.layoutParams?.height?.takeIf { it > 0 } ?: LayoutParams.WRAP_CONTENT
            column.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, suppliedHeight).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                bottomMargin = dp(8)
            })
        }
        if (actions.isNotEmpty()) addSeparator()
        actions.forEachIndexed { index, action ->
            column.addView(actionRow(action), LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            if (index < actions.lastIndex) addSeparator()
        }

        setOnApplyWindowInsetsListener { _, insets ->
            updateInsets(insets)
            insets
        }
        setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(
            WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
                updateInsets(insets)
                return insets
            }
        })
    }

    private fun actionRow(action: GlassAction): View {
        val foreground = if (action.destructive) 0xffffa5af.toInt() else 0xf5ffffff.toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = ripple(Color.TRANSPARENT, 0f)
            isClickable = true
            isFocusable = true
            isScreenReaderFocusable = true
            contentDescription = action.label
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
            addView(TextView(context).apply {
                text = action.label
                textSize = 16f
                setTextColor(foreground)
                includeFontPadding = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageDrawable(MenuGlyph(action.icon, foreground))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(18) })
            setOnClickListener {
                if (!closing && !finished) dismiss(afterDismiss = action.onClick)
            }
        }
    }

    private fun addSeparator() {
        column.addView(View(context).apply {
            setBackgroundColor(0x18ffffff)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)).apply {
            marginStart = dp(18)
            marginEnd = dp(18)
        })
    }

    fun showIn(parent: FrameLayout) {
        if (shown || finished) return
        shown = true
        parentBeforeDismiss = parent
        previousFocus = parent.findFocus()
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            siblingAccessibility[child] = child.importantForAccessibility
            child.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        entrancePending = true
        parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        rootWindowInsets?.let(::updateInsets)
        requestApplyInsets()
    }

    fun dismiss(animate: Boolean = true, afterDismiss: (() -> Unit)? = null) {
        if (finished) return
        if (closing) {
            if (!animate) {
                // HOME, lifecycle teardown, or menu replacement cancels the
                // outgoing row's queued action instead of running it mid-cleanup.
                pendingAction = afterDismiss
                finishDismiss()
            }
            return
        }
        closing = true
        pendingAction = afterDismiss
        val focused = findFocus()
        if (focused?.onCheckIsTextEditor() == true) {
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
        panel.animate().cancel()
        scrim.animate().cancel()
        if (!animate || !isAttachedToWindow || panel.width == 0) {
            finishDismiss()
            return
        }
        scrim.animate().alpha(0f).setDuration(140).start()
        panel.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f)
            .translationY(if (panelAboveAnchor) dp(6).toFloat() else dp(12).toFloat())
            .setDuration(140).withEndAction { finishDismiss() }.start()
    }

    private fun finishDismiss() {
        if (finished) return
        finished = true
        closing = true
        panel.animate().cancel()
        scrim.animate().cancel()
        val owner = parentBeforeDismiss
        (parent as? ViewGroup)?.removeView(this)
        siblingAccessibility.forEach { (view, importance) ->
            if (view.parent === owner) view.importantForAccessibility = importance
        }
        siblingAccessibility.clear()
        // Restoring a search/rename field's focus can reopen its keyboard.
        previousFocus?.takeIf { it.isAttachedToWindow && it.isFocusable && !it.onCheckIsTextEditor() }
            ?.requestFocus()
        previousFocus = null
        parentBeforeDismiss = null
        val action = pendingAction
        pendingAction = null
        onDismiss()
        action?.invoke()
    }

    private fun updateInsets(insets: WindowInsets) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = insets.getInsets(WindowInsets.Type.ime())
        val next = Insets.of(maxOf(bars.left, ime.left), bars.top,
            maxOf(bars.right, ime.right), maxOf(bars.bottom, ime.bottom))
        if (safeInsets != next) {
            safeInsets = next
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        scrim.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        val availableWidth = (w - safeInsets.left - safeInsets.right - dp(24)).coerceAtLeast(1)
        val availableHeight = (h - safeInsets.top - safeInsets.bottom - dp(24)).coerceAtLeast(1)
        val desiredWidth = minOf(availableWidth, dp(if (hasCustomContent) 440 else 360))
        // Measure the content first. LiquidGlassPanel's full-size backdrop must
        // not force a short menu to consume the complete available screen.
        scroll.measure(MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST))
        panel.measure(MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(scroll.measuredHeight.coerceIn(1, availableHeight), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        scrim.layout(0, 0, width, height)
        val safeLeft = safeInsets.left + dp(12)
        val safeTop = safeInsets.top + dp(12)
        val safeRight = (width - safeInsets.right - dp(12)).coerceAtLeast(safeLeft + panel.measuredWidth)
        val safeBottom = (height - safeInsets.bottom - dp(12)).coerceAtLeast(safeTop + panel.measuredHeight)
        var panelLeft = safeLeft + (safeRight - safeLeft - panel.measuredWidth) / 2
        var panelTop = safeBottom - panel.measuredHeight
        panelAboveAnchor = false
        anchorCenterX = null
        anchor?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }?.let { target ->
            val anchorLocation = IntArray(2)
            val overlayLocation = IntArray(2)
            target.getLocationOnScreen(anchorLocation)
            getLocationOnScreen(overlayLocation)
            val anchorLeft = anchorLocation[0] - overlayLocation[0]
            val anchorTop = anchorLocation[1] - overlayLocation[1]
            val anchorBottom = anchorTop + target.height
            val centerX = anchorLeft + target.width / 2f
            anchorCenterX = centerX
            panelLeft = (centerX - panel.measuredWidth / 2f).roundToInt()
                .coerceIn(safeLeft, safeRight - panel.measuredWidth)
            val above = anchorTop - dp(10) - panel.measuredHeight
            val below = anchorBottom + dp(10)
            panelAboveAnchor = above >= safeTop || below + panel.measuredHeight > safeBottom
            panelTop = (if (panelAboveAnchor) above else below)
                .coerceIn(safeTop, safeBottom - panel.measuredHeight)
        }
        panel.layout(panelLeft, panelTop, panelLeft + panel.measuredWidth, panelTop + panel.measuredHeight)
        panel.pivotX = ((anchorCenterX ?: (panelLeft + panel.width / 2f)) - panelLeft).coerceIn(0f, panel.width.toFloat())
        panel.pivotY = if (anchor == null || panelAboveAnchor) panel.height.toFloat() else 0f
        if (entrancePending && panel.width > 0 && panel.height > 0) {
            entrancePending = false
            post {
                if (closing || finished) return@post
                if (!hasFocus()) requestFocus()
                panel.scaleX = 0.96f
                panel.scaleY = 0.96f
                panel.translationY = if (panelAboveAnchor) dp(8).toFloat() else dp(14).toFloat()
                scrim.animate().alpha(1f).setDuration(160).start()
                panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(210).setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
        }
    }

    private fun ripple(color: Int, radius: Float): Drawable {
        fun shape(fill: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = radius }
        return RippleDrawable(ColorStateList.valueOf(0x20ffffff), shape(color), shape(Color.WHITE))
    }

    private fun dp(value: Int) = (value * density).roundToInt()
}

/** Compact monochrome symbols rendered at the same optical size as the menu text. */
private class MenuGlyph(private val kind: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.65f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
        fun route(vararg points: Float) {
            path.reset()
            path.moveTo(points[0], points[1])
            for (index in 2 until points.size step 2) path.lineTo(points[index], points[index + 1])
            canvas.drawPath(path, paint)
        }
        fun rounded(l: Float, t: Float, r: Float, b: Float, radius: Float = 2f) {
            rect.set(l, t, r, b)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        when (kind) {
            "replace", "swap" -> {
                route(4f, 7f, 20f, 7f, 16f, 3f)
                route(20f, 17f, 4f, 17f, 8f, 21f)
            }
            "left" -> { route(10f, 5f, 3f, 12f, 10f, 19f); line(3f, 12f, 21f, 12f) }
            "right", "move" -> { route(14f, 5f, 21f, 12f, 14f, 19f); line(3f, 12f, 21f, 12f) }
            "home" -> {
                route(3f, 10f, 12f, 3f, 21f, 10f)
                route(5f, 9f, 5f, 21f, 10f, 21f, 10f, 14f, 14f, 14f, 14f, 21f, 19f, 21f, 19f, 9f)
            }
            "pages" -> { rounded(4f, 6f, 17f, 21f); route(8f, 3f, 21f, 3f, 21f, 17f) }
            "edit", "rename" -> {
                route(4f, 16f, 4f, 21f, 9f, 20f, 21f, 8f, 16f, 3f, 4f, 16f)
                line(13f, 6f, 18f, 11f)
            }
            "trash", "delete", "remove" -> {
                route(5f, 7f, 6f, 21f, 18f, 21f, 19f, 7f)
                line(3f, 7f, 21f, 7f)
                route(9f, 7f, 9f, 3f, 15f, 3f, 15f, 7f)
                line(10f, 11f, 10f, 17f); line(14f, 11f, 14f, 17f)
            }
            "info" -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 11f, 12f, 17f); line(12f, 7f, 12f, 7.1f) }
            "folder", "ungroup" -> route(3f, 7f, 3f, 20f, 21f, 20f, 21f, 7f, 12f, 7f, 9f, 4f, 3f, 4f, 3f, 7f)
            "grid", "apps", "widget" -> {
                rounded(3f, 3f, 10f, 10f, 1.5f); rounded(14f, 3f, 21f, 10f, 1.5f)
                rounded(3f, 14f, 10f, 21f, 1.5f); rounded(14f, 14f, 21f, 21f, 1.5f)
            }
            "image", "photo", "wallpaper" -> {
                rounded(3f, 4f, 21f, 20f)
                canvas.drawCircle(8f, 9f, 1.5f, paint)
                route(3f, 17f, 9f, 12f, 13f, 16f, 17f, 11f, 21f, 15f)
            }
            "plus", "add" -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            "restore" -> {
                rect.set(4f, 4f, 21f, 21f)
                canvas.drawArc(rect, 215f, 285f, false, paint)
                route(3f, 3f, 3f, 10f, 10f, 10f)
            }
            "check" -> route(4f, 12f, 9f, 17f, 20f, 6f)
            "size" -> {
                rounded(4f, 4f, 20f, 20f)
                route(8f, 13f, 8f, 16f, 11f, 16f)
                route(13f, 8f, 16f, 8f, 16f, 11f)
                line(8f, 16f, 16f, 8f)
            }
            "close", "cancel" -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
            "chevron" -> route(9f, 5f, 16f, 12f, 9f, 19f)
            else -> {
                canvas.drawCircle(5f, 12f, 0.8f, paint)
                canvas.drawCircle(12f, 12f, 0.8f, paint)
                canvas.drawCircle(19f, 12f, 0.8f, paint)
            }
        }
        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
}
