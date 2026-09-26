package com.hoons1994.iphoneduoanimation

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Setup screen for selecting Duo Home as the device's home app. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var primaryAction: Button
    private lateinit var previewAction: Button
    private lateinit var sensorStatus: TextView
    private lateinit var sensorDetail: TextView
    private lateinit var hingeMonitor: HingeAngleMonitor
    private var roleRequestInFlight = false
    private var homeLaunchInFlight = false
    private var latestAngle: Int? = null
    private var lastSensorUpdate = 0L
    private var launchError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roleRequestInFlight = savedInstanceState?.getBoolean(STATE_ROLE_PENDING) ?: false
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(36))
        }
        val centered = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ))
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) {
                    val desiredWidth = (right - left).coerceAtMost(dp(640))
                    if (content.layoutParams.width != desiredWidth) {
                        content.layoutParams = content.layoutParams.apply { width = desiredWidth }
                    }
                }
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            setBackgroundColor(BACKGROUND)
            addView(centered)
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(label(R.string.setup_brand, 20f, TEXT).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            })
        }
        content.addView(brand)
        content.addView(label(R.string.setup_heading, 32f, TEXT).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, spaced(top = 32, bottom = 12))
        content.addView(label(R.string.setup_intro, 16f, MUTED).apply {
            setLineSpacing(dp(5).toFloat(), 1f)
        }, spaced(bottom = 28))

        val roleCard = card()
        roleCard.addView(label(R.string.setup_start_title, 17f, TEXT).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        status = label(R.string.setup_role_unselected, 14f, MUTED).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        roleCard.addView(status, spaced(top = 10, bottom = 20))
        primaryAction = actionButton(primary = true).apply {
            setOnClickListener { requestHomeRole() }
        }
        roleCard.addView(primaryAction, spaced())
        previewAction = actionButton(primary = false).apply {
            setText(R.string.setup_preview)
            setOnClickListener { openHome() }
        }
        roleCard.addView(previewAction, spaced(top = 10))
        content.addView(roleCard, spaced(bottom = 16))

        val sensorCard = card()
        sensorCard.addView(label(R.string.setup_fold_title, 17f, TEXT).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        sensorStatus = label(R.string.setup_sensor_checking, 14f, ACCENT)
        sensorCard.addView(sensorStatus, spaced(top = 12))
        sensorDetail = label(R.string.setup_sensor_hint, 13f, MUTED).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        sensorCard.addView(sensorDetail, spaced(top = 6))
        sensorCard.addView(label(R.string.setup_fold_description, 14f, MUTED).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }, spaced(top = 16))
        content.addView(sensorCard, spaced(bottom = 28))

        content.addView(label(R.string.setup_customize_title, 18f, TEXT).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, spaced(bottom = 16))
        addStep(content, "1", R.string.setup_step_apps)
        addStep(content, "2", R.string.setup_step_widgets)
        addStep(content, "3", R.string.setup_step_pages)
        content.addView(label(R.string.setup_layout_notice, 13f, MUTED).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }, spaced(top = 14, bottom = 10))
        content.addView(label(R.string.setup_return_notice, 13f, MUTED).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        hingeMonitor = HingeAngleMonitor(this) { angle ->
            latestAngle = angle.rawAngleDegrees.roundToInt()
            val now = SystemClock.uptimeMillis()
            if (now - lastSensorUpdate >= SENSOR_LABEL_INTERVAL_MS) {
                lastSensorUpdate = now
                updateSensorStatus()
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        updateStatus()
    }

    override fun onStart() {
        super.onStart()
        latestAngle = null
        lastSensorUpdate = 0L
        hingeMonitor.start()
        updateSensorStatus()
    }

    override fun onResume() {
        super.onResume()
        homeLaunchInFlight = false
        if (::status.isInitialized) updateStatus()
    }

    override fun onStop() {
        hingeMonitor.stop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_ROLE_PENDING, roleRequestInFlight)
        super.onSaveInstanceState(outState)
    }

    private fun requestHomeRole() {
        if (roleRequestInFlight || homeLaunchInFlight) return
        launchError = null
        val roles = getSystemService(RoleManager::class.java)
        if (roles?.isRoleHeld(RoleManager.ROLE_HOME) == true) {
            openHome()
            return
        }
        val intent = if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
            roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            Intent(Settings.ACTION_HOME_SETTINGS)
        }
        roleRequestInFlight = true
        updateStatus()
        try {
            startActivityForResult(intent, REQUEST_HOME_ROLE)
        } catch (_: ActivityNotFoundException) {
            handleRoleLaunchFailure()
        } catch (_: SecurityException) {
            handleRoleLaunchFailure()
        }
    }

    private fun handleRoleLaunchFailure() {
        roleRequestInFlight = false
        launchError = getString(R.string.setup_role_launch_error)
        updateStatus()
    }

    @Deprecated("The system home-role picker returns through this callback on API 33.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_HOME_ROLE) {
            roleRequestInFlight = false
            updateStatus()
        }
    }

    private fun updateStatus() {
        val roles = getSystemService(RoleManager::class.java)
        val held = roles?.isRoleHeld(RoleManager.ROLE_HOME) == true
        val available = roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true
        status.text = launchError ?: getString(when {
            roleRequestInFlight -> R.string.setup_role_pending
            held -> R.string.setup_role_selected
            available -> R.string.setup_role_unselected
            else -> R.string.setup_role_unavailable
        })
        status.setTextColor(if (held && launchError == null) ACCENT else MUTED)
        primaryAction.setText(when {
            roleRequestInFlight -> R.string.setup_role_pending_button
            held -> R.string.setup_open_home
            available -> R.string.setup_choose_default
            else -> R.string.setup_open_home_settings
        })
        primaryAction.isEnabled = !roleRequestInFlight && !homeLaunchInFlight
        primaryAction.alpha = if (primaryAction.isEnabled) 1f else 0.55f
        previewAction.visibility = if (held) View.GONE else View.VISIBLE
        previewAction.isEnabled = !roleRequestInFlight && !homeLaunchInFlight
    }

    private fun updateSensorStatus() {
        if (!hingeMonitor.isAvailable) {
            sensorStatus.setText(R.string.setup_sensor_unavailable)
            sensorStatus.setTextColor(WARNING)
            sensorDetail.setText(R.string.setup_sensor_unavailable_detail)
            return
        }
        sensorStatus.setTextColor(ACCENT)
        sensorStatus.text = latestAngle?.let {
            getString(R.string.setup_sensor_angle, it)
        } ?: getString(R.string.setup_sensor_ready)
        val description = hingeMonitor.description().replace(" · stepped", "")
        sensorDetail.text = getString(
            if (hingeMonitor.isCoarse) R.string.setup_sensor_stepped else R.string.setup_sensor_detail,
            description,
        )
    }

    private fun openHome() {
        if (homeLaunchInFlight || roleRequestInFlight) return
        homeLaunchInFlight = true
        updateStatus()
        try {
            startActivity(Intent(this, HomeActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (_: ActivityNotFoundException) {
            homeLaunchInFlight = false
            launchError = getString(R.string.setup_home_launch_error)
            updateStatus()
        }
    }

    private fun label(resource: Int, size: Float, color: Int) = TextView(this).apply {
        setText(resource)
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(22), dp(20), dp(22))
        background = rounded(SURFACE, 24, OUTLINE)
    }

    private fun actionButton(primary: Boolean) = Button(this).apply {
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) BACKGROUND else TEXT)
        backgroundTintList = null
        background = RippleDrawable(
            ColorStateList.valueOf(if (primary) Color.argb(34, 0, 28, 26) else Color.argb(35, 255, 255, 255)),
            rounded(if (primary) ACCENT else BUTTON_SURFACE, 1000,
                if (primary) null else OUTLINE),
            rounded(Color.WHITE, 1000),
        )
        stateListAnimator = null
        elevation = 0f
        letterSpacing = 0f
        minHeight = dp(56)
        minimumHeight = dp(56)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun addStep(parent: LinearLayout, number: String, resource: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        row.addView(TextView(this).apply {
            text = number
            textSize = 13f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
            background = rounded(SURFACE, 14)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        row.addView(label(resource, 14f, TEXT).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
            topMargin = dp(3)
        })
        parent.addView(row, spaced(bottom = 16))
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun spaced(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val REQUEST_HOME_ROLE = 4101
        const val STATE_ROLE_PENDING = "duo_role_pending"
        const val SENSOR_LABEL_INTERVAL_MS = 200L
        val BACKGROUND = Color.rgb(17, 20, 27)
        val SURFACE = Color.rgb(28, 33, 43)
        val BUTTON_SURFACE = Color.rgb(42, 49, 64)
        val OUTLINE = Color.rgb(44, 51, 65)
        val TEXT = Color.rgb(241, 244, 250)
        val MUTED = Color.rgb(174, 184, 202)
        val ACCENT = Color.rgb(155, 217, 208)
        val WARNING = Color.rgb(255, 191, 163)
    }
}
