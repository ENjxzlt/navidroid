package dev.navidroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.navidroid.data.Album
import dev.navidroid.data.Song
import kotlinx.coroutines.CancellationException

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** Lädt einmal pro [key]; [retry] lädt erneut. */
@Composable
fun <T> rememberLoad(key: Any, block: suspend () -> T): Pair<Load<T>, () -> Unit> {
    var state by remember(key) { mutableStateOf<Load<T>>(Load.Loading) }
    var attempt by remember(key) { mutableStateOf(0) }
    LaunchedEffect(key, attempt) {
        state = Load.Loading
        state = try {
            Load.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Err(e.message ?: e.javaClass.simpleName)
        }
    }
    return state to { attempt++ }
}

@Composable
fun <T> LoadBox(load: Pair<Load<T>, () -> Unit>, content: @Composable (T) -> Unit) {
    when (val s = load.first) {
        Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 3.dp) }
        is Load.Err -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            TextButton(onClick = load.second) { Text("Erneut versuchen") }
        }
        is Load.Ok -> content(s.value)
    }
}

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, round: Int = 8) {
    Box(modifier.clip(RoundedCornerShape(round.dp)).background(Navi.Surface2), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Navi.Faint)
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(bottom = 12.dp))
}

@Composable
fun AlbumTile(app: DesktopApp, album: Album, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (hovered) Navi.Surface else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Cover(app.client.coverArtUrl(album.coverArt, 400), Modifier.fillMaxWidth().aspectRatio(1f))
        Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(album.artist, album.year?.takeIf { it > 0 }?.toString()).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Titelzeile wie in der App, mit Rechtsklick-/⋮-Menü. */
@Composable
fun SongRow(app: DesktopApp, song: Song, index: Int, showCover: Boolean = false, onPlay: () -> Unit) {
    val playerState by app.player.state.collectAsStateCompat()
    val current = playerState.current?.id == song.id
    val accent = MaterialTheme.colorScheme.primary
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (current) Navi.Surface else if (hovered) Navi.Surface.copy(alpha = 0.6f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (current) Icon(Icons.Rounded.GraphicEq, "Läuft", tint = accent, modifier = Modifier.size(18.dp))
            else Text("${song.track ?: index + 1}", style = MaterialTheme.typography.bodyMedium, color = Navi.Faint)
        }
        if (showCover) Cover(app.client.coverArtUrl(song.coverArt, 120), Modifier.size(40.dp), 6)
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = if (current) accent else Navi.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showCover) Text(song.album.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.7f))
        if (song.duration > 0) Text(formatTime(song.duration * 1000L), style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Optionen", tint = Navi.Faint) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Als Nächstes abspielen") }, onClick = { menu = false; app.player.playNext(listOf(song)) })
                DropdownMenuItem(text = { Text("Zur Warteschlange hinzufügen") }, onClick = { menu = false; app.player.enqueue(listOf(song)) })
                DropdownMenuItem(text = { Text("Song-Radio starten") }, onClick = { menu = false; app.startRadio(song) })
                song.albumId?.let { id -> DropdownMenuItem(text = { Text("Zum Album") }, onClick = { menu = false; app.nav.go(Screen.AlbumPage(id)) }) }
                song.artistId?.let { id -> DropdownMenuItem(text = { Text("Zum Künstler") }, onClick = { menu = false; app.nav.go(Screen.ArtistPage(id)) }) }
            }
        }
    }
}

@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat() = collectAsState()

/** Schmaler Schieberegler wie bei Spotify: dünne Spur, Griff erscheint beim Darüberfahren. */
@Composable
fun ThinSlider(value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit, modifier: Modifier = Modifier) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val active = hovered || dragging
    val accent = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.height(16.dp).hoverable(hover)
            .pointerInput(Unit) {
                detectTapGestures { o -> onValueChange((o.x / size.width).coerceIn(0f, 1f)); onValueChangeFinished() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; onValueChange((o.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging = false; onValueChangeFinished() },
                    onDragCancel = { dragging = false; onValueChangeFinished() },
                ) { change, _ -> onValueChange((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val v = value.coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Navi.Outline))
        Box(Modifier.fillMaxWidth(v).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (active) accent else Navi.Text))
        if (active) {
            Box(Modifier.offset(x = maxWidth * v - 6.dp).size(12.dp).clip(RoundedCornerShape(6.dp)).background(Navi.Text))
        }
    }
}
