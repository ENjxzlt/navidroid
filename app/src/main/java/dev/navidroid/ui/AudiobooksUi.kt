package dev.navidroid.ui

import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.navidroid.NaviApp
import dev.navidroid.data.Album
import dev.navidroid.data.AudiobookRepository
import dev.navidroid.data.Bookmark
import dev.navidroid.lidarr.AudiobookSeriesList
import dev.navidroid.playback.PlayerConnection
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

// ---- Abspielen ----

/** Folge abspielen – an der gemerkten Stelle, mit [fromStart] von vorn. */
fun playEpisode(app: NaviApp, player: PlayerConnection, albumId: String, fromStart: Boolean = false, onError: (String) -> Unit = {}) {
    MainScope().launch {
        try {
            if (fromStart) app.audiobooks.clear(albumId)
            val songs = app.client.album(albumId).song.playableNow(app)
            if (songs.isEmpty()) return@launch onError("Folge ist offline nicht verfügbar")
            val b = if (fromStart) null else app.audiobooks.bookmarkFor(albumId)
            val index = b?.let { bm -> songs.indexOfFirst { it.id == bm.entry.id } }?.takeIf { it >= 0 } ?: 0
            val start = if (b != null && songs[index].id == b.entry.id) b.position else 0L
            player.play(songs, index, startPositionMs = start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e.message ?: "Folge konnte nicht geladen werden")
        }
    }
}

private enum class EpisodeState { New, Started, Heard }

private fun stateOf(album: Album, b: Bookmark?): EpisodeState = when {
    b != null -> EpisodeState.Started
    album.playCount > 0 || album.played != null -> EpisodeState.Heard
    else -> EpisodeState.New
}

private fun sortedEpisodes(albums: List<Album>): List<Album> =
    albums.sortedWith(compareBy<Album> { AudiobookRepository.episodeNumber(it.name) ?: Int.MAX_VALUE }.thenBy { it.year ?: 0 }.thenBy { it.name.lowercase() })

/** Nächste Folge einer Serie: angefangene, sonst die erste neue nach der zuletzt gehörten. */
private fun nextEpisode(app: NaviApp, albums: List<Album>): Album? {
    val sorted = sortedEpisodes(albums)
    app.audiobooks.bookmarks.firstOrNull { b -> sorted.any { it.id == b.entry.albumId } }?.let { b ->
        return sorted.first { it.id == b.entry.albumId }
    }
    val lastHeard = sorted.indexOfLast { stateOf(it, null) == EpisodeState.Heard }
    return sorted.drop(lastHeard + 1).firstOrNull { stateOf(it, null) == EpisodeState.New } ?: sorted.firstOrNull { stateOf(it, null) == EpisodeState.New }
}

private fun remainingLabel(album: Album, b: Bookmark): String {
    val left = (album.duration * (1f - AudiobookRepository.progress(album, b))).toInt()
    return if (left >= 60) "noch ${left / 60} Min." else "fast fertig"
}

// ---- Einstieg in der Bibliothek ----

@Composable
fun AudiobooksEntry(app: NaviApp, onClick: () -> Unit) {
    if (!app.audiobooks.enabled) return
    val started = app.audiobooks.bookmarks.size
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Navi.Surface2), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Headphones, null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text("Hörspiele", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            Text(
                if (started > 0) "$started angefangen · weiterhören" else "${app.audiobooks.albums.size} Folgen",
                style = MaterialTheme.typography.bodySmall,
                color = Navi.Muted,
            )
        }
    }
}

// ---- „Weiterhören“ auf der Startseite ----

@Composable
fun ContinueListeningRow(app: NaviApp) {
    val player = LocalPlayer.current
    val context = LocalContext.current
    val items = app.audiobooks.bookmarks.mapNotNull { b -> app.audiobooks.albums.firstOrNull { it.id == b.entry.albumId }?.let { it to b } }
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Weiterhören")
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.first.id }) { (album, b) ->
                ContinueCard(album, b, Modifier.width(150.dp)) {
                    playEpisode(app, player, album.id) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(album: Album, b: Bookmark, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            CoverArt(album.coverArt, 300, Modifier.size(150.dp).clip(RoundedCornerShape(14.dp)))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "Weiterhören", tint = MaterialTheme.colorScheme.onPrimary) }
            }
        }
        ProgressBar(AudiobookRepository.progress(album, b))
        Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(remainingLabel(album, b), style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
    }
}

