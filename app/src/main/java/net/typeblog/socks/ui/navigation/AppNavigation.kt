package net.typeblog.socks.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.viewmodel.compose.viewModel
import net.typeblog.socks.R
import net.typeblog.socks.ui.screens.ProxiesScreen
import net.typeblog.socks.ui.screens.CountriesScreen
import net.typeblog.socks.ui.screens.RecentsScreen
import net.typeblog.socks.ui.screens.StatusScreen
import net.typeblog.socks.ui.screens.SheetScreen
import net.typeblog.socks.ui.screens.SmsScreen
import net.typeblog.socks.ui.screens.MoreScreen
import net.typeblog.socks.ui.screens.BubbleSettingsScreen
import net.typeblog.socks.ui.screens.SettingsScreen
import net.typeblog.socks.ui.screens.SplitTunnelingScreen
import net.typeblog.socks.ui.screens.ThemeScreen
import net.typeblog.socks.ui.screens.DebugLogsScreen
import net.typeblog.socks.ui.screens.AdvancedSettingsScreen
import net.typeblog.socks.ui.viewmodel.VpnViewModel

sealed class Screen(val route: String) {
    data object Profiles : Screen("profiles")
    data object Connect : Screen("connect")
    data object Countries : Screen("countries")
    data object Recents : Screen("recents")
    data object Sheet : Screen("sheet")
    data object Sms : Screen("sms")
    data object More : Screen("more")
    data object Settings : Screen("settings")
    data object SplitTunneling : Screen("split_tunneling")
    data object Theme : Screen("theme")
    data object BubbleSettings : Screen("bubble_settings")
    data object DebugLogs : Screen("debug_logs")
    data object AdvanceSettings : Screen("advance_settings")
}

private data class BottomNavItem(
    val screen: Screen,
    val icon: Painter,
    val selectedIcon: Painter,
    val label: String
)

private val bottomNavRoutes = listOf(
    Screen.Connect.route,
    Screen.Sheet.route,
    Screen.Sms.route,
    Screen.More.route
).toSet()

