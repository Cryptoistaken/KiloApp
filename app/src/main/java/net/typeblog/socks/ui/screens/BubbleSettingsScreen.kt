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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
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
                icon = painterResource(R.drawable.ic_proton_circle_half_filled),
                title = "Circle"
            )

            // Circle-menu alignment: gated on the Circle bubble. Each option
            // carries a live mini preview of that exact arrangement.
            Text(
                "Menu alignment",
                style = MaterialTheme.typography.titleSmall,
                color = if (circleActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
            val alignOptions = listOf(
                CIRCLE_SMALL to "Small circle",
                CIRCLE_UP to "Up",
                CIRCLE_DOWN to "Down",
                CIRCLE_RIGHT to "Right",
                CIRCLE_LEFT to "Left"
            )
            alignOptions.forEach { (value, label) ->
                val selected = circleAlign == value
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = circleActive) {
                        prefs.edit().putString(PREF_CIRCLE_ALIGN, value).apply()
                        circleAlign = value
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected && circleActive) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = if (selected && circleActive) 2.dp else 0.dp
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selected,
                            onClick = if (circleActive) ({
                                prefs.edit().putString(PREF_CIRCLE_ALIGN, value).apply()
                                circleAlign = value
                            }) else null,
                            enabled = circleActive
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (circleActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.weight(1f)
                        )
                        CircleAlignPreview(align = value, buttonDp = 15, boxW = 116, boxH = 76, dimmed = !circleActive)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Button size slider + full-size live preview of the selection.
            Text(
                "Button size (${circleSize}dp)",
                style = MaterialTheme.typography.titleSmall,
                color = if (circleActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
            Slider(
                value = circleSize.toFloat(),
                onValueChange = {
                    circleSize = it.roundToInt()
                    prefs.edit().putInt(PREF_CIRCLE_SIZE, it.roundToInt()).apply()
                },
                valueRange = CIRCLE_SIZE_MIN.toFloat()..CIRCLE_SIZE_MAX.toFloat(),
                steps = (CIRCLE_SIZE_MAX - CIRCLE_SIZE_MIN) - 1,
                enabled = circleActive,
                modifier = Modifier.fillMaxWidth()
            )
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    CircleAlignPreview(
                        align = circleAlign,
                        buttonDp = (circleSize * 0.45f).roundToInt().coerceAtLeast(16),
                        boxW = 220,
                        boxH = 150,
                        dimmed = !circleActive
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SelectableStyleRow(    selected: Boolean,
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
 * Live diagram of the floating circle menu: center bubble + the 4 option
 * bubbles (Proxy/SMS/Sheet/Name) in the exact arrangement [align] produces
 * on screen. Same math as [net.typeblog.socks.CircleBubbleMenu], scaled to
 * the preview box. [dimmed] fades it when the Circle style isn't selected.
 */
@Composable
private fun CircleAlignPreview(
    align: String,
    buttonDp: Int,
    boxW: Int,
    boxH: Int,
    dimmed: Boolean
) {
    val gap = buttonDp + 12
    val off = buttonDp + 14
    val r = buttonDp * 2 + 24
    val pts: List<Pair<Int, Int>> = when (align) {
        CIRCLE_UP -> List(4) { 0 to -(off + it * gap) }
        CIRCLE_DOWN -> List(4) { 0 to (off + it * gap) }
        CIRCLE_RIGHT -> List(4) { (off + it * gap) to 0 }
        CIRCLE_LEFT -> List(4) { (-(off + it * gap)) to 0 }
        else -> listOf(0 to -r, r to 0, 0 to r, -r to 0)
    }
    val half = buttonDp / 2
    val minX = minOf(-half, pts.minOf { it.first - half })
    val maxX = maxOf(half, pts.maxOf { it.first + half })
    val minY = minOf(-half, pts.minOf { it.second - half })
    val maxY = maxOf(half, pts.maxOf { it.second + half })
    // Center the content (anchor + items) inside the box.
    val cx = boxW / 2 - (minX + maxX) / 2
    val cy = boxH / 2 - (minY + maxY) / 2
    val alpha = if (dimmed) 0.35f else 1f
    val centerDp = (buttonDp * 1.2f).roundToInt().coerceAtLeast(18)
    val icons = listOf(
        R.drawable.ic_proton_lock_open_filled_2 to Color(0xFFCC2D4F),
        R.drawable.ic_tab_sms to Color(0xFF18181B),
        R.drawable.ic_tab_sheet to Color(0xFF18181B),
        R.drawable.ic_name_person to Color(0xFF18181B)
    )
    Box(modifier = Modifier.size(boxW.dp, boxH.dp)) {
        // Center bubble.
        Box(
            modifier = Modifier
                .offset(((cx - centerDp / 2)).dp, ((cy - centerDp / 2)).dp)
                .size(centerDp.dp)
                .clip(CircleShape)
                .background(Color(0xFF27272A).copy(alpha = alpha)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_proton_lock_open_filled_2),
                contentDescription = null,
                tint = Color(0xFFCC2D4F).copy(alpha = alpha),
                modifier = Modifier.size((centerDp * 0.45f).roundToInt().dp)
            )
        }
        // The 4 option bubbles.
        pts.forEachIndexed { i, (x, y) ->
            val (icon, tint) = icons[i]
            Box(
                modifier = Modifier
                    .offset((cx + x - half).dp, (cy + y - half).dp)
                    .size(buttonDp.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = alpha)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = tint.copy(alpha = alpha),
                    modifier = Modifier.size((buttonDp * 0.4f).roundToInt().coerceAtLeast(8).dp)
                )
            }
        }
    }
}
