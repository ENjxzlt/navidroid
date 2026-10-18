package dev.navidroid.desktop

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.navidroid.data.Album
import dev.navidroid.data.Mix
import dev.navidroid.data.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

private val pagePadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 32.dp)

private fun greeting() = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Guten Tag"
    else -> "Guten Abend"
}

private data class HomeData(val recent: List<Album>, val newest: List<Album>, val random: List<Album>)

@Composable
fun HomeScreen(app: DesktopApp) {
    val data = rememberLoad("home") {
        coroutineScope {
            val recent = async { app.client.albumList("recent", 20, 0) }
            val newest = async { app.client.albumList("newest", 20, 0) }
            val random = async { app.client.albumList("random", 20, 0) }
            HomeData(recent.await(), newest.await(), random.await())
        }
    }
    val mixes = rememberLoad("mixes") { app.mixes.mixes() }
    LoadBox(data) { d ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            item { Text(greeting(), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 16.dp)) }
            (mixes.first as? Load.Ok)?.value?.takeIf { it.isNotEmpty() }?.let { list ->
                item {
                    Column {
                        SectionTitle("Deine Mixe")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(list, key = { it.id }) { m -> MixTile(m) { app.nav.go(Screen.MixPage(m.id)) } }
                        }
                    }
                }
            }
            albumRow(app, "Zuletzt gespielt", d.recent)
            albumRow(app, "Neu in deiner Bibliothek", d.newest)
            albumRow(app, "Zufällig für dich", d.random)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumRow(app: DesktopApp, title: String, albums: List<Album>) {
    if (albums.isEmpty()) return
    item(key = title) {
        Column {
            SectionTitle(title)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(albums, key = { it.id }) { a -> AlbumTile(app, a, Modifier.width(190.dp)) { app.nav.go(Screen.AlbumPage(a.id)) } }
            }
        }
    }
}

fun mixColor(hue: Float, light: Float = 0.26f): Color {
    val c = java.awt.Color.getHSBColor(hue, 0.45f, light * 2.2f)
    return Color(c.red, c.green, c.blue)
}

