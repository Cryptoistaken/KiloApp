package net.typeblog.socks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import android.content.SharedPreferences
import net.typeblog.socks.util.Constants
import net.typeblog.socks.util.Constants.ACTION_START_VPN
import net.typeblog.socks.util.Constants.ACTION_STOP_VPN
import net.typeblog.socks.util.Constants.ACTION_VPN_STATE_CHANGED
import net.typeblog.socks.util.Constants.BUBBLE_STYLE_CLASSIC
import net.typeblog.socks.util.Constants.BUBBLE_STYLE_CIRCLE
import net.typeblog.socks.util.Constants.BUBBLE_STYLE_LOCK
import net.typeblog.socks.util.Constants.CIRCLE_DOWN
import net.typeblog.socks.util.Constants.CIRCLE_LEFT
import net.typeblog.socks.util.Constants.CIRCLE_RIGHT
import net.typeblog.socks.util.Constants.CIRCLE_SIZE_DEFAULT
import net.typeblog.socks.util.Constants.CIRCLE_SMALL
import net.typeblog.socks.util.Constants.CIRCLE_UP
import net.typeblog.socks.util.Constants.PREF_CIRCLE_ALIGN
import net.typeblog.socks.util.Constants.PREF_CIRCLE_SIZE
import android.content.ClipData
import android.content.ClipboardManager
import net.typeblog.socks.util.NamesRepo
import net.typeblog.socks.util.SmsWatcher
import net.typeblog.socks.util.Constants.PREF_BUBBLE_STYLE
import net.typeblog.socks.util.Constants.PREF_THEME_MODE
import net.typeblog.socks.util.Constants.PREF_BUBBLE_X
import net.typeblog.socks.util.Constants.PREF_BUBBLE_Y
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.ProxyProviders
import net.typeblog.socks.util.NotifText
import net.typeblog.socks.util.ServiceRebind
import net.typeblog.socks.util.SplitTunnel
import net.typeblog.socks.util.ThemeMode
import net.typeblog.socks.util.Utility
import java.util.Locale
import kotlin.math.abs

/**
 * System-wide floating control bubble.
 *
 * Rewritten visual language: a soft gradient orb (brand orange when idle,
 * red when connected, dimmed + breathing while connecting) with a frosted
 * timer pill underneath. All state transitions are animated (color cross-
 * fade, connect "pop", press-down squeeze) instead of hard-cut, and the
 * play/stop glyphs are drawn from custom vectors instead of stock system
 * media icons.
 */
class FloatingControlService : Service() {

    private enum class BubbleState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    private var vpnService: IVpnService? = null
    private var bound = false
    private var pendingProfile: String? = null
    private var state = BubbleState.DISCONNECTED
    private var rebindAttempts = 0
    private var rebindInFlight = false
    private var rebindRunnable: Runnable? = null

    // Last notification content we actually issued, so the 200ms poll loop can
    // skip redundant notify() calls when nothing on screen changed.
    private var lastNotificationText: String? = null
    private var lastNotificationState: String? = null

    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var bubbleView: FrameLayout? = null
    // The inner circle that carries the gradient/icon/spinner/timer. Scaled by
    // the breathing/pop animations; the transparent outer container (bubbleView)
    // stays fixed-sized so the grown circle is never clipped by the square window.
    private var bubbleVisualView: FrameLayout? = null
    private var iconView: ImageView? = null
    private var progressBar: ProgressBar? = null
    private var timerView: TextView? = null
    private var flagPillView: LinearLayout? = null
    private var flagPillText: TextView? = null
    private var flagPillParams: WindowManager.LayoutParams? = null
    private var statusLabelView: TextView? = null
    private var statusLabelParams: WindowManager.LayoutParams? = null

    private var touchSlop = 0
    private var initialX = 0
    private var initialY = 0
    private var initialRawX = 0f
    private var initialRawY = 0f
    private var dragging = false

    private var bubbleStyle: String = Constants.BUBBLE_STYLE_LOCK
    // Lock-style cycling (Protected -> Flag+Digits -> Timer -> every 5s alternate)
    private var lockHandler: Handler = Handler(Looper.getMainLooper())
    private var lockProtRunnable: Runnable? = null
    private var lockFirstFlagRunnable: Runnable? = null
    private var lockCycleRunnable: Runnable? = null
    private var lockFlashHideRunnable: Runnable? = null
    private var lockCycleAlt: Int = 0
    private var lockFlashing: Boolean = false
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    // Last seen system night bit: onConfigurationChanged fires for rotation
    // too, so only re-theme when night actually flipped (device-theme mode).
    private var lastNightYes: Boolean = false

    fun getBubbleStyle(): String = bubbleStyle
    fun isLockStyle(): Boolean = bubbleStyle == Constants.BUBBLE_STYLE_LOCK
    private fun isCircleStyle(): Boolean = bubbleStyle == BUBBLE_STYLE_CIRCLE
    // Circle shares the classic orb; its glyph is always the menu icon.

    // Effective app theme (manual Settings > Theme override, else device):
    // the bubble spinner and the "Connecting" label follow it, status colors
    // stay fixed in every theme.
    private fun isLightMode(): Boolean = !ThemeMode.isDarkTheme(this)
    // Status colors stay fixed in every theme; only the spinner and the
    // "Connecting" label flip between black and white.
    private fun lockGreen(): Int = Color.parseColor("#1C9C7C")
    private fun lockErr(): Int = Color.parseColor("#CC2D4F")
    private fun lockSpin(): Int = if (isLightMode()) Color.parseColor("#0C0C14") else Color.WHITE

    private var bubbleSizePx = 0
    // Reserve extra window space around the visual circle so scale animations
    // (breathing 1.07x, connect-pop 1.18x) never push the drawn oval past the
    // square overlay window bounds, which would clip its border mid-spin.
    private var bubbleGrowMarginPx = 0
    private var bubbleWindowSizePx = 0
    private var breatheAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private var menuOverlay: BubbleMenuOverlay? = null
    private var smsOverlay: SmsMenuOverlay? = null
    private var circleMenu: CircleBubbleMenu? = null
    // Single source of truth for the trigger glyph (Menu vs X). Never infer
    // from window attach state: remove/add cycles and animator timing made
    // that inference strand the wrong glyph and skip the blur.
    private var circleMenuOpen = false

    private val longPressRunnable = Runnable { openBubbleMenu() }

