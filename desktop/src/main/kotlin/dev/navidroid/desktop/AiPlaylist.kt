package dev.navidroid.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.navidroid.data.Song
import dev.navidroid.lidarr.AiMissing
import dev.navidroid.lidarr.AiTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/** Zustand der KI-Playlist; lebt mit der App weiter, damit ein Wechsel der Seite das Ergebnis nicht verwirft. */
class AiPlaylistState(private val app: DesktopApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var wish by mutableStateOf("")
    var count by mutableStateOf(40)
    var preferRare by mutableStateOf(false)
    var suggestMissing by mutableStateOf(false)
    var refine by mutableStateOf("")

    var loading by mutableStateOf(false)
        private set
    var refining by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var name by mutableStateOf("")
        private set
    var description by mutableStateOf("")
        private set
    val tracks = mutableStateListOf<AiTrack>()
    var missing by mutableStateOf<List<AiMissing>>(emptyList())
        private set
    var saving by mutableStateOf(false)
        private set
    var savedId by mutableStateOf<String?>(null)
        private set

    /** Anfrage-Zustand fehlender Alben: false = läuft, true = angefordert, String = Hinweis/Fehler. */
    val requests = mutableStateMapOf<String, Any>()

    val hasResult: Boolean get() = tracks.isNotEmpty()

    enum class Mode { New, Variant, Refine }

    fun generate(mode: Mode = Mode.New) {
        val creds = app.session.current ?: return
        if (wish.isBlank() || loading) return
        loading = true
        refining = mode != Mode.New
        error = null
        val previous = if (mode == Mode.New) null else tracks.map { it.id }
        val refineText = if (mode == Mode.Refine) refine.trim().ifEmpty { null } else null
        scope.launch {
            try {
                val result = app.helper.aiPlaylist(creds, wish, count, preferRare, suggestMissing, previous, refineText)
                name = result.name
                description = result.description
                tracks.clear()
                tracks += result.tracks
                missing = result.missing
                requests.clear()
                savedId = null
                refine = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Playlist konnte nicht erstellt werden"
            } finally {
                loading = false
            }
        }
    }

    fun save() {
        if (saving || tracks.isEmpty()) return
        saving = true
        scope.launch {
            try {
                val pl = app.client.createPlaylist(name.ifBlank { "KI-Playlist" }, tracks.map { it.id })
                app.version++
                savedId = pl?.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Speichern fehlgeschlagen"
            } finally {
                saving = false
            }
        }
    }

    fun play() {
        app.player.play(tracks.map { it.toSong() })
    }

    /** Fehlendes Album bei Lidarr anfordern: nur bei eindeutigem Treffer (gleicher Albumtitel). */
    fun request(m: AiMissing) {
        val key = "${m.artist}|${m.album}"
        if (requests[key] == false || requests[key] == true) return
        requests[key] = false
        scope.launch {
            try {
                val hits = app.lidarr.searchAlbums("${m.artist} ${m.album}")
                val sameTitle = hits.filter { norm(it.title) == norm(m.album) }
                val hit = sameTitle.firstOrNull { norm(it.artistName) == norm(m.artist) } ?: sameTitle.firstOrNull()
                when {
                    hit == null -> requests[key] = "Bei Lidarr nicht gefunden"
                    hit.complete -> requests[key] = "Schon vorhanden"
                    else -> {
                        app.lidarr.requestAlbum(hit)
                        requests[key] = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                requests[key] = e.message ?: "Anfordern fehlgeschlagen"
            }
        }
    }

    fun reset() {
        tracks.clear()
        missing = emptyList()
        requests.clear()
        error = null
        savedId = null
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    companion object {
        private val states = WeakHashMap<DesktopApp, AiPlaylistState>()
        fun of(app: DesktopApp): AiPlaylistState = states.getOrPut(app) { AiPlaylistState(app) }
    }
}

private val ideas = listOf("Workout", "Fokus", "Sonntagmorgen", "Roadtrip", "Abends ruhig", "Party")
private val Field = Color(0xFF17151F)
private val FieldBorder = Color(0xFF3A3550)

/** KI-Playlist über den Claude-Worker des Helfers (Design „KI-Playlist“). */
@Composable
fun AiPlaylistScreen(app: DesktopApp) {
    if (!app.helper.configured) {
        HelperMissingHint(app, "Playlist mit KI", "Die KI-Playlist läuft über deinen Helfer und dessen Claude-Worker. Richte den Helfer in den Einstellungen ein.")
        return
    }
    val st = AiPlaylistState.of(app)
    Row(
        Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 56.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        AiForm(st, Modifier.width(420.dp).fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Field.copy(alpha = 0.85f))) {
            when {
                st.loading -> AiLoading(st.refining)
                st.hasResult -> AiResult(app, st)
                else -> AiEmpty()
            }
        }
    }
}

/** Hinweis, wenn der Helfer fehlt (auch von den Bibliothek-Werkzeugen genutzt). */
@Composable
fun HelperMissingHint(app: DesktopApp, title: String, text: String) {
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(text, color = Navi.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp).width(440.dp))
        Button(onClick = { app.nav.root(Screen.Settings) }) { Text("Einstellungen öffnen") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiForm(st: AiPlaylistState, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PLAYLIST MIT KI", style = MaterialTheme.typography.labelSmall, color = accent)
            Text("Was willst du hören?", style = MaterialTheme.typography.displaySmall)
            Text("Aus deiner Bibliothek · über deinen Claude-Worker", style = MaterialTheme.typography.bodyMedium, color = Navi.Muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Beschreibung", style = MaterialTheme.typography.labelMedium, color = Navi.Muted)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Field)
                    .border(1.dp, if (st.wish.isNotEmpty()) accent.copy(alpha = 0.7f) else FieldBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                if (st.wish.isEmpty()) {
                    Text("z. B. „Ruhige Musik zum Kochen, eher Indie und ein bisschen Soul“", color = Navi.Faint, style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize))
                }
                BasicTextField(
                    value = st.wish,
                    onValueChange = { st.wish = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Navi.Text, fontSize = MaterialTheme.typography.titleMedium.fontSize),
                    cursorBrush = SolidColor(accent),
                    minLines = 4,
                    // Strg+Enter startet direkt
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && e.isCtrlPressedCompat()) { st.generate(); true } else false
                    },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ideas.forEach { idea ->
                    OutlineChip(idea) { st.wish = if (st.wish.isBlank()) idea else "${st.wish.trimEnd()}, $idea" }
                }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Field).padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Länge", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                Segmented(listOf(20 to "20 Titel", 40 to "40 Titel", 60 to "60 Titel"), st.count) { st.count = it }
            }
            ToggleLine("Selten gehörte bevorzugen", "Mehr Neues aus deiner Sammlung", st.preferRare) { st.preferRare = it }
            ToggleLine("Fehlende Alben vorschlagen", "Passende Musik, die du noch nicht hast", st.suggestMissing) { st.suggestMissing = it }
        }
        if (!st.hasResult) st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = { st.generate() },
            enabled = st.wish.isNotBlank() && !st.loading,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Playlist zusammenstellen", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
        }
    }
}

