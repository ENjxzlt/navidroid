package dev.navidroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.navidroid.lidarr.HelperConfig
import dev.navidroid.lidarr.HelperJob
import dev.navidroid.lidarr.HelperPreview
import dev.navidroid.lidarr.LidarrAlbum
import dev.navidroid.lidarr.LidarrArtist
import dev.navidroid.lidarr.LidarrConfig
import dev.navidroid.lidarr.LidarrStore
import dev.navidroid.lidarr.ManualPreview
import dev.navidroid.lidarr.ManualTrack
import dev.navidroid.lidarr.MappingRow
import dev.navidroid.lidarr.PlaylistInfo
import dev.navidroid.lidarr.QueueItem
import dev.navidroid.lidarr.RequestSource
import dev.navidroid.lidarr.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Laufende Downloads (Lidarr + Helfer) für das Abzeichen in der Seitenleiste. */
@Composable
fun rememberActiveDownloads(app: DesktopApp): Int {
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(app.version) {
        while (true) {
            val lidarr = if (app.lidarrStore.config != null) runCatching { app.lidarr.queue().count { it.error == null } }.getOrDefault(0) else 0
            val jobs = if (app.helper.configured) runCatching { app.helper.jobs().count { it.active } }.getOrDefault(0) else 0
            n = lidarr + jobs
            delay(15_000)
        }
    }
    return n
}

/** Stand je Anfrage: false = läuft, true = angefordert. */
private val requested = mutableStateMapOf<String, Boolean>()

/** Offener YouTube-Import; ohne Album wählt man das Album nach dem Einfügen des Links. */
private data class ImportTarget(val album: LidarrAlbum?, val albumId: Int? = album?.takeIf { it.inLibrary }?.id, val link: String = "")

/** Gemeinsamer Zustand der Anfragen-Seite (Dialoge, Helfer-Jobs, Meldungen) – wie das RequestsVm der App. */
private object Req {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var jobs by mutableStateOf<List<HelperJob>>(emptyList())
    var importTarget by mutableStateOf<ImportTarget?>(null)
    var choiceFor by mutableStateOf<LidarrAlbum?>(null)
    var manualPrefill by mutableStateOf<String?>(null)
    var startingImport by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    suspend fun loadJobs(app: DesktopApp) {
        if (app.helper.configured) runCatching { app.helper.jobs() }.onSuccess { jobs = it }
    }

    /** „Anfordern“ bei einem Album, das noch nicht in Lidarr ist: Quelle wählen bzw. gemerkte Wahl nutzen. */
    fun request(app: DesktopApp, album: LidarrAlbum) {
        when (if (app.helper.configured) app.helperStore.requestSource else RequestSource.Lidarr) {
            RequestSource.Lidarr -> requestAlbum(app, album)
            RequestSource.YouTube -> requestViaYouTube(app, album)
            RequestSource.Ask -> choiceFor = album
        }
    }

    fun choose(app: DesktopApp, album: LidarrAlbum, source: RequestSource, remember: Boolean) {
        choiceFor = null
        if (remember) app.helperStore.requestSource = source
        if (source == RequestSource.YouTube) requestViaYouTube(app, album) else requestAlbum(app, album)
    }

    fun requestAlbum(app: DesktopApp, a: LidarrAlbum) {
        if (requested[a.foreignId] == false) return
        requested[a.foreignId] = false
        scope.launch {
            runCatching { app.lidarr.requestAlbum(a) }
                .onSuccess { requested[a.foreignId] = true; message = "„${a.title}“ angefordert"; app.version++ }
                .onFailure { requested.remove(a.foreignId); message = it.message ?: "Anfrage fehlgeschlagen" }
        }
    }

    /** Album in Lidarr anlegen, ohne dass Lidarr selbst sucht, und gleich den YouTube-Import öffnen. */
    private fun requestViaYouTube(app: DesktopApp, album: LidarrAlbum) {
        if (requested[album.foreignId] == false) return
        requested[album.foreignId] = false
        scope.launch {
            try {
                val id = app.lidarr.requestAlbum(album, search = false)
                importTarget = ImportTarget(album, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Album konnte nicht angelegt werden"
            } finally {
                requested.remove(album.foreignId)
            }
        }
    }

    fun startImport(app: DesktopApp, link: String, albumId: Int, videos: Boolean, mapping: List<MappingRow>) =
        start(app, { importTarget = null }) { app.helper.startJob(link, albumId, videos, mapping) }

    fun startManualImport(app: DesktopApp, url: String, artist: String, album: String, year: Int?, thumbnail: String?, tracks: List<ManualTrack>) =
        start(app, { manualPrefill = null }) { app.helper.startManualJob(url, artist, album, year, thumbnail, tracks) }

    private fun start(app: DesktopApp, close: () -> Unit, block: suspend () -> HelperJob) {
        if (startingImport) return
        startingImport = true
        scope.launch {
            try {
                val job = block()
                jobs = listOf(job) + jobs.filter { it.id != job.id }
                close()
                message = "Import von „${job.title}“ gestartet"
                app.version++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Import konnte nicht gestartet werden"
            } finally {
                startingImport = false
            }
        }
    }

    fun retryJob(app: DesktopApp, job: HelperJob) = jobAction(app) { app.helper.retryJob(job.id); message = "„${job.title}“ wird erneut geladen" }

    /** Entfernt nur den Job-Eintrag beim Helfer (wie in der App), nicht die Musik. */
    fun removeJob(app: DesktopApp, job: HelperJob) = jobAction(app) {
        app.helper.deleteJob(job.id)
        jobs = jobs.filter { it.id != job.id }
    }

    private fun jobAction(app: DesktopApp, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                loadJobs(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Aktion fehlgeschlagen"
            }
        }
    }
}

