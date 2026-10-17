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
import androidx.compose.material.icons.rounded.Check
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
    Library("Bibliothek", Icons.Rounded.LibraryMusic),
    Search("Suche", Icons.Rounded.Search),
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
    Box(Modifier.fillMaxSize().background(Navi.Bg)) {
        when (tab) {
            HomeTab.Start -> StartTab(app, nav, onOpenSearch = { onTabChange(HomeTab.Search) }, onCheckUpdates, onLogout)
            HomeTab.Library -> LibraryTab(app, nav)
            HomeTab.Search -> SearchTab(app, nav)
            HomeTab.Requests -> Column(Modifier.fillMaxSize()) { RequestsTab(app) }
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

@Composable
private fun StartTab(
    app: NaviApp,
    nav: NavController,
    onOpenSearch: () -> Unit,
    onCheckUpdates: () -> Unit,
    onLogout: () -> Unit,
) {
    val vm: AlbumsVm = viewModel { AlbumsVm(app.client) }
    val player = LocalPlayer.current
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val recent = rememberLoad("recent") { app.client.albumList("recent", 12, 0) }
    val headerItems = 5 // Kopf, Suche, Zuletzt gespielt, Chips, Abstand

    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, vm.albums.size) {
        if (nearEnd && vm.albums.isNotEmpty()) vm.loadMore()
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = grid,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                StartHeader(
                    app = app,
                    onShuffle = {
                        scope.launch {
                            runCatching { app.client.randomSongs() }
                                .onSuccess { player.play(it) }
                                .onFailure { snackbar.showSnackbar(it.message ?: "Fehler beim Laden") }
                        }
                    },
                    onCheckUpdates = onCheckUpdates,
                    onLogout = onLogout,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Navi.Surface)
                        .clickable(onClick = onOpenSearch)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Navi.Muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Künstler, Alben, Titel", color = Navi.Muted, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                val albums = (recent.state as? Load.Ok)?.value.orEmpty()
                if (albums.isNotEmpty()) {
                    Column(Modifier.bleed(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader("Zuletzt gespielt") {
                            TextButton(onClick = {
                                vm.select("recent")
                                scope.launch { grid.animateScrollToItem(headerItems) }
                            }) { Text("Alle") }
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(albums, key = { it.id }) { album ->
                                AlbumCard(album, Modifier.width(148.dp)) { nav.openAlbum(album.id) }
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                ChipRow(
                    options = albumTypes,
                    selected = vm.type,
                    onSelect = { vm.select(it); scope.launch { grid.scrollToItem(3) } },
                    modifier = Modifier.bleed(20.dp).padding(top = 4.dp),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    vm.albums.isEmpty() && vm.loading -> CenteredProgress(Modifier.fillMaxWidth().padding(32.dp))
                    vm.albums.isEmpty() && vm.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(vm.error!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::loadMore) { Text("Erneut versuchen") }
                    }
                    vm.albums.isEmpty() -> Text("Keine Alben gefunden", color = Navi.Muted, modifier = Modifier.padding(vertical = 24.dp))
                    else -> Spacer(Modifier.height(0.dp))
                }
            }
            items(vm.albums, key = { it.id }) { album ->
                AlbumCard(album) { nav.openAlbum(album.id) }
            }
            if (vm.loading && vm.albums.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { CenteredProgress(Modifier.fillMaxWidth().padding(16.dp)) }
            }
            if (vm.error != null && vm.albums.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = vm::loadMore) { Text("Erneut versuchen") }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun StartHeader(app: NaviApp, onShuffle: () -> Unit, onCheckUpdates: () -> Unit, onLogout: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var onlineLyrics by remember { mutableStateOf(app.lyrics.onlineLyrics) }
    val date = remember { SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(Date()) }
    val initial = app.session.current?.username?.firstOrNull()?.uppercase() ?: "?"

    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(date, style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), color = Navi.Muted)
            Text(greeting(), style = MaterialTheme.typography.headlineMedium)
        }
        RoundIconButton(Icons.Rounded.Shuffle, "Zufallsmix abspielen", onShuffle)
        Box {
            Surface(onClick = { menu = true }, shape = CircleShape, color = Navi.Surface2, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(initial, style = MaterialTheme.typography.titleMedium)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Online-Songtexte (LRCLIB)") },
                    leadingIcon = { Icon(Icons.Rounded.Lyrics, null) },
                    trailingIcon = { if (onlineLyrics) Icon(Icons.Rounded.Check, "Aktiv") },
                    onClick = {
                        onlineLyrics = !onlineLyrics
                        app.lyrics.onlineLyrics = onlineLyrics
                    },
                )
                DropdownMenuItem(
                    text = { Text("Nach Updates suchen") },
                    leadingIcon = { Icon(Icons.Rounded.SystemUpdate, null) },
                    onClick = { menu = false; onCheckUpdates() },
                )
                DropdownMenuItem(
                    text = { Text("Abmelden") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
                    onClick = { menu = false; onLogout() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("NaviDroid ${BuildConfig.VERSION_NAME}") },
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

// ---- Bibliothek ----

private enum class LibrarySection(val label: String) { Artists("Künstler"), Playlists("Playlists") }

@Composable
private fun LibraryTab(app: NaviApp, nav: NavController) {
    var section by rememberSaveable { mutableStateOf(LibrarySection.Artists) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Bibliothek")
        SegmentedControl(
            options = LibrarySection.entries.map { it to it.label },
            selected = section,
            onSelect = { section = it },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        when (section) {
            LibrarySection.Artists -> ArtistsList(app, nav)
            LibrarySection.Playlists -> PlaylistsList(app, nav)
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
    val loader = rememberLoad("playlists") { app.client.playlists() }
    LoadContent(loader) { playlists ->
        if (playlists.isEmpty()) return@LoadContent EmptyHint("Keine Playlists vorhanden")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(playlists, key = { it.id }) { pl ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.openPlaylist(pl.id) }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CoverArt(pl.coverArt, 300, Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(pl.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pl.songCount} Titel · ${formatLong(pl.duration)}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                    }
                }
            }
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
