package net.typeblog.socks.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.SettingsItem

/**
 * More tab — overflow page hosting Countries, Profiles and Settings,
 * which used to be bottom-tab destinations.
 */
@Composable
fun MoreScreen(
    onCountriesClick: () -> Unit,
    onProfilesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Text(
                text = "More",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
        }
        item {
            SettingsItem(
                icon = painterResource(R.drawable.ic_proton_earth),
                label = "Countries",
                description = "Pick a server country",
                showChevron = true,
                onClick = onCountriesClick
            )
        }
        item {
            SettingsItem(
                icon = painterResource(R.drawable.ic_proton_window_terminal),
                label = "Profiles",
                description = "Manage proxy profiles",
                showChevron = true,
                onClick = onProfilesClick
            )
        }
        item {
            SettingsItem(
                icon = painterResource(R.drawable.ic_proton_cog_wheel),
                label = "Settings",
                description = "App settings",
                showChevron = true,
                onClick = onSettingsClick
            )
        }
    }
}
