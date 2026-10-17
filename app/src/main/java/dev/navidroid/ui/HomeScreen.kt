package dev.navidroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import dev.navidroid.BuildConfig
import dev.navidroid.NaviApp
import dev.navidroid.data.Album
import dev.navidroid.data.SubsonicClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class HomeTab(val label: String, val icon: ImageVector) {
    Albums("Alben", Icons.Filled.Album),
    Artists("Künstler", Icons.Filled.Person),
    Playlists("Playlists", Icons.AutoMirrored.Filled.PlaylistPlay),
    Search("Suche", Icons.Filled.Search),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: NaviApp,
    tab: HomeTab,
    nav: NavController,
    onCheckUpdates: () -> Unit,
    onLogout: () -> Unit,
) {
    val player = LocalPlayer.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.label) },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            runCatching { app.client.randomSongs() }
                                .onSuccess { player.play(it) }
                                .onFailure { snackbar.showSnackbar(it.message ?: "Fehler beim Laden") }
                        }
                    }) { Icon(Icons.Filled.Shuffle, "Zufallsmix") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menü") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Nach Updates suchen") },
                                leadingIcon = { Icon(Icons.Filled.SystemUpdate, null) },
                                onClick = { menu = false; onCheckUpdates() },
                            )
                            DropdownMenuItem(
                                text = { Text("Abmelden") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                                onClick = { menu = false; onLogout() },
                            )
                            DropdownMenuItem(
                                text = { Text("Version ${BuildConfig.VERSION_NAME}") },
                                enabled = false,
                                onClick = {},
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                HomeTab.Albums -> AlbumsTab(app, nav)
                HomeTab.Artists -> ArtistsTab(app, nav)
                HomeTab.Playlists -> PlaylistsTab(app, nav)
                HomeTab.Search -> SearchTab(app, nav)
            }
        }
    }
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
    "newest" to "Neu hinzugefügt",
    "recent" to "Zuletzt gespielt",
    "frequent" to "Häufig gespielt",
    "random" to "Zufällig",
    "alphabeticalByName" to "A–Z",
    "starred" to "Favoriten",
)

@Composable
private fun AlbumsTab(app: NaviApp, nav: NavController) {
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
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(albumTypes) { (key, label) ->
                FilterChip(
                    selected = vm.type == key,
                    onClick = {
                        vm.select(key)
                        scope.launch { grid.scrollToItem(0) }
                    },
                    label = { Text(label) },
                )
            }
        }
        when {
            vm.albums.isEmpty() && vm.loading -> CenteredProgress()
            vm.albums.isEmpty() && vm.error != null -> ErrorView(vm.error!!, vm::loadMore)
            vm.albums.isEmpty() -> EmptyHint("Keine Alben gefunden")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = grid,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(vm.albums, key = { it.id }) { album ->
                    AlbumCard(album) { nav.openAlbum(album.id) }
                }
                if (vm.loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CenteredProgress(Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
                if (vm.error != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = vm::loadMore) { Text("Erneut versuchen") }
                    }
                }
            }
        }
    }
}

// ---- Künstler ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistsTab(app: NaviApp, nav: NavController) {
    val loader = rememberLoad("artists") { app.client.artists() }
    LoadContent(loader) { indexes ->
        if (indexes.isEmpty()) return@LoadContent EmptyHint("Keine Künstler gefunden")
        LazyColumn(Modifier.fillMaxSize()) {
            indexes.forEach { index ->
                stickyHeader(key = "h_${index.name}") {
                    Text(
                        index.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                items(index.artist, key = { it.id }) { artist ->
                    ListItem(
                        modifier = Modifier.clickable { nav.openArtist(artist.id) },
                        leadingContent = { CoverArt(artist.coverArt, 300, Modifier.size(48.dp).clip(CircleShape)) },
                        headlineContent = { Text(artist.name) },
                        supportingContent = { Text("${artist.albumCount} Alben") },
                    )
                }
            }
        }
    }
}

// ---- Playlists ----

@Composable
private fun PlaylistsTab(app: NaviApp, nav: NavController) {
    val loader = rememberLoad("playlists") { app.client.playlists() }
    LoadContent(loader) { playlists ->
        if (playlists.isEmpty()) return@LoadContent EmptyHint("Keine Playlists vorhanden")
        LazyColumn(Modifier.fillMaxSize()) {
            items(playlists, key = { it.id }) { pl ->
                ListItem(
                    modifier = Modifier.clickable { nav.openPlaylist(pl.id) },
                    leadingContent = {
                        CoverArt(pl.coverArt, 300, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
                    },
                    headlineContent = { Text(pl.name) },
                    supportingContent = { Text("${pl.songCount} Titel · ${formatDuration(pl.duration)}") },
                )
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
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Künstler, Alben, Titel …") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Leeren") }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (debounced.isEmpty()) {
            EmptyHint("Durchsuche deine Bibliothek")
            return@Column
        }
        val loader = rememberLoad("search:$debounced") { app.client.search(debounced) }
        LoadContent(loader) { r ->
            if (r.artist.isEmpty() && r.album.isEmpty() && r.song.isEmpty()) {
                return@LoadContent EmptyHint("Keine Treffer für „$debounced“")
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (r.artist.isNotEmpty()) {
                    item { SectionTitle("Künstler") }
                    items(r.artist, key = { "a_${it.id}" }) { artist ->
                        ListItem(
                            modifier = Modifier.clickable { nav.openArtist(artist.id) },
                            leadingContent = {
                                CoverArt(artist.coverArt, 300, Modifier.size(48.dp).clip(CircleShape))
                            },
                            headlineContent = { Text(artist.name) },
                        )
                    }
                }
                if (r.album.isNotEmpty()) {
                    item { SectionTitle("Alben") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(r.album, key = { it.id }) { album ->
                                AlbumCard(album, Modifier.width(140.dp)) { nav.openAlbum(album.id) }
                            }
                        }
                    }
                }
                if (r.song.isNotEmpty()) {
                    item { SectionTitle("Titel") }
                    itemsIndexed(r.song) { i, song ->
                        SongRow(song, onClick = { player.play(r.song, i) }, showCover = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

