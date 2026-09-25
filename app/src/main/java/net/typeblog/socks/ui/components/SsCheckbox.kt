package net.typeblog.socks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import net.typeblog.socks.R

// Shared checkbox following the Astryx CheckboxInput anatomy
// (astryx.atmeta.com/components/CheckboxInput): indicator with
// checked/disabled states and an optional screen-reader label, sm/md
// sizes. Box edge and fill use strong tones for 3:1 contrast against the
// surface.
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
