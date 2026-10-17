package dev.navidroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import dev.navidroid.BuildConfig
import dev.navidroid.NaviApp
import dev.navidroid.data.Album
import dev.navidroid.data.SubsonicClient
import dev.navidroid.lidarr.RequestsTab
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class HomeTab(val label: String, val icon: ImageVector) {
    Start("Start", Icons.Rounded.Home),
    Search("Suche", Icons.Rounded.Search),
    Library("Bibliothek", Icons.Rounded.LibraryMusic),
    Requests("Anfragen", Icons.Rounded.AddBox),
}

@Composable
fun HomeScreen(
    app: NaviApp,
    tab: HomeTab,
    nav: NavController,
    onTabChange: (HomeTab) -> Unit,
    onCheckUpdates: () -> Unit,
    onLogout: () -> Unit,
) {
    val offline = !app.offline.online
    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        if (offline) OfflineBanner(onClick = { nav.navigate(Routes.DOWNLOADS) })
        // Unter dem Band brauchen die Tabs keinen Abstand mehr für die Statusleiste.
        Box(Modifier.weight(1f).then(if (offline) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
            when (tab) {
                // Ohne Verbindung zeigt der Start-Tab, was offline verfügbar ist.
                HomeTab.Start -> if (offline) DownloadsContent(app, nav, onBack = null) else HomeStartTab(app, nav, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
                HomeTab.Library -> LibraryTab(app, nav)
                HomeTab.Search -> SearchTab(app, nav)
                HomeTab.Requests -> Column(Modifier.fillMaxSize()) { RequestsTab(app, onUpload = { nav.navigate(Routes.UPLOAD) }) }
            }
        }
    }
}

/** Lässt ein Element über das Innenpolster des Elternelements hinaus bis an den Rand laufen. */
private fun Modifier.bleed(horizontal: Dp) = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

// ---- Alben (mit Nachladen) ----

class AlbumsVm(private val client: SubsonicClient) : ViewModel() {
    var type by mutableStateOf("newest")
        private set
    val albums = mutableStateListOf<Album>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val seen = HashSet<String>()
    private var endReached = false
    private var job: Job? = null

    init {
        loadMore()
    }

    fun select(newType: String) {
        if (newType == type) return
        job?.cancel()
        type = newType
        albums.clear()
        seen.clear()
        endReached = false
        error = null
        loading = false
        loadMore()
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        val t = type
        val offset = albums.size
        job = viewModelScope.launch {
            try {
                val page = client.albumList(t, PAGE_SIZE, offset)
                if (t == type) {
                    albums += page.filter { seen.add(it.id) }
                    if (page.size < PAGE_SIZE) endReached = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (t == type) error = e.message ?: "Fehler beim Laden"
            } finally {
                if (t == type) loading = false
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 60
    }
}

private val albumTypes = listOf(
    "newest" to "Neu",
    "frequent" to "Häufig",
    "random" to "Zufällig",
    "alphabeticalByName" to "A–Z",
    "starred" to "Favoriten",
    "recent" to "Zuletzt gespielt",
)

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Guten Tag"
    else -> "Guten Abend"
}

/** Alle Alben mit Filtern (Neu, Häufig, …) – in der Bibliothek. */
@Composable
private fun AlbumsBrowser(app: NaviApp, nav: NavController) {
    val vm: AlbumsVm = viewModel { AlbumsVm(app.client) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, vm.albums.size) {
        if (nearEnd && vm.albums.isNotEmpty()) vm.loadMore()
    }
    Column(Modifier.fillMaxSize()) {
        ChipRow(
            options = albumTypes,
            selected = vm.type,
            onSelect = { vm.select(it); scope.launch { grid.scrollToItem(0) } },
            modifier = Modifier.padding(vertical = 8.dp),
        )
        when {
            vm.albums.isEmpty() && vm.loading -> CenteredProgress()
            vm.albums.isEmpty() && vm.error != null -> ErrorView(vm.error!!, vm::loadMore)
            vm.albums.isEmpty() -> EmptyHint("Keine Alben gefunden")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = grid,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(vm.albums, key = { it.id }) { album -> AlbumCard(album) { nav.openAlbum(album.id) } }
                if (vm.loading) item(span = { GridItemSpan(maxLineSpan) }) { CenteredProgress(Modifier.fillMaxWidth().padding(16.dp)) }
                if (vm.error != null) item(span = { GridItemSpan(maxLineSpan) }) { TextButton(onClick = vm::loadMore) { Text("Erneut versuchen") } }
            }
        }
    }
}

// ---- Bibliothek ----

private enum class LibrarySection(val label: String) { Playlists("Playlists"), Albums("Alben"), Artists("Künstler") }

@Composable
private fun LibraryTab(app: NaviApp, nav: NavController) {
    var section by rememberSaveable { mutableStateOf(LibrarySection.Playlists) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Bibliothek") {
            if (app.helper.configured) RoundIconButton(Icons.Rounded.Add, "Eigene Dateien hochladen", { nav.navigate(Routes.UPLOAD) })
        }
        DownloadsEntry(app) { nav.navigate(Routes.DOWNLOADS) }
        SegmentedControl(
            options = LibrarySection.entries.map { it to it.label },
            selected = section,
            onSelect = { section = it },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        when (section) {
            LibrarySection.Artists -> ArtistsList(app, nav)
            LibrarySection.Albums -> AlbumsBrowser(app, nav)
            LibrarySection.Playlists -> PlaylistsList(app, nav)
        }
    }
}

/** Eintrag „Heruntergeladen“ oben in der Bibliothek. */
@Composable
private fun DownloadsEntry(app: NaviApp, onClick: () -> Unit) {
    val offline = app.offline
    val count = offline.downloadedCount
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DownloadForOffline, null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text("Heruntergeladen", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            Text(
                if (count == 0) "Musik für unterwegs ohne Netz" else "$count Titel · ${formatBytes(offline.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = Navi.Muted,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistsList(app: NaviApp, nav: NavController) {
    val loader = rememberLoad("artists") { app.client.artists() }
    LoadContent(loader) { indexes ->
        if (indexes.isEmpty()) return@LoadContent EmptyHint("Keine Künstler gefunden")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            indexes.forEach { index ->
                stickyHeader(key = "h_${index.name}") {
                    Text(
                        index.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().background(Navi.Bg).padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
                items(index.artist, key = { it.id }) { artist ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.openArtist(artist.id) }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CoverArt(artist.coverArt, 300, Modifier.size(52.dp).clip(CircleShape))
                        Column(Modifier.weight(1f)) {
                            Text(artist.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (artist.albumCount == 1) "1 Album" else "${artist.albumCount} Alben", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsList(app: NaviApp, nav: NavController) {
    val loader = rememberLoad("playlists:${PlaylistBus.version}") { app.client.playlists() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<dev.navidroid.data.Playlist?>(null) }
    var deleting by remember { mutableStateOf<dev.navidroid.data.Playlist?>(null) }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                PlaylistBus.changed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, e.message ?: "Fehler", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    LoadContent(loader) { playlists ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(60.dp).clip(RoundedCornerShape(10.dp))
                            .border(1.5.dp, Color(0xFF3A3F48), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Neue Playlist", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold))
                        Text("Titel fügst du über ⋮ → „Zur Playlist hinzufügen“ hinzu", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                    }
                }
            }
            if (app.helper.configured) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.navigate(Routes.AI_PLAYLIST) }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(Navi.Surface2), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Mit KI erstellen", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold))
                            Text("Beschreib, was du hören willst – Claude stellt sie zusammen", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                        }
                    }
                }
            }
            if (playlists.isEmpty()) item { Text("Noch keine Playlists.", color = Navi.Muted, modifier = Modifier.padding(20.dp)) }
            items(playlists, key = { it.id }) { pl ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { nav.openPlaylist(pl.id) }.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CoverArt(pl.coverArt, 300, Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(pl.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull("${pl.songCount} Titel", pl.duration.takeIf { it > 0 }?.let(::formatLong)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Navi.Muted,
                        )
                    }
                    Box {
                        androidx.compose.material3.IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, "Optionen", tint = Navi.Faint)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Bearbeiten") }, onClick = { menu = false; nav.editPlaylist(pl.id) })
                            DropdownMenuItem(text = { Text("Umbenennen") }, onClick = { menu = false; renaming = pl })
                            DropdownMenuItem(text = { Text("Löschen", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = pl })
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        PlaylistNameDialog("Neue Playlist", "", "Erstellen", onDismiss = { creating = false }) { name ->
            creating = false
            scope.launch {
                try {
                    val pl = app.client.createPlaylist(name, emptyList())
                    PlaylistBus.changed()
                    pl?.id?.let { nav.openPlaylist(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, e.message ?: "Fehler", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    renaming?.let { pl ->
        PlaylistNameDialog("Playlist umbenennen", pl.name, "Speichern", onDismiss = { renaming = null }) { name ->
            renaming = null
            act { app.client.updatePlaylist(pl.id, name = name) }
        }
    }
    deleting?.let { pl ->
        DeletePlaylistDialog(pl.name, onDismiss = { deleting = null }) {
            deleting = null
            act { app.client.deletePlaylist(pl.id) }
        }
    }
}

// ---- Suche ----

@Composable
private fun SearchTab(app: NaviApp, nav: NavController) {
    val player = LocalPlayer.current
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(350)
        debounced = query.trim()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Suche")
        SearchField(query, { query = it }, "Künstler, Alben, Titel", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (debounced.isEmpty()) {
            EmptyHint("Durchsuche deine Bibliothek")
            return@Column
        }
        val loader = rememberLoad("search:$debounced") { app.client.search(debounced) }
        LoadContent(loader) { r ->
            if (r.artist.isEmpty() && r.album.isEmpty() && r.song.isEmpty()) {
                return@LoadContent EmptyHint("Keine Treffer für „$debounced“")
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (r.artist.isNotEmpty()) {
                    item { SectionHeader("Künstler", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                    items(r.artist, key = { "a_${it.id}" }) { artist ->
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.openArtist(artist.id) }.padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CoverArt(artist.coverArt, 300, Modifier.size(48.dp).clip(CircleShape))
                            Text(artist.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                        }
                    }
                }
                if (r.album.isNotEmpty()) {
                    item { SectionHeader("Alben", Modifier.padding(top = 16.dp, bottom = 12.dp)) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(r.album, key = { it.id }) { album ->
                                AlbumCard(album, Modifier.width(140.dp)) { nav.openAlbum(album.id) }
                            }
                        }
                    }
                }
                if (r.song.isNotEmpty()) {
                    item { SectionHeader("Titel", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                    itemsIndexed(r.song) { i, song ->
                        SongRow(song, onClick = { player.play(r.song, i) }, showCover = true)
                    }
                }
            }
        }
    }
}
