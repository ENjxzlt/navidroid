package dev.navidroid.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.navidroid.lidarr.HelperClient
import dev.navidroid.lidarr.HelperConfig
import dev.navidroid.lidarr.HelperStore
import dev.navidroid.lidarr.LidarrClient
import dev.navidroid.lidarr.LidarrStore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
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
    data object Requests : Screen
    data object Mixes : Screen
    data object Daylist : Screen
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
    val helperStore = HelperStore(FileKeyValue("helper"))
    val helper = HelperClient(http, helperStore)
    val lidarrStore = LidarrStore(FileKeyValue("lidarr"))
    val lidarr = LidarrClient(http, lidarrStore)
    val lyrics = LyricsRepository(http, client, Paths.cache, { settings.onlineLyrics }, { updateSettings(settings.copy(onlineLyrics = it)) })
    val mixes = MixRepository(client, { if (helper.configured) helper.artistGenres() else emptyMap() }) {
        val user = session.current?.username
        if (user != null && helper.configured) helper.historySummary(user).counts else emptyMap()
    }
    val daylist = DesktopDaylist(this)
    val player = DesktopPlayer(client) { settings }
    val nav = Nav()
    var loggedIn by mutableStateOf(session.current != null)
    var rightPanel by mutableStateOf(RightPanel.None)
    var showSettings by mutableStateOf(false)
    /** Hochzählen lädt Listen (Playlists, Startseite) neu, z. B. nach dem Einrichten des Helfers. */
    var version by mutableIntStateOf(0)

    init {
        player.onPlayed = { song, ts ->
            val user = session.current?.username
            if (user != null && helper.configured) MainScope().launch { runCatching { helper.recordPlay(user, song, ts) } }
        }
    }

    /**
     * Einstellungen mit dem Handy abgleichen (über den Helfer): neuere gewinnen.
     * Der Desktop übernimmt Lidarr, Online-Songtexte und Autoplay; Handy-Werte wie Music Assistant bleiben erhalten.
     */
    suspend fun syncSettings(pushLocal: Boolean = false) {
        val user = session.current?.username ?: return
        if (!helper.configured) return
        val (remote, ts) = runCatching { helper.getSettings(user) }.getOrNull() ?: return
        // Fehlender Lidarr-Zugang wird immer vom Handy übernommen, auch wenn der Stand schon als abgeglichen gilt.
        val remoteLidarr = dev.navidroid.lidarr.SyncFormat.lidarr(remote["lidarr"] as? kotlinx.serialization.json.JsonObject)
        if (lidarrStore.config == null && remoteLidarr != null) {
            lidarrStore.save(remoteLidarr)
            version++
        }
        val pulled = ts > settings.syncedAt
        if (pulled) {
            remoteLidarr?.let { lidarrStore.save(it) }
            with(dev.navidroid.lidarr.SyncFormat) {
                updateSettings(settings.copy(
                    onlineLyrics = remote.bool("onlineLyrics") ?: settings.onlineLyrics,
                    autoplay = remote.bool("autoplay") ?: settings.autoplay,
                    syncedAt = ts,
                ))
            }
            version++
        }
        if (pushLocal || !pulled) {
            val now = System.currentTimeMillis()
            val data = kotlinx.serialization.json.buildJsonObject {
                remote.forEach { (k, v) -> put(k, v) }
                lidarrStore.config?.let { put("lidarr", dev.navidroid.lidarr.SyncFormat.lidarr(it)) }
                put("onlineLyrics", kotlinx.serialization.json.JsonPrimitive(settings.onlineLyrics))
                put("autoplay", kotlinx.serialization.json.JsonPrimitive(settings.autoplay))
            }
            runCatching { helper.putSettings(user, data, now) }.onSuccess { updateSettings(settings.copy(syncedAt = now)) }
        }
    }

    fun updateSettings(s: DesktopSettings) {
        settings = s
        DesktopSettings.save(s)
    }

    fun startRadio(seed: Song) {
        MainScope().launch {
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
    val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
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
                e.isCtrlPressed && e.key == Key.F -> { app.nav.root(Screen.Search); true }
                e.isCtrlPressed && e.key == Key.Spacebar -> { app.player.toggle(); true }
                else -> false
            }
        },
    ) {
        // Akzentfarbe folgt dem Cover des laufenden Titels
        val current by app.player.state.collectAsStateCompat()
        val coverUrl = current.current?.coverArt?.let { app.client.coverArtUrl(it, 300) }
        var accentTarget by remember { mutableStateOf(Navi.Accent) }
        LaunchedEffect(coverUrl) { accentTarget = CoverAccent.load(app.http, coverUrl) ?: Navi.Accent }
        val accent by animateColorAsState(accentTarget, tween(600))
        NaviTheme(accent) {
            // Surface setzt die Textfarbe; ohne sie wäre Text ohne eigene Farbe schwarz
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Navi.Bg, contentColor = Navi.Text) {
                if (!app.loggedIn) LoginScreen(app) else MainLayout(app)
                if (app.showSettings) SettingsDialog(app) { app.showSettings = false }
            }
        }
    }
}

