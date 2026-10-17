package dev.navidroid.lidarr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.navidroid.ui.RoundIconButton
import dev.navidroid.ui.ScreenTitle
import dev.navidroid.ui.SearchField
import dev.navidroid.ui.SegmentedControl
import dev.navidroid.ui.theme.Navi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.navidroid.NaviApp
import dev.navidroid.ui.CenteredProgress
import dev.navidroid.ui.EmptyHint
import dev.navidroid.ui.ErrorView
import dev.navidroid.ui.Load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import android.widget.Toast

enum class RequestMode(val label: String) { Albums("Alben"), Artists("Künstler"), Downloads("Downloads") }

/** Offenes YouTube-Import-Blatt; ohne Album wählt man das Album nach dem Einfügen des Links. */
data class ImportTarget(val album: LidarrAlbum?, val albumId: Int? = album?.takeIf { it.inLibrary }?.id)

private enum class ImportMode(val label: String) { Search("Suchen"), Link("Link einfügen") }

class RequestsVm(private val lidarr: LidarrClient, private val helper: HelperClient) : ViewModel() {
    var mode by mutableStateOf(RequestMode.Albums)
        private set
    var query by mutableStateOf("")
        private set
    var albums by mutableStateOf<Load<List<LidarrAlbum>>?>(null)
        private set
    var artists by mutableStateOf<Load<List<LidarrArtist>>?>(null)
        private set
    var queue by mutableStateOf<Load<List<QueueItem>>?>(null)
        private set
    var helperJobs by mutableStateOf<List<HelperJob>>(emptyList())
        private set
    var importTarget by mutableStateOf<ImportTarget?>(null)
        private set
    var choiceFor by mutableStateOf<LidarrAlbum?>(null)
        private set
    var startingImport by mutableStateOf(false)
        private set

    val activeDownloads: Int
        get() = ((queue as? Load.Ok)?.value?.size ?: 0) + helperJobs.count { it.active }

    /** Laufende bzw. erledigte Anfragen, nach MusicBrainz-ID. */
    val busy = mutableStateMapOf<String, Boolean>()
    val requested = mutableStateMapOf<String, Boolean>()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var searchJob: Job? = null

    fun onQuery(q: String) {
        query = q
        search(debounceMs = 600)
    }

    fun selectMode(m: RequestMode) {
        if (m == mode) return
        mode = m
        if (m == RequestMode.Downloads) loadQueue() else search(debounceMs = 0)
    }

    fun search(debounceMs: Long = 0) {
        searchJob?.cancel()
        val q = query.trim()
        val m = mode
        if (m == RequestMode.Downloads) return
        if (q.length < 2) {
            albums = null
            artists = null
            return
        }
        searchJob = viewModelScope.launch {
            delay(debounceMs)
            when (m) {
                RequestMode.Albums -> {
                    albums = Load.Loading
                    albums = load { lidarr.searchAlbums(q) }
                }
                RequestMode.Artists -> {
                    artists = Load.Loading
                    artists = load { lidarr.searchArtists(q) }
                }
                RequestMode.Downloads -> Unit
            }
        }
    }

    fun loadQueue() {
        viewModelScope.launch {
            if (queue !is Load.Ok) queue = Load.Loading
            if (helper.configured) runCatching { helper.jobs() }.onSuccess { helperJobs = it }
            queue = load { lidarr.queue() }
        }
    }

    fun retryJob(job: HelperJob) = jobAction { helper.retryJob(job.id); messages.tryEmit("„${job.title}“ wird erneut geladen") }

    fun removeJob(job: HelperJob) = jobAction {
        helper.deleteJob(job.id)
        helperJobs = helperJobs.filter { it.id != job.id }
    }