@Composable
fun AppNavigation(splitAppsSignal: Int = 0, smsSignal: Int = 0) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    // Persist the last bottom tab so background-kill (Navigation never
    // restores its back stack after process death) returns to the same tab.
    // Plain remember: survives rotation (no double restore) but resets on
    // process death (restore runs exactly when needed).
    val context = LocalContext.current
    val appCtx = remember(context) { context.applicationContext }
    val navPrefs = remember(appCtx) { androidx.preference.PreferenceManager.getDefaultSharedPreferences(appCtx) }
    var tabRestored by remember { mutableStateOf(false) }
    val vpnViewModel: VpnViewModel = viewModel()
    var profilePickMode by rememberSaveable { mutableStateOf(false) }
    var countryPickMode by rememberSaveable { mutableStateOf(false) }
    var homeCountryPickMode by rememberSaveable { mutableStateOf(false) }
    // Pick flows (profile from Home, country from the add-proxy sheet)
    // hide the bottom bar on the pick screen so it feels like a
    // separate page whose only action is selecting.
    val inPickFlow = (profilePickMode && currentDestination?.route == Screen.Profiles.route) ||
        (countryPickMode && currentDestination?.route == Screen.Countries.route) ||
        (homeCountryPickMode && currentDestination?.route == Screen.Countries.route)
    val showBottomBar = currentDestination?.route in bottomNavRoutes && !inPickFlow

    // External request (e.g. bubble refuse-to-connect): jump straight to the
    // split-tunneling apps list so the user can pick apps.
    LaunchedEffect(splitAppsSignal) {
        if (splitAppsSignal > 0) {
            navController.navigate(Screen.SplitTunneling.route + "?startOnApps=true") {
                launchSingleTop = true
            }
        }
    }

    // External request (bubble circle-menu SMS long-press): jump straight to
    // the SMS tab (numbers + live feed).
    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Restore the last bottom tab once per process (see above).
    LaunchedEffect(Unit) {
        if (!tabRestored) {
            tabRestored = true
            val last = navPrefs.getString("last_tab_route", null)
            if (last != null && last != Screen.Connect.route && bottomNavRoutes.contains(last)) {
                navigateToTab(last)
            }
        }
    }

    // Remember the current bottom tab for the next cold start.
    LaunchedEffect(currentDestination?.route) {
        val r = currentDestination?.route
        if (r != null && bottomNavRoutes.contains(r)) {
            navPrefs.edit().putString("last_tab_route", r).apply()
        }
    }

    LaunchedEffect(smsSignal) {
        if (smsSignal > 0) {
            navigateToTab(Screen.Sms.route)
        }
    }

    val bottomNavItems = listOf(
        BottomNavItem(Screen.Connect, painterResource(R.drawable.ic_proton_house), painterResource(R.drawable.ic_proton_house_filled), "Home"),
        BottomNavItem(Screen.Sheet, painterResource(R.drawable.ic_tab_sheet), painterResource(R.drawable.ic_tab_sheet_filled), "Sheet"),
        BottomNavItem(Screen.Sms, painterResource(R.drawable.ic_tab_sms), painterResource(R.drawable.ic_tab_sms_filled), "SMS"),
        BottomNavItem(Screen.More, painterResource(R.drawable.ic_tab_more), painterResource(R.drawable.ic_tab_more_filled), "More")
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp
                ) {
                    // Custom items instead of NavigationBarItem: same look
                    // (filled icon + label, no pill), and clickable with
                    // indication = null so taps have no ripple flash.
                    bottomNavItems.forEach { item ->
                        // Named isSelected (not selected): inside the
                        // semantics {} receiver lambda below, a local named
                        // `selected` would shadow the receiver's var and fail
                        // to compile ('val' cannot be reassigned).
                        val isSelected = currentDestination?.hierarchy?.any {
                            it.route == item.screen.route
                        } == true
                        val contentColor = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .semantics { selected = isSelected }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Tab,
                                    onClickLabel = item.label,
                                    onClick = {
                                        profilePickMode = false
                                        countryPickMode = false
                                        homeCountryPickMode = false
                                        navigateToTab(item.screen.route)
                                    }
                                )
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = if (isSelected) item.selectedIcon else item.icon,
                                contentDescription = item.label,
                                tint = contentColor
                            )
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Connect.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Profiles.route) {
                ProxiesScreen(
                    viewModel = vpnViewModel,
                    pickMode = profilePickMode,
                    onPickProfile = { name ->
                        vpnViewModel.pickProfile(name)
                        profilePickMode = false
                        navigateToTab(Screen.Connect.route)
                    },
                    onPickCountryClick = {
                        countryPickMode = true
                        navigateToTab(Screen.Countries.route)
                    },
                    onNavigateBack = if (!profilePickMode) {
                        { navController.popBackStack() }
                    } else null
                )
            }
            composable(Screen.Connect.route) {
                StatusScreen(
                    viewModel = vpnViewModel,
                    onPickProfileClick = {
                        profilePickMode = true
                        navigateToTab(Screen.Profiles.route)
                    },
                    onOpenSplitAppsClick = {
                        navController.navigate(Screen.SplitTunneling.route + "?startOnApps=true")
                    },
                    onCountryPickClick = {
                        homeCountryPickMode = true
                        navigateToTab(Screen.Countries.route)
                    },
                    onSeeAllRecentsClick = {
                        navigateToTab(Screen.Recents.route)
                    }
                )
            }
            composable(Screen.Countries.route) {
                CountriesScreen(
                    viewModel = vpnViewModel,
                    onConnected = {
                        countryPickMode = false
                        homeCountryPickMode = false
                        navController.navigate(Screen.Connect.route) {
                            popUpTo(Screen.Countries.route) { inclusive = true }
                        }
                    },
                    pickMode = countryPickMode || homeCountryPickMode,
                    onPickCountry = { code ->
                        vpnViewModel.pickCountry(code)
                        if (homeCountryPickMode) {
                            homeCountryPickMode = false
                            navigateToTab(Screen.Connect.route)
                        } else {
                            countryPickMode = false
                            navigateToTab(Screen.Profiles.route)
                        }
                    },
                    onNavigateBack = if (!(countryPickMode || homeCountryPickMode)) {
                        { navController.popBackStack() }
                    } else null
                )
            }
            composable(Screen.Recents.route) {
                // Not a bottom-tab destination, so the bottom bar stays
                // hidden here. Tapping a recent only selects the country:
                // StatusScreen consumes viewModel.pickedCountry and applies
                // the default-profile rewrite, then we return Home.
                RecentsScreen(
                    viewModel = vpnViewModel,
                    onPickRecent = { code ->
                        vpnViewModel.pickAndConnectCountry(code)
                        navigateToTab(Screen.Connect.route)
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Sheet.route) {
                SheetScreen()
            }
            composable(Screen.Sms.route) {
                SmsScreen()
            }
            composable(Screen.More.route) {
                MoreScreen(
                    onCountriesClick = {
                        navController.navigate(Screen.Countries.route)
                    },
                    onProfilesClick = {
                        navController.navigate(Screen.Profiles.route)
                    },
                    onSettingsClick = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToSplitTunneling = {
                        navController.navigate(Screen.SplitTunneling.route)
                    },
                    onNavigateToTheme = {
                        navController.navigate(Screen.Theme.route)
                    },
                    onNavigateToBubbleSettings = {
                        navController.navigate(Screen.BubbleSettings.route)
                    },
                    onNavigateToDebugLogs = {
                        navController.navigate(Screen.DebugLogs.route)
                    },
                    onNavigateToAdvanceSettings = {
                        navController.navigate(Screen.AdvanceSettings.route)
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Theme.route) {
                ThemeScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.BubbleSettings.route) {
                BubbleSettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.SplitTunneling.route + "?startOnApps={startOnApps}",
                arguments = listOf(
                    navArgument("startOnApps") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { entry ->
                SplitTunnelingScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    viewModel = vpnViewModel,
                    startOnApps = entry.arguments?.getBoolean("startOnApps") ?: false
                )
            }
            composable(Screen.DebugLogs.route) {
                DebugLogsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable(Screen.AdvanceSettings.route) {
                AdvancedSettingsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
