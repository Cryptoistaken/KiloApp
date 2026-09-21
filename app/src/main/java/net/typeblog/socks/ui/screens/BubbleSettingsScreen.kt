package net.typeblog.socks.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import net.typeblog.socks.FloatingControlService
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.rememberPref
import net.typeblog.socks.util.Constants.BUBBLE_STYLE_CIRCLE
import net.typeblog.socks.util.Constants.BUBBLE_STYLE_CLASSIC
import net.typeblog.socks.util.Constants.BUBBLE_STYLE_LOCK
import net.typeblog.socks.util.Constants.CIRCLE_DOWN
import net.typeblog.socks.util.Constants.CIRCLE_LEFT
import net.typeblog.socks.util.Constants.CIRCLE_RIGHT
import net.typeblog.socks.util.Constants.CIRCLE_SIZE_DEFAULT
import net.typeblog.socks.util.Constants.CIRCLE_SIZE_MAX
import net.typeblog.socks.util.Constants.CIRCLE_SIZE_MIN
import net.typeblog.socks.util.Constants.CIRCLE_SMALL
import net.typeblog.socks.util.Constants.CIRCLE_UP
import net.typeblog.socks.util.Constants.PREF_BUBBLE_STYLE
import net.typeblog.socks.util.Constants.PREF_CIRCLE_ALIGN
import net.typeblog.socks.util.Constants.PREF_CIRCLE_SIZE
import net.typeblog.socks.util.Constants.PREF_FLOATING_CONTROL
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BubbleSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var floatingEnabled by rememberPref(prefs, PREF_FLOATING_CONTROL) {
        it.getBoolean(PREF_FLOATING_CONTROL, false)
    }
    var bubbleStyle by rememberPref(prefs, PREF_BUBBLE_STYLE) {
        it.getString(PREF_BUBBLE_STYLE, BUBBLE_STYLE_LOCK) ?: BUBBLE_STYLE_LOCK
    }
    var circleAlign by rememberPref(prefs, PREF_CIRCLE_ALIGN) {
        it.getString(PREF_CIRCLE_ALIGN, CIRCLE_SMALL) ?: CIRCLE_SMALL
    }
    var circleSize by rememberPref(prefs, PREF_CIRCLE_SIZE) {
        it.getInt(PREF_CIRCLE_SIZE, CIRCLE_SIZE_DEFAULT)
    }
    // Alignment + size only apply to the Circle bubble; otherwise they stay
    // visible-but-disabled so the layout never shifts under the user.
    val circleActive = bubbleStyle == BUBBLE_STYLE_CIRCLE

    fun canDrawOverlays(c: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c)
    fun startService(c: Context) {
        val i = Intent(c, FloatingControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) else c.startService(i)
    }

    var pendingStart by remember { mutableStateOf(false) }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (pendingStart && r.resultCode == android.app.Activity.RESULT_OK && canDrawOverlays(context)) {
            prefs.edit().putBoolean(PREF_FLOATING_CONTROL, true).putString(PREF_BUBBLE_STYLE, bubbleStyle).apply()
            floatingEnabled = true; startService(context)
        }
        pendingStart = false
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
        if (pendingStart && g && canDrawOverlays(context)) {
            val pi = VpnService.prepare(context)
            if (pi == null) { prefs.edit().putBoolean(PREF_FLOATING_CONTROL, true).apply(); floatingEnabled = true; startService(context) }
            else { pendingStart = true; vpnLauncher.launch(pi) }
        } else pendingStart = false
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!canDrawOverlays(context)) return@rememberLauncherForActivityResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true; notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val pi = VpnService.prepare(context)
            if (pi == null) { prefs.edit().putBoolean(PREF_FLOATING_CONTROL, true).apply(); floatingEnabled = true; startService(context) }
            else { pendingStart = true; vpnLauncher.launch(pi) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            if (prefs.getString(PREF_BUBBLE_STYLE, null) == null) prefs.edit().putString(PREF_BUBBLE_STYLE, BUBBLE_STYLE_LOCK).apply()
            if (canDrawOverlays(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingStart = true; notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val pi = VpnService.prepare(context)
                    if (pi == null) { prefs.edit().putBoolean(PREF_FLOATING_CONTROL, true).apply(); floatingEnabled = true; startService(context) }
                    else { pendingStart = true; vpnLauncher.launch(pi) }
                }
            } else {
                pendingStart = true
                overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
        } else {
            prefs.edit().putBoolean(PREF_FLOATING_CONTROL, false).apply()
            floatingEnabled = false
            context.stopService(Intent(context, FloatingControlService::class.java))
        }
    }

    fun setStyle(style: String) {
        prefs.edit().putString(PREF_BUBBLE_STYLE, style).apply()
        bubbleStyle = style
        if (floatingEnabled) { context.stopService(Intent(context, FloatingControlService::class.java)); startService(context) }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Floating Bubble") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                        Icon(painter = painterResource(R.drawable.lucide_arrow_left), contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface)
            )
        }
    ) { pv ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(pv)
                .padding(horizontal = 16.dp)
        ) {
            // Master switch
            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable floating bubble", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Switch(checked = floatingEnabled, onCheckedChange = { setEnabled(it) })
                }
            }

            Text("Bubble style", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            SelectableStyleRow(
                selected = bubbleStyle == BUBBLE_STYLE_LOCK,
                onClick = { setStyle(BUBBLE_STYLE_LOCK) },
                icon = painterResource(R.drawable.ic_proton_lock_filled),
                title = "Lock"
            )
            Spacer(Modifier.height(8.dp))
            SelectableStyleRow(
                selected = bubbleStyle == BUBBLE_STYLE_CLASSIC,
                onClick = { setStyle(BUBBLE_STYLE_CLASSIC) },
                icon = painterResource(R.drawable.ic_bubble_play),
                title = "Classic"
            )
            Spacer(Modifier.height(8.dp))
            SelectableStyleRow(
                selected = circleActive,
                onClick = { setStyle(BUBBLE_STYLE_CIRCLE) },
                icon = painterResource(R.drawable.ic_menu_burger),
                title = "Circle"
            )

            // Circle menu options: only shown while the Circle bubble is
            // selected. One live preview below animates between alignments
            // and sizes; the slider is the tick-strip from the HTML mockup.
            if (circleActive) {
                Text(
                    "Menu alignment",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
                val alignOptions = listOf(
                    CIRCLE_SMALL to "Circle",
                    CIRCLE_UP to "Up",
                    CIRCLE_DOWN to "Down",
                    CIRCLE_RIGHT to "Right",
                    CIRCLE_LEFT to "Left"
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    alignOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = circleAlign == value,
                            onClick = {
                                prefs.edit().putString(PREF_CIRCLE_ALIGN, value).apply()
                                circleAlign = value
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                Text(
                    "Button size",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TickSlider(
                        value = circleSize,
                        onChange = {
                            circleSize = it
                            prefs.edit().putInt(PREF_CIRCLE_SIZE, it).apply()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            circleSize = CIRCLE_SIZE_DEFAULT
                            circleAlign = CIRCLE_SMALL
                            prefs.edit()
                                .putInt(PREF_CIRCLE_SIZE, CIRCLE_SIZE_DEFAULT)
                                .putString(PREF_CIRCLE_ALIGN, CIRCLE_SMALL)
                                .apply()
                        }
                    ) {
                        Text("Reset", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircleAlignPreview(
                            align = circleAlign,
                            buttonDp = circleSize,
                            boxW = 320,
                            boxH = 300
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SelectableStyleRow(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.painter.Painter,
    title: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

/**
 * Tick-strip slider from the HTML mockup: tick bars light up to the value
 * and an invisible slider on top captures the scrub. No buttons.
 */
@Composable
private fun TickSlider(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val ticks = 25
    val frac = (value - CIRCLE_SIZE_MIN).toFloat() / (CIRCLE_SIZE_MAX - CIRCLE_SIZE_MIN)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${value}dp",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(52.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f).height(30.dp)) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
            ) {
                repeat(ticks) { k ->
                    val on = k.toFloat() / (ticks - 1) <= frac
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(if (on) 20.dp else 10.dp)
                            .clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                    )
                }
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = CIRCLE_SIZE_MIN.toFloat()..CIRCLE_SIZE_MAX.toFloat(),
                steps = (CIRCLE_SIZE_MAX - CIRCLE_SIZE_MIN) - 1,
                modifier = Modifier.fillMaxSize().alpha(0f)
            )
        }
    }
}

/**
 * Live diagram of the floating circle menu: center bubble + the 4 option
 * bubbles (Proxy/SMS/Sheet/Name) in the exact arrangement [align] produces
 * on screen. Same fixed math as circle-bubble.html + [net.typeblog.socks.CircleBubbleMenu]:
 * GAP 58dp / OFF 62dp for lines, 68.75dp radius for the small circle.
 * Trigger = buttonDp, items = buttonDp - 2, so the middle hamburger grows
 * together with the side bubbles when the slider moves.
 *
 * Visuals mirror the overlay exactly: dark trigger (#27272A + faint light
 * stroke) with the X glyph it shows while open, #F4F4F5 items, proxy lock
 * at 0.58 scale nudged 2dp left while disconnected.
 *
 * Alignment switches replay the overlay choreography: items collapse home
 * staggered (circle i*70ms, lines reverse (n-1-i)*70ms, layer spins -360
 * for circle only), slots swap while hidden, then items expand staggered
 * (circle i*20ms, lines i*60ms) on the same 300/0.866 springs.
 */
@Composable
private fun CircleAlignPreview(
    align: String,
    buttonDp: Int,
    boxW: Int,
    boxH: Int
) {
    fun isLine(a: String): Boolean =
        a == CIRCLE_UP || a == CIRCLE_DOWN || a == CIRCLE_RIGHT || a == CIRCLE_LEFT

    // Trigger = full slider size, items = size - 2, exactly like the overlay.
    val triggerDp = buttonDp.dp
    val itemDp = (buttonDp - 2).coerceAtLeast(16).dp
    val menuSpringDp = spring<Dp>(stiffness = 300f, dampingRatio = 0.866f)
    val menuSpringF = spring<Float>(stiffness = 300f, dampingRatio = 0.866f)
    val abtn by animateDpAsState(itemDp, menuSpringDp, label = "btn")
    val centerDp by animateDpAsState(triggerDp, menuSpringDp, label = "center")
    // Fixed spread from the HTML mockup: lines OFF 62 + GAP 58 per step,
    // small circle radius 68.75. Slider never moves the spread, only the
    // bubble diameters.
    val gap = 58.dp
    val off = 62.dp
    val r = 68.75.dp
    fun pt(i: Int): Pair<Dp, Dp> = when (align) {
        CIRCLE_UP -> 0.dp to -(off + gap * i)
        CIRCLE_DOWN -> 0.dp to (off + gap * i)
        CIRCLE_RIGHT -> (off + gap * i) to 0.dp
        CIRCLE_LEFT -> (-(off + gap * i)) to 0.dp
        else -> listOf(0.dp to -r, r to 0.dp, 0.dp to r, -r to 0.dp)[i]
    }
    val slots = List(4) { pt(it) }
    // Per-item collapse/expand progress + layer spin, driven by LaunchedEffect.
    val reveals = remember { List(4) { Animatable(0f) } }
    val spin = remember { Animatable(0f) }
    var ready by remember { mutableStateOf(false) }
    var openSlots by remember { mutableStateOf(slots) }
    var openAlign by remember { mutableStateOf(align) }
    LaunchedEffect(align) {
        if (!ready) {
            // First mount: staggered entry, like the overlay show().
            openSlots = slots
            openAlign = align
            val lineNow = isLine(align)
            openSlots.mapIndexed { i, _ ->
                launch {
                    delay(if (lineNow) i * 60L else i * 20L)
                    reveals[i].animateTo(1f, menuSpringF)
                }
            }.joinAll()
            ready = true
            return@LaunchedEffect
        }
        // 1. Collapse home from the OLD slots, staggered like hide().
        val closeLine = isLine(openAlign)
        val spinJob = if (!closeLine) {
            launch {
                spin.animateTo(-360f, tween(420))
                spin.snapTo(0f)
            }
        } else {
            null
        }
        openSlots.mapIndexed { i, _ ->
            launch {
                delay(if (closeLine) (3 - i) * 70L else i * 70L)
                reveals[i].animateTo(0f, menuSpringF)
            }
        }.joinAll()
        spinJob?.join()
        // 2. Swap slots while hidden, then expand staggered like show().
        openSlots = slots
        openAlign = align
        val lineNow = isLine(align)
        openSlots.mapIndexed { i, _ ->
            launch {
                delay(if (lineNow) i * 60L else i * 20L)
                reveals[i].animateTo(1f, menuSpringF)
            }
        }.joinAll()
    }
    val half = abtn / 2
    val centerHalf = centerDp / 2
    val minX = minOf(-centerHalf, openSlots.minOf { it.first - half })
    val maxX = maxOf(centerHalf, openSlots.maxOf { it.first + half })
    val minY = minOf(-centerHalf, openSlots.minOf { it.second - half })
    val maxY = maxOf(centerHalf, openSlots.maxOf { it.second + half })
    // Center the content (anchor + items) inside the box.
    val acx by animateDpAsState(boxW.dp / 2 - (minX + maxX) / 2, menuSpringDp, label = "cx")
    val acy by animateDpAsState(boxH.dp / 2 - (minY + maxY) / 2, menuSpringDp, label = "cy")
    val icons = listOf(
        R.drawable.ic_proton_lock_open_filled_2 to Color(0xFFCC2D4F),
        R.drawable.ic_tab_sms to Color(0xFF18181B),
        R.drawable.ic_tab_sheet to Color(0xFF18181B),
        R.drawable.ic_name_person to Color(0xFF18181B)
    )
    Box(modifier = Modifier.size(boxW.dp, boxH.dp)) {
        // Center bubble: dark orb + light stroke + X glyph, exactly what the
        // overlay trigger shows while its menu is open.
        Box(
            modifier = Modifier
                .offset(acx - centerDp / 2, acy - centerDp / 2)
                .size(centerDp)
                .clip(CircleShape)
                .background(Color(0xFF27272A))
                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close_x),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(centerDp * 0.43f)
            )
        }
        // The 4 option bubbles ride the collapse/expand progress; the layer
        // spins -360 on circle close, exactly like the overlay.
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                rotationZ = spin.value
            }
        ) {
            openSlots.forEachIndexed { i, (sx, sy) ->
                val rv = reveals[i].value
                val sc = 0.2f + 0.8f * rv
                val (icon, tint) = icons[i]
                Box(
                    modifier = Modifier
                        .offset(acx + sx * rv - half, acy + sy * rv - half)
                        .size(abtn)
                        .graphicsLayer {
                            scaleX = sc
                            scaleY = sc
                            alpha = rv
                        }
                        .clip(CircleShape)
                        .background(Color(0xFFF4F4F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier
                            .size(((if (i == 0) abtn * 0.58f else abtn * 0.4f)).coerceAtLeast(8.dp))
                            .then(if (i == 0) Modifier.offset(x = (-2).dp) else Modifier)
                    )
                }
            }
        }
    }
}