    private fun jobAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                loadQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(e.message ?: "Aktion fehlgeschlagen")
            }
        }
    }

    fun openImport(album: LidarrAlbum?) {
        importTarget = ImportTarget(album)
    }

    /** "Anfordern" bei einem Album, das noch nicht in Lidarr ist: Quelle wählen bzw. gemerkte Wahl nutzen. */
    fun request(album: LidarrAlbum) {
        when (if (helper.configured) helper.requestSource else RequestSource.Lidarr) {
            RequestSource.Lidarr -> requestAlbum(album)
            RequestSource.YouTube -> requestViaYouTube(album)
            RequestSource.Ask -> choiceFor = album
        }
    }

    fun dismissChoice() {
        choiceFor = null
    }

    fun choose(album: LidarrAlbum, source: RequestSource, remember: Boolean) {
        choiceFor = null
        if (remember) helper.requestSource = source
        if (source == RequestSource.YouTube) requestViaYouTube(album) else requestAlbum(album)
    }

    /** Album in Lidarr anlegen, ohne dass Lidarr selbst sucht, und gleich die YouTube-Suche öffnen. */
    private fun requestViaYouTube(album: LidarrAlbum) {
        if (busy[album.foreignId] == true) return
        busy[album.foreignId] = true
        viewModelScope.launch {
            try {
                val id = lidarr.requestAlbum(album, search = false)
                importTarget = ImportTarget(album, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(e.message ?: "Album konnte nicht angelegt werden")
            } finally {
                busy.remove(album.foreignId)
            }
        }
    }

    fun closeImport() {
        importTarget = null
    }

    fun startImport(link: String, albumId: Int, videos: Boolean, mapping: List<MappingRow>) {
        if (startingImport) return
        startingImport = true
        viewModelScope.launch {
            try {
                val job = helper.startJob(link, albumId, videos, mapping)
                helperJobs = listOf(job) + helperJobs.filter { it.id != job.id }
                importTarget = null
                mode = RequestMode.Downloads
                messages.tryEmit("Import von „${job.title}“ gestartet")
                loadQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(e.message ?: "Import konnte nicht gestartet werden")
            } finally {
                startingImport = false
            }
        }
    }

    fun requestAlbum(album: LidarrAlbum) = run(album.foreignId, "„${album.title}“ angefordert") {
        lidarr.requestAlbum(album)
    }

    fun requestArtist(artist: LidarrArtist) = run(artist.foreignId, "${artist.name} hinzugefügt – Lidarr sucht die Alben") {
        lidarr.requestArtist(artist)
    }

    fun searchArtist(artist: LidarrArtist) = run(artist.foreignId, "Suche nach fehlenden Alben von ${artist.name} gestartet") {
        lidarr.searchArtist(artist)
    }

    private fun run(id: String, success: String, block: suspend () -> Unit) {
        if (busy[id] == true) return
        busy[id] = true
        viewModelScope.launch {
            try {
                block()
                requested[id] = true
                messages.tryEmit(success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(e.message ?: "Anfrage fehlgeschlagen")
            } finally {
                busy.remove(id)
            }
        }
    }

    private suspend fun <T> load(block: suspend () -> T): Load<T> = try {
        Load.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Err(e.message ?: "Fehler")
    }
}

@Composable
fun RequestsTab(app: NaviApp) {
    var configured by remember { mutableStateOf(app.lidarrStore.config != null) }
    var showSettings by remember { mutableStateOf(false) }

    if (!configured) {
        ScreenTitle("Anfragen")
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.LibraryAdd, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(16.dp))
            Text("Musik über Lidarr anfordern", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(8.dp))
            Text(
                "Verbinde deinen Lidarr-Server, um neue Künstler und Alben zu suchen und herunterladen zu lassen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = { showSettings = true }) { Text("Lidarr verbinden") }
        }
    } else {
        RequestsContent(app, onOpenSettings = { showSettings = true })
    }

    if (showSettings) {
        LidarrSettingsDialog(
            app = app,
            onDismiss = { showSettings = false },
            onChanged = { configured = app.lidarrStore.config != null },
        )
    }
}