/** Text aus der System-Zwischenablage. */
private fun clipboardText(): String? = runCatching {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
}.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

private fun isYouTubeLink(s: String) = Regex("""^https?://([a-z0-9-]+\.)*(youtube\.com|youtu\.be)/""", RegexOption.IGNORE_CASE).containsMatchIn(s)

/** „Link einfügen“: YouTube-Link aus der Zwischenablage übernehmen und den Import öffnen. */
private fun pasteLink() {
    val text = clipboardText()
    if (text != null && isYouTubeLink(text)) {
        Req.importTarget = ImportTarget(null, link = text)
    } else {
        Req.importTarget = ImportTarget(null)
        Req.message = "Kein YouTube-Link in der Zwischenablage – füge ihn im Dialog ein."
    }
}

@Composable
fun RequestsScreen(app: DesktopApp) {
    if (app.lidarrStore.config == null) {
        Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Musik über Lidarr anfordern", style = MaterialTheme.typography.headlineSmall)
            Text("Verbinde deinen Lidarr-Server in den Einstellungen.", color = Navi.Muted, modifier = Modifier.padding(8.dp))
            Button(onClick = { app.showSettings = true }) { Text("Einstellungen öffnen") }
        }
        return
    }
    val helperReady = app.helper.configured
    var artists by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var confirmArtist by remember { mutableStateOf<LidarrArtist?>(null) }
    LaunchedEffect(query) { delay(500); debounced = query.trim() }
    LaunchedEffect(Req.message) { if (Req.message != null) { delay(3_500); Req.message = null } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 64.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Anfragen", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                if (helperReady) {
                    HeaderButton(Icons.Rounded.Link, "Link einfügen", ::pasteLink)
                    HeaderButton(Icons.Rounded.Upload, "Ohne Lidarr importieren") { Req.manualPrefill = query.trim() }
                }
                Chip("Alben", !artists) { artists = false }
                Chip("Künstler", artists) { artists = true }
            }
            Row(Modifier.fillMaxSize().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        query, { query = it },
                        placeholder = { Text(if (artists) "Künstler bei Lidarr suchen" else "Album bei Lidarr suchen") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
                    )
                    if (debounced.isNotEmpty()) {
                        if (artists) {
                            val res = rememberLoad("lidarr-artists:$debounced") { app.lidarr.searchArtists(debounced) }
                            LoadBox(res) { list -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(list, key = { it.foreignId.ifEmpty { it.name } }) { a -> ArtistResult(app, a) { confirmArtist = a } } } }
                        } else {
                            val res = rememberLoad("lidarr-albums:$debounced") { app.lidarr.searchAlbums(debounced) }
                            LoadBox(res) { list ->
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                                    items(list, key = { it.foreignId.ifEmpty { it.title } }) { a -> AlbumResult(app, a, helperReady) }
                                    if (helperReady) item { ImportFooter(query) }
                                }
                            }
                        }
                    } else {
                        Text("Suche nach Alben oder Künstlern, die noch fehlen – Lidarr lädt sie herunter.", color = Navi.Muted)
                        if (helperReady && !artists) ImportFooter(query)
                    }
                }
                DownloadsPanel(app, Modifier.weight(2f))
            }
        }
        Req.message?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).clip(RoundedCornerShape(12.dp)).background(Navi.Surface2).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }

    Req.importTarget?.let { YouTubeImportDialog(app, it) }
    Req.choiceFor?.let { RequestChoiceDialog(app, it) }
    Req.manualPrefill?.let { ManualImportDialog(app, it) }
    confirmArtist?.let { artist ->
        AlertDialog(
            onDismissRequest = { confirmArtist = null },
            title = { Text("${artist.name} anfordern?") },
            text = { Text("Lidarr fügt den Künstler hinzu und lädt alle Alben laut deinem Metadaten-Profil herunter. Für einzelne Alben nutze die Album-Suche.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmArtist = null
                    requested[artist.foreignId] = false
                    Req.scope.launch {
                        runCatching { app.lidarr.requestArtist(artist) }
                            .onSuccess { requested[artist.foreignId] = true; Req.message = "${artist.name} hinzugefügt – Lidarr sucht die Alben" }
                            .onFailure { requested.remove(artist.foreignId); Req.message = it.message ?: "Anfrage fehlgeschlagen" }
                    }
                }) { Text("Alle Alben anfordern") }
            },
            dismissButton = { TextButton(onClick = { confirmArtist = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Navi.Outline, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Color(0xFFC9CDD4), modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Unter den Treffern: YouTube-Link einfügen oder ohne Lidarr importieren. */
@Composable
private fun ImportFooter(query: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Navi.Outline, RoundedCornerShape(14.dp)).clickable(onClick = ::pasteLink).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Link, null, tint = Color(0xFFC9CDD4), modifier = Modifier.size(18.dp))
            Text("Nicht dabei? YouTube-Link einfügen", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4))
        }
        TextButton(onClick = { Req.manualPrefill = query.trim() }, modifier = Modifier.fillMaxWidth()) {
            Text("Album nicht bei Lidarr? Ohne Lidarr importieren", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ResultRow(
    cover: String?, title: String, sub: String, state: String?, stateColor: Color, action: String?, primary: Boolean, enabled: Boolean,
    extra: (@Composable () -> Unit)? = null, onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Navi.Surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Cover(cover, Modifier.size(60.dp), 8)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1)
        }
        state?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = stateColor) }
        extra?.invoke()
        if (action != null) {
            if (primary) Button(onClick = onAction, enabled = enabled, shape = RoundedCornerShape(20.dp)) { Text(action, fontWeight = FontWeight.ExtraBold) }
            else OutlinedButton(onClick = onAction, enabled = enabled, shape = RoundedCornerShape(20.dp)) { Text(action) }
        }
    }
}

