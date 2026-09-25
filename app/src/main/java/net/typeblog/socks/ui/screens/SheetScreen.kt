package net.typeblog.socks.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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

// Wallet tab is hidden for now; the tab, its screen and its data layer
// are all still in place. Flip to true to bring the tab back.
private const val SHOW_WALLET_TAB = false

private enum class SheetTab { FILES, WALLET, ARCHIVE }

/**
 * Sheet tab: My Files / Wallet / Archive for regular users (admins in the
 * app get the same user-only views). Matches the SheetSubmit website home
 * tabs; data lives in the local-first SQLite store.
 */
@Composable
fun SheetScreen(modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(SheetTab.FILES) }
    // Open file as saveable primitives: a custom data class in
    // rememberSaveable crashes state save on backgrounding, which is why
    // the app used to drop back to Home.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var openArchived by rememberSaveable { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }

    BackHandler(enabled = openId != null) {
        openId = null
    }

    val openedId = openId
    if (openedId != null) {
        SheetDetailScreen(
            fileId = openedId,
            archived = openArchived,
            onBack = { openId = null },
            onRestoreArchived = { openId = it; openArchived = false },
            modifier = modifier.fillMaxSize()
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!selecting) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 16.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                )
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SheetHomeTab(
                selected = tab == SheetTab.FILES,
                icon = if (tab == SheetTab.FILES) R.drawable.ic_ss_myfiles_sel else R.drawable.ic_ss_myfiles_idle,
                label = "My Files",
                onClick = { tab = SheetTab.FILES; selecting = false }
            )
            SheetHomeTab(
                selected = tab == SheetTab.ARCHIVE,
                icon = if (tab == SheetTab.ARCHIVE) R.drawable.ic_ss_archive_sel else R.drawable.ic_ss_archive_idle,
                label = "Archive",
                onClick = { tab = SheetTab.ARCHIVE; selecting = false }
            )
            if (SHOW_WALLET_TAB) {
                SheetHomeTab(
                    selected = tab == SheetTab.WALLET,
                    icon = if (tab == SheetTab.WALLET) R.drawable.ic_ss_wallet_sel else R.drawable.ic_ss_wallet_idle,
                    label = "Wallet",
                    onClick = { tab = SheetTab.WALLET; selecting = false }
                )
            }
        }
        }
        when (tab) {
            SheetTab.FILES -> SheetFilesTab(
                onOpenFile = { openId = it; openArchived = false },
                onSelectionModeChange = { selecting = it },
                modifier = Modifier.weight(1f)
            )
            SheetTab.WALLET -> SheetWalletTab(modifier = Modifier.weight(1f))
            SheetTab.ARCHIVE -> SheetArchiveTab(
                onOpenArchived = { openId = it; openArchived = true },
                onSelectionModeChange = { selecting = it },
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
            modifier = Modifier.size(14.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(contentColor)
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
