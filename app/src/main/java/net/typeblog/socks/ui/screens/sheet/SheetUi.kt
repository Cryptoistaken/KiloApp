package net.typeblog.socks.ui.screens.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.typeblog.socks.R
import net.typeblog.socks.util.sheet.SheetPreset
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image

// Shared bits for the Sheet port. Icons are 1:1 ports of the website SVGs
// (res/drawable/ic_ss_*.xml); status colors match the site tokens.

val AliveGreen = Color(0xFF229342)
val DeadRed = Color(0xFFE33B2E)
val DupYellow = Color(0xFFEAB308)
val PageBlue = Color(0xFF2563EB)
val CrossOrange = Color(0xFFF6821F)

@Composable
fun StatusDot(
    status: String,
    dead: Boolean,
    isDup: Boolean,
    modifier: Modifier = Modifier
) {
    val color = when {
        dead || status == "bad" -> DeadRed
        isDup -> DupYellow
        status == "eligible" -> PageBlue
        status == "good" || status == "done" -> AliveGreen
        status == "pending" -> DupYellow
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (color == Color.Transparent) MaterialTheme.colorScheme.outline else color),
        contentAlignment = Alignment.Center
    ) {}
}

@Composable
fun IndicatorSquare(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}

@Composable
fun PresetIcon(preset: SheetPreset, sizeDp: Int = 14, modifier: Modifier = Modifier) {
    val res = when (preset) {
        SheetPreset.COOKIE -> R.drawable.ic_ss_cookie
        SheetPreset.COMBO -> R.drawable.ic_ss_twofa
        SheetPreset.PAGE -> R.drawable.ic_ss_page
    }
    Image(
        painter = painterResource(res),
        contentDescription = null,
        modifier = modifier.size(sizeDp.dp)
    )
}

@Composable
fun PasswordBadge(password: String, modifier: Modifier = Modifier) {
    val res = when (password) {
        "dgddigital" -> R.drawable.ic_ss_pw_dgd
        "Love@12345", "L0VE@12345" -> R.drawable.ic_ss_pw_love
        else -> null
    }
    if (res != null) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = modifier.size(12.dp)
        )
    } else {
        Text(
            text = password.take(8),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
    }
}

@Composable
fun EmptySheetState(title: String, sub: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.ic_ss_doc2x),
            contentDescription = null,
            modifier = Modifier.size(48.dp)
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = sub,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun fmtDate(ts: Long): String {
    if (ts <= 0) return ""
    val f = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
    return f.format(java.util.Date(ts))
}

fun fmtUsd(v: Double): String {
    val r = Math.round(v * 100) / 100.0
    return if (r == 0.0) "0.00" else if (r == Math.floor(r)) "%,d".format(r.toLong()) else "%,.2f".format(r)
}

@Composable
fun rowsIndicatorColor(): Color {
    return if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFEDEDED) else Color(0xFF525252)
}

@Composable
fun FileIconTile(preset: SheetPreset, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        PresetIcon(preset = preset, sizeDp = 14)
    }
}

@Composable
fun TypeBadgePill(content: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun SheetMenuItem(
    icon: Int,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        },
        leadingIcon = {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                colorFilter = if (danger) androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.error)
                else androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
            )
        },
        onClick = onClick
    )
}

internal fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

internal fun sanitizeFileName(name: String): String {
    val s = name.trim().ifEmpty { "file" }
    return s.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80)
}
