package dev.navidroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.navidroid.NaviApp

@Composable
fun AlbumScreen(app: NaviApp, id: String, nav: NavController) {
    val player = LocalPlayer.current
    val loader = rememberLoad("album:$id") { app.client.album(id) }
    val title = (loader.state as? Load.Ok)?.value?.name.orEmpty()
    DetailScaffold(title, onBack = { nav.popBackStack() }) {
        LoadContent(loader) { album ->
            val multiDisc = album.song.mapNotNull { it.discNumber }.distinct().size > 1
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    DetailHeader(
                        coverArt = album.coverArt,
                        title = album.name,
                        subtitle = album.artist,
                        meta = listOfNotNull(
                            album.year?.takeIf { it > 0 }?.toString(),
                            "${album.songCount} Titel",
                            formatDuration(album.duration),
                        ).joinToString(" · "),
                        onPlay = { player.play(album.song) },
                        onShuffle = { player.play(album.song, shuffle = true) },
                        onSubtitleClick = album.artistId?.let { artistId -> { nav.openArtist(artistId) } },
                    )
                }
                itemsIndexed(album.song, key = { _, s -> s.id }) { i, song ->
                    val number = song.track?.let { t -> if (multiDisc && song.discNumber != null) song.discNumber * 100 + t else t }
                    SongRow(song, onClick = { player.play(album.song, i) }, number = number)
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(app: NaviApp, id: String, nav: NavController) {
    val loader = rememberLoad("artist:$id") { app.client.artist(id) }
    val title = (loader.state as? Load.Ok)?.value?.name.orEmpty()
    DetailScaffold(title, onBack = { nav.popBackStack() }) {
        LoadContent(loader) { artist ->
            if (artist.album.isEmpty()) return@LoadContent EmptyHint("Keine Alben")
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "${artist.albumCount} Alben",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(artist.album.sortedByDescending { it.year ?: 0 }, key = { it.id }) { album ->
                    AlbumCard(album) { nav.openAlbum(album.id) }
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(app: NaviApp, id: String, nav: NavController) {
    val player = LocalPlayer.current
    val loader = rememberLoad("playlist:$id") { app.client.playlist(id) }
    val title = (loader.state as? Load.Ok)?.value?.name.orEmpty()
    DetailScaffold(title, onBack = { nav.popBackStack() }) {
        LoadContent(loader) { pl ->
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    DetailHeader(
                        coverArt = pl.coverArt,
                        title = pl.name,
                        subtitle = pl.comment?.takeIf { it.isNotBlank() } ?: pl.owner,
                        meta = "${pl.songCount} Titel · ${formatDuration(pl.duration)}",
                        onPlay = { player.play(pl.entry) },
                        onShuffle = { player.play(pl.entry, shuffle = true) },
                    )
                }
                itemsIndexed(pl.entry) { i, song ->
                    SongRow(song, onClick = { player.play(pl.entry, i) }, showCover = true)
                }
            }
        }
    }
}
