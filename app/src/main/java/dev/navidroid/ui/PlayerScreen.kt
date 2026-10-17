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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import dev.navidroid.NaviApp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import dev.navidroid.playback.PlayerUiState
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.delay

@Composable
fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val player = LocalPlayer.current
    val progress by produceState(0f, state.durationMs) {
        while (true) {
            value = if (state.durationMs > 0) (player.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = Navi.Surface2,
        modifier = modifier.fillMaxWidth().height(64.dp).shadow(12.dp, RoundedCornerShape(18.dp)),
    ) {
        Box {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoverImage(state.artworkUri, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), albumCoverAlternative(state.albumId))
                Column(Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(onClick = player::togglePlayPause, shape = CircleShape, color = Navi.Text, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state.isPlaying) "Pause" else "Abspielen",
                            tint = Navi.Bg,
                        )
                    }
                }
                IconButton(onClick = player::next) { Icon(Icons.Rounded.SkipNext, "Nächster Titel", tint = Navi.Text) }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).fillMaxWidth().height(2.dp).background(Color(0xFF2E323B))
            ) {
                Box(Modifier.fillMaxWidth(progress).height(2.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

/** Fortschrittsbalken mit Zeiten, wie im Redesign: dünne Spur, kleiner runder Griff. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackProgress(state: PlayerUiState, soft: Color, modifier: Modifier = Modifier) {
    val player = LocalPlayer.current
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.positionMs
            delay(250)
        }
    }
    val fraction = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = dragging?.let { (it * state.durationMs).toLong() } ?: position
    Column(modifier) {
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekTo((it * state.durationMs).toLong()) }
                dragging = null
            },
            enabled = state.durationMs > 0,
            thumb = { Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White)) },
            track = { s ->
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(s.value).height(4.dp).background(Color.White))
                }
            },
            modifier = Modifier.height(24.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatMs(shownMs), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = soft)
            Spacer(Modifier.weight(1f))
            Text("-" + formatMs((state.durationMs - shownMs).coerceAtLeast(0)), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = soft)
        }
    }
}

@Composable
fun PlayPauseButton(state: PlayerUiState, background: Color, size: Int) {
    val player = LocalPlayer.current
    Surface(onClick = player::togglePlayPause, shape = CircleShape, color = Color.White, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.isPlaying) "Pause" else "Abspielen",
                tint = background,
                modifier = Modifier.size((size * 0.45f).dp),
            )
        }
    }
}

@Composable
fun PlayerScreen(onClose: () -> Unit, onOpenLyrics: () -> Unit, onOpenAlbum: (String) -> Unit, onOpenArtist: (String) -> Unit) {
    val player = LocalPlayer.current
    val s by player.state.collectAsStateWithLifecycle()
    val coverAlternative = albumCoverAlternative(s.albumId)
    val tint = rememberCoverTint(s.artworkUri, fallback = Navi.Surface, alternative = coverAlternative)
    val soft = mutedOn(tint)
    val accent = MaterialTheme.colorScheme.primary
    var showQueue by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as NaviApp

    // Musikvideo vom YouTube-Helfer (optional, in den Lidarr-Einstellungen aktivierbar)
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var showVideo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.mediaId) {
        videoUrl = null
        val artist = s.artist
        if (s.mediaId != null && artist != null && app.helper.videosEnabled) {
            videoUrl = app.helper.findVideo(artist, s.title)
        }
        if (videoUrl == null) showVideo = false
    }

    Column(
        Modifier.fillMaxSize().background(tint).systemBarsPadding().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Rounded.KeyboardArrowDown, "Player schließen", onClose, container = Color.White.copy(alpha = 0.08f))
            if (videoUrl != null) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    SongVideoToggle(showVideo, tint, soft) { showVideo = it }
                }
            } else {
                Column(
                    Modifier.weight(1f).clickable(enabled = s.albumId != null) { s.albumId?.let(onOpenAlbum) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("AUS DEM ALBUM", style = MaterialTheme.typography.labelSmall, color = soft)
                    Text(s.album.orEmpty(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                RoundIconButton(Icons.Rounded.MoreHoriz, "Weitere Optionen", { menu = true }, container = Color.White.copy(alpha = 0.08f))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    s.mediaId?.let { songId ->
                        DropdownMenuItem(
                            text = { Text("Zur Playlist hinzufügen …") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                            onClick = {
                                menu = false
                                addToPlaylist(
                                    listOf(dev.navidroid.data.Song(id = songId, title = s.title, artist = s.artist, album = s.album, albumId = s.albumId)),
                                    s.title,
                                    s.artist.orEmpty(),
                                    null,
                                )
                            },
                        )
                    }
                    s.albumId?.let { id ->
                        DropdownMenuItem(
                            text = { Text("Zum Album") },
                            leadingIcon = { Icon(Icons.Rounded.Album, null) },
                            onClick = { menu = false; onOpenAlbum(id) },
                        )
                    }
                    s.artistId?.let { id ->
                        DropdownMenuItem(
                            text = { Text("Zum Künstler") },
                            leadingIcon = { Icon(Icons.Rounded.Person, null) },
                            onClick = { menu = false; onOpenArtist(id) },
                        )
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val url = videoUrl
            if (showVideo && url != null) {
                SyncedVideo(url, app.helper.authHeaders(), Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            } else {
                CoverImage(
                    s.artworkUri,
                    Modifier.aspectRatio(1f).shadow(24.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)),
                    coverAlternative,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (s.hasItem) s.title else "Keine Wiedergabe",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    s.artist.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = soft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = s.artistId != null) { s.artistId?.let(onOpenArtist) },
                )
            }
            IconButton(onClick = player::toggleStarCurrent, enabled = s.hasItem) {
                Icon(
                    if (s.starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (s.starred) "Aus Favoriten entfernen" else "Zu Favoriten",
                    tint = if (s.starred) accent else Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        PlaybackProgress(s, soft)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = player::toggleShuffle, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Shuffle, "Zufällige Wiedergabe", tint = if (s.shuffle) accent else soft)
            }
            IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, "Vorheriger Titel", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            PlayPauseButton(s, tint, 80)
            IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, "Nächster Titel", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = player::cycleRepeat, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Wiederholen",
                    tint = if (s.repeatMode != Player.REPEAT_MODE_OFF) accent else soft,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BottomPill(Icons.Rounded.Lyrics, "Songtext", onOpenLyrics, Modifier.weight(1f))
            BottomPill(Icons.AutoMirrored.Rounded.QueueMusic, "Warteschlange", { showQueue = true }, Modifier.weight(1f))
        }
    }

    if (showQueue) QueueSheet(s, onDismiss = { showQueue = false })
}

@Composable
private fun SongVideoToggle(video: Boolean, tint: Color, soft: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.width(172.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.08f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(false to "Song", true to "Video").forEach { (value, label) ->
            val active = value == video
            Surface(
                onClick = { onChange(value) },
                shape = RoundedCornerShape(18.dp),
                color = if (active) Color.White else Color.Transparent,
                modifier = Modifier.weight(1f).height(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold),
                        color = if (active) tint else soft,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomPill(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.08f), modifier = modifier.height(56.dp)) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), color = Color.White)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(s: PlayerUiState, onDismiss: () -> Unit) {
    val player = LocalPlayer.current
    val accent = MaterialTheme.colorScheme.primary
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Navi.Surface) {
        Text(
            "Warteschlange",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Text(
            "${s.queue.size} Titel",
            style = MaterialTheme.typography.bodySmall,
            color = Navi.Muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = s.currentIndex.coerceAtLeast(0))
        LazyColumn(state = listState, modifier = Modifier.padding(top = 8.dp)) {
            itemsIndexed(s.queue) { i, entry ->
                val current = i == s.currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (current) Navi.Surface2 else Color.Transparent)
                        .clickable { player.skipTo(i) }
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        if (current) Icon(Icons.Rounded.GraphicEq, "Läuft gerade", tint = accent, modifier = Modifier.size(18.dp))
                        else Text("${i + 1}", color = Navi.Faint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = if (current) accent else Navi.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(entry.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!current) {
                        IconButton(onClick = { player.removeAt(i) }) { Icon(Icons.Rounded.Close, "Entfernen", tint = Navi.Muted) }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }
        }
    }
}