@Composable
private fun ProgressBar(value: Float, modifier: Modifier = Modifier.fillMaxWidth()) {
    LinearProgressIndicator(
        progress = { value.coerceAtLeast(0.02f) },
        modifier = modifier.height(4.dp).clip(RoundedCornerShape(2.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Navi.Surface2,
        drawStopIndicator = {},
    )
}

// ---- Übersicht ----

@Composable
fun AudiobooksScreen(app: NaviApp, nav: NavController) {
    val player = LocalPlayer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        app.audiobooks.ensure(force = true)
        app.audiobooks.refreshBookmarks()
        loading = false
    }
    val repo = app.audiobooks
    val series = repo.albums.groupBy { it.artistId to (it.artist ?: "Unbekannt") }.entries.sortedBy { it.key.second.lowercase() }

    LazyColumn(Modifier.fillMaxSize().background(Navi.Bg), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() })
                Text("Hörspiele", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Bibliothek wählen", tint = Navi.Muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val choose = { id: Int -> menu = false; scope.launch { repo.choose(id); repo.refreshBookmarks() }; Unit }
                        DropdownMenuItem(text = { Text("Bibliothek automatisch erkennen") }, onClick = { choose(AudiobookRepository.AUTO) }, trailingIcon = { if (repo.folderSetting == AudiobookRepository.AUTO) Icon(Icons.Rounded.Check, null) })
                        repo.folders.forEach { f ->
                            DropdownMenuItem(text = { Text("Bibliothek: ${f.name}") }, onClick = { choose(f.id) }, trailingIcon = { if (repo.folderSetting == f.id) Icon(Icons.Rounded.Check, null) })
                        }
                        DropdownMenuItem(text = { Text("Keine Hörspiel-Bibliothek") }, onClick = { choose(AudiobookRepository.OFF) }, trailingIcon = { if (repo.folderSetting == AudiobookRepository.OFF) Icon(Icons.Rounded.Check, null) })
                    }
                }
            }
        }
        when {
            loading && repo.albums.isEmpty() -> item { CenteredProgress(Modifier.fillMaxWidth().padding(32.dp)) }
            repo.folder == null -> item {
                Text(
                    repo.error ?: "Keine Hörspiel-Bibliothek gefunden. Lege in Navidrome eine eigene Bibliothek für Hörspiele an " +
                        "(z. B. „Hörspiele“) oder wähle sie oben rechts im Menü.",
                    color = Navi.Muted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            else -> {
                val started = repo.bookmarks.mapNotNull { b -> repo.albums.firstOrNull { it.id == b.entry.albumId }?.let { it to b } }
                if (started.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader("Weiterhören")
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(started, key = { it.first.id }) { (album, b) ->
                                ContinueCard(album, b, Modifier.width(150.dp)) {
                                    playEpisode(app, player, album.id) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }
                    }
                }
                item { SectionHeader("Serien") }
                if (series.isEmpty()) item { Text("Die Bibliothek „${repo.folder?.name}“ ist leer.", color = Navi.Muted, modifier = Modifier.padding(horizontal = 20.dp)) }
                items(series, key = { "s:${it.key.first ?: it.key.second}" }) { (key, albums) ->
                    val cover = sortedEpisodes(albums).lastOrNull()?.coverArt
                    val heard = albums.count { stateOf(it, repo.bookmarkFor(it.id)) == EpisodeState.Heard }
                    Row(
                        Modifier.fillMaxWidth().clickable { key.first?.let { nav.navigate("audiobooks/$it") } }.padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CoverArt(cover, 300, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(key.second, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${albums.size} Folgen · $heard gehört", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                        }
                    }
                }
            }
        }
        if (app.helper.configured) item { YouTubeSeriesSection(app) }
    }
}

// ---- Serie ----

@Composable
fun SeriesScreen(app: NaviApp, artistId: String, nav: NavController) {
    val player = LocalPlayer.current
    val context = LocalContext.current
    var newestFirst by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        app.audiobooks.ensure(force = true)
        app.audiobooks.refreshBookmarks()
    }
    val repo = app.audiobooks
    val albums = repo.albums.filter { it.artistId == artistId }
    val sorted = sortedEpisodes(albums).let { if (newestFirst) it.reversed() else it }
    val next = nextEpisode(app, albums)
    val toast = { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }

    LazyColumn(Modifier.fillMaxSize().background(Navi.Bg), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { newestFirst = !newestFirst }) {
                        Icon(Icons.Rounded.SwapVert, if (newestFirst) "Älteste zuerst" else "Neueste zuerst", tint = Navi.Muted)
                    }
                }
                Text(albums.firstOrNull()?.artist ?: "Serie", style = MaterialTheme.typography.headlineLarge)
                Text("${albums.size} Folgen", color = Navi.Muted, style = MaterialTheme.typography.bodyMedium)
                if (next != null) {
                    val b = repo.bookmarkFor(next.id)
                    Surface(
                        onClick = { playEpisode(app, player, next.id, onError = toast) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
                            Column(Modifier.weight(1f)) {
                                Text(if (b != null) "Weiterhören" else "Nächste Folge", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                                Text(next.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        items(sorted, key = { it.id }) { album ->
            EpisodeRow(app, album, repo.bookmarkFor(album.id), onPlay = { playEpisode(app, player, album.id, onError = toast) },
                onRestart = { playEpisode(app, player, album.id, fromStart = true, onError = toast) }, onOpen = { nav.openAlbum(album.id) })
        }
    }
}

@Composable
private fun EpisodeRow(app: NaviApp, album: Album, b: Bookmark?, onPlay: () -> Unit, onRestart: () -> Unit, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val state = stateOf(album, b)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CoverArt(album.coverArt, 200, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                album.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (state == EpisodeState.Heard) Navi.Muted else Navi.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when (state) {
                EpisodeState.Started -> {
                    ProgressBar(AudiobookRepository.progress(album, b!!), Modifier.width(120.dp))
                    Text(remainingLabel(album, b), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                EpisodeState.Heard -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.Check, null, tint = Navi.Muted, modifier = Modifier.size(14.dp))
                    Text("Gehört", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                }
                EpisodeState.New -> Text(formatLong(album.duration), style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mehr", tint = Navi.Muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (state != EpisodeState.New) DropdownMenuItem(text = { Text("Von vorn abspielen") }, onClick = { menu = false; onRestart() })
                DropdownMenuItem(text = { Text("Kapitel anzeigen") }, onClick = { menu = false; onOpen() })
            }
        }
    }
}

// ---- Neue Folgen von YouTube (Helfer) ----

@Composable
private fun YouTubeSeriesSection(app: NaviApp) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var data by remember { mutableStateOf<AudiobookSeriesList?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val reload: () -> Unit = {
        scope.launch {
            try {
                data = app.helper.audiobookSeries(); error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    val d = data
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Neue Folgen von YouTube") {
            if (d?.enabled == true) {
                IconButton(onClick = {
                    scope.launch {
                        runCatching { app.helper.checkAudiobookSeries() }.onSuccess { data = it }
                        Toast.makeText(context, "Wird geprüft – neue Folgen erscheinen unter Anfragen → Downloads", Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.Rounded.Refresh, "Jetzt prüfen", tint = Navi.Muted) }
                IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, "Serie hinzufügen", tint = Navi.Muted) }
            }
        }
        val hint = when {
            error != null -> error!!
            d == null -> null
            !d.enabled -> "Im Helfer ist kein Hörspiel-Ordner eingerichtet (AUDIOBOOK_DIR). Danach lädt er neue Folgen automatisch."
            d.series.isEmpty() -> "Füge eine Serie hinzu (z. B. Die drei ???). Der Helfer schaut alle paar Stunden in den Reiter " +
                "„Veröffentlichungen“ des YouTube-Kanals und lädt neue Folgen in die Hörspiel-Bibliothek."
            else -> null
        }
        hint?.let { Text(it, color = Navi.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
        if (d == null && error == null) CircularProgressIndicator(Modifier.padding(horizontal = 20.dp).size(20.dp), strokeWidth = 2.dp)
        d?.series?.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    val status = listOfNotNull(
                        s.channel?.name?.let { "Kanal: $it" },
                        if (s.imported > 0) "${s.imported} geladen" else null,
                        s.lastCheck?.let { "geprüft ${java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date(it))}" } ?: "noch nicht geprüft",
                    ).joinToString(" · ")
                    Text(status, style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                    s.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                IconButton(onClick = { scope.launch { runCatching { app.helper.removeAudiobookSeries(s.id) }; reload() } }) {
                    Icon(Icons.Rounded.Delete, "Serie entfernen", tint = Navi.Muted)
                }
            }
        }
    }
    if (adding) AddSeriesDialog(app, onDismiss = { adding = false }, onAdded = { adding = false; reload() })
}

@Composable
private fun AddSeriesDialog(app: NaviApp, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("Die drei ???") }
    var channel by remember { mutableStateOf("") }
    var importExisting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Serie hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (wie in der Bibliothek)") }, singleLine = true)
                OutlinedTextField(channel, { channel = it }, label = { Text("YouTube-Kanal (Link, optional)") }, singleLine = true)
                Row(Modifier.clickable { importExisting = !importExisting }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(importExisting, { importExisting = it })
                    Text("Auch schon erschienene Folgen laden", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Ohne Häkchen lädt der Helfer nur Folgen, die ab jetzt erscheinen. Vorhandene Folgen werden nie doppelt geladen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Navi.Muted,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        app.helper.addAudiobookSeries(name.trim(), channel.trim(), importExisting)
                        onAdded()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Suche Kanal …" else "Hinzufügen") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Abbrechen") } },
    )
}
