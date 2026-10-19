package dev.navidroid.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import dev.navidroid.NaviApp
import dev.navidroid.data.Artist
import dev.navidroid.data.Song
import dev.navidroid.lidarr.VideoCandidate
import dev.navidroid.lidarr.VideoRequest
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Titel, für den gerade ein Musikvideo ausgewählt wird (Dialog in NaviRoot). */
object VideoPickBus {
    var song by mutableStateOf<Song?>(null)
}

private fun startVideoFetch(app: NaviApp, title: String, requests: List<VideoRequest>, toast: (String) -> Unit, onDone: () -> Unit = {}) {
    MainScope().launch {
        try {
            app.helper.fetchVideos(title, requests)
            toast(if (requests.size == 1) "Musikvideo wird geladen – Fortschritt unter Anfragen → Downloads" else "${requests.size} Musikvideos werden gesucht und geladen")
            onDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast(e.message ?: "Konnte nicht gestartet werden")
        }
    }
}

// ---- Video für einen Titel auswählen ----

@Composable
fun VideoPickDialog(app: NaviApp, song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var hits by remember(song.id) { mutableStateOf<List<VideoCandidate>?>(null) }
    var error by remember(song.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(song.id) {
        try {
            hits = app.helper.videoCandidates(song)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Suche fehlgeschlagen"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Musikvideo für „${song.title}“", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            val list = hits
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text("Kein passendes Video auf YouTube gefunden.", color = Navi.Muted)
                else -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.videoId }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                onDismiss()
                                startVideoFetch(app, song.title, listOf(VideoRequest(song, c.videoId)), { Toast.makeText(context, it, Toast.LENGTH_LONG).show() })
                            }.padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = c.thumbnail,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Navi.Surface2),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(c.channel.takeIf { it.isNotBlank() }, c.seconds?.let { formatDuration(it.toInt()) }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Navi.Muted,
                                    maxLines = 1,
                                )
                                if (c.auto) Text("Offizielles Video", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

// ---- Werkzeug: Musikvideos nachladen ----

private class ArtistVideos(val artist: Artist, val albums: List<Pair<dev.navidroid.data.Album, List<Song>>>, val have: Map<String, Boolean>)

@Composable
fun MusicVideosScreen(app: NaviApp, nav: NavController) {
    var artist by remember { mutableStateOf<Artist?>(null) }
    BackHandler(enabled = artist != null) { artist = null }
    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { if (artist != null) artist = null else nav.popBackStack() })
            Text(artist?.name ?: "Musikvideos nachladen", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
        }
        val a = artist
        if (a == null) ArtistPicker(app) { artist = it } else ArtistVideosView(app, a)
    }
}

@Composable
private fun ArtistPicker(app: NaviApp, onPick: (Artist) -> Unit) {
    var query by remember { mutableStateOf("") }
    val loader = rememberLoad("videos:artists") {
        app.audiobooks.ensure()
        app.client.artists().flatMap { it.artist }.filterNot { app.audiobooks.isAudiobookArtist(it.id) }
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Wähle einen Künstler. Fehlende Musikvideos lädt der Helfer von YouTube – automatisch nur offizielle Videos, " +
                "sonst suchst du das passende pro Titel selbst aus.",
            style = MaterialTheme.typography.bodySmall,
            color = Navi.Muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        SearchField(query, { query = it }, "Künstler suchen", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        LoadContent(loader) { artists ->
            val q = query.trim().lowercase()
            val shown = if (q.isEmpty()) artists else artists.filter { it.name.lowercase().contains(q) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown, key = { it.id }) { artist ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(artist) }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CoverArt(artist.coverArt, 200, Modifier.size(44.dp).clip(CircleShape))
                        Column(Modifier.weight(1f)) {
                            Text(artist.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (artist.albumCount == 1) "1 Album" else "${artist.albumCount} Alben", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistVideosView(app: NaviApp, artist: Artist) {
    val context = LocalContext.current
    val toast = { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    var version by remember { mutableStateOf(0) }
    val loader = rememberLoad("videos:${artist.id}:$version") {
        val albums = app.client.artist(artist.id).album
        val full = coroutineScope { albums.map { al -> async { al to runCatching { app.client.album(al.id).song }.getOrDefault(emptyList()) } }.awaitAll() }
            .sortedByDescending { it.first.year ?: 0 }
        val songs = full.flatMap { it.second }
        val have = app.helper.videoStatus(songs)
        ArtistVideos(artist, full, songs.zip(have).associate { (s, h) -> s.id to h })
    }
    LoadContent(loader) { data ->
        val missing = data.albums.flatMap { it.second }.filter { data.have[it.id] != true }
        val selected = remember(data) { mutableStateListOf<String>().apply { addAll(missing.map { it.id }) } }
        val total = data.have.size
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${total - missing.size} von $total Titeln haben ein Musikvideo", style = MaterialTheme.typography.bodyMedium, color = Navi.Muted)
                    if (missing.isNotEmpty()) {
                        Surface(
                            onClick = {
                                val chosen = missing.filter { it.id in selected }
                                if (chosen.isEmpty()) return@Surface
                                startVideoFetch(app, artist.name, chosen.map { VideoRequest(it) }, toast) { version++ }
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "${selected.size} automatisch suchen & laden",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        Text(
                            "Automatisch wird nur ein eindeutig offizielles Video geladen. Bei den anderen tippe auf das Video-Symbol und wähle selbst.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Navi.Muted,
                        )
                    }
                }
            }
            data.albums.forEach { (album, songs) ->
                if (songs.isEmpty()) return@forEach
                item(key = "a:${album.id}") {
                    Text(
                        listOfNotNull(album.name, album.year?.toString()).joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(songs, key = { "s:${album.id}:${it.id}" }) { song ->
                    val has = data.have[song.id] == true
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (has) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.CheckCircle, "Video vorhanden", tint = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            Checkbox(song.id in selected, { on -> if (on) selected.add(song.id) else selected.remove(song.id) })
                        }
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (has) Navi.Muted else Navi.Text)
                            if (song.duration > 0) Text(formatDuration(song.duration), style = MaterialTheme.typography.bodySmall, color = Navi.Faint)
                        }
                        IconButton(onClick = { VideoPickBus.song = song }) {
                            Icon(Icons.Rounded.SmartDisplay, if (has) "Anderes Video wählen" else "Video selbst wählen", tint = Navi.Muted)
                        }
                    }
                }
            }
        }
    }
}
