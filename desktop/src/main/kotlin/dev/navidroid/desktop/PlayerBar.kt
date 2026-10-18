package dev.navidroid.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.sp
import dev.navidroid.data.Lyrics
import dev.navidroid.data.SongRef

@Composable
fun PlayerBar(app: DesktopApp) {
    val s by app.player.state.collectAsStateCompat()
    val song = s.current
    val accent = MaterialTheme.colorScheme.primary
    var seeking by remember { mutableStateOf<Float?>(null) }
    Row(
        Modifier.fillMaxWidth().height(88.dp).background(Navi.Bg).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (song != null) {
                Cover(app.client.coverArtUrl(song.coverArt, 200), Modifier.size(56.dp).clickable { song.albumId?.let { app.nav.go(Screen.AlbumPage(it)) } }, 8)
                Column {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { song.artistId?.let { app.nav.go(Screen.ArtistPage(it)) } })
                }
            } else if (s.error != null) {
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = app.player::toggleShuffle) { Icon(Icons.Rounded.Shuffle, "Zufällig", tint = if (s.shuffle) accent else Navi.Muted) }
                IconButton(onClick = app.player::previous) { Icon(Icons.Rounded.SkipPrevious, "Zurück", modifier = Modifier.size(28.dp)) }
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Navi.Text).clickable(onClick = app.player::toggle),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playing) "Pause" else "Abspielen", tint = Navi.Bg) }
                IconButton(onClick = app.player::next) { Icon(Icons.Rounded.SkipNext, "Weiter", modifier = Modifier.size(28.dp)) }
                IconButton(onClick = app.player::cycleRepeat) {
                    Icon(if (s.repeat == Repeat.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Wiederholen", tint = if (s.repeat != Repeat.Off) accent else Navi.Muted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(formatTime(((seeking ?: 0f) * s.durationMs).toLong().takeIf { seeking != null } ?: s.positionMs), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, fontSize = 11.sp)
                Slider(
                    value = seeking ?: if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = { seeking?.let { app.player.seek((it * s.durationMs).toLong()) }; seeking = null },
                    colors = SliderDefaults.colors(thumbColor = Navi.Text, activeTrackColor = Navi.Text, inactiveTrackColor = Navi.Outline),
                    modifier = Modifier.width(480.dp).height(20.dp),
                )
                Text(formatTime(s.durationMs), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, fontSize = 11.sp)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { app.rightPanel = if (app.rightPanel == RightPanel.Lyrics) RightPanel.None else RightPanel.Lyrics }) {
                Icon(Icons.Rounded.Lyrics, "Songtext", tint = if (app.rightPanel == RightPanel.Lyrics) accent else Navi.Muted)
            }
            QueueToggle(app)
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Navi.Muted, modifier = Modifier.padding(start = 8.dp))
            Slider(
                value = s.volume / 100f,
                onValueChange = { app.player.setVolume((it * 100).toInt()) },
                onValueChangeFinished = { app.updateSettings(app.settings.copy(volume = app.player.state.value.volume)) },
                colors = SliderDefaults.colors(thumbColor = Navi.Text, activeTrackColor = Navi.Text, inactiveTrackColor = Navi.Outline),
                modifier = Modifier.width(130.dp),
            )
        }
    }
}

@Composable
fun QueuePanel(app: DesktopApp, modifier: Modifier) {
    val s by app.player.state.collectAsStateCompat()
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Navi.Surface).padding(12.dp)) {
        Text("Warteschlange", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(s.queue) { i, song ->
                val current = i == s.index
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (current) Navi.Surface2 else Color.Transparent)
                        .clickable { app.player.skipTo(i) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Cover(app.client.coverArtUrl(song.coverArt, 100), Modifier.size(40.dp), 6)
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = if (current) accent else Navi.Text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist.orEmpty(), color = Navi.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!current) IconButton(onClick = { app.player.removeAt(i) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, "Entfernen", tint = Navi.Faint, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** Mitlaufender Songtext (synchron von Navidrome bzw. LRCLIB), Klick auf eine Zeile springt dorthin. */
@Composable
fun LyricsPanel(app: DesktopApp, modifier: Modifier) {
    val s by app.player.state.collectAsStateCompat()
    val song = s.current
    var lyrics by remember(song?.id) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(song?.id) { mutableStateOf(true) }
    LaunchedEffect(song?.id) {
        loading = true
        lyrics = song?.let { runCatching { app.lyrics.load(SongRef(it.id, it.title, it.artist, it.album, it.duration * 1000L)) }.getOrNull() }
        loading = false
    }
    val list = rememberLazyListState()
    val l = lyrics
    val active = l?.lineAt(s.positionMs) ?: -1
    LaunchedEffect(active) { if (active > 2) list.animateScrollToItem(active - 2) }
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Navi.Surface).padding(20.dp)) {
        Text("Songtext", style = MaterialTheme.typography.titleLarge)
        l?.let { Text(it.source, style = MaterialTheme.typography.bodySmall, color = Navi.Muted) }
        when {
            song == null -> Text("Nichts wird abgespielt.", color = Navi.Muted, modifier = Modifier.padding(top = 16.dp))
            loading -> Text("Lädt …", color = Navi.Muted, modifier = Modifier.padding(top = 16.dp))
            l == null || l.lines.isEmpty() -> Text("Kein Songtext gefunden.", color = Navi.Muted, modifier = Modifier.padding(top = 16.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(l.lines) { i, line ->
                    val color by animateColorAsState(
                        when {
                            !l.synced -> Navi.Text
                            i == active -> Navi.Text
                            i < active -> Navi.Muted
                            else -> Navi.Faint.copy(alpha = 0.6f)
                        },
                    )
                    Text(
                        line.text.ifBlank { "♪" },
                        color = color,
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (l.synced) 22.sp else 17.sp, lineHeight = 30.sp),
                        modifier = if (l.synced) Modifier.clickable { app.player.seek(line.startMs) } else Modifier,
                    )
                }
            }
        }
    }
}