@Composable
private fun AlbumResult(app: DesktopApp, a: LidarrAlbum, helperReady: Boolean) {
    val scope = rememberCoroutineScope()
    val st = requested[a.foreignId]
    val accent = MaterialTheme.colorScheme.primary
    val sub = listOfNotNull(a.artistName.takeIf { it.isNotEmpty() }, a.year, a.type).joinToString(" · ")
    // Steht in Lidarr, fehlt aber noch: zusätzlich den Weg über YouTube anbieten
    val youTube: (@Composable () -> Unit)? = if (helperReady && a.inLibrary && !a.complete) ({
        OutlinedButton(onClick = { Req.importTarget = ImportTarget(a) }, shape = RoundedCornerShape(20.dp)) {
            Icon(Icons.Rounded.SmartDisplay, null, modifier = Modifier.size(16.dp))
            Text("Von YouTube holen", modifier = Modifier.padding(start = 6.dp))
        }
    }) else null
    when {
        a.complete -> ResultRow(a.imageUrl, a.title, sub, "Vorhanden", Color(0xFF7BD88F), null, false, false) {}
        st == true || (a.inLibrary && a.monitored && a.trackFileCount == 0) -> ResultRow(a.imageUrl, a.title, sub, "Lidarr sucht …", accent, "Erneut suchen", false, st != false, youTube) {
            scope.launch { requested[a.foreignId] = false; runCatching { app.lidarr.requestAlbum(a) }; requested[a.foreignId] = true }
        }
        a.inLibrary -> ResultRow(a.imageUrl, a.title, sub, null, accent, if (st == false) "…" else "Suchen", true, st == null, youTube) { Req.requestAlbum(app, a) }
        else -> ResultRow(a.imageUrl, a.title, sub, null, accent, if (st == false) "…" else "Anfordern", true, st == null) { Req.request(app, a) }
    }
}

@Composable
private fun ArtistResult(app: DesktopApp, a: LidarrArtist, onRequest: () -> Unit) {
    val scope = rememberCoroutineScope()
    val st = requested[a.foreignId]
    val sub = listOfNotNull(a.type, a.disambiguation).joinToString(" · ").ifEmpty { "Künstler" }
    if (a.inLibrary) {
        ResultRow(a.imageUrl, a.name, sub, "In Lidarr", MaterialTheme.colorScheme.primary, if (st == true) "Suche läuft" else "Fehlende suchen", false, st == null) {
            scope.launch { requested[a.foreignId] = false; runCatching { app.lidarr.searchArtist(a) }; requested[a.foreignId] = true }
        }
    } else {
        ResultRow(a.imageUrl, a.name, sub, null, Navi.Muted, when (st) { true -> "Hinzugefügt"; false -> "…"; null -> "Hinzufügen" }, true, st == null, onAction = onRequest)
    }
}

