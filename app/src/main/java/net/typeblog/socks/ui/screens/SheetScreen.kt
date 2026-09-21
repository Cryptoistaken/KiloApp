package net.typeblog.socks.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.typeblog.socks.R
import net.typeblog.socks.ui.screens.sheet.SheetArchiveTab
import net.typeblog.socks.ui.screens.sheet.SheetDetailScreen
import net.typeblog.socks.ui.screens.sheet.SheetFilesTab
import net.typeblog.socks.ui.screens.sheet.SheetWalletTab

private enum class SheetTab { FILES, WALLET, ARCHIVE }

private data class OpenSheet(val id: String, val archived: Boolean)

/**
 * Sheet tab: My Files / Wallet / Archive for regular users (admins in the
 * app get the same user-only views). Matches the SheetSubmit website home
 * tabs; data lives in the local-first SQLite store.
 */
@Composable
fun SheetScreen(modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(SheetTab.FILES) }
    var open by rememberSaveable { mutableStateOf<OpenSheet?>(null) }

    BackHandler(enabled = open != null) {
        open = null
    }

    val opened = open
    if (opened != null) {
        SheetDetailScreen(
            fileId = opened.id,
            archived = opened.archived,
            onBack = { open = null },
            onRestoreArchived = { open = OpenSheet(it, false) },
            modifier = modifier.fillMaxSize()
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            SheetHomeTab(
                selected = tab == SheetTab.FILES,
                icon = R.drawable.ic_ss_myfiles,
                label = "My Files",
                onClick = { tab = SheetTab.FILES },
                modifier = Modifier.weight(1f)
            )
            SheetHomeTab(
                selected = tab == SheetTab.WALLET,
                icon = R.drawable.ic_ss_wallet,
                label = "Wallet",
                onClick = { tab = SheetTab.WALLET },
                modifier = Modifier.weight(1f)
            )
            SheetHomeTab(
                selected = tab == SheetTab.ARCHIVE,
                icon = R.drawable.ic_ss_archive,
                label = "Archive",
                onClick = { tab = SheetTab.ARCHIVE },
                modifier = Modifier.weight(1f)
            )
        }
        when (tab) {
            SheetTab.FILES -> SheetFilesTab(
                onOpenFile = { open = OpenSheet(it, false) },
                modifier = Modifier.weight(1f)
            )
            SheetTab.WALLET -> SheetWalletTab(modifier = Modifier.weight(1f))
            SheetTab.ARCHIVE -> SheetArchiveTab(
                onOpenArchived = { open = OpenSheet(it, true) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SheetHomeTab(
    selected: Boolean,
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surface
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .then(
                if (selected) Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                ) else Modifier
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClickLabel = label,
                onClick = onClick
            )
            .padding(vertical = 7.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}
