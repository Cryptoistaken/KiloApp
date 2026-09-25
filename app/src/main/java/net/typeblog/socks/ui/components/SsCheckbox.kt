package net.typeblog.socks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.typeblog.socks.R

// Shared checkbox following the Astryx CheckboxInput anatomy
// (astryx.atmeta.com/components/CheckboxInput): indicator with
// checked/disabled states, always a label (screen-reader label when
// hidden), optional description below the label, sm/md sizes. Box edge
// and fill use strong tones for 3:1 contrast against the surface.
enum class SsCheckboxSize { SM, MD }

@Composable
fun SsCheckIndicator(
    checked: Boolean,
    size: SsCheckboxSize = SsCheckboxSize.MD,
    enabled: Boolean = true,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val box = if (size == SsCheckboxSize.MD) 20.dp else 16.dp
    val radius = if (size == SsCheckboxSize.MD) 5.dp else 4.dp
    val glyph = if (size == SsCheckboxSize.MD) 13.dp else 10.dp
    val shape = RoundedCornerShape(radius)
    val alpha = if (enabled) 1f else 0.4f
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(box)
            .clip(shape)
            .background(
                if (checked) scheme.onSurface.copy(alpha = alpha)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .border(
                1.5.dp,
                if (checked) scheme.onSurface.copy(alpha = alpha)
                else scheme.onSurfaceVariant.copy(alpha = alpha),
                shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_ss_check),
                contentDescription = contentDescription,
                modifier = Modifier.size(glyph),
                tint = scheme.surface
            )
        }
    }
}

@Composable
fun SsCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    description: String? = null,
    size: SsCheckboxSize = SsCheckboxSize.MD,
    enabled: Boolean = true,
    hideLabel: Boolean = false,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SsCheckIndicator(
            checked = checked,
            size = size,
            enabled = enabled,
            contentDescription = if (hideLabel) label else null
        )
        if (!hideLabel) {
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    color = if (enabled) scheme.onSurface
                    else scheme.onSurfaceVariant
                )
                if (description != null) {
                    Text(
                        text = description,
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