@Composable
private fun DownloadsPanel(app: DesktopApp, modifier: Modifier) {
    var queue by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            queue = runCatching { app.lidarr.queue() }.getOrDefault(queue)
            Req.loadJobs(app)
            delay(8_000)
        }
    }
    val jobs = Req.jobs
    val running = jobs.filter { it.status != "done" }
    val done = jobs.filter { it.status == "done" }.take(5)
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxSize().padding(bottom = 16.dp).clip(RoundedCornerShape(16.dp)).background(Navi.Surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Downloads", style = MaterialTheme.typography.titleLarge)
        if (queue.isEmpty() && jobs.isEmpty()) Text("Nichts in der Warteschlange.", color = Navi.Muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(running, key = { "job_${it.id}" }) { j -> HelperJobCard(j, onRetry = { Req.retryJob(app, j) }, onRemove = { Req.removeJob(app, j) }) }
            items(queue) { q ->
                DownloadCard("LIDARR · ${q.status.uppercase()}", "${q.title}" + (q.artist?.let { " · $it" } ?: ""), q.progress, q.error ?: q.timeLeft?.let { "noch $it" } ?: "", q.error != null, accent)
            }
            items(done, key = { "done_${it.id}" }) { j ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Navi.Surface2, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Navi.Surface2), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(15.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(j.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Importiert · ${j.artist}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(source: String, title: String, progress: Float?, status: String, error: Boolean, accent: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Navi.Surface2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(source, style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
        Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { (progress ?: 0f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = accent, trackColor = Navi.Outline, drawStopIndicator = {},
        )
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else Navi.Muted, maxLines = 2)
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Navi.Outline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(MaterialTheme.colorScheme.primary))
    }
}

/** Helfer-Job mit Schritten; fehlgeschlagene Jobs lassen sich erneut starten oder entfernen. */
@Composable
private fun HelperJobCard(job: HelperJob, onRetry: () -> Unit, onRemove: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Navi.Surface2).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(job.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = job.steps.firstOrNull()?.total ?: 0
                Text("${job.artist} · $count Titel", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(Navi.Outline).padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Rounded.SmartDisplay, null, tint = Color(0xFFC9CDD4), modifier = Modifier.size(13.dp))
                Text("YOUTUBE", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC9CDD4))
            }
        }
        if (job.status == "queued") Text("Wartet auf den Start …", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
        job.steps.forEach { step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val dot = Modifier.size(20.dp).clip(CircleShape)
                when (step.state) {
                    "done" -> Box(dot.background(accent), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp)) }
                    "failed" -> Box(dot.background(MaterialTheme.colorScheme.error), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, null, tint = Navi.Bg, modifier = Modifier.size(12.dp)) }
                    "active" -> Box(dot.border(2.dp, accent, CircleShape))
                    else -> Box(dot.border(2.dp, Color(0xFF3A3F48), CircleShape))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row {
                        Text(step.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = if (step.state == "todo") Navi.Faint else Navi.Text, modifier = Modifier.weight(1f))
                        if (step.total > 1 && step.state != "todo") Text("${step.done}/${step.total}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                    }
                    if (step.state == "active" && step.total > 1) ProgressLine(step.done.toFloat() / step.total)
                }
            }
        }
        if (job.skipped.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${job.skipped.size} Titel übersprungen:", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFFFFB86B))
                job.skipped.take(5).forEach { s -> Text("• ${s.title} – ${s.reason}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        job.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (job.status == "failed") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) { Text("Erneut versuchen", fontWeight = FontWeight.ExtraBold) }
                OutlinedButton(onClick = onRemove, shape = RoundedCornerShape(20.dp)) { Text("Entfernen") }
            }
        }
    }
}

// ---- Dialoge ----

/** Rahmen wie im Design: dunkle Karte, 20 dp Rundung, scrollbar. */
@Composable
private fun SheetDialog(onDismiss: () -> Unit, width: Int = 640, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(width.dp).heightIn(max = 780.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF181A1F))
                .verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun PrimaryAction(label: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, shape = RoundedCornerShape(22.dp), modifier = Modifier.height(44.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else {
            Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
            Text(label, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun DialogButtons(onCancel: () -> Unit, primary: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(22.dp), modifier = Modifier.height(44.dp)) { Text("Abbrechen") }
        primary()
    }
}

/** Quelle wählen, wenn „Anfordern“ auf „Fragen“ steht. */
@Composable
private fun RequestChoiceDialog(app: DesktopApp, album: LidarrAlbum) {
    var remember by remember { mutableStateOf(false) }
    SheetDialog({ Req.choiceFor = null }, width = 520) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(album.imageUrl, Modifier.size(64.dp), 12)
            Column {
                Text("ANFORDERN", style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
                Text(album.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(album.artistName, album.year).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Navi.Muted)
            }
        }
        SourceOption(Icons.Rounded.Download, "Über Lidarr", "Sucht bei deinen Indexern, beste Qualität (z. B. FLAC). Kann dauern.", true) {
            Req.choose(app, album, RequestSource.Lidarr, remember)
        }
        SourceOption(Icons.Rounded.SmartDisplay, "Direkt auf YouTube suchen", "Sofort verfügbar, als MP3 320 kbit/s. Lidarr erkennt das Album danach.", false) {
            Req.choose(app, album, RequestSource.YouTube, remember)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { remember = !remember }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = remember, onCheckedChange = { remember = it })
            Text("Auswahl merken und nicht mehr fragen", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4))
        }
    }
}

