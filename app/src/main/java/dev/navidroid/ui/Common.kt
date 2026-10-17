package dev.navidroid.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import dev.navidroid.NaviApp
import dev.navidroid.data.Album
import dev.navidroid.data.Song
import dev.navidroid.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

val LocalPlayer = staticCompositionLocalOf<PlayerConnection> { error("Kein PlayerConnection bereitgestellt") }

// ---- Navigation ----

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val ALBUM = "album/{id}"
    const val ARTIST = "artist/{id}"
    const val PLAYLIST = "playlist/{id}"
    const val PLAYER = "player"
}

fun NavController.openAlbum(id: String) = navigate("album/$id")
fun NavController.openArtist(id: String) = navigate("artist/$id")
fun NavController.openPlaylist(id: String) = navigate("playlist/$id")

// ---- Laden mit Zustand, der Navigation überlebt ----

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val reload: () -> Unit)

class LoadStore : ViewModel() {
    val states = mutableStateMapOf<String, Load<Any?>>()
}

/** Lädt Daten einmalig pro [key] im ViewModel des aktuellen Navigationsziels. */
@Composable
fun <T> rememberLoad(key: String, block: suspend () -> T): Loader<T> {
    val store: LoadStore = viewModel()
    val currentBlock by rememberUpdatedState(block)
    val start = {
        store.states[key] = Load.Loading
        store.viewModelScope.launch {
            store.states[key] = try {
                Load.Ok(currentBlock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Err(e.message ?: e.javaClass.simpleName)
            }
        }
        Unit
    }
    LaunchedEffect(key) { if (key !in store.states) start() }
    @Suppress("UNCHECKED_CAST")
    val state = (store.states[key] ?: Load.Loading) as Load<T>
    return Loader(state, start)
}

@Composable
fun <T> LoadContent(loader: Loader<T>, content: @Composable (T) -> Unit) {
    when (val s = loader.state) {
        Load.Loading -> CenteredProgress()
        is Load.Err -> ErrorView(s.message, loader.reload)
        is Load.Ok -> content(s.value)
    }
}

@Composable
fun CenteredProgress(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.size(12.dp))
        OutlinedButton(onClick = onRetry) { Text("Erneut versuchen") }
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// ---- Bausteine ----

@Composable
fun CoverArt(id: String?, size: Int, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as NaviApp
    val url = remember(id, size) { app.client.coverArtUrl(id, size) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun AlbumCard(album: Album, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        CoverArt(album.coverArt, 300, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            album.artist.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SongRow(song: Song, onClick: () -> Unit, number: Int? = null, showCover: Boolean = false) {
    val player = LocalPlayer.current
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val leading: (@Composable () -> Unit)? = when {
        showCover -> {
            { CoverArt(song.coverArt, 300, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) }
        }
        number != null -> {
            {
                Text(
                    "$number",
                    modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> null
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = leading,
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(song.artist, formatDuration(song.duration)).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Optionen") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Als Nächstes abspielen") },
                        onClick = {
                            player.playNext(listOf(song))
                            menu = false
                            Toast.makeText(context, "Wird als Nächstes gespielt", Toast.LENGTH_SHORT).show()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Zur Warteschlange hinzufügen") },
                        onClick = {
                            player.enqueue(listOf(song))
                            menu = false
                            Toast.makeText(context, "Zur Warteschlange hinzugefügt", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) { content() }
    }
}

@Composable
fun DetailHeader(
    coverArt: String?,
    title: String,
    subtitle: String?,
    meta: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSubtitleClick: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverArt(coverArt, 600, Modifier.fillMaxWidth(0.65f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier,
            )
        }
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.size(6.dp))
                Text("Abspielen")
            }
            OutlinedButton(onClick = onShuffle) {
                Icon(Icons.Filled.Shuffle, null)
                Spacer(Modifier.size(6.dp))
                Text("Zufällig")
            }
        }
    }
}

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatMs(ms: Long): String = formatDuration((ms / 1000).toInt())
