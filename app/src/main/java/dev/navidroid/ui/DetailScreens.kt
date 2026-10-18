package dev.navidroid.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.navidroid.NaviApp
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.launch

/** Kopfbereich in der Farbe des Covers mit Cover, Titel und Abspiel-Aktionen. */
@Composable
private fun TintedHeader(
    coverUrl: String?,
    alternative: CoverAlternative?,
    overline: String,
    title: String,
    subtitle: String?,
    meta: String,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSubtitleClick: (() -> Unit)? = null,
    starred: Boolean? = null,
    onToggleStar: () -> Unit = {},
    onAddToPlaylist: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    downloadStatus: String? = null,
    download: (@Composable () -> Unit)? = null,
    menu: (@Composable () -> Unit)? = null,
) {
    val tint = rememberCoverTint(coverUrl, alternative = alternative)
    val soft = mutedOn(tint)
    Column(
        Modifier.fillMaxWidth().background(tint).statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", onBack, container = Color.Black.copy(alpha = 0.3f))
            Spacer(Modifier.weight(1f))
            menu?.invoke()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            CoverImage(
                coverUrl,
                Modifier.size(132.dp).shadow(16.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)),
                alternative,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(overline, style = MaterialTheme.typography.labelSmall, color = soft)
                Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier,
                    )
                }
                Text(meta, style = MaterialTheme.typography.bodySmall, color = soft)
                downloadStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onPlay,
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text("Abspielen", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            download?.invoke()
            OutlineCircle(Icons.Rounded.Shuffle, "Zufällig abspielen", onShuffle)
            onAddToPlaylist?.let { OutlineCircle(Icons.AutoMirrored.Rounded.PlaylistAdd, "Zur Playlist hinzufügen", it) }
            onEdit?.let { OutlineCircle(Icons.Rounded.Edit, "Playlist bearbeiten", it) }
            if (starred != null) {
                OutlineCircle(
                    if (starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (starred) "Aus Favoriten entfernen" else "Zu Favoriten",
                    onToggleStar,
                    tint = if (starred) MaterialTheme.colorScheme.primary else Navi.Text,
                )
            }
        }
    }
}

@Composable
private fun OutlineCircle(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Navi.Text) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, tint = tint) }
    }
}

@Composable
private fun PlainBack(onBack: () -> Unit) {
    Box(Modifier.statusBarsPadding().padding(start = 20.dp, top = 8.dp)) {
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", onBack)
    }
}