@Composable
private fun SourceOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, highlighted: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Navi.Surface2).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (highlighted) accent else Navi.Outline), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (highlighted) MaterialTheme.colorScheme.onPrimary else accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.ExtraBold))
            Text(text, style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Navi.Faint)
    }
}

private val bracketNoise = Regex("""[\(\[][^\)\]]*[\)\]]""")

private sealed interface Res<out T> {
    data object Loading : Res<Nothing>
    data class Ok<T>(val value: T) : Res<T>
    data class Err(val message: String) : Res<Nothing>
}

private suspend fun <T> attempt(fallback: String, block: suspend () -> T): Res<T> = try {
    Res.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Res.Err(e.message ?: fallback)
}

/** YouTube-Import (Design „Von YouTube importieren“): Link → Album → Zuordnung der Titel → Import über den Helfer. */
@Composable
private fun YouTubeImportDialog(app: DesktopApp, target: ImportTarget) {
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    var link by remember(target) { mutableStateOf(target.link) }
    var albumId by remember(target) { mutableStateOf(target.albumId) }
    // Mit bekanntem Album und ohne Link zuerst auf YouTube suchen
    var searchMode by remember(target) { mutableStateOf(target.albumId != null && target.link.isBlank()) }
    val initialQuery = target.album?.let { "${it.artistName} ${it.title}".trim() }.orEmpty()
    var query by remember(target) { mutableStateOf(initialQuery) }
    var submitted by remember(target) { mutableStateOf(initialQuery) }
    var results by remember(target) { mutableStateOf<Res<List<SearchHit>>?>(null) }
    var preview by remember(target) { mutableStateOf<Res<HelperPreview>?>(null) }
    var candidates by remember(target) { mutableStateOf<Res<List<LidarrAlbum>>?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var videos by remember { mutableStateOf(app.helper.videosEnabled) }

    LaunchedEffect(submitted, albumId, searchMode) {
        if (!searchMode || albumId == null || submitted.isBlank()) return@LaunchedEffect
        results = Res.Loading
        results = attempt("YouTube-Suche fehlgeschlagen") { app.helper.search(submitted, albumId) }
    }
    LaunchedEffect(link, albumId) {
        val url = link.trim()
        if (url.isEmpty()) { preview = null; return@LaunchedEffect }
        delay(500)
        preview = Res.Loading
        preview = attempt("Link konnte nicht gelesen werden") { app.helper.preview(url, albumId) }
    }
    // Ohne Album: passende Alben anhand des Playlist-Titels vorschlagen
    val playlistTitle = (preview as? Res.Ok)?.value?.playlist?.title
    LaunchedEffect(playlistTitle, albumId) {
        if (albumId != null || playlistTitle.isNullOrBlank()) return@LaunchedEffect
        candidates = Res.Loading
        candidates = attempt("Lidarr-Suche fehlgeschlagen") { app.lidarr.searchAlbums(bracketNoise.replace(playlistTitle, " ").trim()).take(5) }
    }

    SheetDialog({ Req.importTarget = null }) {
        Column {
            Text("Von YouTube importieren", style = MaterialTheme.typography.headlineSmall)
            target.album?.let { Text("Für „${it.title}“ · ${it.artistName}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted) }
        }
        if (albumId != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Suchen", searchMode) { searchMode = true }
                Chip("Link einfügen", !searchMode) { searchMode = false }
            }
        }
        if (searchMode && albumId != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Künstler und Album") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { submitted = query.trim() }, enabled = query.isNotBlank(), shape = RoundedCornerShape(22.dp), modifier = Modifier.height(44.dp)) { Text("Auf YouTube suchen") }
            }
            when (val r = results) {
                null -> Unit
                Res.Loading -> Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Suche Playlists und Alben-Videos …", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(top = 12.dp))
                }
                is Res.Err -> Text(r.message, color = MaterialTheme.colorScheme.error)
                is Res.Ok -> {
                    if (r.value.isEmpty()) Text("Nichts Passendes gefunden. Probier einen anderen Suchbegriff oder füge einen Link ein.", color = Navi.Muted)
                    r.value.forEachIndexed { i, hit ->
                        SearchHitCard(hit, best = i == 0 && hit.matched > 0) { link = hit.url; searchMode = false }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Link (Album-Playlist, Video oder Kanal) · Strg+V fügt ein", style = MaterialTheme.typography.labelMedium, color = Navi.Muted)
                OutlinedTextField(
                    link, { link = it },
                    placeholder = { Text("https://music.youtube.com/playlist?list=…") },
                    leadingIcon = { Icon(Icons.Rounded.Link, null) },
                    trailingIcon = { IconButton(onClick = { clipboardText()?.let { link = it } }) { Icon(Icons.Rounded.ContentPaste, "Einfügen") } },
                    singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                )
            }
            when (val p = preview) {
                null -> Text("Füge den Link einer YouTube-Playlist (oder eines einzelnen Videos) ein.", style = MaterialTheme.typography.bodyMedium, color = Navi.Muted)
                Res.Loading -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Res.Err -> Text(p.message, color = MaterialTheme.colorScheme.error)
                is Res.Ok -> {
                    val pv = p.value
                    if (albumId == null) {
                        PlaylistCard(pv.playlist)
                        Text("Zu welchem Album gehört das?", style = MaterialTheme.typography.titleMedium)
                        when (val c = candidates) {
                            null, Res.Loading -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                            is Res.Err -> Text(c.message, color = MaterialTheme.colorScheme.error)
                            is Res.Ok -> {
                                if (c.value.isEmpty()) Text("Kein passendes Album bei MusicBrainz gefunden. Suche es links in der Album-Suche und starte den Import von dort.", color = Navi.Muted)
                                c.value.forEach { cand ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !choosing) {
                                            if (cand.inLibrary) albumId = cand.id
                                            else {
                                                choosing = true
                                                scope.launch {
                                                    try {
                                                        albumId = app.lidarr.requestAlbum(cand, search = false)
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        Req.message = e.message ?: "Album konnte nicht angelegt werden"
                                                    } finally {
                                                        choosing = false
                                                    }
                                                }
                                            }
                                        }.padding(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Cover(cand.imageUrl, Modifier.size(52.dp), 8)
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
                        val album = pv.album!!
                        val matched = pv.mapping.count { it.videoId != null }
                        // Karte wie im Design: Album oben, darunter Video → Titel
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF121317)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Cover(pv.playlist.thumbnail, Modifier.size(64.dp), 8)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(album.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(album.artist.takeIf { it.isNotBlank() }, album.year?.toString(), "${pv.mapping.size} Titel").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                                }
                                Text("$matched von ${pv.mapping.size} erkannt", style = MaterialTheme.typography.labelMedium, color = accent)
                            }
                            pv.mapping.forEachIndexed { i, row ->
                                MappingLine(
                                    from = row.videoTitle ?: "Kein passendes Video",
                                    to = "${row.trackNumber.takeIf { it > 0 } ?: (i + 1)} · ${row.title}",
                                    ok = row.videoId != null,
                                )
                            }
                            pv.unmatchedVideos.forEach { v -> MappingLine(from = v.title, to = "Wird übersprungen", ok = false) }
                        }
                        if (target.album == null) TextButton(onClick = { albumId = null }, contentPadding = PaddingValues(0.dp)) { Text("Anderes Album wählen") }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF121317)).padding(horizontal = 14.dp, vertical = 4.dp)) {
                            SettingLine("Format") { Text("MP3 · 320 kbit/s", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)) }
                            HorizontalDivider(color = Navi.Outline)
                            SettingLine("Ziel") {
                                Text(pv.targetFolder.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider(color = Navi.Outline)
                            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Musikvideos mitladen", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4))
                                    Text("Im Video-Ordner des Helfers, abspielbar im Player", style = MaterialTheme.typography.bodySmall, color = Navi.Faint)
                                }
                                Switch(videos, { videos = it })
                            }
                        }
                        Text("Der Helfer lädt die Titel als MP3, setzt die Tags und holt das Cover. Lidarr erkennt das Album danach. Nur Inhalte importieren, die du nutzen darfst.", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                        DialogButtons({ Req.importTarget = null }) {
                            PrimaryAction(if (matched == 1) "1 Titel importieren" else "$matched Titel importieren", matched > 0, Req.startingImport) {
                                Req.startImport(app, link, album.id, videos, pv.mapping)
                            }
                        }
                    }
                }
            }
        }
        val ready = !(searchMode && albumId != null) && albumId != null && (preview as? Res.Ok)?.value?.album != null
        if (!ready) DialogButtons({ Req.importTarget = null }) {}
    }
}

