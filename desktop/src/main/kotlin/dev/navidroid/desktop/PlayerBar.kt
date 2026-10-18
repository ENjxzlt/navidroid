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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.navidroid.data.Lyrics
import dev.navidroid.data.Song
import dev.navidroid.data.SongRef
import dev.navidroid.lidarr.LyricsTranslation
import dev.navidroid.lidarr.SongInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PlayerBar(app: DesktopApp) {
    val s by app.player.state.collectAsStateCompat()
    val song = s.current
    val accent = MaterialTheme.colorScheme.primary
    var seeking by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    val starred = remember { mutableStateMapOf<String, Boolean>() }
    var infoFor by remember { mutableStateOf<Song?>(null) }
    Row(
        Modifier.fillMaxWidth().height(88.dp).background(Navi.Bg).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (song != null) {
                Cover(app.client.coverArtUrl(song.coverArt, 200), Modifier.size(56.dp).clickable { song.albumId?.let { app.nav.go(Screen.AlbumPage(it)) } }, 8)
                Column(Modifier.weight(1f, fill = false)) {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { song.artistId?.let { app.nav.go(Screen.ArtistPage(it)) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isStarred = starred[song.id] ?: (song.starred != null)
                    IconButton(onClick = {
                        val id = song.id
                        val target = !isStarred
                        starred[id] = target
                        scope.launch {
                            try {
                                app.client.setStarred(id, target)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                starred[id] = !target
                            }
                        }
                    }) {
                        Icon(
                            if (isStarred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (isStarred) "Aus Favoriten entfernen" else "Zu Favoriten",
                            tint = if (isStarred) accent else Navi.Muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (app.helper.configured) IconButton(onClick = { infoFor = song }) {
                        Icon(Icons.Rounded.Info, "Über diesen Song", tint = Navi.Muted, modifier = Modifier.size(20.dp))
                    }
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
                ThinSlider(
                    value = seeking ?: if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = { seeking?.let { app.player.seek((it * s.durationMs).toLong()) }; seeking = null },
                    modifier = Modifier.width(480.dp),
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
            ThinSlider(
                value = s.volume / 100f,
                onValueChange = { app.player.setVolume((it * 100).toInt()) },
                onValueChangeFinished = { app.updateSettings(app.settings.copy(volume = app.player.state.value.volume)) },
                modifier = Modifier.width(130.dp),
            )
        }
    }
    infoFor?.let { SongInfoDialog(app, it) { infoFor = null } }
}

/** „Über diesen Song“: kurze Zusammenfassung und Fakten vom Helfer (Claude). */
@Composable
fun SongInfoDialog(app: DesktopApp, song: Song, onDismiss: () -> Unit) {
    val (load, _) = rememberLoad(song.id) { app.helper.songInfo(song.artist.orEmpty(), song.title, song.album) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        title = { Text("Über „${song.title}“", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (load) {
                    Load.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Claude schreibt …", color = Navi.Muted)
                    }
                    is Load.Err -> Text("Konnte keine Infos laden: ${load.message}", color = MaterialTheme.colorScheme.error)
                    is Load.Ok<SongInfo> -> {
                        val info = load.value
                        if (info.summary.isNotBlank()) Text(info.summary, style = MaterialTheme.typography.bodyLarge, color = Navi.Text)
                        info.facts.forEach { f ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("•", color = MaterialTheme.colorScheme.primary)
                                Text(f, style = MaterialTheme.typography.bodyMedium, color = Navi.Text)
                            }
                        }
                        Text(
                            if (info.known) "Von Claude zusammengefasst – kann Fehler enthalten." else "Claude kennt diesen Song nicht genau.",
                            style = MaterialTheme.typography.bodySmall, color = Navi.Faint, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        },
        containerColor = Navi.Surface,
    )
}

@Composable
fun QueuePanel(app: DesktopApp, modifier: Modifier) {
    val s by app.player.state.collectAsStateCompat()
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Navi.Surface).padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Warteschlange", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { app.rightPanel = RightPanel.None }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "Schließen", tint = Navi.Muted, modifier = Modifier.size(18.dp))
            }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(s.queue) { i, song ->
                val current = i == s.index
                if (i == s.index + 1) Text(
                    "ALS NÄCHSTES", style = MaterialTheme.typography.labelSmall, color = Navi.Muted,
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                )
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
                    if (current) Text("Läuft", style = MaterialTheme.typography.labelMedium, color = accent)
                    else IconButton(onClick = { app.player.removeAt(i) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, "Entfernen", tint = Navi.Faint, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** Mitlaufender Songtext (synchron von Navidrome bzw. LRCLIB), Klick auf eine Zeile springt dorthin; optional mit Übersetzung (Claude). */
@Composable
fun LyricsPanel(app: DesktopApp, modifier: Modifier) {
    val s by app.player.state.collectAsStateCompat()
    val song = s.current
    val accent = MaterialTheme.colorScheme.primary
    var lyrics by remember(song?.id) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(song?.id) { mutableStateOf(true) }
    LaunchedEffect(song?.id) {
        loading = true
        lyrics = song?.let { runCatching { app.lyrics.load(SongRef(it.id, it.title, it.artist, it.album, it.duration * 1000L)) }.getOrNull() }
        loading = false
    }
    // Übersetzung – wird beim Songwechsel zurückgesetzt
    var translate by remember(song?.id) { mutableStateOf(false) }
    var translation by remember(song?.id) { mutableStateOf<LyricsTranslation?>(null) }
    var translating by remember(song?.id) { mutableStateOf(false) }
    var note by remember(song?.id) { mutableStateOf<String?>(null) }
    val l = lyrics
    LaunchedEffect(song?.id, translate, l) {
        if (!translate || song == null || l == null || translation != null) return@LaunchedEffect
        if (l.lines.isEmpty()) { note = "Schon auf Deutsch"; translate = false; return@LaunchedEffect }
        translating = true
        note = null
        try {
            val r = app.helper.translateLyrics(song.id, song.artist.orEmpty(), song.title, l.lines.map { it.text })
            if (r.language.equals("de", ignoreCase = true) || r.lines.isEmpty()) {
                note = "Schon auf Deutsch"
                translate = false
            } else translation = r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note = "Übersetzung fehlgeschlagen"
            translate = false
        } finally {
            translating = false
        }
    }
    val shown = translation?.takeIf { translate }
    val list = rememberLazyListState()
    val active = l?.lineAt(s.positionMs) ?: -1
    LaunchedEffect(active) { if (active > 2) list.animateScrollToItem(active - 2) }
    val tint = lerp(Color(0xFF3A2216), accent, 0.12f).let { lerp(it, Color.Black, 0.15f) }
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(tint).padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Songtext", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (app.helper.configured && song != null && l != null && l.lines.isNotEmpty()) {
                Row(
                    Modifier.clip(CircleShape)
                        .background(if (translate) Navi.Text else Color.White.copy(alpha = 0.12f))
                        .clickable { translate = !translate; note = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val fg = if (translate) Navi.Bg else Navi.Text
                    if (translating) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = fg)
                    else Icon(Icons.Rounded.Translate, null, tint = fg, modifier = Modifier.size(14.dp))
                    Text("Übersetzen", style = MaterialTheme.typography.labelMedium, color = fg)
                }
            }
            IconButton(onClick = { app.rightPanel = RightPanel.None }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "Schließen", tint = Navi.Muted, modifier = Modifier.size(18.dp))
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(top = 4.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                song == null -> Text("Nichts wird abgespielt.", color = Navi.Muted, modifier = Modifier.padding(top = 16.dp))
                loading -> Text("Lädt …", color = Navi.Muted, modifier = Modifier.padding(top = 16.dp))
                l == null || l.lines.isEmpty() -> Text("Kein Songtext gefunden.", color = Navi.Muted, modifier = Modifier.padding(top = 16.dp))
                else -> LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state = list, contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(l.lines) { i, line ->
                        val color by animateColorAsState(
                            when {
                                !l.synced -> Navi.Text
                                i == active -> Navi.Text
                                i < active -> Navi.Text.copy(alpha = 0.55f)
                                else -> Navi.Text.copy(alpha = 0.3f)
                            },
                        )
                        val translated = shown?.lines?.getOrNull(i)?.takeIf { it.isNotBlank() && line.text.isNotBlank() }
                        Column(if (l.synced) Modifier.fillMaxWidth().clickable { app.player.seek(line.startMs) } else Modifier.fillMaxWidth()) {
                            Text(
                                line.text.ifBlank { "♪" },
                                color = color,
                                style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (l.synced) 22.sp else 17.sp, lineHeight = if (l.synced) 30.sp else 24.sp),
                            )
                            if (translated != null) Text(
                                translated,
                                color = color.copy(alpha = color.alpha * 0.7f),
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (l.synced) 15.sp else 13.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        l?.let {
            Text(
                "Songtext: ${it.source}" + if (shown != null) " · Übersetzung: Claude" else "",
                style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