    private val pollHandler = Handler(Looper.getMainLooper())
    private var connectTimeoutHandler: Handler? = null
    private var connectTimeoutRunnable: Runnable? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollState()
            updateFlagPill()
            updateForegroundNotification()
            pollHandler.postDelayed(
                this,
                if (state == BubbleState.CONNECTING) CONNECTING_POLL_INTERVAL else POLL_INTERVAL
            )
        }
    }

    private val timerHandler = Handler(Looper.getMainLooper())

    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimerText()
            timerHandler.postDelayed(this, TIMER_INTERVAL)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vpnService = IVpnService.Stub.asInterface(service)
            bound = true
            rebindAttempts = 0
            Log.d(TAG, "Bound to SocksVpnService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            bound = false
            Log.d(TAG, "Unbound from SocksVpnService — scheduling re-bind")
            // The :vpn process may have been killed/recreated (it survives the
            // UI process). If the VPN is still running there, pollState will
            // immediately flip us back to CONNECTED once the fresh bind lands.
            scheduleRebind()
        }
    }

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START_VPN -> {
                    Log.d(TAG, "Notification Connect action")
                    startVpn()
                }
                ACTION_STOP_VPN -> {
                    Log.d(TAG, "Notification Disconnect action")
                    stopVpn()
                }
                ACTION_VPN_STATE_CHANGED -> {
                    pollState()
                    updateFlagPill()
                    updateForegroundNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission not granted — stopping service")
            Toast.makeText(
                this,
                getString(R.string.bubble_overlay_permission),
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        bubbleStyle = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PREF_BUBBLE_STYLE, BUBBLE_STYLE_LOCK) ?: BUBBLE_STYLE_LOCK
        lastNightYes = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        createNotificationChannel()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        // Use display context for WindowManager so overlay is a top-level system window,
        // not attached to the service's window token.
        refreshWindowManager()
        bubbleView = createBubbleView()
        params = buildLayoutParams()
        restoreBubblePosition()
        flagPillView = createFlagPillView()
        flagPillParams = buildFlagPillLayoutParams()
        statusLabelView = createStatusLabelView()
        statusLabelParams = buildStatusLabelLayoutParams()
        addBubbleToWindow()
        addFlagPillToWindow()
        addStatusLabelToWindow()
        updateFlagPillPosition()
        updateStatusLabelPosition()
        updateStatusLabel()
        bindToVpnService()
        registerActionReceiver()
        pollHandler.post(pollRunnable)
        connectTimeoutHandler = Handler(Looper.getMainLooper())
        menuOverlay = BubbleMenuOverlay(
            this,
            onCountrySelected = { code -> onBubbleCountrySelected(code) },
            onExitRequested = { stopFloatingBubble() },
            onDismissed = { longPressFired = false }
        )
        smsOverlay = SmsMenuOverlay(
            this,
            onNumberCopy = { display -> copySmsEntry(display) },
            onGenerate = { digits -> provisionSmsNumber(range = digits) },
            onDismissed = { longPressFired = false }
        )
        circleMenu = CircleBubbleMenu(
            this,
            onProxyTap = { circleMenu?.hide(); handleTap() },
            onProxyLongPress = { circleMenu?.hide(); openCountryMenu() },
            onSmsTap = { circleMenu?.hide(); provisionSmsNumber() },
            onSmsDoubleTap = { circleMenu?.hide(); provisionSmsNumber(forceNew = true) },
            onSmsLongPress = { circleMenu?.hide(); openSmsPopup() },
            onSheetTap = { circleMenu?.hide(); toast("SheetSubmit coming soon") },
            onNameTap = { copyRandomName() },
            onDismissed = {
                circleMenuOpen = false
                longPressFired = false
                setCircleGlyph()
                // Anchor is free again: settle inside the clearance so the
                // next expand always fits.
                nudgeBubbleIntoClearance()
            },
            onCloseAnim = { playMenuClosePulse() }
        )
        prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREF_BUBBLE_STYLE) {
                val newStyle = PreferenceManager.getDefaultSharedPreferences(this)
                    .getString(PREF_BUBBLE_STYLE, BUBBLE_STYLE_LOCK) ?: BUBBLE_STYLE_LOCK
                if (newStyle != bubbleStyle) {
                    recreateBubbleForStyleChange(newStyle)
                }
            } else if (key == PREF_CIRCLE_SIZE) {
                // Slider grows the trigger AND the 4 open menu items together:
                // rebuild the trigger live, then resize the open menu in
                // place (no collapse needed). Trigger stays on top after its
                // window re-add so items keep diving underneath it. While the
                // menu is open the trigger keeps its anchor spot (no
                // clearance nudge — that would detach it from its items).
                if (isCircleStyle()) {
                    val menuOpen = circleMenu?.isShowing() == true
                    recreateBubbleForStyleChange(
                        bubbleStyle,
                        preserveCenter = true,
                        applyClearance = !menuOpen
                    )
                    try {
                        val newSize = PreferenceManager.getDefaultSharedPreferences(this)
                            .getInt(
                                PREF_CIRCLE_SIZE,
                                Constants.CIRCLE_SIZE_DEFAULT
                            )
                            .coerceIn(
                                Constants.CIRCLE_SIZE_MIN,
                                Constants.CIRCLE_SIZE_MAX
                            )
                        circleMenu?.updateSize(newSize)
                        if (circleMenu?.isShowing() == true) bringBubbleToFront()
                    } catch (_: Exception) {
                    }
                }
            } else if (key == PREF_CIRCLE_ALIGN) {
                // Alignment switch moves the open menu bubbles live on screen
                // (circle <-> up/down/left/right) — no collapse needed. When
                // closed, nudge the parked trigger inside the new clearance
                // so the next expand always fits (never while open: the
                // trigger is the menu's anchor).
                if (isCircleStyle()) {
                    try {
                        val newAlign = PreferenceManager.getDefaultSharedPreferences(this)
                            .getString(PREF_CIRCLE_ALIGN, CIRCLE_SMALL) ?: CIRCLE_SMALL
                        circleMenu?.updateAlign(newAlign)
                        if (circleMenu?.isShowing() != true) nudgeBubbleIntoClearance()
                    } catch (_: Exception) {
                    }
                }
            } else if (key == PREF_THEME_MODE) {
                // Manual Theme pick: re-apply the theme-wired bubble elements
                // (spinner tint, Connecting label) and re-inflate the popup so
                // its -night resources match the effective theme.
                updateBubbleUi(state)
                menuOverlay?.refreshTheme()
                smsOverlay?.hide()
            }
        }
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(prefListener)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubbleView?.isAttachedToWindow == true) {
            return START_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshWindowManager()
        menuOverlay?.onConfigurationChanged()
        smsOverlay?.onConfigurationChanged()
        circleMenu?.hideNow()
        reClampBubblePosition()
        val nightYes = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (nightYes != lastNightYes) {
            lastNightYes = nightYes
            updateBubbleUi(state)
            // Popup refreshes itself inside menuOverlay.onConfigurationChanged()
            // above; a manual Theme pick is handled by the pref listener.
        }
    }

    private fun refreshWindowManager() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return
        windowManager = createDisplayContext(display)
            .getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    /**
     * Rotation/density changes can leave the saved bubble position outside the new
     * display's content area — re-clamp it into the current inset-aware drag
     * bounds so it is never left off-screen.
     */
    private fun reClampBubblePosition() {
        val lp = params ?: return
        val (newX, newY) = clampBubbleToBounds(lp.x, lp.y)
        if (newX != lp.x || newY != lp.y) {
            lp.x = newX
            lp.y = newY
            try {
                if (bubbleView?.isAttachedToWindow == true) {
                    windowManager?.updateViewLayout(bubbleView, lp)
                }
            } catch (e: Exception) {
                Log.e(TAG, "updateViewLayout failed during re-clamp", e)
            }
            persistBubblePosition()
        }
        updateFlagPillPosition()
        updateStatusLabelPosition()
    }

    private fun recreateBubbleForStyleChange(newStyle: String, preserveCenter: Boolean = false, applyClearance: Boolean = true) {
        bubbleStyle = newStyle
        val oldX = params?.x
        val oldY = params?.y
        // Size slider: keep the trigger's CENTER stable so it never detaches
        // from open menu items; style switches keep legacy top-left behavior.
        val oldCenterX = params?.let { it.x + bubbleWindowSizePx / 2 }
        val oldCenterY = params?.let { it.y + bubbleWindowSizePx / 2 }
        stopLockSequence()
        stopTimer()
        stopBreathing()
        removeStatusLabelFromWindow()
        removeFlagPillFromWindow()
        removeBubbleFromWindow()
        bubbleView = createBubbleView()
        params = buildLayoutParams()
        if (preserveCenter && oldCenterX != null && oldCenterY != null) {
            val (nx, ny) = clampBubbleToBounds(
                oldCenterX - bubbleWindowSizePx / 2,
                oldCenterY - bubbleWindowSizePx / 2,
                applyClearance
            )
            params?.x = nx
            params?.y = ny
        } else if (oldX != null && oldY != null) {
            val (nx, ny) = clampBubbleToBounds(oldX, oldY, applyClearance)
            params?.x = nx
            params?.y = ny
        }
        flagPillView = createFlagPillView()
        flagPillParams = buildFlagPillLayoutParams()
        statusLabelView = createStatusLabelView()
        statusLabelParams = buildStatusLabelLayoutParams()
        addBubbleToWindow()
        addFlagPillToWindow()
        addStatusLabelToWindow()
        updateFlagPillPosition()
        updateStatusLabelPosition()
        updateStatusLabel()
        // re-apply ui for current state
        updateBubbleUi(state)
        updateForegroundNotification()
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollRunnable)
        rebindRunnable?.let { pollHandler.removeCallbacks(it); rebindRunnable = null }
        rebindInFlight = false
        timerHandler.removeCallbacks(timerRunnable)
        stopLockSequence()
        prefListener?.let { PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(it) }
        connectTimeoutRunnable?.let { connectTimeoutHandler?.removeCallbacks(it) }
        breatheAnimator?.cancel(); breatheAnimator = null
        colorAnimator?.cancel(); colorAnimator = null
        bound = false
        vpnService = null
        try {
            unregisterReceiver(actionReceiver)
        } catch (_: Exception) {
        }
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {
        }
        longPressHandler.removeCallbacks(longPressRunnable)
        menuOverlay?.hide()
        smsOverlay?.hide()
        circleMenu?.hideNow()
        persistBubblePosition()
        removeFlagPillFromWindow()
        removeStatusLabelFromWindow()
        removeBubbleFromWindow()
        super.onDestroy()
    }

    private fun registerActionReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_START_VPN)
            addAction(ACTION_STOP_VPN)
            addAction(ACTION_VPN_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(actionReceiver, filter)
        }
    }

    private fun createBubbleView(): FrameLayout {
        // Resolve style from prefs before sizing
        bubbleStyle = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PREF_BUBBLE_STYLE, BUBBLE_STYLE_LOCK) ?: BUBBLE_STYLE_LOCK
        val density = resources.displayMetrics.density
        if (isLockStyle()) {
            val sizePx = (96 * density).toInt()
            bubbleSizePx = sizePx
            bubbleGrowMarginPx = 0
            bubbleWindowSizePx = sizePx
            val glyphSizePx = (42 * density).toInt()

            val root = FrameLayout(this)
            root.clipChildren = false
            root.clipToPadding = false
            root.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)

            val circle = FrameLayout(this)
            circle.clipChildren = false
            circle.clipToPadding = false
            circle.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
            circle.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28 * density
                setColor(Color.TRANSPARENT)
            }

            iconView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(glyphSizePx, glyphSizePx, Gravity.CENTER)
                setImageResource(R.drawable.ic_proton_lock_open_filled_2)
                setColorFilter(lockErr())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            circle.addView(iconView)

            progressBar = ProgressBar(this, null, android.R.attr.progressBarStyle).apply {
                isIndeterminate = true
                indeterminateDrawable?.mutate()?.setTint(lockSpin())
                layoutParams = FrameLayout.LayoutParams(glyphSizePx, glyphSizePx, Gravity.CENTER)
                visibility = View.GONE
            }
            circle.addView(progressBar)

            timerView = TextView(this).apply {
                text = "00:00"
                includeFontPadding = false
                setTextColor(Color.BLACK)
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.02f
                gravity = Gravity.CENTER
                // Below icon: 1dp gap (was 4dp) — even closer to icon like html demo
                val topMargin = (sizePx / 2 + glyphSizePx / 2 + (1 * density).toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply { this.topMargin = topMargin }
                visibility = View.GONE
            }
            circle.addView(timerView)

            root.addView(circle)
            bubbleVisualView = circle
            root.setOnTouchListener(createTouchListener())
            return root
        } else {
            // Circle trigger = size slider (like HTML S = size); Classic stays
            // fixed 60dp. Items are (size - 2), so the middle grows together
            // with the 4 side bubbles when the slider moves.
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val circleDp = if (isCircleStyle()) {
                prefs.getInt(PREF_CIRCLE_SIZE, CIRCLE_SIZE_DEFAULT)
                    .coerceIn(
                        net.typeblog.socks.util.Constants.CIRCLE_SIZE_MIN,
                        net.typeblog.socks.util.Constants.CIRCLE_SIZE_MAX
                    )
            } else {
                60
            }
            val sizePx = (circleDp * density).toInt()
            bubbleSizePx = sizePx
            val growMarginPx = (12 * density).toInt()
            bubbleGrowMarginPx = growMarginPx
            val windowSizePx = sizePx + 2 * growMarginPx
            bubbleWindowSizePx = windowSizePx
            val glyphSizePx = (sizePx * 0.43f).toInt().coerceAtLeast((18 * density).toInt())
            val progressSizePx = (sizePx * 0.43f).toInt().coerceAtLeast((18 * density).toInt())

            val root = FrameLayout(this)
            root.clipChildren = false
            root.clipToPadding = false
            root.layoutParams = FrameLayout.LayoutParams(windowSizePx, windowSizePx)

            val circle = FrameLayout(this)
            circle.outlineProvider = ViewOutlineProvider.BACKGROUND
            circle.clipChildren = false
            circle.clipToPadding = false
            circle.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)

            if (isCircleStyle()) {
                // HTML .cm-trigger: dark control button, always #27272A with
                // a faint light border — never the VPN state gradient (state
                // lives on the Proxy item's lock, not the trigger).
                circle.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#27272A"))
                    setStroke((1 * density).toInt(), Color.argb(26, 255, 255, 255))
                }
            } else {
                val (startColor, endColor) = stateGradient(BubbleState.DISCONNECTED)
                val background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(startColor, endColor))
                background.shape = GradientDrawable.OVAL
                circle.background = background
            }

            iconView = ImageView(this)
            iconView!!.layoutParams = FrameLayout.LayoutParams(glyphSizePx, glyphSizePx, Gravity.CENTER)
            iconView!!.setImageResource(if (isCircleStyle()) R.drawable.ic_menu_burger else R.drawable.ic_bubble_play)
            iconView!!.setColorFilter(Color.WHITE)
            iconView!!.scaleType = ImageView.ScaleType.FIT_CENTER
            circle.addView(iconView)

            progressBar = ProgressBar(this, null, android.R.attr.progressBarStyle)
            progressBar!!.isIndeterminate = true
            progressBar!!.indeterminateDrawable?.mutate()
                ?.setTint(Color.WHITE)
            progressBar!!.layoutParams = FrameLayout.LayoutParams(progressSizePx, progressSizePx, Gravity.CENTER)
            progressBar!!.visibility = View.GONE
            circle.addView(progressBar)

            timerView = TextView(this)
            timerView!!.text = "00:00"
            timerView!!.setTextColor(Color.WHITE)
            timerView!!.textSize = 11.5f
            timerView!!.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
            timerView!!.letterSpacing = 0.02f
            timerView!!.gravity = Gravity.CENTER
            timerView!!.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            timerView!!.visibility = View.GONE
            circle.addView(timerView)

            root.addView(circle)
            bubbleVisualView = circle
            root.setOnTouchListener(createTouchListener())
            return root
        }
    }

    private fun createFlagPillView(): LinearLayout {
        val density = resources.displayMetrics.density
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER
        pill.setPadding(
            (5 * density).toInt(),
            (3 * density).toInt(),
            (5 * density).toInt(),
            (3 * density).toInt()
        )

        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = 12 * density
        bg.setColor(Color.WHITE)
        pill.background = bg

        flagPillText = TextView(this)
        flagPillText?.textSize = 10f
        flagPillText?.setTextColor(Color.BLACK)
        flagPillText?.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        flagPillText?.letterSpacing = 0.04f
        pill.addView(flagPillText)

        pill.visibility = View.GONE
        return pill
    }

    private fun buildFlagPillLayoutParams(): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }
    }

    private fun addFlagPillToWindow() {
        val wm = windowManager ?: return
        val view = flagPillView ?: return
        try {
            if (view.isAttachedToWindow) return
            wm.addView(view, flagPillParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add flag pill overlay", e)
        }
    }

    private fun removeFlagPillFromWindow() {
        val wm = windowManager ?: return
        val view = flagPillView ?: return
        try {
            if (view.isAttachedToWindow) {
                wm.removeView(view)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove flag pill overlay", e)
        }
    }

    private fun updateFlagPill() {
        if (isLockStyle()) {
            flagPillView?.visibility = View.GONE
            return
        }
        val pill = flagPillView ?: return
        val text = flagPillText ?: return
        if (state == BubbleState.CONNECTED) {
            val countryCode = try {
                vpnService?.countryCode ?: ""
            } catch (e: Exception) {
                ""
            }
            if (countryCode.isNotEmpty()) {
                val flag = Utility.countryCodeToFlag(countryCode)
                val ip = try {
                    vpnService?.currentIp ?: ""
                } catch (e: Exception) {
                    ""
                }
                val lastOctet = when {
                    ip.contains('.') -> ip.substringAfterLast('.')
                    ip.contains(':') -> ip.substringAfterLast(':').takeLast(4)
                    else -> ""
                }
                text.text = if (lastOctet.isNotEmpty()) {
                    "$flag $countryCode $lastOctet"
                } else {
                    "$flag $countryCode"
                }
                pill.visibility = View.VISIBLE
                updateFlagPillPosition()
            } else {
                pill.visibility = View.GONE
            }
        } else {
            pill.visibility = View.GONE
        }
    }

    private fun updateFlagPillPosition() {
        val pillParams = flagPillParams ?: return
        val bubbleParams = params ?: return
        val density = resources.displayMetrics.density
        // Use the same inset-aware display source as the bubble drag math so the
        // pill and bubble share one coordinate system. The pill uses
        // TOP|CENTER_HORIZONTAL gravity: x is measured from the display's
        // horizontal center, y from the display's top edge — the same origin the
        // bubble's TOP|START coordinates use.
        val insets = currentSystemBarInsets()
        val bounds = currentDragBounds()
        val displayWidth = bounds.width() + insets.left + insets.right
        val pill = flagPillView
        if (pill != null && pill.visibility == View.VISIBLE) {
            // Center on the bubble but clamp inside the visible bounds, so the
            // pill never spills off-screen when the bubble sits at a side edge.
            val pillW = if (pill.width > 0) {
                pill.width
            } else {
                try {
                    pill.measure(
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    pill.measuredWidth
                } catch (_: Exception) {
                    0
                }
            }
            val minCenter = bounds.left + pillW / 2
            val maxCenter = (bounds.right - pillW / 2).coerceAtLeast(minCenter)
            val centerX = (bubbleParams.x + bubbleWindowSizePx / 2).coerceIn(minCenter, maxCenter)
            pillParams.x = centerX - displayWidth / 2
        } else {
            pillParams.x = (bubbleParams.x + bubbleWindowSizePx / 2) - displayWidth / 2
        }
        pillParams.y = bubbleParams.y + bubbleGrowMarginPx + bubbleSizePx - (2 * density).toInt()
        try {
            if (flagPillView?.isAttachedToWindow == true) {
                windowManager?.updateViewLayout(flagPillView, pillParams)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update flag pill position", e)
        }
        // The pill may not be measured yet (height == 0) when it was just made
        // visible — re-run once it is laid out so its geometry is correct.
        if (flagPillView?.height ?: 0 == 0) {
            flagPillView?.post { updateFlagPillPosition() }
        }
    }

    private fun createStatusLabelView(): TextView {
        val tv = TextView(this)
        tv.includeFontPadding = false
        tv.textSize = 12f
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.letterSpacing = 0.01f
        tv.gravity = Gravity.CENTER
        tv.setShadowLayer(4f, 0f, 2f, Color.argb(100, 0, 0, 0))
        tv.visibility = View.GONE
        return tv
    }

    private fun buildStatusLabelLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = 0 }
    }

    private fun addStatusLabelToWindow() {
        val wm = windowManager ?: return
        val v = statusLabelView ?: return
        try { if (v.isAttachedToWindow) return; wm.addView(v, statusLabelParams) } catch (e: Exception) { Log.e(TAG, "add status label failed", e) }
    }

    private fun removeStatusLabelFromWindow() {
        val wm = windowManager ?: return
        val v = statusLabelView ?: return
        try { if (v.isAttachedToWindow) wm.removeView(v) } catch (e: Exception) { Log.e(TAG, "remove status label failed", e) }
    }

    private fun updateStatusLabel() {
        val tv = statusLabelView ?: return
        if (!isLockStyle()) { tv.visibility = View.GONE; return }
        when (state) {
            BubbleState.DISCONNECTED -> {
                tv.text = "Unprotected"
                tv.setTextColor(lockErr())
                tv.visibility = View.VISIBLE
                updateStatusLabelPosition()
            }
            BubbleState.CONNECTING -> {
                tv.text = "Connecting"
                tv.setTextColor(if (isLightMode()) Color.BLACK else Color.WHITE)
                tv.visibility = View.VISIBLE
                updateStatusLabelPosition()
            }
            BubbleState.CONNECTED -> {
                tv.visibility = View.GONE
            }
        }
    }

    /**
     * Pixels the status label ("Connecting"/"Unprotected") hangs below the
     * bubble window's bottom edge. 0 when the label is hidden. Used to keep
     * the label above the nav bar at the bottom edge.
     */
    private fun statusLabelOverhangPx(): Int {
        val tv = statusLabelView ?: return 0
        if (tv.visibility != View.VISIBLE) return 0
        val density = resources.displayMetrics.density
        val labelH = if (tv.height > 0) tv.height else (20 * density).toInt()
        val glyphPx = (42 * density).toInt()
        val timerTop = bubbleSizePx / 2 + glyphPx / 2 + (1 * density).toInt()
        return (bubbleGrowMarginPx + timerTop + labelH - bubbleWindowSizePx).coerceAtLeast(0)
    }

    /**
     * Lowest bubble y that keeps the bubble AND its followers (flag pill,
     * status label) above the nav bar. Replaces the ad-hoc maxY math that
     * only reserved room for the pill.
     */
    private fun bubbleMaxY(bounds: Rect): Int {
        val density = resources.displayMetrics.density
        val pillHeightPx = if (flagPillView?.visibility == View.VISIBLE) {
            flagPillView?.height ?: 0
        } else {
            0
        }
        return (bounds.bottom - bubbleWindowSizePx - pillHeightPx -
            statusLabelOverhangPx() + (2 * density).toInt())
            .coerceAtLeast(bounds.top)
    }

    /**
     * Required clearance around the trigger CENTER so an expanded circle
     * menu always fits on screen: [left, top, right, bottom] in px. Circle
     * needs its orbit radius on every side; lines need the full strip
     * (OFF + 3*GAP) on their side, item half + 8dp elsewhere. Zero when the
     * circle style is off. Mirrors CircleBubbleMenu's layout math.
     */
    private fun menuClearancePx(): IntArray {
        if (!isCircleStyle()) return intArrayOf(0, 0, 0, 0)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val align = prefs.getString(PREF_CIRCLE_ALIGN, CIRCLE_SMALL) ?: CIRCLE_SMALL
        val sizeDp = prefs.getInt(PREF_CIRCLE_SIZE, Constants.CIRCLE_SIZE_DEFAULT)
            .coerceIn(Constants.CIRCLE_SIZE_MIN, Constants.CIRCLE_SIZE_MAX)
        val density = resources.displayMetrics.density
        val slotMargin = (sizeDp * density / 2 - density + 8 * density).toInt()
        val gap = (58 * density).toInt()
        val off = (62 * density).toInt()
        val r = (68.75f * density).toInt()
        val reach = off + 3 * gap
        return when (align) {
            CIRCLE_UP -> intArrayOf(slotMargin, reach + slotMargin, slotMargin, slotMargin)
            CIRCLE_DOWN -> intArrayOf(slotMargin, slotMargin, slotMargin, reach + slotMargin)
            CIRCLE_RIGHT -> intArrayOf(slotMargin, slotMargin, reach + slotMargin, slotMargin)
            CIRCLE_LEFT -> intArrayOf(reach + slotMargin, slotMargin, slotMargin, slotMargin)
            else -> {
                val c = r + slotMargin
                intArrayOf(c, c, c, c)
            }
        }
    }

    /**
     * Clamp a desired top-left bubble position into the drag bounds, inset
     * by the menu clearance so the trigger can never park where its
     * expanded items would clip. Falls back to the plain trigger fit when
     * the screen is too small for the full clearance.
     */
    private fun clampBubbleToBounds(x: Int, y: Int, applyClearance: Boolean = true): Pair<Int, Int> {
        val bounds = currentDragBounds()
        var minX = bounds.left
        var maxX = (bounds.right - bubbleWindowSizePx).coerceAtLeast(minX)
        var minY = bounds.top
        var maxY = bubbleMaxY(bounds)
        if (applyClearance && isCircleStyle() && bubbleWindowSizePx > 0) {
            val cl = menuClearancePx()
            val winHalf = bubbleWindowSizePx / 2
            val cMinX = bounds.left + cl[0] - winHalf
            val cMaxX = bounds.right - cl[2] - winHalf
            if (cMinX <= cMaxX) {
                minX = maxOf(minX, cMinX)
                maxX = minOf(maxX, cMaxX)
            }
            val cMinY = bounds.top + cl[1] - winHalf
            val cMaxY = bounds.bottom - cl[3] - winHalf
            val pillMaxY = bubbleMaxY(bounds)
            if (cMinY <= minOf(cMaxY, pillMaxY)) {
                minY = maxOf(minY, cMinY)
                maxY = minOf(maxY, minOf(cMaxY, pillMaxY))
            }
            if (minX > maxX) {
                minX = bounds.left
                maxX = (bounds.right - bubbleWindowSizePx).coerceAtLeast(minX)
            }
            if (minY > maxY) {
                minY = bounds.top
                maxY = bubbleMaxY(bounds)
            }
        }
        return Pair(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
    }

    /** Nudge the parked trigger inside the menu clearance (never mid-drag
     * or while the open menu uses it as anchor). */
    private fun nudgeBubbleIntoClearance() {
        val lp = params ?: return
        if (dragging) return
        if (circleMenu?.isShowing() == true) return
        val (nx, ny) = clampBubbleToBounds(lp.x, lp.y)
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx
            lp.y = ny
            try {
                if (bubbleView?.isAttachedToWindow == true) {
                    windowManager?.updateViewLayout(bubbleView, lp)
                }
            } catch (e: Exception) {
                Log.e(TAG, "nudge into clearance failed", e)
            }
            updateFlagPillPosition()
            updateStatusLabelPosition()
            persistBubblePosition()
        }
    }

    private fun updateStatusLabelPosition() {
        val lp = statusLabelParams ?: return
        val bp = params ?: return
        val density = resources.displayMetrics.density
        val insets = currentSystemBarInsets()
        val bounds = currentDragBounds()
        val displayWidth = bounds.width() + insets.left + insets.right
        // Center on the bubble but clamp inside the visible bounds, so a wide
        // label never spills off-screen at the left/right edges.
        val tv = statusLabelView
        val labelW = when {
            tv == null -> 0
            tv.width > 0 -> tv.width
            else -> (tv.paint.measureText(tv.text.toString()) +
                tv.paddingLeft + tv.paddingRight).toInt()
        }
        val minCenter = bounds.left + labelW / 2
        val maxCenter = (bounds.right - labelW / 2).coerceAtLeast(minCenter)
        val centerX = (bp.x + bubbleWindowSizePx / 2).coerceIn(minCenter, maxCenter)
        lp.x = centerX - displayWidth / 2
        // Exactly where timer (Protected / flag) sits: below icon 1dp — even closer like html demo
        val glyphPx = (42 * density).toInt()
        val timerTop = bubbleSizePx / 2 + glyphPx / 2 + (1 * density).toInt()
        lp.y = bp.y + bubbleGrowMarginPx + timerTop
        val labelH = if (tv != null && tv.height > 0) tv.height else (20 * density).toInt()
        // If the label would hang under the nav bar (bubble parked at the
        // bottom when the label appears), nudge the whole bubble up — but
        // never mid-drag, where it would fight the finger.
        if (!dragging && tv != null && tv.visibility == View.VISIBLE) {
            val overflow = (lp.y + labelH) - bounds.bottom
            if (overflow > 0) {
                bp.y = (bp.y - overflow).coerceIn(bounds.top, bubbleMaxY(bounds))
                try {
                    if (bubbleView?.isAttachedToWindow == true) {
                        windowManager?.updateViewLayout(bubbleView, bp)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "update status label nudge failed", e)
                }
                lp.y = bp.y + bubbleGrowMarginPx + timerTop
                updateFlagPillPosition()
                persistBubblePosition()
            }
        }
        try { if (statusLabelView?.isAttachedToWindow == true) windowManager?.updateViewLayout(statusLabelView, lp) } catch (e: Exception) { Log.e(TAG, "update status label pos failed", e) }
        if (statusLabelView?.height ?: 0 == 0) statusLabelView?.post { updateStatusLabelPosition() }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            bubbleWindowSizePx,
            bubbleWindowSizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            // Density-scale the initial offset so it sits below the status bar on
            // any density instead of a raw-pixel 100px.
            y = (100 * resources.displayMetrics.density).toInt()
        }
    }

    /**
     * Loads the last saved bubble position (persisted across re-enables and app
     * restarts) and clamps it into the current drag bounds so the bubble is never
     * restored off-screen on a differently-sized display.
     */
    private fun restoreBubblePosition() {
        val lp = params ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val savedX = prefs.getInt(PREF_BUBBLE_X, Int.MIN_VALUE)
        val savedY = prefs.getInt(PREF_BUBBLE_Y, Int.MIN_VALUE)
        if (savedX == Int.MIN_VALUE || savedY == Int.MIN_VALUE) return
        val (nx, ny) = clampBubbleToBounds(savedX, savedY)
        lp.x = nx
        lp.y = ny
    }

    private fun persistBubblePosition() {
        val lp = params ?: return
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putInt(PREF_BUBBLE_X, lp.x)
            .putInt(PREF_BUBBLE_Y, lp.y)
            .apply()
    }

    private fun currentSystemBarInsets(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val insets = windowManager?.currentWindowMetrics?.windowInsets
                    ?.getInsets(WindowInsets.Type.systemBars())
                if (insets != null) {
                    return Rect(insets.left, insets.top, insets.right, insets.bottom)
                }
            } catch (e: Exception) {
            }
        }
        val statusBarHeight = try {
            resources.getDimensionPixelSize(
                resources.getIdentifier("status_bar_height", "dimen", "android")
            )
        } catch (e: Exception) {
            0
        }
        val navBarHeight = try {
            resources.getDimensionPixelSize(
                resources.getIdentifier("navigation_bar_height", "dimen", "android")
            )
        } catch (e: Exception) {
            0
        }
        return Rect(0, statusBarHeight, 0, navBarHeight)
    }

    /**
     * Visible content area for the bubble/pill: the full display bounds minus ALL
     * four system-bar/cutout insets (API 30+ via currentWindowMetrics, else
     * display metrics minus status/navigation bar dimensions).
     */
    private fun currentDragBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val metrics = windowManager?.currentWindowMetrics
                if (metrics != null) {
                    val b = metrics.bounds
                    val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
                    return Rect(insets.left, insets.top, b.width() - insets.right, b.height() - insets.bottom)
                }
            } catch (e: Exception) {
            }
        }
        val dm = resources.displayMetrics
        val insets = currentSystemBarInsets()
        return Rect(insets.left, insets.top, dm.widthPixels - insets.right, dm.heightPixels - insets.bottom)
    }

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { v, event ->
            val lp = v.layoutParams as WindowManager.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialRawX = event.rawX
                    initialRawY = event.rawY
                    dragging = false
                    longPressFired = false
                    longPressHandler.postDelayed(longPressRunnable, 480)
                    bubbleVisualView?.let {
                        it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(120).start()
                    } ?: v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(120).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    // While the circle menu is open the trigger is its anchor:
                    // lock dragging so the hamburger can never detach from its
                    // items. The tap on ACTION_UP still toggles (closes).
                    val menuOpen = isCircleStyle() && circleMenu?.isShowing() == true
                    if (!menuOpen && (abs(event.rawX - initialRawX) > touchSlop || abs(event.rawY - initialRawY) > touchSlop)) {
                        dragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        // Clamp inside the visible area, inset by the menu
                        // clearance so the trigger can never park where its
                        // expanded items would clip off-screen.
                        val (nx, ny) = clampBubbleToBounds(
                            initialX + (event.rawX - initialRawX).toInt(),
                            initialY + (event.rawY - initialRawY).toInt()
                        )
                        lp.x = nx
                        lp.y = ny
                        try {
                            windowManager?.updateViewLayout(v, lp)
                            updateFlagPillPosition()
                            updateStatusLabelPosition()
                        } catch (e: Exception) {
                            Log.e(TAG, "updateViewLayout failed", e)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    val circle = bubbleVisualView
                    if (circle != null) {
                        circle.animate().scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(OvershootInterpolator(2.5f)).start()
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(OvershootInterpolator(2.5f)).start()
                    }
                    if (!dragging && !longPressFired) {
                        v.performClick()
                        if (isCircleStyle()) toggleCircleMenu() else handleTap()
                    }
                    val wasDragging = dragging
                    dragging = false
                    if (wasDragging) {
                        persistBubblePosition()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    bubbleVisualView?.let {
                        it.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    } ?: v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    dragging = false
                }
            }
            true
        }
    }

    private fun bindToVpnService() {
        val intent = Intent(this, SocksVpnService::class.java)
        try {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to SocksVpnService", e)
        }
    }

    /**
     * Re-establish the cross-process bind after it is lost.
     *
     * Unlike the old cap, this NEVER gives up: while the VPN keeps running in
     * the :vpn process the bubble must keep its spinner in sync, so we keep
     * retrying on an escalating-but-bounded backoff forever. Because the bind
     * only lands once the service is actually available, a perpetual schedule
     * costs nothing when it is alive (each no-op poll returns immediately).
     */
    private fun scheduleRebind() {
        if (rebindInFlight) return
        rebindInFlight = true
        rebindAttempts++
        val delayMs = ServiceRebind.backoffDelayMs(rebindAttempts)
        rebindRunnable?.let { pollHandler.removeCallbacks(it) }
        val r = Runnable {
            rebindInFlight = false
            if (!bound) {
                Log.d(TAG, "Re-binding to SocksVpnService (attempt $rebindAttempts)")
                bindToVpnService()
                if (!bound) {
                    scheduleRebind()
                }
            }
        }
        rebindRunnable = r
        pollHandler.postDelayed(r, delayMs)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notify_channel_status),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.notify_channel_status_desc)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        // Rebuild lazily so we can compare against the last-issued content.
        val notification = buildForegroundNotification()
        val latestText = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val latestState = state.name
        if (latestText == lastNotificationText && latestState == lastNotificationState) return
        manager.notify(NOTIFICATION_ID, notification)
        lastNotificationText = latestText
        lastNotificationState = latestState
    }

    private fun buildForegroundNotification(): Notification {
        val connectIntent = Intent(ACTION_START_VPN).apply { setPackage(packageName) }
        val connectPending = PendingIntent.getBroadcast(
            this, 1, connectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(ACTION_STOP_VPN).apply { setPackage(packageName) }
        val stopPending = PendingIntent.getBroadcast(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = try {
            when {
                state == BubbleState.CONNECTED -> getString(R.string.bubble_state_connected)
                state == BubbleState.CONNECTING -> getString(R.string.bubble_state_connecting)
                else -> getString(R.string.bubble_state_off)
            }
        } catch (e: Exception) {
            "Floating control"
        }
        val text = try {
            when {
                state == BubbleState.CONNECTED && !vpnService?.currentIp.isNullOrEmpty() -> {
                    val ip = vpnService?.currentIp ?: ""
                    val country = vpnService?.country ?: ""
                    // Notification content stays plain ASCII (repo rule): no
                    // flag emoji, no middle-dot separator.
                    if (country.isNotEmpty()) "$country - $ip" else "$ip"
                }
                state == BubbleState.CONNECTED -> getString(R.string.notify_verifying)
                state == BubbleState.CONNECTING -> getString(R.string.notify_establishing)
                else -> getString(R.string.bubble_vpn_off)
            }
        } catch (e: Exception) {
            "Floating control"
        }

        val isConnected = state == BubbleState.CONNECTED || state == BubbleState.CONNECTING
        val buttonText = if (isConnected) getString(R.string.notify_action_disconnect)
            else getString(R.string.bubble_action_connect)
        val buttonPending = if (isConnected) stopPending else connectPending

        // Standard notification (no custom RemoteViews): the custom-content
        // layout rendered as an EMPTY notification row on some devices
        // (SystemUI shows only the app label + time). Title/text/action always
        // render in the standard template.
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(NotifText.fit(text))
            .setSmallIcon(R.drawable.ic_notification_transparent)
            // Plain-drawable copy of the launcher PNG: R.mipmap.ic_launcher
            // resolves to the adaptive-icon XML on API 26+, which
            // BitmapFactory cannot decode (returns null), leaving a stale or
            // missing large icon in the notification shade.
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.app_icon))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .addAction(0, buttonText, buttonPending)
            .build()
    }

    private fun addBubbleToWindow() {
        val wm = windowManager ?: return
        val view = bubbleView ?: return
        try {
            if (view.isAttachedToWindow) return
            wm.addView(view, params)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Overlay token invalid — permission may have been revoked", e)
            Toast.makeText(
                this,
                getString(R.string.bubble_overlay_denied),
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    private fun removeBubbleFromWindow() {
        val wm = windowManager ?: return
        val view = bubbleView ?: return
        try {
            if (view.isAttachedToWindow) {
                wm.removeView(view)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay view", e)
        }
    }

    private fun handleTap() {
        when (state) {
            BubbleState.CONNECTED -> stopVpn()
            BubbleState.CONNECTING -> {
                // Instant cancel: a tap while connecting aborts the attempt
                // (accidental bubble taps shouldn't force a full wait).
                // Clearing pendingProfile stops pollState from auto-starting
                // after the cancel; stopVpn resets state and timeout.
                Log.d(TAG, "Tap while connecting: instant cancel")
                pendingProfile = null
                stopVpn()
            }
            BubbleState.DISCONNECTED -> startVpn()
        }
    }

    private fun openBubbleMenu() {
        // Long-press while the menu is already open keeps it as-is (no
        // re-show flicker) — and must not strand longPressFired=true, which
        // would swallow the next tap on the hamburger.
        if (isCircleStyle() && circleMenu?.isShowing() == true) {
            longPressFired = false
            return
        }
        longPressFired = true
        bubbleView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (isCircleStyle()) {
            showCircleMenu()
            return
        }
        openCountryMenu()
    }

    private fun showCircleMenu() {
        // Never re-show over an open menu: CircleBubbleMenu.show() tears the
        // overlay down first, which would visibly detach the trigger from
        // its items. Tap toggles via toggleCircleMenu(); long-press no-ops.
        if (circleMenu?.isShowing() == true) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // HTML lock-line under the Proxy bubble: red while connecting,
        // green once protected (flag + country when known).
        val (proxySub, proxySubColor) = when (state) {
            BubbleState.CONNECTING -> Pair("Connecting", lockErr())
            BubbleState.CONNECTED -> {
                val cc = try {
                    vpnService?.countryCode ?: ""
                } catch (_: Exception) {
                    ""
                }
                if (cc.isNotEmpty()) {
                    Pair(
                        "${Utility.countryCodeToFlag(cc)} $cc",
                        lockGreen()
                    )
                } else {
                    Pair("Protected", lockGreen())
                }
            }
            else -> Pair("", Color.WHITE)
        }
        circleMenu?.show(
            (params?.x ?: 0) + bubbleWindowSizePx / 2,
            (params?.y ?: 0) + bubbleWindowSizePx / 2,
            prefs.getString(PREF_CIRCLE_ALIGN, CIRCLE_SMALL) ?: CIRCLE_SMALL,
            prefs.getInt(PREF_CIRCLE_SIZE, CIRCLE_SIZE_DEFAULT),
            state == BubbleState.CONNECTED,
            proxySub,
            proxySubColor
        )
        // HTML layering: .cm-trigger z-50 sits ABOVE .cm-items-layer z-0, so
        // menus emerge from underneath the main button. The item windows are
        // added after the bubble window (so they paint on top) — re-insert
        // the bubble window so the trigger stays on top and items dive under
        // it. Item windows are small and touch-transparent, so taps outside
        // them always reach the app below.
        circleMenuOpen = true
        bringBubbleToFront()
        setCircleGlyph()
    }

    /**
     * Re-inserts the bubble window above the full-screen circle-menu scrim.
     * Same [params] (position preserved); no-op when the bubble isn't up.
     */
    private fun bringBubbleToFront() {
        try {
            val wm = windowManager ?: return
            val view = bubbleView ?: return
            val lp = params ?: return
            if (!view.isAttachedToWindow) return
            wm.removeView(view)
            wm.addView(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "bringBubbleToFront failed", e)
        }
    }

    private fun toggleCircleMenu() {
        try {
            bubbleView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {
        }
        // Swap the glyph immediately on EVERY tap (open AND close) so the
        // trigger never lags the menu — the close path must not wait for
        // the item fly-home animation to finish.
        if (circleMenu?.isShowing() == true) {
            circleMenuOpen = false
            setCircleGlyph(open = false)
            circleMenu?.hide()
        } else {
            showCircleMenu()
        }
    }

    // HTML trigger swap: Menu glyph normally, X while the menu is open.
    // Exact mockup: 200ms opacity + blur(10px)->blur(0) swap on the whole
    // trigger button, on EVERY open and close tap — API 31+; older devices
    // keep the crossfade. Target comes from circleMenuOpen (never inferred
    // from attach state); drawable + tag update synchronously so a remove/add
    // or a fast double-tap can never strand the wrong glyph. The blur clears
    // on a timer — never in an animator end-action, which detach/cancel paths
    // can swallow and strand the blur forever.
    private var circleGlyphGen = 0
    private fun setCircleGlyph(open: Boolean? = null) {
        if (!isCircleStyle()) return
        try {
            val iv = iconView ?: return
            val trigger = bubbleVisualView
            val showX = open ?: circleMenuOpen
            val target = if (showX) R.drawable.ic_close_x else R.drawable.ic_menu_burger
            if (iv.tag == target && iv.visibility == View.VISIBLE && iv.alpha == 1f) return
            circleGlyphGen++
            val gen = circleGlyphGen
            try {
                iv.animate().cancel()
            } catch (_: Exception) {
            }
            try {
                iv.clearAnimation()
            } catch (_: Exception) {
            }
            iv.visibility = View.VISIBLE
            iv.alpha = 1f
            progressBar?.visibility = View.GONE
            iv.setImageResource(target)
            iv.setColorFilter(Color.WHITE)
            iv.tag = target
            val blurOn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            if (!blurOn) return
            try {
                trigger?.setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(10f, 10f, android.graphics.Shader.TileMode.CLAMP)
                )
            } catch (_: Exception) {
                return
            }
            iv.alpha = 0f
            try {
                iv.animate().alpha(1f).setDuration(200).start()
            } catch (_: Exception) {
                iv.alpha = 1f
            }
            pollHandler.postDelayed({
                if (gen != circleGlyphGen) return@postDelayed
                try {
                    trigger?.setRenderEffect(null)
                } catch (_: Exception) {
                }
            }, 220)
        } catch (_: Exception) {
        }
    }

    // Exact HTML MenuTrigger.closeAnimation: shake loop (translateX
    // [0,2,-2,0,2,-2,0]) repeating through the whole close while the trigger
    // grows 1.0 -> 1.15 -> 1.3 (capped 1.5), then snaps back in 100ms.
    // No color wash: the trigger stays #27272A throughout.
    private fun playMenuClosePulse() {
        val v = bubbleVisualView ?: bubbleView ?: return
        try {
            val dp = resources.displayMetrics.density
            // One 70ms pass, looped ~6x to cover the 420ms item close.
            val shake = android.animation.ObjectAnimator.ofFloat(
                v, "translationX", 0f, 2 * dp, -2 * dp, 0f, 2 * dp, -2 * dp, 0f
            ).setDuration(70)
            try {
                shake.repeatCount = 5
                shake.start()
            } catch (_: Exception) {
            }
            val steps = listOf(1f, 1.15f, 1.3f)
            fun step(i: Int) {
                if (i >= steps.size) {
                    try {
                        shake.cancel()
                    } catch (_: Exception) {
                    }
                    try {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100)
                            .setInterpolator(android.view.animation.OvershootInterpolator(2f))
                            .withEndAction {
                                try {
                                    v.translationX = 0f
                                } catch (_: Exception) {
                                }
                            }.start()
                    } catch (_: Exception) {
                    }
                    return
                }
                val s = steps[i].coerceAtMost(1.5f)
                try {
                    v.animate().scaleX(s).scaleY(s).setDuration(35).withEndAction {
                        v.postDelayed({ step(i + 1) }, 70)
                    }.start()
                } catch (_: Exception) {
                    v.postDelayed({ step(i + 1) }, 70)
                }
            }
            step(0)
        } catch (_: Exception) {
        }
    }

    private fun openCountryMenu() {
        if (menuOverlay == null) {
            menuOverlay = BubbleMenuOverlay(
                this,
                onCountrySelected = { code -> onBubbleCountrySelected(code) },
                onExitRequested = { stopFloatingBubble() },
                onDismissed = { longPressFired = false }
            )
        }
        val x = params?.x ?: 0
        val y = params?.y ?: 0
        val connectedCountry = try { vpnService?.countryCode } catch (_: Exception) { null }
        menuOverlay?.show(
            x + bubbleWindowSizePx / 2,
            y + bubbleWindowSizePx / 2,
            bubbleSizePx,
            if (state == BubbleState.CONNECTED) connectedCountry else null,
            supportsCountrySwitch()
        )
    }

    private fun supportsCountrySwitch(): Boolean {
        return try {
            val p = ProfileManager.getInstance(this).getDefault()
            ProxyProviders.detectType(p.getServer(), p.getUsername()) != ProxyProviders.TYPE_CUSTOM
        } catch (_: Exception) {
            false
        }
    }

    private fun copyText(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("KiloApp", text))
        } catch (e: Exception) {
            Log.w(TAG, "clipboard copy failed", e)
        }
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "toast failed", e)
        }
    }

    private fun copyRandomName() {
        try {
            val name = NamesRepo.random(this)
            copyText(name)
            bubbleView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            toast("Copied $name")
        } catch (e: Exception) {
            Log.e(TAG, "Name copy failed", e)
        }
        circleMenu?.hide()
    }

    // SMS tap contract from the HTML mockup: single tap provisions a new
    // number (or does nothing while one waits), double-tap always
    // regenerates, popup Gen always generates from its range and appends.
    private fun provisionSmsNumber(range: String? = null, forceNew: Boolean = false) {
        try {
            SmsWatcher.start(this)
            if (range == null && !forceNew && SmsWatcher.hasWaiting()) {
                toast("Waiting for SMS... double-tap for a new number")
                return
            }
            val pat = range?.filter { it.isDigit() }?.ifEmpty { null }
                ?: SmsWatcher.countries.firstOrNull()?.prefix?.ifEmpty { null } ?: "228"
            SmsWatcher.provision(pat) { n ->
                if (n == null) {
                    toast("No numbers available, try again")
                } else {
                    copyText(n.display)
                    toast("Number copied: ${n.display}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS provision from bubble failed", e)
            toast("SMS unavailable right now")
        }
    }

    // SMS long-press: same proxy-style panel, anchored at the bubble —
    // range bar plus appending number rows.
    private fun openSmsPopup() {
        try {
            longPressFired = true
            bubbleView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val x = params?.x ?: 0
            val y = params?.y ?: 0
            smsOverlay?.show(
                x + bubbleWindowSizePx / 2,
                y + bubbleWindowSizePx / 2,
                bubbleSizePx
            )
        } catch (e: Exception) {
            Log.e(TAG, "open SMS popup failed", e)
        }
    }

    // Popup number row: always copies the number, waiting or arrived.
    private fun copySmsEntry(display: String) {
        try {
            copyText(display)
            toast("Number copied: $display")
            bubbleView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } catch (e: Exception) {
            Log.e(TAG, "SMS popup copy failed", e)
        }
    }

    private fun onBubbleCountrySelected(code: String) {
        try {
            val profile = ProfileManager.getInstance(this).getDefault()
            val username = profile.getUsername()
            val newUsername = ProxyProviders.switchCountry(profile.getServer(), username, code) ?: return
            profile.setUsername(newUsername)
            Utility.addRecentCountry(this, code)
            bubbleView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            Log.d(TAG, "Bubble menu: country switched to $code")
            menuOverlay?.hide()
            // Auto-restart VPN to apply the new zone.
            when (state) {
                BubbleState.CONNECTED -> {
                    stopVpn()
                    pollHandler.postDelayed({ startVpn() }, 500)
                }
                BubbleState.DISCONNECTED -> startVpn()
                BubbleState.CONNECTING -> { /* let current connect finish; user can re-tap */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bubble menu country select failed", e)
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "Bubble stop requested")
        pendingProfile = null
        try {
            vpnService?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN", e)
        }
        // Ordered stop intent: covers cancel-while-connecting when the binder
        // has not seen the queued start yet (binder stop is then a no-op).
        try {
            val stopIntent = Intent(this, SocksVpnService::class.java).setAction(ACTION_STOP_VPN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(stopIntent)
            } else {
                startService(stopIntent)
            }
        } catch (_: Exception) {
        }
        setState(BubbleState.DISCONNECTED)
    }

    private fun stopFloatingBubble() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean(Constants.PREF_FLOATING_CONTROL, false)
            .apply()
        stopSelf()
    }

    private fun isSplitIncludeEmpty(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return SplitTunnel.isIncludeEmpty(prefs, packageName)
    }

    private fun openSplitAppsPage() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_SPLIT_APPS, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open split-tunneling apps page", e)
        }
    }

    private fun startVpn() {
        val manager = ProfileManager.getInstance(this)
        // getProfiles() always contains a leading "Default" placeholder even when
        // the user has not configured any real proxy. Connecting to that placeholder
        // points at 127.0.0.1:1080, which "connects" the tunnel then fails ~8s later
        // with a proxy error. If only the placeholder exists there is nothing to
        // connect to - tell the user instead of starting a doomed VPN.
        if (manager.getProfiles().size <= 1) {
            Log.w(TAG, "Bubble tap ignored: no proxy profiles configured")
            Toast.makeText(
                this,
                getString(R.string.bubble_no_proxy),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        // Split tunneling with zero effective apps can never connect (empty
        // allow-list drags our own UID into the tunnel): refuse and open the
        // apps list.
        if (isSplitIncludeEmpty()) {
            Log.w(TAG, "Bubble tap ignored: split tunneling with no apps")
            Toast.makeText(
                this,
                "Select an app",
                Toast.LENGTH_LONG
            ).show()
            openSplitAppsPage()
            return
        }
        val defaultProfile = ProfileManager.getInstance(this).getDefault().getName()
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            doStart(defaultProfile)
        } else {
            pendingProfile = defaultProfile
            setState(BubbleState.CONNECTING)
            prepare.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(prepare)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch VPN permission", e)
                setState(BubbleState.DISCONNECTED)
            }
        }
    }

    private fun doStart(name: String) {
        pendingProfile = null
        setState(BubbleState.CONNECTING)
        try {
            val profile = ProfileManager.getInstance(this).getProfile(name)
            if (profile == null) {
                Log.e(TAG, "doStart: profile not found: $name")
                setState(BubbleState.DISCONNECTED)
                return
            }
            Utility.startVpn(this, profile)
            ProfileManager.getInstance(this).switchDefault(name)
            Log.d(TAG, "doStart succeeded for: $name")
        } catch (e: Exception) {
            Log.e(TAG, "doStart failed", e)
            setState(BubbleState.DISCONNECTED)
        }
    }

    private fun startConnectTimeout() {
        connectTimeoutRunnable?.let { connectTimeoutHandler?.removeCallbacks(it) }
        connectTimeoutRunnable = Runnable {
            if (state == BubbleState.CONNECTING) {
                Log.w(TAG, "Connection timeout — forcing DISCONNECTED")
                setState(BubbleState.DISCONNECTED)
            }
        }
        connectTimeoutHandler?.postDelayed(connectTimeoutRunnable!!, CONNECT_TIMEOUT_MS)
    }

    private fun pollState() {
        if (pendingProfile != null && VpnService.prepare(this) == null) {
            val name = pendingProfile ?: return
            pendingProfile = null
            doStart(name)
            return
        }
        if (!bound || vpnService == null) {
            // Binder lost (e.g. :vpn process killed while UI process survived).
            // Do not silently freeze in the current state — schedule a re-bind
            // so pollState can resume once the fresh connection lands.
            if (rebindAttempts == 0) {
                scheduleRebind()
            }
            return
        }
        try {
            val snapshot = vpnService!!.state
            val running = snapshot.getBoolean(Constants.VPN_STATE_RUNNING)
            val connected = snapshot.getBoolean(Constants.VPN_STATE_CONNECTED)
            // Only claim CONNECTED once the SOCKS proxy has actually been verified
            // (IP check routed through it succeeded, or a direct probe handshake
            // completed). Tunnel-up alone is a false positive — a dead or misconfigured
            // proxy brings the tun up then fails seconds later.
            if (connected) {
                setState(BubbleState.CONNECTED)
            } else if (running) {
                if (state == BubbleState.DISCONNECTED) {
                    setState(BubbleState.CONNECTING)
                }
            } else {
                setState(BubbleState.DISCONNECTED)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed", e)
            // A stale/dead binder surfaces as an exception here even if the
            // system has not yet delivered onServiceDisconnected. Treat it as
            // a lost bind: drop the reference and schedule a re-bind so the
            // spinner is never stranded on a CONNECTING state.
            vpnService = null
            bound = false
            if (state == BubbleState.CONNECTED) {
                setState(BubbleState.DISCONNECTED)
            }
            scheduleRebind()
        }
    }

    private fun setState(newState: BubbleState) {
        if (state == newState) return
        val oldState = state
        state = newState
        if (oldState == BubbleState.CONNECTING) {
            connectTimeoutRunnable?.let { connectTimeoutHandler?.removeCallbacks(it) }
        }
        // Arm the 20s escape hatch on ANY transition into CONNECTING — not just
        // bubble-initiated connects — so a state promoted by pollState can never
        // get stranded on the spinner with no way out.
        if (newState == BubbleState.CONNECTING) {
            startConnectTimeout()
        }
        updateBubbleUi(oldState)
        updateForegroundNotification()
    }

    private fun updateBubbleUi(oldState: BubbleState) {
        val view = bubbleView ?: return
        val circle = bubbleVisualView ?: view
        // Circle trigger stays dark #27272A like the HTML .cm-trigger in every
        // VPN state — never run the gradient crossfade on it.
        if (!isCircleStyle()) {
            animateGradientTransition(oldState, state)
        }
        stopBreathing()

        if (isLockStyle()) {
            when (state) {
                BubbleState.CONNECTING -> {
                    iconView?.apply { visibility = View.GONE }
                    progressBar?.visibility = View.VISIBLE
                    progressBar?.indeterminateDrawable?.mutate()?.setTint(lockSpin())
                    timerView?.visibility = View.GONE
                    stopTimer()
                    stopLockSequence()
                }
                BubbleState.CONNECTED -> {
                    progressBar?.visibility = View.GONE
                    iconView?.visibility = View.VISIBLE
                    iconView?.setImageResource(R.drawable.ic_proton_lock_filled)
                    iconView?.setColorFilter(lockGreen())
                    if (getConnectedSince() > 0L) {
                        timerView?.visibility = View.VISIBLE
                        startLockSequence()
                    } else {
                        timerView?.visibility = View.GONE
                        stopLockSequence()
                    }
                    if (oldState == BubbleState.CONNECTING) playConnectPop(circle)
                }
                BubbleState.DISCONNECTED -> {
                    iconView?.visibility = View.VISIBLE
                    iconView?.setImageResource(R.drawable.ic_proton_lock_open_filled_2)
                    iconView?.setColorFilter(lockErr())
                    progressBar?.visibility = View.GONE
                    timerView?.visibility = View.GONE
                    stopTimer()
                    stopLockSequence()
                }
            }
            updateFlagPill()
            updateStatusLabel()
            return
        }

        when (state) {
            BubbleState.CONNECTING -> {
                if (isCircleStyle()) {
                    // Circle trigger keeps its Menu/X glyph while connecting —
                    // VPN state lives on the Proxy item's lock, like the HTML
                    // mockup. Never hide the glyph for the spinner here: that
                    // stomped the X while the menu was open.
                    setCircleGlyph()
                    timerView?.visibility = View.GONE
                    stopTimer()
                } else {
                    iconView?.visibility = View.GONE
                    progressBar?.visibility = View.VISIBLE
                    progressBar?.indeterminateDrawable?.mutate()?.setTint(Color.WHITE)
                    timerView?.visibility = View.GONE
                    stopTimer()
                }
                startBreathing(circle)
            }
            BubbleState.CONNECTED -> {
                progressBar?.visibility = View.GONE
                if (isCircleStyle()) {
                    setCircleGlyph()
                    timerView?.visibility = View.GONE
                    stopTimer()
                } else if (getConnectedSince() > 0L) {
                    iconView?.visibility = View.GONE
                    timerView?.visibility = View.VISIBLE
                    updateTimerText()
                    startTimer()
                } else {
                    iconView?.visibility = View.VISIBLE
                    iconView?.setImageResource(R.drawable.ic_bubble_stop)
                    iconView?.setColorFilter(Color.WHITE)
                    timerView?.visibility = View.GONE
                    stopTimer()
                }
                if (oldState == BubbleState.CONNECTING) {
                    playConnectPop(circle)
                }
            }
            BubbleState.DISCONNECTED -> {
                iconView?.visibility = View.VISIBLE
                progressBar?.visibility = View.GONE
                if (isCircleStyle()) {
                    setCircleGlyph()
                } else {
                    iconView?.setImageResource(R.drawable.ic_bubble_play)
                }
                iconView?.setColorFilter(Color.WHITE)
                timerView?.visibility = View.GONE
                stopTimer()
            }
        }
        updateFlagPill()
    }

    private fun playConnectPop(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate()
            .scaleX(1.18f).scaleY(1.18f)
            .setDuration(140)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(OvershootInterpolator(3f))
                    .start()
            }
            .start()
    }

    private fun startBreathing(view: View) {
        val animator = ValueAnimator.ofFloat(1f, 1.07f).apply {
            duration = 650
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
            }
        }
        breatheAnimator = animator
        animator.start()
    }

    private fun stopBreathing() {
        breatheAnimator?.cancel()
        breatheAnimator = null
        val circle = bubbleVisualView ?: return
        circle.scaleX = 1f
        circle.scaleY = 1f
    }

    private fun animateGradientTransition(oldState: BubbleState, newState: BubbleState) {
        if (isCircleStyle()) return
        if (bubbleVisualView == null && bubbleView == null) return
        val (oldStart, oldEnd) = stateGradient(oldState)
        val (newStart, newEnd) = stateGradient(newState)

        colorAnimator?.cancel()
        val evaluator = ArgbEvaluator()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val start = evaluator.evaluate(fraction, oldStart, newStart) as Int
                val end = evaluator.evaluate(fraction, oldEnd, newEnd) as Int
                applyGradientColors(intArrayOf(start, end))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    applyGradientColors(intArrayOf(newStart, newEnd))
                }
            })
        }
        colorAnimator = animator
        animator.start()
    }

    /**
     * Rebuilds the bubble's gradient background with the given colors.
     *
     * [GradientDrawable.setColors] (the mutable in-place setter) only exists on
     * API 24+, but this app supports minSdk 21, so a fresh [GradientDrawable] is
     * constructed each time instead — cheap enough for a ~260ms crossfade.
     */
    private fun applyGradientColors(colors: IntArray) {
        if (isCircleStyle()) return
        val view = bubbleVisualView ?: bubbleView ?: return
        val drawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        drawable.shape = GradientDrawable.OVAL
        view.background = drawable
    }

    private fun getConnectedSince(): Long {
        return try {
            vpnService?.connectedSince ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun isProxyVerified(): Boolean {
        return try {
            vpnService?.isProxyVerified ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun formatElapsed(elapsedMillis: Long): String {
        val totalSeconds = elapsedMillis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun updateTimerText() {
        if (isLockStyle() && lockFlashing) return
        val view = timerView ?: return
        val connectedSince = getConnectedSince()
        val elapsed = if (connectedSince > 0L) {
            (java.lang.System.currentTimeMillis() - connectedSince).coerceAtLeast(0L)
        } else {
            0L
        }
        if (isLockStyle()) {
            view.setTextColor(Color.BLACK)
            view.textSize = 11f
            view.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            view.letterSpacing = 0.02f
        }
        view.text = formatElapsed(elapsed)
    }

    private fun startTimer() {
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)
    }

    private fun stopTimer() {
        timerHandler.removeCallbacks(timerRunnable)
    }

    // Lock-style Protected -> Flag+Digits -> Timer -> every 5s Flag+Code/Flag+Digits alternate 1.1s
    private fun startLockSequence() {
        stopLockSequence()
        stopTimer()
        val tv = timerView ?: return
        val green = lockGreen()
        if (lockCountryCode().isEmpty()) {
            // No verified geo: never show a placeholder flag. Timer only.
            updateTimerText()
            tv.alpha = 1f
            startTimer()
            return
        }
        // Protected 1.5s - large green
        tv.alpha = 1f
        tv.setTextColor(green)
        tv.textSize = 12f
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.letterSpacing = 0.01f
        tv.text = "Protected"
        // Tick timer string separately via timerRunnable once sequence completes
        lockProtRunnable = Runnable {
            if (state != BubbleState.CONNECTED) return@Runnable
            // fade to Flag+Digits
            tv.animate().alpha(0f).setDuration(200).withEndAction {
                if (state != BubbleState.CONNECTED) return@withEndAction
                val (flag, digits) = lockFlagDigits()
                if (flag.isEmpty()) {
                    // Geo vanished mid-sequence (stale bind): fall back to
                    // the timer instead of a blank row.
                    updateTimerText()
                    tv.alpha = 0f
                    tv.animate().alpha(1f).setDuration(200).start()
                    startTimer()
                    return@withEndAction
                }
                tv.setTextColor(Color.BLACK)
                tv.textSize = 11f
                tv.letterSpacing = 0.02f
                tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                tv.text = flag + " " + digits
                tv.alpha = 0f
                tv.animate().alpha(1f).setDuration(200).start()
                lockFirstFlagRunnable = Runnable {
                    if (state != BubbleState.CONNECTED) return@Runnable
                    tv.animate().alpha(0f).setDuration(200).withEndAction {
                        if (state != BubbleState.CONNECTED) return@withEndAction
                        // show 00:12 counting
                        tv.setTextColor(Color.BLACK)
                        tv.textSize = 11f
                        updateTimerText()
                        tv.alpha = 0f
                        tv.animate().alpha(1f).setDuration(200).start()
                        startTimer()
                        // start 5s cycle after 5s
                        lockCycleAlt = 0
                        lockCycleRunnable = object : Runnable {
                            override fun run() {
                                if (state != BubbleState.CONNECTED || lockFlashing) {
                                    lockHandler.postDelayed(this, EVERY_MS)
                                    return
                                }
                                val elapsedSec = (if (getConnectedSince() > 0) (java.lang.System.currentTimeMillis() - getConnectedSince()) / 1000 else 0)
                                if (elapsedSec % 60L == 0L) {
                                    // guard tick
                                    lockHandler.postDelayed({ if (state == BubbleState.CONNECTED && !lockFlashing) doLockFlash() }, 300)
                                } else {
                                    doLockFlash()
                                }
                                lockHandler.postDelayed(this, EVERY_MS)
                            }
                        }
                        lockHandler.postDelayed(lockCycleRunnable!!, EVERY_MS)
                    }.start()
                }
                lockHandler.postDelayed(lockFirstFlagRunnable!!, FIRST_FLAG_HOLD_MS)
            }.start()
        }
        lockHandler.postDelayed(lockProtRunnable!!, PROT_MS)
    }

    private fun stopLockSequence() {
        lockProtRunnable?.let { lockHandler.removeCallbacks(it); lockProtRunnable = null }
        lockFirstFlagRunnable?.let { lockHandler.removeCallbacks(it); lockFirstFlagRunnable = null }
        lockCycleRunnable?.let { lockHandler.removeCallbacks(it); lockCycleRunnable = null }
        lockFlashHideRunnable?.let { lockHandler.removeCallbacks(it); lockFlashHideRunnable = null }
        lockFlashing = false
    }

    private fun doLockFlash() {
        val tv = timerView ?: return
        if (state != BubbleState.CONNECTED) return
        if (lockCountryCode().isEmpty()) return
        lockFlashing = true
        val isCode = lockCycleAlt == 0
        lockCycleAlt = 1 - lockCycleAlt
        val w = tv.width
        if (w > 0) tv.minWidth = w
        tv.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            if (state != BubbleState.CONNECTED) { lockFlashing = false; return@withEndAction }
            val (flag, digits) = lockFlagDigits()
            val code = lockCountryCode()
            if (code.isEmpty() || flag.isEmpty()) return@withEndAction
            tv.setTextColor(Color.BLACK)
            if (isCode) {
                tv.textSize = 12f
                tv.text = flag + " " + code
            } else {
                tv.textSize = 11f
                tv.text = flag + " " + digits
            }
            tv.alpha = 0f
            tv.animate().alpha(1f).setDuration(FADE_MS).start()
            lockFlashHideRunnable = Runnable {
                tv.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                    if (state != BubbleState.CONNECTED) { lockFlashing = false; tv.minWidth = 0; return@withEndAction }
                    lockFlashing = false
                    tv.minWidth = 0
                    tv.setTextColor(Color.BLACK)
                    tv.textSize = 11f
                    updateTimerText()
                    tv.alpha = 0f
                    tv.animate().alpha(1f).setDuration(FADE_MS).start()
                }.start()
            }
            lockHandler.postDelayed(lockFlashHideRunnable!!, CYCLE_HOLD_MS)
        }.start()
    }

    private fun lockFlagDigits(): Pair<String, String> {
        val code = try { vpnService?.countryCode ?: "" } catch (_: Exception) { "" }
        // No verified geo: empty, never a placeholder. Callers skip the
        // flag phases when this is empty so the bubble cannot show a stale
        // country as connected.
        if (code.isEmpty()) return Pair("", "")
        val flag = Utility.countryCodeToFlag(code)
        val ip = try { vpnService?.currentIp ?: "" } catch (_: Exception) { "" }
        val lastOctet = when {
            ip.contains('.') -> ip.substringAfterLast('.')
            ip.contains(':') -> ip.substringAfterLast(':').takeLast(4)
            else -> ""
        }.ifEmpty { "" }
        return Pair(flag, lastOctet)
    }

    private fun lockCountryCode(): String {
        return try { vpnService?.countryCode ?: "" } catch (_: Exception) { "" }
    }

    // lock timing constants are in companion below

    /** Solid fill color per bubble state (start == end, so the gradient renders flat). */
    private fun stateGradient(state: BubbleState): Pair<Int, Int> {
        if (isLockStyle()) {
            val t = Color.TRANSPARENT
            return Pair(t, t)
        }
        return when (state) {
            BubbleState.CONNECTING -> Pair(
                0xFF000000.toInt(),
                0xFF000000.toInt()
            )
            BubbleState.CONNECTED -> Pair(
                0xFFDC2626.toInt(),
                0xFFDC2626.toInt()
            )
            BubbleState.DISCONNECTED -> Pair(
                0xFF000000.toInt(),
                0xFF000000.toInt()
            )
        }
    }

    companion object {
        private const val TAG = "FloatingControlService"
        private const val POLL_INTERVAL = 1000L
        private const val CONNECTING_POLL_INTERVAL = 200L
        private const val TIMER_INTERVAL = 1000L
        private const val CHANNEL_ID = "floating_control"
        private const val NOTIFICATION_ID = 2
        // Matches the 20s "not yet connected" timeout in StatusScreen's
        // LaunchedEffect(isConnecting) so both surfaces abandon a stuck
        // connect together.
        private const val PROT_MS = 1500L
        private const val FIRST_FLAG_HOLD_MS = 2300L
        private const val CYCLE_HOLD_MS = 900L
        private const val FADE_MS = 200L
        private const val EVERY_MS = 5000L
        private const val CONNECT_TIMEOUT_MS = 20_000L

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, FloatingControlService::class.java))
            } else {
                context.startService(Intent(context, FloatingControlService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingControlService::class.java))
        }
    }
}