@Composable
private fun SearchHitCard(hit: SearchHit, best: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (best) Navi.Surface2 else Color.Transparent)
            .border(1.dp, if (best) accent else Navi.Outline, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width = 104.dp, height = 58.dp)) {
            Cover(hit.thumbnail, Modifier.fillMaxSize(), 8)
            Text(
                if (hit.kind == "chapters") "${hit.count} Kapitel" else "${hit.count} Videos",
                style = MaterialTheme.typography.bodySmall, color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(hit.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(if (hit.kind == "chapters") "Video mit Kapiteln" else "Playlist", hit.uploader, if (hit.durationSec > 0) "${hit.durationSec / 60} Min." else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (hit.total > 0) {
                val label = buildString {
                    if (best) append("Beste Übereinstimmung · ")
                    append("${hit.matched}/${hit.total} Titel")
                    if (hit.extra > 0) append(" · ${hit.extra} übrig")
                }
                Text(
                    label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = if (best) MaterialTheme.colorScheme.onPrimary else Color(0xFFC9CDD4),
                    modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(if (best) accent else Navi.Outline).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaylistCard(playlist: PlaylistInfo) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF121317)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(playlist.thumbnail, Modifier.size(width = 96.dp, height = 54.dp), 8)
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(playlist.uploader, if (playlist.count == 1) "1 Video" else "${playlist.count} Videos", if (playlist.durationSec > 0) "${playlist.durationSec / 60} Min." else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Zeile „Video → Titel“ wie im Design; nicht zugeordnete Zeilen sind gedämpft. */
@Composable
private fun MappingLine(from: String, to: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(from, style = MaterialTheme.typography.bodyMedium, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(if (ok) Icons.AutoMirrored.Rounded.ArrowForward else Icons.Rounded.Remove, null, tint = Navi.Muted, modifier = Modifier.size(16.dp))
        Text(
            to, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (ok) FontWeight.Bold else FontWeight.Medium),
            color = if (ok) Navi.Text else Navi.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SettingLine(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

// ---- Import ohne Lidarr ----

private class EditableTrack(val videoId: String, val source: String, title: String, selected: Boolean) {
    var title by mutableStateOf(title)
    var selected by mutableStateOf(selected)
}

private val teaserWords = Regex("(?i)\\b(teaser|trailer|snippet|interview|making of|behind the scenes)\\b")

@Composable
private fun ManualImportDialog(app: DesktopApp, prefill: String) {
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf(prefill) }
    var year by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(true) }
    var link by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<Res<List<SearchHit>>?>(null) }
    var preview by remember { mutableStateOf<Res<ManualPreview>?>(null) }
    val tracks = remember { mutableStateListOf<EditableTrack>() }
    val canSearch = artist.isNotBlank() && album.isNotBlank()
    val close = { Req.manualPrefill = null }

    fun loadPreview(url: String) {
        link = url
        preview = Res.Loading
        scope.launch {
            preview = attempt("Link konnte nicht gelesen werden") {
                val p = app.helper.manualPreview(url, artist)
                tracks.clear()
                p.tracks.forEach { t -> tracks += EditableTrack(t.videoId, t.source, t.title, !teaserWords.containsMatchIn(t.source)) }
                p
            }
        }
    }

    SheetDialog(close) {
        val p = (preview as? Res.Ok)?.value
        if (p != null) {
            // Schritt 2: Titel prüfen
            val selected = tracks.filter { it.selected }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(p.playlist.thumbnail, Modifier.size(84.dp), 14)
                Column(Modifier.weight(1f)) {
                    Text(listOfNotNull("ALBUM", year.toIntOrNull()?.toString()).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
                    Text(album, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(artist, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4))
                    Text("${selected.size} von ${tracks.size} Titeln ausgewählt", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                }
            }
            TextButton(onClick = { preview = null }, contentPadding = PaddingValues(0.dp)) { Text("Andere Quelle wählen") }
            tracks.forEach { t ->
                val number = if (t.selected) selected.indexOf(t) + 1 else null
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Checkbox(checked = t.selected, onCheckedChange = { t.selected = it })
                    Text(number?.toString() ?: "–", modifier = Modifier.width(22.dp), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = Navi.Faint, textAlign = TextAlign.Center)
                    Column(Modifier.weight(1f)) {
                        BasicTextField(
                            value = t.title, onValueChange = { t.title = it }, singleLine = true, enabled = t.selected,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, color = if (t.selected) Navi.Text else Navi.Faint),
                            cursorBrush = SolidColor(accent), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                        Text(t.source, style = MaterialTheme.typography.bodySmall, color = Navi.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ziel", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4))
                Text(
                    "Musikordner/$artist/$album" + (year.toIntOrNull()?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                )
            }
            DialogButtons(close) {
                PrimaryAction(if (selected.size == 1) "1 Titel importieren" else "${selected.size} Titel importieren", selected.isNotEmpty(), Req.startingImport) {
                    Req.startManualImport(
                        app, link, artist, album, year.toIntOrNull(), p.playlist.thumbnail,
                        selected.mapIndexed { i, t -> ManualTrack(t.videoId, t.title.trim().ifEmpty { "Titel ${i + 1}" }, i + 1) },
                    )
                }
            }
        } else {
            // Schritt 1: Album angeben und Quelle suchen
            Column {
                Text("Ohne Lidarr importieren", style = MaterialTheme.typography.headlineSmall)
                Text("Für Alben, die Lidarr nicht kennt · direkt in deine Bibliothek", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
            }
            OutlinedTextField(artist, { artist = it }, label = { Text("Künstler") }, singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f))
                OutlinedTextField(year, { v -> year = v.filter(Char::isDigit).take(4) }, label = { Text("Jahr") }, singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.width(96.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Auf YouTube suchen", searchMode) { searchMode = true }
                Chip("Link einfügen", !searchMode) { searchMode = false }
            }
            if (searchMode) {
                OutlinedButton(
                    onClick = {
                        hits = Res.Loading
                        scope.launch { hits = attempt("Suche fehlgeschlagen") { app.helper.search("${artist.trim()} ${album.trim()}", null) } }
                    },
                    enabled = canSearch, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().height(44.dp),
                ) { Text(if (canSearch) "Suchen" else "Erst Künstler und Album angeben") }
                when (val h = hits) {
                    null -> Unit
                    Res.Loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is Res.Err -> Text(h.message, color = MaterialTheme.colorScheme.error)
                    is Res.Ok -> {
                        if (h.value.isEmpty()) Text("Nichts gefunden – probier den Link.", color = Navi.Muted)
                        h.value.forEachIndexed { i, hit -> SearchHitCard(hit, best = i == 0) { loadPreview(hit.url) } }
                    }
                }
            } else {
                OutlinedTextField(
                    link, { link = it }, label = { Text("Playlist-Link") }, placeholder = { Text("https://www.youtube.com/playlist?list=…") },
                    trailingIcon = { IconButton(onClick = { clipboardText()?.let { link = it } }) { Icon(Icons.Rounded.ContentPaste, "Einfügen") } },
                    singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { loadPreview(link) }, enabled = canSearch && link.isNotBlank(), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Text("Weiter", fontWeight = FontWeight.ExtraBold)
                }
            }
            when (val pv = preview) {
                Res.Loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Res.Err -> Text(pv.message, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Navi.Surface2).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Navi.Muted, modifier = Modifier.size(18.dp))
                Text("Lidarr kennt dieses Album danach nicht: keine Überwachung und kein späteres Qualitäts-Upgrade.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9CDD4))
            }
            DialogButtons(close) {}
        }
    }
}

/** Einstellungen: Helfer, Lidarr, Songtexte, Autoplay. */
@Composable
fun SettingsDialog(app: DesktopApp, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var helperUrl by remember { mutableStateOf(app.helperStore.config?.url.orEmpty()) }
    var helperToken by remember { mutableStateOf(app.helperStore.config?.token.orEmpty()) }
    var lidarrUrl by remember { mutableStateOf(app.lidarrStore.config?.url.orEmpty()) }
    var lidarrKey by remember { mutableStateOf(app.lidarrStore.config?.apiKey.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Einstellungen") },
        text = {
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("NAVIDROID-HELFER", style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
                OutlinedTextField(helperUrl, { helperUrl = it }, label = { Text("Adresse") }, placeholder = { Text("http://192.168.178.10:8787") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(helperToken, { helperToken = it }, label = { Text("Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("LIDARR", style = MaterialTheme.typography.labelSmall, color = Navi.Muted, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(lidarrUrl, { lidarrUrl = it }, label = { Text("Adresse") }, placeholder = { Text("http://192.168.178.10:8686") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lidarrKey, { lidarrKey = it }, label = { Text("API-Schlüssel") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Online-Songtexte (LRCLIB)", modifier = Modifier.weight(1f))
                    Switch(app.settings.onlineLyrics, { app.updateSettings(app.settings.copy(onlineLyrics = it)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Autoplay mit ähnlicher Musik", modifier = Modifier.weight(1f))
                    Switch(app.settings.autoplay, { app.updateSettings(app.settings.copy(autoplay = it)) })
                }
                status?.let { Text(it, color = if (it.startsWith("Gespeichert")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                status = null
                scope.launch {
                    try {
                        if (helperUrl.isNotBlank()) {
                            val url = LidarrStore.normalizeUrl(helperUrl)
                            app.helper.health(url, helperToken.trim())
                            // Musikvideo-Einstellung beibehalten
                            app.helperStore.save(HelperConfig(url, helperToken.trim(), app.helperStore.config?.videos ?: false))
                        } else app.helperStore.clear()
                        if (lidarrUrl.isNotBlank()) {
                            val url = LidarrStore.normalizeUrl(lidarrUrl)
                            val opts = app.lidarr.connect(url, lidarrKey.trim())
                            val old = app.lidarrStore.config
                            app.lidarrStore.save(LidarrConfig(
                                url, lidarrKey.trim(),
                                old?.qualityProfileId?.takeIf { id -> opts.qualityProfiles.any { it.value == id } } ?: opts.qualityProfiles.first().value,
                                old?.metadataProfileId?.takeIf { id -> opts.metadataProfiles.any { it.value == id } } ?: opts.metadataProfiles.first().value,
                                old?.rootFolderPath?.takeIf { p -> opts.rootFolders.any { it.value == p } } ?: opts.rootFolders.first().value,
                            ))
                        } else app.lidarrStore.clear()
                        status = "Gespeichert"
                        app.syncSettings(pushLocal = true)
                        app.version++
                        onDismiss()
                    } catch (e: Exception) {
                        status = e.message ?: "Verbindung fehlgeschlagen"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Prüfe …" else "Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Schließen") } },
    )
}
