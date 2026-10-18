package dev.navidroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.navidroid.lidarr.HelperConfig
import dev.navidroid.lidarr.HelperJob
import dev.navidroid.lidarr.LidarrAlbum
import dev.navidroid.lidarr.LidarrArtist
import dev.navidroid.lidarr.LidarrConfig
import dev.navidroid.lidarr.LidarrStore
import dev.navidroid.lidarr.QueueItem
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
    var artists by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    LaunchedEffect(query) { delay(500); debounced = query.trim() }
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 64.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Anfragen", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
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
                        LoadBox(res) { list -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(list, key = { it.foreignId.ifEmpty { it.name } }) { a -> ArtistResult(app, a) } } }
                    } else {
                        val res = rememberLoad("lidarr-albums:$debounced") { app.lidarr.searchAlbums(debounced) }
                        LoadBox(res) { list -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(list, key = { it.foreignId.ifEmpty { it.title } }) { a -> AlbumResult(app, a) } } }
                    }
                } else {
                    Text("Suche nach Alben oder Künstlern, die noch fehlen – Lidarr lädt sie herunter.", color = Navi.Muted)
                }
            }
            DownloadsPanel(app, Modifier.weight(2f))
        }
    }
}

@Composable
private fun ResultRow(cover: String?, title: String, sub: String, state: String?, stateColor: androidx.compose.ui.graphics.Color, action: String?, primary: Boolean, enabled: Boolean, onAction: () -> Unit) {
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
        if (action != null) {
            if (primary) Button(onClick = onAction, enabled = enabled, shape = RoundedCornerShape(20.dp)) { Text(action, fontWeight = FontWeight.ExtraBold) }
            else OutlinedButton(onClick = onAction, enabled = enabled, shape = RoundedCornerShape(20.dp)) { Text(action) }
        }
    }
}

@Composable
private fun AlbumResult(app: DesktopApp, a: LidarrAlbum) {
    val scope = rememberCoroutineScope()
    val st = requested[a.foreignId]
    val accent = MaterialTheme.colorScheme.primary
    val sub = listOfNotNull(a.artistName.takeIf { it.isNotEmpty() }, a.year, a.type).joinToString(" · ")
    when {
        a.complete -> ResultRow(a.imageUrl, a.title, sub, "Vorhanden", androidx.compose.ui.graphics.Color(0xFF7BD88F), null, false, false) {}
        st == true || (a.inLibrary && a.monitored && a.trackFileCount == 0) -> ResultRow(a.imageUrl, a.title, sub, "Lidarr sucht …", accent, "Erneut suchen", false, st != false) {
            scope.launch { requested[a.foreignId] = false; runCatching { app.lidarr.requestAlbum(a) }; requested[a.foreignId] = true }
        }
        else -> ResultRow(a.imageUrl, a.title, sub, null, accent, if (st == false) "…" else "Anfordern", true, st == null) {
            scope.launch {
                requested[a.foreignId] = false
                runCatching { app.lidarr.requestAlbum(a) }.onSuccess { requested[a.foreignId] = true; app.version++ }.onFailure { requested.remove(a.foreignId) }
            }
        }
    }
}

@Composable
private fun ArtistResult(app: DesktopApp, a: LidarrArtist) {
    val scope = rememberCoroutineScope()
    val st = requested[a.foreignId]
    val sub = listOfNotNull(a.type, a.disambiguation).joinToString(" · ").ifEmpty { "Künstler" }
    if (a.inLibrary) {
        ResultRow(a.imageUrl, a.name, sub, "In Lidarr", MaterialTheme.colorScheme.primary, if (st == true) "Suche läuft" else "Fehlende suchen", false, st == null) {
            scope.launch { requested[a.foreignId] = false; runCatching { app.lidarr.searchArtist(a) }; requested[a.foreignId] = true }
        }
    } else {
        ResultRow(a.imageUrl, a.name, sub, null, Navi.Muted, when (st) { true -> "Hinzugefügt"; false -> "…"; null -> "Hinzufügen" }, true, st == null) {
            scope.launch {
                requested[a.foreignId] = false
                runCatching { app.lidarr.requestArtist(a) }.onSuccess { requested[a.foreignId] = true }.onFailure { requested.remove(a.foreignId) }
            }
        }
    }
}

@Composable
private fun DownloadsPanel(app: DesktopApp, modifier: Modifier) {
    var queue by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var jobs by remember { mutableStateOf<List<HelperJob>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            queue = runCatching { app.lidarr.queue() }.getOrDefault(queue)
            if (app.helper.configured) jobs = runCatching { app.helper.jobs().take(8) }.getOrDefault(jobs)
            delay(8_000)
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxSize().padding(bottom = 16.dp).clip(RoundedCornerShape(16.dp)).background(Navi.Surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Downloads", style = MaterialTheme.typography.titleLarge)
        if (queue.isEmpty() && jobs.isEmpty()) Text("Nichts in der Warteschlange.", color = Navi.Muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(queue) { q ->
                DownloadCard("LIDARR", "${q.title}" + (q.artist?.let { " · $it" } ?: ""), q.progress, q.error ?: listOfNotNull(q.status, q.timeLeft).joinToString(" · "), q.error != null, accent)
            }
            items(jobs, key = { it.id }) { j ->
                val step = j.steps.firstOrNull { it.state == "running" } ?: j.steps.lastOrNull { it.state == "done" }
                val progress = step?.let { if (it.total > 0) it.done.toFloat() / it.total else null }
                val status = when (j.status) {
                    "done" -> "Importiert"
                    "error" -> j.error ?: "Fehler"
                    else -> step?.let { "${it.label} · ${it.done} von ${it.total}" } ?: "Wartet"
                }
                DownloadCard("YOUTUBE", "${j.title} · ${j.artist}", if (j.status == "done") 1f else progress, status, j.status == "error", accent)
            }
        }
    }
}

@Composable
private fun DownloadCard(source: String, title: String, progress: Float?, status: String, error: Boolean, accent: androidx.compose.ui.graphics.Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Navi.Surface2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(source, style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
        Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { (progress ?: 0f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = accent, trackColor = Navi.Outline, drawStopIndicator = {},
        )
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else Navi.Muted, maxLines = 2)
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
                            app.helperStore.save(HelperConfig(url, helperToken.trim(), false))
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
