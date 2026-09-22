package net.typeblog.socks.ui.screens.sheet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.typeblog.socks.R

/**
 * Multi-select header (Vivo style, website palette): gold-free single row
 * [Select all | N selected | Cancel]. Replaces the old two-row header plus
 * the top text action buttons — actions live in [SelectBottomBar].
 */
@Composable
fun SelectHeader(
    count: Int,
    total: Int,
    onToggleAll: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (count >= total && total > 0) "Unselect all" else "Select all",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleAll
                )
                .padding(6.dp)
        )
        Text(
            text = "$count selected",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(
            text = "Cancel",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel
                )
                .padding(6.dp)
        )
    }
}

data class SelectAction(
    val icon: Int,
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Floating bottom action bar: white card, icon-above-label actions.
 * Every action must ask for confirmation (bulk dialogs) before running.
 */
@Composable
fun SelectBottomBar(
    actions: List<SelectAction>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .shadow(8.dp, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { a ->
                val tint = if (a.danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
                Column(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = a.onClick
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Image(
                        painter = painterResource(a.icon),
                        contentDescription = a.label,
                        modifier = Modifier.size(22.dp),
                        colorFilter = ColorFilter.tint(tint)
                    )
                    Text(
                        text = a.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = tint
                    )
                }
            }
        }
    }
}

/** Checkbox square shown top-end of a card while selecting. */
@Composable
fun SelectCheckBox(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.onSurface
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .border(
                1.5.dp,
                if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outlineVariant,
                androidx.compose.foundation.shape.RoundedCornerShape(5.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Image(
                painter = painterResource(R.drawable.ic_ss_check),
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.surface)
            )
        }
    }
}