@Composable
fun AlbumScreen(app: NaviApp, id: String, nav: NavController) {
    val player = LocalPlayer.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoad("album:$id:${LibraryBus.version}") {
        try {
            app.client.album(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            app.offline.album(id) ?: throw e
        }
    }
    when (val s = loader.state) {
        is Load.Ok -> {
            val album = s.value
            var starred by remember(album.id) { mutableStateOf(album.starred != null) }
            val cover = remember(album.coverArt) { app.client.coverArtUrl(album.coverArt, 600) }
            val alternative: CoverAlternative = remember(album.id) {
                { app.covers.forAlbumInfo(album.musicBrainzId, album.name, album.artist, 500) }
            }
            val dl = app.offline.collection("album:${album.id}")?.let { app.offline.progress(it) }
            val multiDisc = album.song.mapNotNull { it.discNumber }.distinct().size > 1
            CoverAccentTheme(cover, alternative) {
            LazyColumn(Modifier.fillMaxSize().background(Navi.Bg), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    TintedHeader(
                        coverUrl = cover,
                        alternative = alternative,
                        overline = listOfNotNull("ALBUM", album.year?.takeIf { it > 0 }?.toString()).joinToString(" · "),
                        title = album.name,
                        subtitle = album.artist,
                        meta = listOfNotNull("${album.songCount} Titel", album.duration.takeIf { it > 0 }?.let(::formatLong)).joinToString(" · "),
                        onBack = { nav.popBackStack() },
                        onPlay = { player.play(album.song.playableNow(app)) },
                        onShuffle = { player.play(album.song.playableNow(app), shuffle = true) },
                        onSubtitleClick = album.artistId?.let { artistId -> { nav.openArtist(artistId) } },
                        starred = starred,
                        downloadStatus = downloadLine(dl),
                        download = { DownloadToggle(dl, { app.offline.downloadAlbum(album) }, { app.offline.remove("album:${album.id}") }) },
                        menu = { AlbumMenuButton(app, album, nav) { addToPlaylist(album.song, album.name, album.artist.orEmpty(), album.coverArt) } },
                        onToggleStar = {
                            val target = !starred
                            starred = target
                            scope.launch {
                                runCatching { app.client.setStarred(album.id, target, isAlbum = true) }
                                    .onFailure { starred = !target }
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                itemsIndexed(album.song, key = { _, song -> song.id }) { i, song ->
                    val number = song.track?.let { t -> val d = song.discNumber; if (multiDisc && d != null) d * 100 + t else t }
                    SongRow(song, onClick = { playFrom(app, player, album.song, i) }, number = number)
                }
            }
            }
        }
        Load.Loading -> Column(Modifier.fillMaxSize()) { PlainBack { nav.popBackStack() }; CenteredProgress() }
        is Load.Err -> Column(Modifier.fillMaxSize()) { PlainBack { nav.popBackStack() }; ErrorView(s.message, loader.reload) }
    }
}

@Composable
fun PlaylistScreen(app: NaviApp, id: String, nav: NavController) {
    val player = LocalPlayer.current
    val loader = rememberLoad("playlist:$id:${PlaylistBus.version}") {
        try {
            app.client.playlist(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            app.offline.playlist(id) ?: throw e
        }
    }
    when (val s = loader.state) {
        is Load.Ok -> {
            val pl = s.value
            val cover = remember(pl.coverArt) { app.client.coverArtUrl(pl.coverArt, 600) }
            val dl = app.offline.collection("playlist:${pl.id}")?.let { app.offline.progress(it) }
            CoverAccentTheme(cover) {
            LazyColumn(Modifier.fillMaxSize().background(Navi.Bg), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    TintedHeader(
                        coverUrl = cover,
                        alternative = null,
                        overline = "PLAYLIST",
                        title = pl.name,
                        subtitle = pl.comment?.takeIf { it.isNotBlank() } ?: pl.owner,
                        meta = listOfNotNull("${pl.songCount} Titel", pl.duration.takeIf { it > 0 }?.let(::formatLong)).joinToString(" · "),
                        onBack = { nav.popBackStack() },
                        onPlay = { player.play(pl.entry.playableNow(app)) },
                        onShuffle = { player.play(pl.entry.playableNow(app), shuffle = true) },
                        onEdit = { nav.editPlaylist(pl.id) },
                        downloadStatus = downloadLine(dl),
                        download = { DownloadToggle(dl, { app.offline.downloadPlaylist(pl) }, { app.offline.remove("playlist:${pl.id}") }) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                itemsIndexed(pl.entry) { i, song ->
                    SongRow(song, onClick = { playFrom(app, player, pl.entry, i) }, showCover = true)
                }
            }
            }
        }
        Load.Loading -> Column(Modifier.fillMaxSize()) { PlainBack { nav.popBackStack() }; CenteredProgress() }
        is Load.Err -> Column(Modifier.fillMaxSize()) { PlainBack { nav.popBackStack() }; ErrorView(s.message, loader.reload) }
    }
}

@Composable
fun ArtistScreen(app: NaviApp, id: String, nav: NavController) {
    val loader = rememberLoad("artist:$id") { app.client.artist(id) }
    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        PlainBack { nav.popBackStack() }
        LoadContent(loader) { artist ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverArt(artist.coverArt, 300, Modifier.size(88.dp).clip(CircleShape))
                        Column {
                            Text(artist.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (artist.albumCount == 1) "1 Album" else "${artist.albumCount} Alben",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Navi.Muted,
                            )
                        }
                    }
                }
                if (artist.album.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text("Keine Alben", color = Navi.Muted) }
                }
                items(artist.album.sortedByDescending { it.year ?: 0 }, key = { it.id }) { album ->
                    AlbumCard(album) { nav.openAlbum(album.id) }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "youtube") { YouTubeArtistSection(app, artist.name) }
            }
        }
    }
}

/** Ohne Verbindung nur heruntergeladene Titel. */
fun List<dev.navidroid.data.Song>.playableNow(app: NaviApp) =
    if (app.offline.online) this else filter { app.offline.isDownloaded(it.id) }

/** Ab Titel [index] abspielen; offline werden nicht geladene Titel übersprungen. */
fun playFrom(app: NaviApp, player: dev.navidroid.playback.PlayerConnection, songs: List<dev.navidroid.data.Song>, index: Int) {
    val list = songs.playableNow(app)
    val start = list.indexOfFirst { it.id == songs[index].id }
    if (start >= 0) player.play(list, start)
}
