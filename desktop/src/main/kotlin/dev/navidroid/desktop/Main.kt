package dev.navidroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.navidroid.data.Auth
import dev.navidroid.data.LyricsRepository
import dev.navidroid.data.MixRepository
import dev.navidroid.data.Song
import dev.navidroid.data.SubsonicClient
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

/** Navigation: einfacher Verlauf mit Zurück. */
sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Albums : Screen
    data object Artists : Screen
    data class AlbumPage(val id: String) : Screen
    data class ArtistPage(val id: String) : Screen
    data class PlaylistPage(val id: String) : Screen
    data class MixPage(val id: String) : Screen
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current get() = stack.last()
    fun go(s: Screen) { if (stack.last() != s) stack.add(s) }
    fun root(s: Screen) { stack.clear(); stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

/** Alles, was die Oberfläche braucht. */
class DesktopApp {
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    val session = DesktopSession()
    var settings by mutableStateOf(DesktopSettings.load())
    val client = SubsonicClient(http, session)
    val lyrics = LyricsRepository(http, client, Paths.cache, { settings.onlineLyrics }, { updateSettings(settings.copy(onlineLyrics = it)) })
    val mixes = MixRepository(client) { emptyMap() }
    val player = DesktopPlayer(client) { settings }
    val nav = Nav()
    var loggedIn by mutableStateOf(session.current != null)
    var rightPanel by mutableStateOf(RightPanel.None)

    fun updateSettings(s: DesktopSettings) {
        settings = s
        DesktopSettings.save(s)
    }

    fun startRadio(seed: Song) {
        kotlinx.coroutines.MainScope().launch {
            val similar = runCatching { client.similarSongs(seed.id, 50) }.getOrDefault(emptyList()).filter { it.id != seed.id }
            player.play(listOf(seed) + similar)
        }
    }
}

enum class RightPanel { None, Queue, Lyrics }

fun main() = application {
    val app = remember { DesktopApp() }
    setSingletonImageLoaderFactory { ctx: PlatformContext ->
        ImageLoader.Builder(ctx)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { app.http })) }
            .diskCache { DiskCache.Builder().directory(java.io.File(Paths.cache, "images").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }
    val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
    val mpris = remember {
        Mpris(app.player, { app.client.coverArtUrl(it, 600) }, { windowState.isMinimized = false }, { app.player.release(); exitApplication() })
            .also { it.start() }
    }
    Window(
        onCloseRequest = { mpris.stop(); app.player.release(); exitApplication() },
        title = "NaviDroid",
        state = windowState,
        icon = runCatching { androidx.compose.ui.res.painterResource("icon.png") }.getOrNull(),
        onPreviewKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@Window false
            when {
                e.key == Key.MediaPlayPause -> { app.player.toggle(); true }
                e.key == Key.MediaNext -> { app.player.next(); true }
                e.key == Key.MediaPrevious -> { app.player.previous(); true }
                e.isCtrlPressed && e.key == Key.F -> { app.nav.go(Screen.Search); true }
                e.isCtrlPressed && e.key == Key.Spacebar -> { app.player.toggle(); true }
                else -> false
            }
        },
    ) {
        NaviTheme {
            Box(Modifier.fillMaxSize().background(Navi.Bg)) {
                if (!app.loggedIn) LoginScreen(app) else MainLayout(app)
            }
        }
    }
}

@Composable
private fun LoginScreen(app: DesktopApp) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(380.dp).clip(RoundedCornerShape(24.dp)).background(Navi.Surface).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("NaviDroid", style = MaterialTheme.typography.headlineLarge)
            Text("Mit deinem Navidrome-Server verbinden", color = Navi.Muted)
            OutlinedTextField(server, { server = it }, label = { Text("Server-Adresse") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Benutzername") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Passwort") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && server.isNotBlank() && user.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val c = Auth.createCredentials(server, user, pass)
                            app.client.ping(c)
                            app.session.save(c)
                            app.loggedIn = true
                        } catch (e: Exception) {
                            error = e.message ?: "Anmeldung fehlgeschlagen"
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text(if (busy) "Verbinde …" else "Anmelden") }
        }
    }
}