@Composable
private fun LoginScreen(app: DesktopApp) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var helperUrl by remember { mutableStateOf(app.helperStore.config?.url.orEmpty()) }
    var helperToken by remember { mutableStateOf(app.helperStore.config?.token.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF2B1E16), Navi.Bg), radius = 1400f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(420.dp).clip(RoundedCornerShape(24.dp)).background(Navi.Surface).padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("NaviDroid", style = MaterialTheme.typography.headlineLarge)
            Text("Mit deinem Navidrome-Server verbinden. Dein Passwort wird nicht gespeichert.", color = Navi.Muted)
            OutlinedTextField(server, { server = it }, label = { Text("Server-Adresse") }, placeholder = { Text("http://192.168.178.10:4533") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Benutzername") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Passwort") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(helperUrl, { helperUrl = it }, label = { Text("NaviDroid-Helfer (optional)") }, placeholder = { Text("http://192.168.178.10:8787") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (helperUrl.isNotBlank()) {
                OutlinedTextField(helperToken, { helperToken = it }, label = { Text("Helfer-Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
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
                            if (helperUrl.isNotBlank()) {
                                val url = dev.navidroid.lidarr.LidarrStore.normalizeUrl(helperUrl)
                                app.helper.health(url, helperToken)
                                app.helperStore.save(HelperConfig(url, helperToken.trim(), false))
                            }
                            app.session.save(c)
                            app.loggedIn = true
                        } catch (e: Exception) {
                            error = e.message ?: "Anmeldung fehlgeschlagen"
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(if (busy) "Verbinde …" else "Anmelden", fontWeight = FontWeight.ExtraBold) }
        }
    }
}

@Composable
private fun MainLayout(app: DesktopApp) {
    LaunchedEffect(app.loggedIn) { runCatching { app.syncSettings() } }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { app.daylist.ensure() }
            delay(60_000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sidebar(app, Modifier.width(250.dp).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0xFF121317))) {
                ScreenContent(app, app.nav.current)
                if (app.nav.stack.size > 1) {
                    IconButton(
                        onClick = app.nav::back,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp).size(36.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.35f)),
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", modifier = Modifier.size(20.dp)) }
                }
            }
            when (app.rightPanel) {
                RightPanel.Queue -> QueuePanel(app, Modifier.width(340.dp).fillMaxHeight())
                RightPanel.Lyrics -> LyricsPanel(app, Modifier.width(420.dp).fillMaxHeight())
                RightPanel.None -> Unit
            }
        }
        PlayerBar(app)
    }
}

@Composable
private fun Sidebar(app: DesktopApp, modifier: Modifier) {
    val playlists = rememberLoad("playlists:${app.version}") { app.client.playlists() }
    val active = rememberActiveDownloads(app)
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 16.dp)) {
            Text("NaviDroid", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { app.showSettings = true }) { Icon(Icons.Rounded.Settings, "Einstellungen", tint = Navi.Muted) }
        }
        NavItem(Icons.Rounded.Home, "Start", app.nav.current == Screen.Home) { app.nav.root(Screen.Home) }
        NavItem(Icons.Rounded.Search, "Suche", app.nav.current == Screen.Search) { app.nav.root(Screen.Search) }
        NavItem(Icons.Rounded.Album, "Alben", app.nav.current == Screen.Albums) { app.nav.root(Screen.Albums) }
        NavItem(Icons.Rounded.Person, "Künstler", app.nav.current == Screen.Artists) { app.nav.root(Screen.Artists) }
        NavItem(Icons.Rounded.AddBox, "Anfragen", app.nav.current == Screen.Requests, badge = active) { app.nav.root(Screen.Requests) }
        Text("BIBLIOTHEK", style = MaterialTheme.typography.labelSmall, color = Navi.Muted, modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (app.nav.current == Screen.Mixes) Navi.Surface2 else Color.Transparent)
                .clickable { app.nav.root(Screen.Mixes) }.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Navi.Surface2), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Text("Mixe & Daylist", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
        }
        Box(Modifier.weight(1f)) {
            when (val s = playlists.first) {
                is Load.Ok -> LazyColumn {
                    items(s.value.filterNot { MixRepository.isPluginPlaylist(it.comment) }, key = { it.id }) { pl ->
                        val selected = app.nav.current == Screen.PlaylistPage(pl.id)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) Navi.Surface2 else Color.Transparent)
                                .clickable { app.nav.go(Screen.PlaylistPage(pl.id)) }.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Cover(app.client.coverArtUrl(pl.coverArt, 100), Modifier.size(32.dp), 6)
                            Text(pl.name, style = MaterialTheme.typography.bodyMedium, color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFD5D8DE), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
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
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) Navi.Surface2 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else Navi.Muted, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold), color = if (selected) Navi.Text else Navi.Muted, modifier = Modifier.weight(1f))
        if (badge > 0) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text("$badge", style = MaterialTheme.typography.labelSmall, color = Navi.Bg)
            }
        }
    }
}

@Composable
private fun ScreenContent(app: DesktopApp, screen: Screen) {
    when (screen) {
        Screen.Home -> HomeScreen(app)
        Screen.Search -> SearchScreen(app)
        Screen.Albums -> AlbumsScreen(app)
        Screen.Artists -> ArtistsScreen(app)
        Screen.Requests -> RequestsScreen(app)
        Screen.Mixes -> MixesFolderScreen(app)
        Screen.Daylist -> DaylistScreen(app)
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
