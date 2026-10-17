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

class RequestsVm(private val lidarr: LidarrClient) : ViewModel() {
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
            queue = load { lidarr.queue() }
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
    val vm: RequestsVm = viewModel { RequestsVm(app.lidarr) }
    val context = LocalContext.current
    var confirmArtist by remember { mutableStateOf<LidarrArtist?>(null) }

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
            badge = { m -> if (m == RequestMode.Downloads) ((vm.queue as? Load.Ok)?.value?.size ?: 0) else 0 },
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
                SearchResults(vm.albums, onRetry = { vm.search() }) { list ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.foreignId.ifEmpty { it.title } }) { album -> AlbumResult(vm, album) }
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
private fun <T> SearchResults(state: Load<List<T>>?, onRetry: () -> Unit, content: @Composable (List<T>) -> Unit) {
    when (state) {
        null -> EmptyHint("Suche bei MusicBrainz über Lidarr")
        Load.Loading -> CenteredProgress()
        is Load.Err -> ErrorView(state.message, onRetry)
        is Load.Ok -> if (state.value.isEmpty()) EmptyHint("Keine Treffer") else content(state.value)
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
private fun AlbumResult(vm: RequestsVm, album: LidarrAlbum) {
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
                onAction = { vm.requestAlbum(album) },
            )
        },
    )
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
                Text(actionLabel, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = Navi.OnAccent)
            }
        }
    }
}

@Composable
private fun QueueList(vm: RequestsVm) {
    when (val q = vm.queue) {
        null, Load.Loading -> CenteredProgress()
        is Load.Err -> ErrorView(q.message, vm::loadQueue)
        is Load.Ok -> if (q.value.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Keine aktiven Downloads", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = vm::loadQueue) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Aktualisieren")
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(q.value) { item ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Navi.Surface).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.status.uppercase(), style = MaterialTheme.typography.labelSmall, color = Navi.Muted, modifier = Modifier.weight(1f))
                            item.timeLeft?.let { Text("noch $it", style = MaterialTheme.typography.bodySmall, color = Navi.Muted) }
                        }
                        Text(
                            listOfNotNull(item.title, item.artist).joinToString(" · "),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.progress?.let { p ->
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Navi.Outline)) {
                                Box(Modifier.fillMaxWidth(p).height(6.dp).background(MaterialTheme.colorScheme.primary))
                            }
                        }
                        item.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        }
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    app.lidarrStore.save(
                        LidarrConfig(LidarrStore.normalizeUrl(url), apiKey.trim(), quality!!, metadata!!, root!!)
                    )
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