@Composable
private fun MainLayout(app: DesktopApp) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Sidebar(app, Modifier.width(240.dp).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, end = 8.dp).clip(RoundedCornerShape(16.dp)).background(Navi.Surface.copy(alpha = 0.55f))) {
                Column(Modifier.fillMaxSize()) {
                    if (app.nav.stack.size > 1) {
                        IconButton(onClick = app.nav::back, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück")
                        }
                    }
                    Box(Modifier.weight(1f)) { ScreenContent(app, app.nav.current) }
                }
            }
            when (app.rightPanel) {
                RightPanel.Queue -> QueuePanel(app, Modifier.width(340.dp).fillMaxHeight().padding(top = 8.dp, end = 8.dp))
                RightPanel.Lyrics -> LyricsPanel(app, Modifier.width(420.dp).fillMaxHeight().padding(top = 8.dp, end = 8.dp))
                RightPanel.None -> Unit
            }
        }
        PlayerBar(app)
    }
}

@Composable
private fun Sidebar(app: DesktopApp, modifier: Modifier) {
    val playlists = rememberLoad("playlists") { app.client.playlists() }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("NaviDroid", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 16.dp))
        NavItem(Icons.Rounded.Home, "Start", app.nav.current == Screen.Home) { app.nav.root(Screen.Home) }
        NavItem(Icons.Rounded.Search, "Suche", app.nav.current == Screen.Search) { app.nav.root(Screen.Search) }
        NavItem(Icons.Rounded.Album, "Alben", app.nav.current == Screen.Albums) { app.nav.root(Screen.Albums) }
        NavItem(Icons.Rounded.Person, "Künstler", app.nav.current == Screen.Artists) { app.nav.root(Screen.Artists) }
        Text("PLAYLISTS", style = MaterialTheme.typography.labelSmall, color = Navi.Muted, modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 6.dp))
        Box(Modifier.weight(1f)) {
            when (val s = playlists.first) {
                is Load.Ok -> LazyColumn {
                    items(s.value.filterNot { MixRepository.isPluginPlaylist(it.comment) }, key = { it.id }) { pl ->
                        val selected = app.nav.current == Screen.PlaylistPage(pl.id)
                        Text(
                            pl.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary else Navi.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { app.nav.go(Screen.PlaylistPage(pl.id)) }.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
                else -> Unit
            }
        }
        NavItem(Icons.AutoMirrored.Rounded.Logout, "Abmelden", false) {
            app.player.pause()
            app.session.clear()
            app.loggedIn = false
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) Navi.Surface2 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else Navi.Muted, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold), color = if (selected) Navi.Text else Navi.Muted)
    }
}

@Composable
private fun ScreenContent(app: DesktopApp, screen: Screen) {
    when (screen) {
        Screen.Home -> HomeScreen(app)
        Screen.Search -> SearchScreen(app)
        Screen.Albums -> AlbumsScreen(app)
        Screen.Artists -> ArtistsScreen(app)
        is Screen.AlbumPage -> AlbumScreen(app, screen.id)
        is Screen.ArtistPage -> ArtistScreen(app, screen.id)
        is Screen.PlaylistPage -> PlaylistScreen(app, screen.id)
        is Screen.MixPage -> MixScreen(app, screen.id)
    }
}

@Composable
fun QueueToggle(app: DesktopApp) {
    IconButton(onClick = { app.rightPanel = if (app.rightPanel == RightPanel.Queue) RightPanel.None else RightPanel.Queue }) {
        Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Warteschlange", tint = if (app.rightPanel == RightPanel.Queue) MaterialTheme.colorScheme.primary else Navi.Muted)
    }
}

@Composable
fun Spacer16() = Spacer(Modifier.height(16.dp))
