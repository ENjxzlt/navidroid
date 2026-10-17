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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.navigation.NavController
import dev.navidroid.NaviApp
import dev.navidroid.data.Album
import dev.navidroid.data.Mix
import dev.navidroid.data.Playlist
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private enum class HomeFilter(val label: String) { All("Alle"), Music("Musik"), Mixes("Mixe") }

private data class HomeData(
    val recent: List<Album>,
    val newest: List<Album>,
    val forgotten: List<Album>,
    val playlists: List<Playlist>,
)

private sealed interface QuickItem {
    data class AlbumItem(val album: Album) : QuickItem
    data class PlaylistItem(val playlist: Playlist) : QuickItem
}

fun mixColor(hue: Float, lightness: Float = 0.26f): Color = Color(ColorUtils.HSLToColor(floatArrayOf(hue * 360f, 0.42f, lightness)))

@Composable
fun HomeStartTab(app: NaviApp, nav: NavController, onOpenSettings: () -> Unit, onOpenRequests: () -> Unit = {}) {
    var filter by rememberSaveable { mutableStateOf(HomeFilter.All) }
    val data = rememberLoad("home:${PlaylistBus.version}:${LibraryBus.version}") {
        coroutineScope {
            val recent = async { app.client.albumList("recent", 12, 0) }
            val newest = async { app.client.albumList("newest", 12, 0) }
            val all = async { runCatching { app.client.albumList("alphabeticalByName", 500, 0) }.getOrDefault(emptyList()) }
            val playlists = async { runCatching { app.client.playlists() }.getOrDefault(emptyList()) }
            HomeData(
                recent = recent.await(),
                newest = newest.await(),
                forgotten = all.await().filter { it.played != null }.sortedBy { it.played }.take(12),
                playlists = playlists.await(),
            )
        }
    }
    val mixes = rememberLoad("mixes") { app.mixes.mixes() }
    val initial = app.session.current?.username?.firstOrNull()?.uppercase() ?: "?"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Row(
                Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(onClick = onOpenSettings, shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                HomeFilter.entries.forEach { f -> PillChip(f.label, filter == f) { filter = f } }
            }
        }

        if (filter != HomeFilter.Music) {
            item(key = "daylist") { DaylistTile(app, nav, Modifier.padding(horizontal = 20.dp)) }
        }

        val d = (data.state as? Load.Ok)?.value
        if (filter != HomeFilter.Mixes) {
            if (d != null) {
                val quick = (d.recent.take(6).map { QuickItem.AlbumItem(it) } + d.playlists.take(2).map { QuickItem.PlaylistItem(it) }).take(8)
                if (quick.isNotEmpty()) item {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        quick.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { q -> QuickTile(q, nav, Modifier.weight(1f)) }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else if (data.state is Load.Loading) {
                item { CenteredProgress(Modifier.fillMaxWidth().padding(32.dp)) }
            } else if (data.state is Load.Err) {
                item { Text((data.state as Load.Err).message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
        }

        if (filter != HomeFilter.Music) {
            when (val m = mixes.state) {
                is Load.Ok -> if (m.value.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader("Deine Mixe")
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(m.value, key = { it.id }) { mix -> MixTile(mix, Modifier.width(150.dp)) { nav.navigate("mix/${mix.id}") } }
                        }
                    }
                }
                Load.Loading -> item { CenteredProgress(Modifier.fillMaxWidth().padding(16.dp)) }
                is Load.Err -> item { Text("Mixe: ${m.message}", color = Navi.Muted, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
        }

        if (filter != HomeFilter.Mixes) {
            suggestionRows(app, onOpenRequests)
            youtubeReleasesRow(app)
        }

        if (filter != HomeFilter.Mixes && d != null) {
            albumRow("Zuletzt gespielt", d.recent, nav)
            albumRow("Neu in deiner Bibliothek", d.newest, nav)
            albumRow("Lange nicht gehört", d.forgotten, nav)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumRow(title: String, albums: List<Album>, nav: NavController) {
    if (albums.isEmpty()) return
    item(key = "row_$title") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(title)
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(albums, key = { it.id }) { album -> AlbumCard(album, Modifier.width(132.dp)) { nav.openAlbum(album.id) } }
            }
        }
    }
}

@Composable
private fun QuickTile(item: QuickItem, nav: NavController, modifier: Modifier) {
    val (title, cover, onClick) = when (item) {
        is QuickItem.AlbumItem -> Triple(item.album.name, item.album.coverArt) { nav.openAlbum(item.album.id) }
        is QuickItem.PlaylistItem -> Triple(item.playlist.name, item.playlist.coverArt) { nav.openPlaylist(item.playlist.id) }
    }
    val app = LocalContext.current.applicationContext as NaviApp
    val alternative: CoverAlternative? = (item as? QuickItem.AlbumItem)?.album?.let { a ->
        remember(a.id) { { app.covers.forAlbumInfo(a.musicBrainzId, a.name, a.artist, 250) } }
    }
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(8.dp)).background(Navi.Surface2).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(cover, 300, Modifier.size(56.dp), alternative)
        Text(
            title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

@Composable
fun MixTile(mix: Mix, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(mixColor(mix.hue))) {
            Text("MIX", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(10.dp))
            Text(
                mix.name,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp),
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 16.dp),
            )
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp).background(mixColor(mix.hue, 0.72f)))
        }
        Text(mix.description, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MixScreen(app: NaviApp, id: String, nav: NavController) {
    val player = LocalPlayer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoad("mix:$id") {
        try {
            app.mixes.mix(id) ?: app.mixes.mixes().first { it.id == id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            app.offline.collection("mix:$id")?.let { c -> dev.navidroid.data.Mix(id, c.name, c.subtitle.orEmpty(), c.songs, 0.6f) } ?: throw e
        }
    }
    when (val s = loader.state) {
        Load.Loading -> CenteredProgress()
        is Load.Err -> ErrorView(s.message, loader.reload)
        is Load.Ok -> {
            val mix = s.value
            LazyColumn(Modifier.fillMaxSize().background(Navi.Bg), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Column(
                        Modifier.fillMaxWidth().background(mixColor(mix.hue, 0.16f)).statusBarsPadding()
                            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() }, container = Color.Black.copy(alpha = 0.3f))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                            MixTile(mix, Modifier.width(132.dp)) {}
                            Column(Modifier.padding(bottom = 30.dp)) {
                                Text("MIX · ${mix.songs.size} TITEL", style = MaterialTheme.typography.labelSmall, color = mutedOn(mixColor(mix.hue, 0.16f)))
                                Text(mix.name, style = MaterialTheme.typography.headlineSmall)
                                Text(downloadLine(app.offline.collection("mix:${mix.id}")?.let { app.offline.progress(it) }) ?: "Täglich neu gemischt", style = MaterialTheme.typography.bodySmall, color = mutedOn(mixColor(mix.hue, 0.16f)))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(onClick = { player.play(mix.songs.playableNow(app)) }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).height(48.dp)) {
                                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Abspielen", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                            DownloadToggle(
                                app.offline.collection("mix:${mix.id}")?.let { app.offline.progress(it) },
                                { app.offline.downloadMix(mix.id, mix.name, mix.description, mix.songs) },
                                { app.offline.remove("mix:${mix.id}") },
                            )
                            RoundIconButton(Icons.Rounded.Shuffle, "Zufällig abspielen", { player.play(mix.songs.playableNow(app), shuffle = true) }, container = Color.White.copy(alpha = 0.1f), size = 48)
                            RoundIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Als Playlist speichern", {
                                scope.launch {
                                    try {
                                        app.client.createPlaylist(mix.name, mix.songs.map { it.id })
                                        PlaylistBus.changed()
                                        Toast.makeText(context, "Als Playlist „${mix.name}“ gespeichert", Toast.LENGTH_SHORT).show()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Toast.makeText(context, e.message ?: "Fehler", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }, container = Color.White.copy(alpha = 0.1f), size = 48)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                itemsIndexed(mix.songs) { i, song ->
                    SongRow(song, onClick = { playFrom(app, player, mix.songs, i) }, showCover = true)
                }
            }
        }
    }
}
