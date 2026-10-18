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
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.navidroid.lidarr.MaPlayer
import kotlinx.coroutines.delay

@Composable
fun PlayerBar(app: DesktopApp) {
    val s by app.player.state.collectAsStateCompat()
    // Fernwiedergabe über Music Assistant anschließen (Main.kt bleibt unverändert)
    remember { if (app.player.remoteBackend == null) app.player.remoteBackend = MaBackend(app) }
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
                    s.remote?.let { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                            Icon(Icons.Rounded.Speaker, null, tint = accent, modifier = Modifier.size(12.dp))
                            Text("Spielt auf ${r.name}", style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
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
            SleepButton(app)
            if (app.settings.videos) IconButton(onClick = { app.rightPanel = if (app.rightPanel == RightPanel.Video) RightPanel.None else RightPanel.Video }) {
                Icon(Icons.Rounded.OndemandVideo, "Video", tint = if (app.rightPanel == RightPanel.Video) accent else Navi.Muted)
            }
            IconButton(onClick = { app.rightPanel = if (app.rightPanel == RightPanel.Lyrics) RightPanel.None else RightPanel.Lyrics }) {
                Icon(Icons.Rounded.Lyrics, "Songtext", tint = if (app.rightPanel == RightPanel.Lyrics) accent else Navi.Muted)
            }
            QueueToggle(app)
            DevicesButton(app)
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = if (s.remote != null) accent else Navi.Muted, modifier = Modifier.padding(start = 8.dp))
            if (s.remote != null) {
                // Lautstärke des Geräts, gesendet erst beim Loslassen
                var remoteVol by remember(s.remote) { mutableStateOf<Float?>(null) }
                ThinSlider(
                    value = remoteVol ?: ((s.remoteVolume ?: 50) / 100f),
                    onValueChange = { remoteVol = it },
                    onValueChangeFinished = { remoteVol?.let { app.player.setRemoteVolume((it * 100).toInt()) }; remoteVol = null },
                    modifier = Modifier.width(110.dp),
                )
            } else ThinSlider(
                value = s.volume / 100f,
                onValueChange = { app.player.setVolume((it * 100).toInt()) },
                onValueChangeFinished = { app.updateSettings(app.settings.copy(volume = app.player.state.value.volume)) },
                modifier = Modifier.width(110.dp),
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

/** Music Assistant über den Helfer, wie in der App. */
private class MaBackend(private val app: DesktopApp) : RemoteBackend {
    private fun ma() = app.settings.takeIf { it.maUrl.isNotBlank() } ?: throw IllegalStateException("Music Assistant ist nicht eingerichtet")

    override suspend fun play(playerId: String, songIds: List<String>) {
        val s = ma()
        val creds = app.session.current ?: throw IllegalStateException("Nicht angemeldet")
        app.helper.maPlay(s.maUrl, s.maToken, playerId, creds, songIds)
    }

    override suspend fun command(playerId: String, name: String, value: Double?) {
        val s = ma()
        app.helper.maCommand(s.maUrl, s.maToken, playerId, name, value)
    }

    override suspend fun state(playerId: String): RemoteStatus {
        val s = ma()
        val p = app.helper.maState(s.maUrl, s.maToken, playerId)
        return RemoteStatus(
            playing = p.state == "playing",
            elapsedMs = ((p.elapsed ?: 0.0) * 1000).toLong(),
            durationMs = p.duration?.let { (it * 1000).toLong() },
            volume = p.volume?.toInt(),
            queueIndex = p.queueIndex,
        )
    }
}

/** Popover über dem Knopf, rechtsbündig, im Fenster gehalten. */
private class AbovePosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
private fun BarPopover(onDismiss: () -> Unit, width: androidx.compose.ui.unit.Dp, padding: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { 16.dp.roundToPx() }
    Popup(popupPositionProvider = remember(gap) { AbovePosition(gap) }, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(width).shadow(24.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(PopoverBg).padding(padding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    }
}

private val PopoverBg = Color(0xFF23262E)

@Composable
private fun BarPill(active: Boolean, label: String?, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(CircleShape).background(if (active) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = if (label.isNullOrEmpty()) 9.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
        if (!label.isNullOrEmpty()) Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

/** Einschlaf-Timer: Knopf mit Restzeit und Auswahl wie im Design. */
@Composable
private fun SleepButton(app: DesktopApp) {
    val sleep by app.player.sleep.collectAsStateCompat()
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleep) {
        while (sleep.endsAt != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val label = when {
        sleep.endsAt != null -> formatTime((sleep.endsAt!! - now).coerceAtLeast(0))
        sleep.endOfTrack -> "Titelende"
        else -> null
    }
    Box {
        BarPill(open || sleep.active, label, { open = !open }) {
            Icon(Icons.Rounded.Bedtime, "Einschlaf-Timer", tint = if (open || sleep.active) accent else Navi.Muted, modifier = Modifier.size(18.dp))
        }
        if (open) BarPopover({ open = false }, 260.dp, 8.dp) {
            Text("Einschlaf-Timer", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp))
            Text("Die Musik wird zum Schluss leiser und hält dann an.", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            val options = listOf<Triple<String, Boolean, () -> Unit>>(
                Triple("Aus", !sleep.active) { app.player.cancelSleep() },
                Triple("15 Minuten", sleep.endsAt != null && sleep.minutes == 15) { app.player.sleepIn(15) },
                Triple("30 Minuten", sleep.endsAt != null && sleep.minutes == 30) { app.player.sleepIn(30) },
                Triple("45 Minuten", sleep.endsAt != null && sleep.minutes == 45) { app.player.sleepIn(45) },
                Triple("1 Stunde", sleep.endsAt != null && sleep.minutes == 60) { app.player.sleepIn(60) },
                Triple("Am Ende des Titels", sleep.endOfTrack) { app.player.sleepAtEndOfTrack() },
            )
            options.forEach { (text, selected, action) ->
                Box(
                    Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable { action(); open = false }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) accent else Navi.Text) }
            }
        }
    }
}

private fun maIcon(p: MaPlayer): ImageVector {
    val provider = (p.provider ?: "").lowercase()
    val type = (p.type ?: "").lowercase()
    return when {
        "cast" in provider || "chromecast" in provider -> Icons.Rounded.Cast
        "tv" in type || "dlna" in provider -> Icons.Rounded.Tv
        else -> Icons.Rounded.Speaker
    }
}

private fun maKind(p: MaPlayer): String {
    val provider = (p.provider ?: "").lowercase()
    return when {
        "alexa" in provider -> "Echo / Alexa"
        "cast" in provider -> "Google Cast"
        "dlna" in provider -> "DLNA"
        "airplay" in provider -> "AirPlay"
        "sonos" in provider -> "Sonos"
        "squeezelite" in provider || "slimproto" in provider -> "Squeezebox"
        "hass" in provider -> "Home Assistant"
        else -> p.provider ?: "Music Assistant"
    }
}

/** Geräte: dieser Rechner oder ein Lautsprecher über Music Assistant. */
@Composable
private fun DevicesButton(app: DesktopApp) {
    val s by app.player.state.collectAsStateCompat()
    var open by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Box {
        BarPill(open || s.remote != null, null, { open = !open }) {
            Icon(Icons.Rounded.Devices, "Geräte", tint = if (open || s.remote != null) accent else Navi.Muted, modifier = Modifier.size(18.dp))
        }
        if (open) BarPopover({ open = false }, 340.dp, 16.dp) { DevicesContent(app, s) { open = false } }
    }
}

@Composable
private fun DevicesContent(app: DesktopApp, s: PlayerState, close: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val maReady = app.settings.maUrl.isNotBlank() && app.helper.configured
    val (players, reload) = rememberLoad("ma-players:$maReady:${app.settings.maUrl}") {
        if (maReady) app.helper.maPlayers(app.settings.maUrl, app.settings.maToken) else emptyList()
    }
    val remote = s.remote
    Text("AKTUELLES GERÄT", style = MaterialTheme.typography.labelSmall, color = Navi.Muted, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Icon(if (remote != null) Icons.Rounded.Speaker else Icons.Rounded.Computer, null, tint = Navi.Bg, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(remote?.name ?: "Dieser Rechner", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (remote != null) "über Music Assistant" else "PipeWire · Lautsprecher / Kopfhörer", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
        }
    }
    s.remoteError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp)) }
    Text("GERÄT AUSWÄHLEN · MUSIC ASSISTANT", style = MaterialTheme.typography.labelSmall, color = Navi.Muted, modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp))
    if (remote != null) DeviceRow(Icons.Rounded.Computer, "Dieser Rechner", "Hier weiterspielen", false) {
        app.player.disconnectRemote()
        close()
    }
    when {
        !maReady -> Text(
            if (!app.helper.configured) "Für Echo, Google Cast, DLNA und AirPlay wird der NaviDroid-Helfer gebraucht."
            else "Für Echo, Google Cast, DLNA und AirPlay: Music Assistant in der App auf dem Handy verbinden – die Einstellung wird übernommen.",
            style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(6.dp),
        )
        else -> when (players) {
            Load.Loading -> Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Suche Geräte …", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
            }
            is Load.Err -> Column(Modifier.padding(6.dp)) {
                Text(players.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = reload) { Text("Erneut versuchen") }
            }
            is Load.Ok -> {
                if (players.value.isEmpty()) Text("Keine Geräte gefunden.", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(6.dp))
                players.value.forEach { p ->
                    DeviceRow(maIcon(p), p.name.ifBlank { p.id }, if (p.available) maKind(p) else maKind(p) + " · nicht erreichbar", remote?.id == p.id) {
                        app.player.connectRemote(RemoteTarget(p.id, p.name.ifBlank { p.id }))
                        close()
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, kind: String, active: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Navi.Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (active) accent else Color(0xFFD5D8DE), modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (active) accent else Navi.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kind, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
