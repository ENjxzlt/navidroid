package dev.navidroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.navidroid.NaviApp
import dev.navidroid.update.UpdateDialog
import dev.navidroid.update.UpdateVm
import dev.navidroid.update.Updater

@Composable
fun NaviRoot(app: NaviApp) {
    val nav = rememberNavController()
    val player = LocalPlayer.current
    val playerState by player.state.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    var tab by rememberSaveable { mutableStateOf(HomeTab.Albums) }

    val updates: UpdateVm = viewModel { UpdateVm(Updater(app, app.http)) }
    LaunchedEffect(Unit) { updates.checkOnStart() }
    UpdateDialog(updates)

    val showMini = playerState.hasItem && route != null && route != Routes.PLAYER && route != Routes.LOGIN

    Scaffold(
        bottomBar = {
            Column {
                if (showMini) MiniPlayer(playerState, onOpen = { nav.navigate(Routes.PLAYER) })
                if (route == Routes.HOME) {
                    NavigationBar {
                        HomeTab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                } else if (showMini) {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    ) { padding ->
        // Nur den unteren Abstand übernehmen; die Statusleiste behandeln die TopAppBars der Screens.
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        NavHost(
            navController = nav,
            startDestination = if (app.session.current != null) Routes.HOME else Routes.LOGIN,
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(app) {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                }
            }
            composable(Routes.HOME) {
                HomeScreen(
                    app = app,
                    tab = tab,
                    nav = nav,
                    onCheckUpdates = { updates.checkManually() },
                    onLogout = {
                        player.stop()
                        app.session.clear()
                        nav.navigate(Routes.LOGIN) { popUpTo(Routes.HOME) { inclusive = true } }
                    },
                )
            }
            composable(Routes.ALBUM) { e -> AlbumScreen(app, e.arguments?.getString("id").orEmpty(), nav) }
            composable(Routes.ARTIST) { e -> ArtistScreen(app, e.arguments?.getString("id").orEmpty(), nav) }
            composable(Routes.PLAYLIST) { e -> PlaylistScreen(app, e.arguments?.getString("id").orEmpty(), nav) }
            composable(Routes.PLAYER) { PlayerScreen(onClose = { nav.popBackStack() }) }
        }
    }
}