@Composable
private fun MixTile(m: Mix, onClick: () -> Unit) {
    Column(Modifier.width(200.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(mixColor(m.hue))) {
            Text("MIX", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(12.dp))
            Text(m.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 3, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
        Text(m.description, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SearchScreen(app: DesktopApp) {
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    LaunchedEffect(query) { delay(300); debounced = query.trim() }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp)) {
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text("Künstler, Alben, Titel") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.width(520.dp),
        )
        if (debounced.isEmpty()) return@Column
        val res = rememberLoad(debounced) { app.client.search(debounced) }
        LoadBox(res) { r ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (r.artist.isNotEmpty()) {
                    item { SectionTitle("Künstler") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(r.artist, key = { it.id }) { a ->
                                Column(Modifier.width(140.dp).clip(RoundedCornerShape(12.dp)).clickable { app.nav.go(Screen.ArtistPage(a.id)) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Cover(app.client.coverArtUrl(a.coverArt, 300), Modifier.size(124.dp), 62)
                                    Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                }
                if (r.album.isNotEmpty()) {
                    item { SectionTitle("Alben", Modifier.padding(top = 16.dp)) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(r.album, key = { it.id }) { a -> AlbumTile(app, a, Modifier.width(180.dp)) { app.nav.go(Screen.AlbumPage(a.id)) } }
                        }
                    }
                }
                if (r.song.isNotEmpty()) {
                    item { SectionTitle("Titel", Modifier.padding(top = 16.dp)) }
                    itemsIndexed(r.song) { i, s -> SongRow(app, s, i, showCover = true) { app.player.play(r.song, i) } }
                }
            }
        }
    }
}

@Composable
fun AlbumsScreen(app: DesktopApp) {
    val types = listOf("newest" to "Neu", "alphabeticalByName" to "A–Z", "frequent" to "Häufig", "random" to "Zufällig", "starred" to "Favoriten")
    var type by remember { mutableStateOf("newest") }
    val albums = rememberLoad("albums:$type") { app.client.albumList(type, 500, 0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 32.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Alben", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 16.dp))
            types.forEach { (k, label) -> Chip(label, k == type) { type = k } }
        }
        LoadBox(albums) { list ->
            LazyVerticalGrid(GridCells.Adaptive(190.dp), contentPadding = PaddingValues(horizontal = 26.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { a -> AlbumTile(app, a) { app.nav.go(Screen.AlbumPage(a.id)) } }
            }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else Navi.Text,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) MaterialTheme.colorScheme.primary else Navi.Surface2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
fun ArtistsScreen(app: DesktopApp) {
    val artists = rememberLoad("artists") { app.client.artists().flatMap { it.artist } }
    LoadBox(artists) { list ->
        LazyVerticalGrid(GridCells.Adaptive(170.dp), contentPadding = PaddingValues(horizontal = 26.dp, vertical = 16.dp), modifier = Modifier.fillMaxSize()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Künstler", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(6.dp)) }
            items(list, key = { it.id }) { a ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { app.nav.go(Screen.ArtistPage(a.id)) }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(app.client.coverArtUrl(a.coverArt, 300), Modifier.fillMaxWidth().aspectRatio(1f), 999)
                    Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(if (a.albumCount == 1) "1 Album" else "${a.albumCount} Alben", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                }
            }
        }
    }
}

/** Kopf für Album, Playlist und Mix. */
@Composable
private fun Header(app: DesktopApp, cover: String?, overline: String, title: String, subtitle: String?, meta: String, songs: List<Song>, onSubtitle: (() -> Unit)? = null, tile: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
        if (tile != null) Box(Modifier.size(220.dp)) { tile() } else Cover(cover, Modifier.size(220.dp), 14)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(overline, style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
            Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, modifier = if (onSubtitle != null) Modifier.clickable(onClick = onSubtitle) else Modifier)
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { app.player.play(songs) }, shape = RoundedCornerShape(24.dp), modifier = Modifier.height(44.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Text("Abspielen", modifier = Modifier.padding(start = 6.dp), fontWeight = FontWeight.ExtraBold)
                }
                OutlinedButton(onClick = { app.player.play(songs, shuffle = true) }, shape = RoundedCornerShape(24.dp), modifier = Modifier.height(44.dp)) {
                    Icon(Icons.Rounded.Shuffle, null)
                    Text("Zufällig", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

private fun totalTime(songs: List<Song>): String? = songs.sumOf { it.duration }.takeIf { it > 0 }?.let {
    val h = it / 3600
    val m = (it % 3600) / 60
    if (h > 0) "$h Std $m Min" else "$m Min"
}

@Composable
fun AlbumScreen(app: DesktopApp, id: String) {
    val album = rememberLoad("album:$id") { app.client.album(id) }
    LoadBox(album) { a ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding) {
            item {
                Header(
                    app, app.client.coverArtUrl(a.coverArt, 600),
                    listOfNotNull("ALBUM", a.year?.takeIf { it > 0 }?.toString()).joinToString(" · "),
                    a.name, a.artist,
                    listOfNotNull("${a.song.size} Titel", totalTime(a.song)).joinToString(" · "),
                    a.song,
                    onSubtitle = a.artistId?.let { aid -> { app.nav.go(Screen.ArtistPage(aid)) } },
                )
            }
            itemsIndexed(a.song, key = { _, s -> s.id }) { i, s -> SongRow(app, s, i) { app.player.play(a.song, i) } }
        }
    }
}

@Composable
fun PlaylistScreen(app: DesktopApp, id: String) {
    val pl = rememberLoad("playlist:$id") { app.client.playlist(id) }
    LoadBox(pl) { p ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding) {
            item {
                Header(app, app.client.coverArtUrl(p.coverArt, 600), "PLAYLIST", p.name, p.comment?.takeIf { it.isNotBlank() } ?: p.owner,
                    listOfNotNull("${p.entry.size} Titel", totalTime(p.entry)).joinToString(" · "), p.entry)
            }
            itemsIndexed(p.entry) { i, s -> SongRow(app, s, i, showCover = true) { app.player.play(p.entry, i) } }
        }
    }
}

@Composable
fun MixScreen(app: DesktopApp, id: String) {
    val mix = rememberLoad("mix:$id") { app.mixes.mix(id) ?: app.mixes.mixes().first { it.id == id } }
    LoadBox(mix) { m ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding) {
            item {
                Header(app, null, "MIX · TÄGLICH NEU", m.name, m.description, "${m.songs.size} Titel", m.songs, tile = {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(mixColor(m.hue))) {
                        Text("MIX", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(14.dp))
                    }
                })
            }
            itemsIndexed(m.songs) { i, s -> SongRow(app, s, i, showCover = true) { app.player.play(m.songs, i) } }
        }
    }
}

@Composable
fun ArtistScreen(app: DesktopApp, id: String) {
    val artist = rememberLoad("artist:$id") { app.client.artist(id) }
    LoadBox(artist) { a ->
        LazyVerticalGrid(GridCells.Adaptive(190.dp), contentPadding = PaddingValues(horizontal = 26.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(8.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(app.client.coverArtUrl(a.coverArt, 400), Modifier.size(160.dp).clip(CircleShape), 999)
                    Column {
                        Text("KÜNSTLER", style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
                        Text(a.name, style = MaterialTheme.typography.displaySmall)
                        Text(if (a.albumCount == 1) "1 Album" else "${a.albumCount} Alben", color = Navi.Muted)
                        Button(
                            onClick = {
                                kotlinx.coroutines.MainScope().launch {
                                    val songs = runCatching { app.client.similarSongsByArtist(a.id, 50) }.getOrDefault(emptyList())
                                    if (songs.isNotEmpty()) app.player.play(songs)
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier.padding(top = 12.dp).height(40.dp),
                        ) { Text("Künstler-Radio") }
                    }
                }
            }
            items(a.album.sortedByDescending { it.year ?: 0 }, key = { it.id }) { al -> AlbumTile(app, al) { app.nav.go(Screen.AlbumPage(al.id)) } }
        }
    }
}