@Composable
private fun RequestsContent(app: NaviApp, onOpenSettings: () -> Unit) {
    val vm: RequestsVm = viewModel { RequestsVm(app.lidarr, app.helper) }
    val helperReady = app.helper.configured
    val context = LocalContext.current
    var confirmArtist by remember { mutableStateOf<LidarrArtist?>(null) }

    // Suchbegriff aus anderen Screens übernehmen (z. B. fehlende Alben aus KI-Playlists)
    LaunchedEffect(dev.navidroid.ui.RequestSearchBus.pendingQuery) {
        dev.navidroid.ui.RequestSearchBus.pendingQuery?.let { q ->
            vm.selectMode(RequestMode.Albums)
            vm.onQuery(q)
            dev.navidroid.ui.RequestSearchBus.pendingQuery = null
        }
    }
    LaunchedEffect(vm) {
        if (vm.queue == null) vm.loadQueue()
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // Downloads automatisch aktualisieren, solange die Ansicht offen ist.
    LaunchedEffect(vm.mode) {
        while (vm.mode == RequestMode.Downloads) {
            vm.loadQueue()
            delay(10_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Anfragen") {
            RoundIconButton(Icons.Rounded.Tune, "Lidarr-Einstellungen", onOpenSettings)
        }
        SegmentedControl(
            options = RequestMode.entries.map { it to it.label },
            selected = vm.mode,
            onSelect = vm::selectMode,
            badge = { m -> if (m == RequestMode.Downloads) vm.activeDownloads else 0 },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        if (vm.mode == RequestMode.Downloads) {
            QueueList(vm)
        } else {
            SearchField(
                value = vm.query,
                onValueChange = vm::onQuery,
                placeholder = if (vm.mode == RequestMode.Albums) "Album bei Lidarr suchen" else "Künstler bei Lidarr suchen",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (vm.mode == RequestMode.Albums) {
                SearchResults(
                    vm.albums,
                    onRetry = { vm.search() },
                    empty = {
                        Column(Modifier.fillMaxSize()) {
                            EmptyHint("Keine Treffer")
                        }
                    },
                    emptyFooter = if (helperReady) ({ PasteLinkButton { vm.openImport(null) } }) else null,
                ) { list ->
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(list, key = { it.foreignId.ifEmpty { it.title } }) { album ->
                            AlbumResult(vm, album, helperReady)
                        }
                        if (helperReady) item { PasteLinkButton { vm.openImport(null) } }
                    }
                }
            } else {
                SearchResults(vm.artists, onRetry = { vm.search() }) { list ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.foreignId.ifEmpty { it.name } }) { artist ->
                            ArtistResult(vm, artist, onRequest = { confirmArtist = artist })
                        }
                    }
                }
            }
        }
    }

    vm.importTarget?.let { target -> YouTubeImportSheet(app, vm, target) }
    vm.choiceFor?.let { album -> RequestChoiceSheet(album, vm) }

    confirmArtist?.let { artist ->
        AlertDialog(
            onDismissRequest = { confirmArtist = null },
            title = { Text("${artist.name} anfordern?") },
            text = { Text("Lidarr fügt den Künstler hinzu und lädt alle Alben laut deinem Metadaten-Profil herunter. Für einzelne Alben nutze die Album-Suche.") },
            confirmButton = {
                TextButton(onClick = { vm.requestArtist(artist); confirmArtist = null }) { Text("Alle Alben anfordern") }
            },
            dismissButton = { TextButton(onClick = { confirmArtist = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun <T> SearchResults(
    state: Load<List<T>>?,
    onRetry: () -> Unit,
    empty: @Composable () -> Unit = { EmptyHint("Keine Treffer") },
    emptyFooter: (@Composable () -> Unit)? = null,
    content: @Composable (List<T>) -> Unit,
) {
    when (state) {
        null -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { EmptyHint("Suche bei MusicBrainz über Lidarr") }
            emptyFooter?.invoke()
        }
        Load.Loading -> CenteredProgress()
        is Load.Err -> ErrorView(state.message, onRetry)
        is Load.Ok -> if (state.value.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { empty() }
                emptyFooter?.invoke()
            }
        } else content(state.value)
    }
}

@Composable
private fun PasteLinkButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Navi.Outline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(48.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Link, null, tint = Color(0xFFC9CDD4), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Nicht dabei? YouTube-Link einfügen", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4))
        }
    }
}

@Composable
private fun RemoteCover(url: String?, modifier: Modifier) {
    Box(modifier.background(Navi.Surface2), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.LibraryAdd, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

@Composable
private fun AlbumResult(vm: RequestsVm, album: LidarrAlbum, helperReady: Boolean) {
    Column {
    ListItem(
        leadingContent = {
            RemoteCover(
                album.imageUrl,
                Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)),
            )
        },
        headlineContent = { Text(album.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(album.artistName.takeIf { it.isNotEmpty() }, album.year, album.type).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            RequestAction(
                busy = vm.busy[album.foreignId] == true,
                done = vm.requested[album.foreignId] == true,
                doneLabel = "Angefordert",
                existingLabel = when {
                    album.complete -> "Vorhanden"
                    else -> null
                },
                actionLabel = if (album.inLibrary) "Suchen" else "Anfordern",
                onAction = { if (album.inLibrary) vm.requestAlbum(album) else vm.request(album) },
            )
        },
    )
    if (helperReady && album.inLibrary && !album.complete) {
        MissingSourceCard(
            onYouTube = { vm.openImport(album) },
            onRetry = { vm.requestAlbum(album) },
        )
    }
    }
}

/** Hinweis bei Alben, die in Lidarr stehen, aber noch fehlen – mit dem Weg über YouTube. */
@Composable
private fun MissingSourceCard(onYouTube: () -> Unit, onRetry: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Navi.Surface)
            .border(1.dp, Navi.Outline, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Navi.Outline), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Column {
                Text("Noch nicht heruntergeladen", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.ExtraBold))
                Text("Findet Lidarr keine Quelle?", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
            }
        }
        Text(
            "Du kannst das Album aus einer YouTube-Playlist holen. Es wird als MP3 in deinen Lidarr-Ordner gelegt und automatisch erkannt.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFC9CDD4),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(onClick = onYouTube, shape = RoundedCornerShape(26.dp), color = accent, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SmartDisplay, null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Von YouTube holen", style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Bei Lidarr erneut suchen", color = Color(0xFFC9CDD4))
            }
        }
    }
}

