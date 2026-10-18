package dev.navidroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.navidroid.NaviApp
import dev.navidroid.ui.theme.Navi
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
    var tab by rememberSaveable { mutableStateOf(HomeTab.Start) }

    val updates: UpdateVm = viewModel { UpdateVm(Updater(app, app.http)) }
    LaunchedEffect(Unit) { updates.checkOnStart() }
    UpdateDialog(updates)
    PlaylistBus.addRequest?.let { AddToPlaylistSheet(app, it) }
    if (DeviceBus.open) DevicesSheet(app)
    // Vom Widget: direkt zur Daylist
    LaunchedEffect(OpenBus.target, app.session.current) {
        if (OpenBus.target == "daylist" && app.session.current != null) {
            OpenBus.target = null
            nav.navigate(Routes.DAYLIST)
        }
    }

    val fullScreen = route == Routes.PLAYER || route == Routes.LYRICS || route == Routes.LOGIN || route == Routes.PLAYLIST_EDIT || route == Routes.AI_PLAYLIST || route == Routes.ALBUM_EDIT
    val showMini = playerState.hasItem && route != null && !fullScreen

    // Die Akzentfarbe der ganzen App folgt dem Cover des laufenden Titels.
    CoverAccentTheme(playerState.artworkUri, albumCoverAlternative(playerState.albumId)) {
    Scaffold(
        containerColor = Navi.Bg,
        snackbarHost = { androidx.compose.material3.SnackbarHost(Snack.host) },
        bottomBar = {
            Column {
                if (showMini) {
                    MiniPlayer(
                        playerState,
                        onOpen = { nav.navigate(Routes.PLAYER) },
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    )
                }
                if (route == Routes.HOME) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Navi.Divider))
                    NavigationBar(containerColor = Navi.Bg, tonalElevation = 0.dp) {
                        HomeTab.entries.forEach { t ->
                            val selected = tab == t
                            NavigationBarItem(
                                selected = selected,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = {
                                    Text(t.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = Navi.Faint,
                                    unselectedTextColor = Navi.Faint,
                                    indicatorColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                } else if (showMini) {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    ) { padding ->
        // Nur den unteren Abstand übernehmen; die Statusleiste behandeln die Screens selbst.
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
                    onTabChange = { tab = it },
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
            composable(Routes.PLAYLIST_EDIT) { e ->
                PlaylistEditScreen(
                    app,
                    e.arguments?.getString("id").orEmpty(),
                    nav,
                    onAddTracks = {
                        tab = HomeTab.Search
                        nav.popBackStack(Routes.HOME, inclusive = false)
                    },
                )
            }
            composable(Routes.PLAYER) {
                PlayerScreen(
                    onClose = { nav.popBackStack() },
                    onOpenLyrics = { nav.navigate(Routes.LYRICS) },
                    onOpenAlbum = { id -> nav.popBackStack(); nav.openAlbum(id) },
                    onOpenArtist = { id -> nav.popBackStack(); nav.openArtist(id) },
                )
            }
            composable("mix/{id}") { e -> MixScreen(app, e.arguments?.getString("id").orEmpty(), nav) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    app,
                    nav,
                    onCheckUpdates = { updates.checkManually() },
                    onLogout = {
                        player.stop()
                        app.session.clear()
                        nav.navigate(Routes.LOGIN) { popUpTo(Routes.HOME) { inclusive = true } }
                    },
                )
            }
            composable(Routes.AI_PLAYLIST) {
                AiPlaylistScreen(app, nav, onSearchMissing = { query ->
                    RequestSearchBus.pendingQuery = query
                    tab = HomeTab.Requests
                    nav.popBackStack(Routes.HOME, inclusive = false)
                })
            }
            composable(Routes.DOWNLOADS) { DownloadsScreen(app, nav) }
            composable(Routes.DAYLIST) { DaylistScreen(app, nav) }
            composable(Routes.ALBUM_EDIT) { e -> AlbumEditScreen(app, e.arguments?.getString("id").orEmpty(), nav) }
            composable(Routes.TRASH) { TrashScreen(app, nav) }
            composable(Routes.MIXES) { MixesFolderScreen(app, nav) }
            composable(Routes.DUPLICATES) { DuplicatesScreen(app, nav) }
            composable(Routes.QUALITY) { QualityScreen(app, nav) }
            composable(Routes.UPLOAD) { UploadScreen(app, nav) }
            composable(Routes.OFFLINE_COLLECTION) { e -> OfflineCollectionScreen(app, e.arguments?.getString("id").orEmpty(), nav) }
            composable(Routes.LYRICS) { LyricsScreen(app, onClose = { nav.popBackStack() }) }
        }
    }
    }
}

/** Ziel, das von außen (Widget) geöffnet werden soll. */
object OpenBus {
    var target by mutableStateOf<String?>(null)
}
