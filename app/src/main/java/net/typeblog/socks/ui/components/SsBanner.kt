package net.typeblog.socks.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

// Shared banner following the Astryx Banner anatomy
// (astryx.atmeta.com/components/Banner): card container, status-tinted
// surface, auto status icon, short title, optional description, end-aligned
// action, optional dismiss. The title always carries the meaning; the icon
// is decorative.
enum class SsBannerStatus { INFO, SUCCESS, WARNING, ERROR }

private val SsAmber = Color(0xFFD97706)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SsBanner(
    status: SsBannerStatus,
    title: String,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dismissable: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val statusColor = when (status) {
        SsBannerStatus.INFO -> scheme.onSurface
        SsBannerStatus.SUCCESS -> scheme.tertiary
        SsBannerStatus.WARNING -> SsAmber
        SsBannerStatus.ERROR -> scheme.error
    }
    val fill = when (status) {
        SsBannerStatus.INFO -> scheme.primaryContainer
        SsBannerStatus.SUCCESS -> scheme.tertiary.copy(alpha = 0.12f)
        SsBannerStatus.WARNING -> SsAmber.copy(alpha = 0.14f)
        SsBannerStatus.ERROR -> scheme.error.copy(alpha = 0.10f)
    }
    val iconRes = when (status) {
        SsBannerStatus.INFO -> R.drawable.ic_ss_banner_info
        SsBannerStatus.SUCCESS -> R.drawable.ic_ss_banner_success
        SsBannerStatus.WARNING -> R.drawable.ic_ss_banner_warning
        SsBannerStatus.ERROR -> R.drawable.ic_ss_banner_error
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = statusColor
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface
            )
            if (description != null) {
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = actionLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .combinedClickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        if (dismissable) {
            IconButton(
                onClick = { onDismiss?.invoke() },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_x),
                    contentDescription = "Dismiss $title",
                    modifier = Modifier.size(14.dp),
                    tint = scheme.onSurfaceVariant
                )
            }
        }
    }
}