private fun androidx.compose.ui.input.key.KeyEvent.isCtrlPressedCompat() = isCtrlPressed || isMetaPressed

@Composable
private fun OutlineChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).border(1.dp, FieldBorder, RoundedCornerShape(17.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF0F0E14)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            val sel = value == selected
            Box(
                Modifier.height(30.dp).clip(RoundedCornerShape(8.dp)).background(if (sel) Color(0xFF2C2840) else Color.Transparent)
                    .clickable { onSelect(value) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelMedium, color = if (sel) Navi.Text else Navi.Muted) }
        }
    }
}

@Composable
private fun ToggleLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AiEmpty() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text("Beschreibe Stimmung, Anlass oder Stil", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Claude sucht passende Titel aus deiner Bibliothek. Danach kannst du die Playlist anpassen, abspielen oder speichern.",
            style = MaterialTheme.typography.bodyMedium, color = Navi.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).width(380.dp),
        )
    }
}

@Composable
private fun AiLoading(refining: Boolean) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator(strokeWidth = 3.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            if (refining) "Claude passt die Playlist an …" else "Claude stellt deine Playlist zusammen …",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text("Das dauert meist eine knappe Minute.", style = MaterialTheme.typography.bodyMedium, color = Navi.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun durationText(seconds: Int): String {
    val m = seconds / 60
    return if (m >= 60) "${m / 60} Std ${m % 60} Min" else "$m Min"
}

@Composable
private fun AiResult(app: DesktopApp, st: AiPlaylistState) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Mosaik aus den ersten vier Covern
            Column(Modifier.size(88.dp).clip(RoundedCornerShape(12.dp))) {
                listOf(0, 2).forEach { row ->
                    Row {
                        listOf(0, 1).forEach { col ->
                            Cover(app.client.coverArtUrl(st.tracks.getOrNull(row + col)?.coverArt, 200), Modifier.size(44.dp), 0)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(13.dp))
                    Text("VON CLAUDE", style = MaterialTheme.typography.labelSmall, color = accent, modifier = Modifier.padding(start = 6.dp))
                }
                Text(st.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(st.description.takeIf { it.isNotBlank() }, "${st.tracks.size} Titel", durationText(st.tracks.sumOf { it.duration })).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = Navi.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { st.generate(AiPlaylistState.Mode.Variant) }) { Icon(Icons.Rounded.Refresh, "Neue Variante", tint = Navi.Muted) }
            IconButton(onClick = { st.reset() }) { Icon(Icons.Rounded.Close, "Verwerfen", tint = Navi.Muted) }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 2.dp)) {
            items(st.tracks, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { app.player.play(st.tracks.map { it.toSong() }, st.tracks.indexOf(t)) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Cover(app.client.coverArtUrl(t.coverArt, 120), Modifier.size(40.dp), 6)
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(t.artist, t.album).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (t.duration > 0) Text(formatTime(t.duration * 1000L), style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                    IconButton(onClick = { st.tracks.remove(t) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, "Aus Vorschlag entfernen", tint = Navi.Faint, modifier = Modifier.size(18.dp))
                    }
                }
            }
            items(st.missing, key = { "missing:${it.artist}|${it.album}" }) { m -> MissingRow(app, st, m) }
        }

        // Anpassen: gleiche Anfrage mit Änderungswunsch und den bisherigen Titeln
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF0F0E14))
                    .border(1.dp, FieldBorder, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (st.refine.isEmpty()) Text("Anpassen, z. B. „mehr Frauenstimmen“", color = Navi.Faint, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    value = st.refine,
                    onValueChange = { st.refine = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Navi.Text),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { st.generate(AiPlaylistState.Mode.Refine) }),
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) { st.generate(AiPlaylistState.Mode.Refine); true } else false
                    },
                )
            }
            OutlinedButton(
                onClick = { st.generate(AiPlaylistState.Mode.Refine) },
                enabled = st.refine.isNotBlank(),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, FieldBorder),
                modifier = Modifier.height(44.dp),
            ) { Text("Anpassen") }
        }
        st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { st.play() }, enabled = st.tracks.isNotEmpty(), shape = RoundedCornerShape(23.dp), modifier = Modifier.weight(1f).height(46.dp)) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Abspielen", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold))
            }
            val saved = st.savedId
            OutlinedButton(
                onClick = { if (saved != null) app.nav.go(Screen.PlaylistPage(saved)) else st.save() },
                enabled = !st.saving && st.tracks.isNotEmpty(),
                shape = RoundedCornerShape(23.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Navi.Text),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                when {
                    st.saving -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    saved != null -> Text("Gespeichert · Öffnen", style = MaterialTheme.typography.titleSmall)
                    else -> Text("Als Playlist speichern", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

private fun AiTrack.toSong() = Song(id = id, title = title, artist = artist, album = album, albumId = albumId, coverArt = coverArt, duration = duration, year = year)

@Composable
private fun MissingRow(app: DesktopApp, st: AiPlaylistState, m: AiMissing) {
    val state = st.requests["${m.artist}|${m.album}"]
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF221E2E)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Fehlt dir: ${m.artist} – ${m.album}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = (state as? String) ?: m.reason.ifBlank { "Würde gut passen" }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = if (state is String) MaterialTheme.colorScheme.error else Navi.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val lidarr = app.lidarrStore.config != null
        Box(
            Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF2C2840))
                .clickable(enabled = state != false && state != true) { if (lidarr) st.request(m) else app.nav.root(Screen.Settings) }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    !lidarr -> "Lidarr einrichten"
                    state == false -> "…"
                    state == true -> "Angefordert"
                    else -> "Anfordern"
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