@Composable
private fun ArtistResult(vm: RequestsVm, artist: LidarrArtist, onRequest: () -> Unit) {
    ListItem(
        leadingContent = { RemoteCover(artist.imageUrl, Modifier.size(60.dp).clip(CircleShape)) },
        headlineContent = { Text(artist.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val info = listOfNotNull(
                if (artist.inLibrary) "In Lidarr" else null,
                artist.type,
                artist.disambiguation,
            ).joinToString(" · ")
            if (info.isNotEmpty()) Text(info, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            RequestAction(
                busy = vm.busy[artist.foreignId] == true,
                done = vm.requested[artist.foreignId] == true,
                doneLabel = if (artist.inLibrary) "Gestartet" else "Angefordert",
                existingLabel = null,
                actionLabel = if (artist.inLibrary) "Suchen" else "Anfordern",
                onAction = { if (artist.inLibrary) vm.searchArtist(artist) else onRequest() },
            )
        },
    )
}

@Composable
private fun RequestAction(
    busy: Boolean,
    done: Boolean,
    doneLabel: String,
    existingLabel: String?,
    actionLabel: String,
    onAction: () -> Unit,
) {
    when {
        busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        done || existingLabel != null -> {
            val color = if (done) MaterialTheme.colorScheme.primary else Navi.Muted
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Navi.Surface).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = color)
                Spacer(Modifier.size(6.dp))
                Text(if (done) doneLabel else existingLabel.orEmpty(), style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
        else -> Surface(
            onClick = onAction,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(36.dp),
        ) {
            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun QueueList(vm: RequestsVm) {
    val jobs = vm.helperJobs
    when (val q = vm.queue) {
        null, Load.Loading -> if (jobs.isEmpty()) CenteredProgress() else DownloadList(vm, jobs, emptyList())
        is Load.Err -> if (jobs.isEmpty()) ErrorView(q.message, vm::loadQueue) else DownloadList(vm, jobs, emptyList())
        is Load.Ok -> if (q.value.isEmpty() && jobs.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Keine aktiven Downloads", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = vm::loadQueue) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Aktualisieren")
                }
            }
        } else {
            DownloadList(vm, jobs, q.value)
        }
    }
}

@Composable
private fun DownloadList(vm: RequestsVm, jobs: List<HelperJob>, queue: List<QueueItem>) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(jobs.filter { it.status != "done" }, key = { "job_${it.id}" }) { job ->
            HelperJobCard(job, onRetry = { vm.retryJob(job) }, onRemove = { vm.removeJob(job) })
        }
        items(queue) { item ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Navi.Surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LIDARR · ${item.status.uppercase()}", style = MaterialTheme.typography.labelSmall, color = Navi.Muted, modifier = Modifier.weight(1f))
                    item.timeLeft?.let { Text("noch $it", style = MaterialTheme.typography.bodySmall, color = Navi.Muted) }
                }
                Text(
                    listOfNotNull(item.title, item.artist).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.progress?.let { p -> ProgressLine(p) }
                item.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
        items(jobs.filter { it.status == "done" }.take(5), key = { "done_${it.id}" }) { job ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, Navi.Surface2, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Navi.Surface2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Importiert · ${job.artist}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Navi.Outline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun HelperJobCard(job: HelperJob, onRetry: () -> Unit, onRemove: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Navi.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(job.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = job.steps.firstOrNull()?.total ?: 0
                Text("${job.artist} · $count Titel", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(Navi.Outline).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.SmartDisplay, null, tint = Color(0xFFC9CDD4), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("YOUTUBE", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing / 2), color = Color(0xFFC9CDD4))
            }
        }
        if (job.status == "queued") {
            Text("Wartet auf den Start …", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
        }
        job.steps.forEach { step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val dotModifier = Modifier.size(24.dp).clip(CircleShape)
                when (step.state) {
                    "done" -> Box(dotModifier.background(accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                    }
                    "failed" -> Box(dotModifier.background(MaterialTheme.colorScheme.error), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, null, tint = Navi.Bg, modifier = Modifier.size(14.dp))
                    }
                    "active" -> Box(dotModifier.border(2.dp, accent, CircleShape))
                    else -> Box(dotModifier.border(2.dp, Color(0xFF3A3F48), CircleShape))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(
                            step.label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (step.state == "todo") Navi.Faint else Navi.Text,
                            modifier = Modifier.weight(1f),
                        )
                        if (step.total > 1 && step.state != "todo") {
                            Text("${step.done}/${step.total}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                        }
                    }
                    if (step.state == "active" && step.total > 1) ProgressLine(step.done.toFloat() / step.total)
                }
            }
        }
        if (job.skipped.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${job.skipped.size} Titel übersprungen:", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFFFFB86B))
                job.skipped.take(5).forEach { s ->
                    Text("• ${s.title} – ${s.reason}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        job.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (job.status == "failed") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(onClick = onRetry, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).height(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Erneut versuchen", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Surface(onClick = onRemove, shape = RoundedCornerShape(20.dp), color = Color.Transparent, border = BorderStroke(1.dp, Navi.Outline), modifier = Modifier.height(40.dp)) {
                    Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text("Entfernen", style = MaterialTheme.typography.labelLarge, color = Navi.Text)
                    }
                }
            }
        }
    }
}

// ---- Quelle wählen ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestChoiceSheet(album: LidarrAlbum, vm: RequestsVm) {
    var remember by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = vm::dismissChoice, containerColor = Navi.Surface) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteCover(album.imageUrl, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                Column {
                    Text("ANFORDERN", style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
                    Text(album.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(album.artistName, album.year).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Navi.Muted)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceOption(
                    icon = Icons.Rounded.Download,
                    title = "Über Lidarr",
                    text = "Sucht bei deinen Indexern, beste Qualität (z. B. FLAC). Kann dauern.",
                    highlighted = true,
                ) { vm.choose(album, RequestSource.Lidarr, remember) }
                SourceOption(
                    icon = Icons.Rounded.SmartDisplay,
                    title = "Direkt auf YouTube suchen",
                    text = "Sofort verfügbar, als MP3 320 kbit/s. Lidarr erkennt das Album danach.",
                    highlighted = false,
                ) { vm.choose(album, RequestSource.YouTube, remember) }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { remember = !remember }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = remember, onCheckedChange = { remember = it })
                Text("Auswahl merken und nicht mehr fragen", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4))
            }
        }
    }
}

