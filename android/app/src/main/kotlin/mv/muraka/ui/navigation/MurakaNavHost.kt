package mv.muraka.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import mv.muraka.ui.capture.CaptureScreen
import mv.muraka.ui.detail.SightingDetailScreen
import mv.muraka.ui.profile.ProfileScreen
import mv.muraka.ui.sightings.MySightingsScreen
import mv.muraka.ui.sync.SyncStatusScreen

/**
 * The signed-in app.
 *
 * Three tabs and a capture action. The feature set is deliberately small — review, maps
 * and administration live in the dashboard, not on the phone — and keeping it that way is
 * what makes the capture flow fit in under 60 seconds and 8 taps (NFR6).
 */
@Composable
fun MurakaNavHost(
    pendingCount: Int,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    // Chrome is hidden on capture and detail: both are full-attention screens, and a
    // navigation bar under a camera preview is an invitation to lose the shot.
    val showChrome = Destination.tabs.any { tab ->
        currentRoute?.hierarchy?.any { it.route == tab.route } == true
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            AnimatedVisibility(visible = showChrome) {
                NavigationBar {
                    Destination.tabs.forEach { tab ->
                        val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.switchTab(tab) },
                            label = { Text(tab.label) },
                            icon = {
                                if (tab == Destination.Sync && pendingCount > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge(
                                                modifier = Modifier.semantics {
                                                    contentDescription =
                                                        "$pendingCount waiting to upload"
                                                },
                                            ) { Text("$pendingCount") }
                                        },
                                    ) { Icon(tab.icon, contentDescription = null) }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(visible = showChrome) {
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate(Destination.Capture.route) },
                    icon = { Icon(Icons.Filled.AddAPhoto, contentDescription = null) },
                    text = { Text("New sighting") },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.MySightings.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.MySightings.route) {
                MySightingsScreen(
                    viewModel = hiltViewModel(),
                    onOpenSighting = { id ->
                        navController.navigate(Destination.SightingDetail.routeTo(id))
                    },
                )
            }

            composable(Destination.Sync.route) {
                SyncStatusScreen(viewModel = hiltViewModel())
            }

            composable(Destination.Profile.route) {
                ProfileScreen(viewModel = hiltViewModel())
            }

            composable(Destination.Capture.route) {
                CaptureScreen(
                    viewModel = hiltViewModel(),
                    onDone = { navController.popBackStack() },
                )
            }

            composable(Destination.SightingDetail.route) {
                SightingDetailScreen(
                    viewModel = hiltViewModel(),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Standard Material tab switching: single top, state saved and restored.
 *
 * Without `saveState`/`restoreState` a contributor who checks the sync queue and comes
 * back has lost their scroll position in a history that may be hundreds of sightings long.
 */
private fun NavHostController.switchTab(tab: Destination.Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
