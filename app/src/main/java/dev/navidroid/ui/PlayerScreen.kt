package dev.navidroid.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import dev.navidroid.playback.PlayerUiState
import kotlinx.coroutines.delay

@Composable
fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit) {
    val player = LocalPlayer.current
    val progress by produceState(0f, state.durationMs) {
        while (true) {
            value = if (state.durationMs > 0) (player.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = state.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.artist.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = player::togglePlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "Pause" else "Abspielen",
                    )
                }
                IconButton(onClick = player::next) { Icon(Icons.Filled.SkipNext, "Nächster Titel") }
            }
        }
    }
}

@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val player = LocalPlayer.current
    val s by player.state.collectAsStateWithLifecycle()
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.positionMs
            delay(250)
        }
    }
    val fraction = if (s.durationMs > 0) (position.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = dragging?.let { (it * s.durationMs).toLong() } ?: position

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Schließen") }
            Text(
                s.album.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showQueue = true }) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Warteschlange")
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = s.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        if (!s.hasItem) {
            Text("Keine Wiedergabe", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            s.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            s.artist.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(16.dp))
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekTo((it * s.durationMs).toLong()) }
                dragging = null
            },
            enabled = s.durationMs > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatMs(shownMs), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatMs(s.durationMs), style = MaterialTheme.typography.bodySmall)
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = player::toggleShuffle) {
                Icon(
                    Icons.Filled.Shuffle,
                    "Zufällige Wiedergabe",
                    tint = if (s.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Vorheriger Titel", Modifier.size(36.dp))
            }
            FilledIconButton(onClick = player::togglePlayPause, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (s.isPlaying) "Pause" else "Abspielen",
                    Modifier.size(40.dp),
                )
            }
            IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, "Nächster Titel", Modifier.size(36.dp))
            }
            IconButton(onClick = player::cycleRepeat) {
                Icon(
                    if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    "Wiederholen",
                    tint = if (s.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showQueue) QueueSheet(s, onDismiss = { showQueue = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(s: PlayerUiState, onDismiss: () -> Unit) {
    val player = LocalPlayer.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Warteschlange (${s.queue.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = s.currentIndex.coerceAtLeast(0))
        LazyColumn(state = listState) {
            itemsIndexed(s.queue) { i, entry ->
                val current = i == s.currentIndex
                ListItem(
                    modifier = Modifier.clickable { player.skipTo(i) },
                    leadingContent = {
                        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                            if (current) Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.primary)
                            else Text("${i + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    headlineContent = {
                        Text(
                            entry.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    supportingContent = { Text(entry.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        if (!current) {
                            IconButton(onClick = { player.removeAt(i) }) { Icon(Icons.Filled.Close, "Entfernen") }
                        }
                    },
                )
            }
        }
    }
}