@Composable
private fun SourceOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, highlighted: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = Navi.Surface2, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (highlighted) accent else Navi.Outline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (highlighted) MaterialTheme.colorScheme.onPrimary else accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.ExtraBold))
                Text(text, style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Navi.Faint)
        }
    }
}

// ---- YouTube-Import ----

private val bracketNoise = Regex("""[\(\[][^\)\]]*[\)\]]""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YouTubeImportSheet(app: NaviApp, vm: RequestsVm, target: ImportTarget) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val accent = MaterialTheme.colorScheme.primary
    var link by rememberSaveable { mutableStateOf("") }
    var albumId by remember { mutableStateOf(target.albumId) }
    var mode by remember { mutableStateOf(if (target.albumId != null) ImportMode.Search else ImportMode.Link) }
    val initialQuery = target.album?.let { "${it.artistName} ${it.title}".trim() }.orEmpty()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var submitted by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<Load<List<SearchHit>>?>(null) }
    LaunchedEffect(submitted, albumId, mode) {
        if (mode != ImportMode.Search || albumId == null || submitted.isBlank()) return@LaunchedEffect
        results = Load.Loading
        results = try {
            Load.Ok(app.helper.search(submitted, albumId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Err(e.message ?: "YouTube-Suche fehlgeschlagen")
        }
    }
    var preview by remember { mutableStateOf<Load<HelperPreview>?>(null) }
    var candidates by remember { mutableStateOf<Load<List<LidarrAlbum>>?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var videos by remember { mutableStateOf(app.helper.videosEnabled) }

    LaunchedEffect(link, albumId) {
        val url = link.trim()
        if (url.isEmpty()) {
            preview = null
            return@LaunchedEffect
        }
        delay(500)
        preview = Load.Loading
        preview = try {
            Load.Ok(app.helper.preview(url, albumId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Err(e.message ?: "Link konnte nicht gelesen werden")
        }
    }
    // Ohne Album: passende Alben anhand des Playlist-Titels vorschlagen.
    val playlistTitle = (preview as? Load.Ok)?.value?.playlist?.title
    LaunchedEffect(playlistTitle, albumId) {
        if (albumId != null || playlistTitle.isNullOrBlank()) return@LaunchedEffect
        candidates = Load.Loading
        candidates = try {
            Load.Ok(app.lidarr.searchAlbums(bracketNoise.replace(playlistTitle, " ").trim()).take(5))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Err(e.message ?: "Lidarr-Suche fehlgeschlagen")
        }
    }

    ModalBottomSheet(
        onDismissRequest = vm::closeImport,
        containerColor = Navi.Surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    Text("Von YouTube importieren", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
                    target.album?.let {
                        Text("Für „${it.title}“ · ${it.artistName}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                    }
                }
            }
            if (albumId != null) {
                item {
                    SegmentedControl(
                        options = ImportMode.entries.map { it to it.label },
                        selected = mode,
                        onSelect = { mode = it },
                    )
                }
            }
            if (mode == ImportMode.Search && albumId != null) {
                item {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Künstler und Album",
                    )
                    Surface(
                        onClick = { submitted = query.trim() },
                        enabled = query.isNotBlank(),
                        shape = RoundedCornerShape(22.dp),
                        color = Navi.Surface2,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Auf YouTube suchen", style = MaterialTheme.typography.labelLarge, color = Navi.Text)
                        }
                    }
                }
                when (val r = results) {
                    null -> Unit
                    Load.Loading -> item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Suche Playlists und Alben-Videos …", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    is Load.Err -> item { Text(r.message, color = MaterialTheme.colorScheme.error) }
                    is Load.Ok -> {
                        if (r.value.isEmpty()) item {
                            Text("Nichts Passendes gefunden. Probier einen anderen Suchbegriff oder füge einen Link ein.", color = Navi.Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                        itemsIndexed(r.value, key = { _, h -> h.url }) { i, hit ->
                            SearchHitCard(hit, best = i == 0 && hit.matched > 0) {
                                link = hit.url
                                mode = ImportMode.Link
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Nur Inhalte importieren, die du nutzen darfst.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Navi.Faint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
            item {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Playlist-Link") },
                    placeholder = { Text("https://www.youtube.com/playlist?list=…") },
                    leadingIcon = { Icon(Icons.Rounded.Link, null) },
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.let { link = it.trim() } }) {
                            Icon(Icons.Rounded.ContentPaste, "Einfügen")
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when (val p = preview) {
                null -> item {
                    Text(
                        "Füge den Link einer YouTube-Playlist (oder eines einzelnen Videos) ein.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Navi.Muted,
                    )
                }
                Load.Loading -> item { CenteredProgress(Modifier.fillMaxWidth().padding(24.dp)) }
                is Load.Err -> item { Text(p.message, color = MaterialTheme.colorScheme.error) }
                is Load.Ok -> {
                    val pv = p.value
                    item { PlaylistCard(pv.playlist) }
                    if (albumId == null) {
                        item { Text("Zu welchem Album gehört das?", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)) }
                        when (val c = candidates) {
                            null, Load.Loading -> item { CenteredProgress(Modifier.fillMaxWidth().padding(16.dp)) }
                            is Load.Err -> item { Text(c.message, color = MaterialTheme.colorScheme.error) }
                            is Load.Ok -> {
                                if (c.value.isEmpty()) item {
                                    Text("Kein passendes Album bei MusicBrainz gefunden. Suche es oben im Alben-Tab und starte den Import von dort.", color = Navi.Muted, style = MaterialTheme.typography.bodyMedium)
                                }
                                items(c.value, key = { "cand_${it.foreignId}" }) { cand ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable(enabled = !choosing) {
                                                if (cand.inLibrary) {
                                                    albumId = cand.id
                                                } else {
                                                    choosing = true
                                                    scope.launch {
                                                        try {
                                                            albumId = app.lidarr.requestAlbum(cand)
                                                        } catch (e: CancellationException) {
                                                            throw e
                                                        } catch (e: Exception) {
                                                            vm.messages.tryEmit(e.message ?: "Album konnte nicht angelegt werden")
                                                        } finally {
                                                            choosing = false
                                                        }
                                                    }
                                                }
                                            }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        RemoteCover(cand.imageUrl, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
                                        Column(Modifier.weight(1f)) {
                                            Text(cand.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(listOfNotNull(cand.artistName, cand.year, cand.type).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (choosing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        else Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Navi.Muted)
                                    }
                                }
                            }
                        }
                    } else if (pv.album != null) {
                        val matched = pv.mapping.count { it.videoId != null }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Zuordnung zu ${pv.album.title}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$matched von ${pv.mapping.size} erkannt", style = MaterialTheme.typography.labelMedium, color = accent)
                            }
                            if (target.album == null) {
                                TextButton(onClick = { albumId = null }, contentPadding = PaddingValues(0.dp)) { Text("Anderes Album wählen") }
                            }
                        }
                        items(pv.mapping, key = { "map_${it.trackId}" }) { row ->
                            MappingLine(
                                ok = row.videoId != null,
                                title = row.title,
                                detail = if (row.videoId != null) "Video ${row.videoIndex} · „${row.videoTitle}“" else "Kein passendes Video – wird ausgelassen",
                            )
                        }
                        items(pv.unmatchedVideos, key = { "skip_${it.id}" }) { v ->
                            MappingLine(ok = false, title = "Wird übersprungen", detail = "Video ${v.index} · „${v.title}“ passt zu keinem Titel", muted = true)
                        }
                        item {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Navi.Surface2).padding(horizontal = 14.dp, vertical = 4.dp)) {
                                SettingLine("Format") { Text("MP3 · 320 kbit/s", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)) }
                                HorizontalDivider(color = Navi.Outline)
                                SettingLine("Ziel") {
                                    Text(
                                        pv.targetFolder.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                HorizontalDivider(color = Navi.Outline)
                                Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Musikvideos mitladen", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4))
                                        Text("Im Video-Ordner des Helfers, abspielbar im Player", style = MaterialTheme.typography.bodySmall, color = Navi.Faint)
                                    }
                                    Switch(checked = videos, onCheckedChange = { videos = it })
                                }
                            }
                        }
                        item {
                            Surface(
                                onClick = { vm.startImport(link, pv.album.id, videos, pv.mapping) },
                                enabled = matched > 0 && !vm.startingImport,
                                shape = RoundedCornerShape(26.dp),
                                color = if (matched > 0) accent else Navi.Outline,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    if (vm.startingImport) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    } else {
                                        Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.onPrimary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (matched == 1) "1 Titel importieren" else "$matched Titel importieren",
                                            style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.ExtraBold),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    }
                                }
                            }
                            Text(
                                "Nur Inhalte importieren, die du nutzen darfst.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Navi.Faint,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun SearchHitCard(hit: SearchHit, best: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (best) Navi.Surface2 else Color.Transparent,
        border = BorderStroke(1.dp, if (best) accent else Navi.Outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(width = 104.dp, height = 58.dp).clip(RoundedCornerShape(8.dp)).background(Navi.Outline)) {
                hit.thumbnail?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        if (hit.kind == "chapters") "${hit.count} Kapitel" else "${hit.count} Videos",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing),
                        color = Color.White,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(hit.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (hit.kind == "chapters") "Video mit Kapiteln" else "Playlist",
                        hit.uploader,
                        if (hit.durationSec > 0) "${hit.durationSec / 60} Min." else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Navi.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hit.total > 0) {
                    val label = buildString {
                        if (best) append("Beste Übereinstimmung · ")
                        append("${hit.matched}/${hit.total} Titel")
                        if (hit.extra > 0) append(" · ${hit.extra} übrig")
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(11.dp)).background(if (best) accent else Navi.Outline).padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing),
                            color = if (best) MaterialTheme.colorScheme.onPrimary else Color(0xFFC9CDD4),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(playlist: PlaylistInfo) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Navi.Surface2).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)).background(Navi.Outline)) {
            playlist.thumbnail?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    playlist.uploader,
                    if (playlist.count == 1) "1 Video" else "${playlist.count} Videos",
                    if (playlist.durationSec > 0) "${playlist.durationSec / 60} Min." else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Navi.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MappingLine(ok: Boolean, title: String, detail: String, muted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (ok) MaterialTheme.colorScheme.primary else Navi.Outline),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ok) Icons.Rounded.Check else Icons.Rounded.Remove,
                null,
                tint = if (ok) MaterialTheme.colorScheme.onPrimary else Navi.Muted,
                modifier = Modifier.size(14.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = if (muted) Navi.Muted else Navi.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing, fontWeight = FontWeight.Medium), color = Navi.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SettingLine(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

// ---- Einstellungen ----

@Composable
fun LidarrSettingsDialog(app: NaviApp, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val existing = app.lidarrStore.config
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var apiKey by remember { mutableStateOf(existing?.apiKey.orEmpty()) }
    var options by remember { mutableStateOf<LidarrClient.ServerOptions?>(null) }
    var quality by remember { mutableStateOf(existing?.qualityProfileId) }
    var metadata by remember { mutableStateOf(existing?.metadataProfileId) }
    var root by remember { mutableStateOf(existing?.rootFolderPath) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val helperExisting = app.helperStore.config
    var helperUrl by remember { mutableStateOf(helperExisting?.url.orEmpty()) }
    var helperToken by remember { mutableStateOf(helperExisting?.token.orEmpty()) }
    var helperVideos by remember { mutableStateOf(helperExisting?.videos ?: false) }
    var helperStatus by remember { mutableStateOf<String?>(null) }
    var helperChecking by remember { mutableStateOf(false) }

    fun connect() {
        busy = true
        error = null
        scope.launch {
            try {
                val o = app.lidarr.connect(LidarrStore.normalizeUrl(url), apiKey.trim())
                if (o.rootFolders.isEmpty()) throw LidarrException("In Lidarr ist kein Stammordner eingerichtet")
                options = o
                if (o.qualityProfiles.none { it.value == quality }) quality = o.qualityProfiles.firstOrNull()?.value
                if (o.metadataProfiles.none { it.value == metadata }) metadata = o.metadataProfiles.firstOrNull()?.value
                if (o.rootFolders.none { it.value == root }) root = o.rootFolders.first().value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                options = null
                error = e.message ?: "Verbindung fehlgeschlagen"
            } finally {
                busy = false
            }
        }
    }

    // Bei bestehender Konfiguration direkt die Auswahl laden.
    LaunchedEffect(Unit) { if (existing != null) connect() }

    val o = options
    val canSave = o != null && quality != null && metadata != null && root != null && !busy

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lidarr") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; options = null },
                    label = { Text("Server-URL") },
                    placeholder = { Text("http://192.168.1.10:8686") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; options = null },
                    label = { Text("API-Key") },
                    supportingText = { Text("Lidarr → Settings → General → Security") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (o == null) {
                    OutlinedButton(
                        onClick = { connect() },
                        enabled = !busy && url.isNotBlank() && apiKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Verbindung prüfen")
                    }
                } else {
                    Text(
                        "Verbunden mit Lidarr ${o.version}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OptionPicker("Qualitätsprofil", o.qualityProfiles, quality) { quality = it }
                    OptionPicker("Metadatenprofil", o.metadataProfiles, metadata) { metadata = it }
                    OptionPicker("Stammordner", o.rootFolders, root) { root = it }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("YouTube-Helfer (optional)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text(
                    "Docker-Dienst auf deinem Server, der fehlende Alben von YouTube holt. Einrichtung: siehe README im GitHub-Repo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Navi.Muted,
                )
                OutlinedTextField(
                    value = helperUrl,
                    onValueChange = { helperUrl = it; helperStatus = null },
                    label = { Text("Helfer-URL") },
                    placeholder = { Text("http://192.168.1.10:8787") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = helperToken,
                    onValueChange = { helperToken = it; helperStatus = null },
                    label = { Text("Token") },
                    supportingText = { Text("HELPER_TOKEN aus der docker-compose.yml") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Musikvideos im Player", modifier = Modifier.weight(1f))
                    Switch(checked = helperVideos, onCheckedChange = { helperVideos = it })
                }
                var source by remember { mutableStateOf(app.helper.requestSource) }
                Column {
                    Text("Beim Anfordern", style = MaterialTheme.typography.labelMedium, color = Navi.Muted)
                    SegmentedControl(
                        options = listOf(RequestSource.Ask to "Fragen", RequestSource.Lidarr to "Lidarr", RequestSource.YouTube to "YouTube"),
                        selected = source,
                        onSelect = { source = it; app.helper.requestSource = it },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        helperChecking = true
                        scope.launch {
                            helperStatus = try {
                                val h = app.helper.health(helperUrl, helperToken)
                                "Helfer ${h.version} · yt-dlp ${h.ytDlp} · " +
                                    (h.lidarr?.let { "Lidarr $it verbunden" } ?: "Lidarr nicht erreichbar: ${h.lidarrError}")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                "Fehler: ${e.message}"
                            } finally {
                                helperChecking = false
                            }
                        }
                    },
                    enabled = !helperChecking && helperUrl.isNotBlank() && helperToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (helperChecking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Helfer prüfen")
                }
                helperStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Fehler")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    app.lidarrStore.save(
                        LidarrConfig(LidarrStore.normalizeUrl(url), apiKey.trim(), quality!!, metadata!!, root!!)
                    )
                    if (helperUrl.isNotBlank() && helperToken.isNotBlank()) {
                        app.helperStore.save(HelperConfig(LidarrStore.normalizeUrl(helperUrl), helperToken.trim(), helperVideos))
                    } else {
                        app.helperStore.clear()
                    }
                    onChanged()
                    onDismiss()
                },
            ) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = { app.lidarrStore.clear(); onChanged(); onDismiss() }) {
                        Text("Trennen", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

@Composable
private fun <T> OptionPicker(label: String, options: List<Option<T>>, selected: T?, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    options.firstOrNull { it.value == selected }?.label ?: "Auswählen",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(text = { Text(opt.label) }, onClick = { onSelect(opt.value); open = false })
                }
            }
        }
    }
}
