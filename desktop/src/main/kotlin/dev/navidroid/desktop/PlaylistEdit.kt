package dev.navidroid.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.navidroid.data.Playlist
import dev.navidroid.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val EditPanel = Color(0xFF121317)
private val DangerText = Color(0xFFF19A9A)
private val DangerBorder = Color(0xFF6B2E2E)

/** Eintrag mit eigener ID, damit doppelte Titel beim Ziehen auseinandergehalten werden. */
private class EditEntry(val uid: Int, val song: Song)

/** Playlist bearbeiten: umbenennen, sortieren (ziehen), Titel entfernen, löschen. */
@Composable
fun PlaylistEditScreen(app: DesktopApp, id: String) {
    val load = rememberLoad("playlist-edit:$id") { app.client.playlist(id) }
    LoadBox(load) { pl -> PlaylistEditor(app, pl) }
}

@Composable
private fun PlaylistEditor(app: DesktopApp, pl: Playlist) {
    val accent = MaterialTheme.colorScheme.primary
    var name by remember { mutableStateOf(pl.name) }
    var nextUid by remember { mutableIntStateOf(pl.entry.size) }
    val entries = remember { mutableStateListOf<EditEntry>().apply { pl.entry.forEachIndexed { i, s -> add(EditEntry(i, s)) } } }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    fun save() {
        val newIds = entries.map { it.song.id }
        val newName = name.trim()
        val renamed = newName.isNotEmpty() && newName != pl.name
        val reordered = newIds != pl.entry.map { it.id }
        if (!renamed && !reordered) return app.nav.back()
        saving = true
        error = null
        MainScope().launch {
            try {
                if (renamed) app.client.updatePlaylist(pl.id, name = newName)
                // createPlaylist mit playlistId ersetzt alle Titel in der neuen Reihenfolge
                if (reordered) app.client.createPlaylist(null, newIds, playlistId = pl.id)
                app.version++
                app.nav.back()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Speichern fehlgeschlagen"
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.3f).compositeOver(EditPanel), EditPanel), endY = 900f))) {
        // Kopf: Cover, Name, Knöpfe
        Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
                Cover(app.client.coverArtUrl(pl.coverArt, 600), Modifier.size(200.dp).shadow(24.dp, RoundedCornerShape(14.dp)), 14)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PLAYLIST · BEARBEITEN", style = MaterialTheme.typography.labelSmall, color = Navi.Text.copy(alpha = 0.8f))
                    Text("Name", style = MaterialTheme.typography.labelMedium, color = Navi.Text.copy(alpha = 0.8f))
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        enabled = !saving,
                        textStyle = MaterialTheme.typography.headlineLarge.copy(color = Navi.Text, fontSize = 28.sp),
                        cursorBrush = SolidColor(accent),
                        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.25f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    Text(
                        listOfNotNull("${entries.size} Titel", editTotalTime(entries.map { it.song }), "Titel ziehen zum Sortieren").joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = Navi.Text.copy(alpha = 0.75f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = ::save, enabled = !saving && name.isNotBlank(), shape = RoundedCornerShape(22.dp), modifier = Modifier.height(44.dp)) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Fertig", fontWeight = FontWeight.ExtraBold)
                }
                OutlinedButton(onClick = { adding = true }, enabled = !saving, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), modifier = Modifier.height(44.dp)) {
                    Text("Titel hinzufügen", color = Navi.Text, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { app.nav.back() }, enabled = !saving, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), modifier = Modifier.height(44.dp)) {
                    Text("Abbrechen", color = Navi.Text, fontWeight = FontWeight.Bold)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp)) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { confirmDelete = true }, enabled = !saving, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, DangerBorder), modifier = Modifier.height(44.dp)) {
                    Text("Playlist löschen", color = DangerText, fontWeight = FontWeight.Bold)
                }
            }
        }
        ReorderableTracks(app, entries, !saving, Modifier.weight(1f))
    }

    if (adding) AddTracksDialog(app, onAdd = { s -> entries += EditEntry(nextUid++, s) }) { adding = false }
    if (confirmDelete) DeletePlaylistDialog(app, pl) { confirmDelete = false }
}

private fun editTotalTime(songs: List<Song>): String? = songs.sumOf { it.duration }.takeIf { it > 0 }?.let {
    val h = it / 3600
    val m = (it % 3600) / 60
    if (h > 0) "$h Std $m Min" else "$m Min"
}

/** Titelliste mit Ziehgriff zum Umsortieren und Minus zum Entfernen. */
@Composable
private fun ReorderableTracks(app: DesktopApp, entries: MutableList<EditEntry>, enabled: Boolean, modifier: Modifier) {
    val rowHeight = 56.dp
    val gap = 2.dp
    val stepPx = with(LocalDensity.current) { (rowHeight + gap).toPx() }
    var draggingUid by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        entries.forEach { entry ->
            key(entry.uid) {
                val dragging = draggingUid == entry.uid
                Row(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .fillMaxWidth()
                        .height(rowHeight)
                        .then(if (dragging) Modifier.shadow(12.dp, RoundedCornerShape(8.dp)) else Modifier)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (dragging) Navi.Outline else Color.Transparent)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand).pointerInput(entry.uid, enabled) {
                            if (!enabled) return@pointerInput
                            detectDragGestures(
                                onDragStart = { draggingUid = entry.uid; dragOffset = 0f },
                                onDragEnd = { draggingUid = null; dragOffset = 0f },
                                onDragCancel = { draggingUid = null; dragOffset = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val index = entries.indexOfFirst { it.uid == entry.uid }
                                    if (dragOffset > stepPx / 2 && index in 0 until entries.lastIndex) {
                                        entries.add(index + 1, entries.removeAt(index))
                                        dragOffset -= stepPx
                                    } else if (dragOffset < -stepPx / 2 && index > 0) {
                                        entries.add(index - 1, entries.removeAt(index))
                                        dragOffset += stepPx
                                    }
                                },
                            )
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.DragIndicator, "Verschieben", tint = if (dragging) accent else Navi.Muted, modifier = Modifier.size(20.dp)) }
                    Cover(app.client.coverArtUrl(entry.song.coverArt, 120), Modifier.size(44.dp), 6)
                    Column(Modifier.weight(1f)) {
                        Text(entry.song.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(entry.song.album.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.8f))
                    Text(if (entry.song.duration > 0) formatTime(entry.song.duration * 1000L) else "", style = MaterialTheme.typography.bodyMedium, color = Navi.Muted, modifier = Modifier.width(48.dp))
                    IconButton(onClick = { entries.remove(entry) }, enabled = enabled, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Remove, "Aus Playlist entfernen", tint = Navi.Muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (entries.isEmpty()) Text("Die Playlist ist leer.", color = Navi.Muted, modifier = Modifier.padding(16.dp))
    }
}

/** Titel suchen und an die Playlist anhängen (gespeichert wird mit „Fertig“). */
@Composable
private fun AddTracksDialog(app: DesktopApp, onAdd: (Song) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    val added = remember { mutableStateListOf<String>() }
    LaunchedEffect(query) { delay(300); debounced = query.trim() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Navi.Surface,
        title = { Text("Titel hinzufügen") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Titel, Künstler oder Album") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)) {
                    if (debounced.isNotEmpty()) {
                        val res = rememberLoad("pl-add:$debounced") { app.client.search(debounced).song }
                        when (val s = res.first) {
                            Load.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 3.dp) }
                            is Load.Err -> Text(s.message, color = MaterialTheme.colorScheme.error)
                            is Load.Ok -> if (s.value.isEmpty()) Text("Keine Titel für „$debounced“", color = Navi.Muted)
                            else LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                                items(s.value, key = { it.id }) { song ->
                                    val done = song.id in added
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Cover(app.client.coverArtUrl(song.coverArt, 120), Modifier.size(40.dp), 6)
                                        Column(Modifier.weight(1f)) {
                                            Text(song.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(listOfNotNull(song.artist, song.album).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        IconButton(onClick = { added += song.id; onAdd(song) }, enabled = !done) {
                                            Icon(if (done) Icons.Rounded.Check else Icons.Rounded.Add, if (done) "Hinzugefügt" else "Hinzufügen", tint = if (done) MaterialTheme.colorScheme.primary else Navi.Text)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}

@Composable
private fun DeletePlaylistDialog(app: DesktopApp, pl: Playlist, onDismiss: () -> Unit) {
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        containerColor = Navi.Surface,
        title = { Text("„${pl.name}“ löschen?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Die Playlist wird auf dem Server gelöscht. Die Titel selbst bleiben in deiner Bibliothek.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    working = true
                    MainScope().launch {
                        try {
                            app.client.deletePlaylist(pl.id)
                            app.offline.remove(pl.id)
                            app.version++
                            onDismiss()
                            app.nav.root(Screen.Home)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Löschen fehlgeschlagen"
                            working = false
                        }
                    }
                },
                enabled = !working,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06C6C), contentColor = Navi.Bg),
            ) { Text(if (working) "Wird gelöscht …" else "Löschen", fontWeight = FontWeight.ExtraBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Abbrechen") } },
    )
}

/** Dialog „Neue Playlist“ aus der Seitenleiste. */
@Composable
fun NewPlaylistDialog(app: DesktopApp, onDismiss: () -> Unit) = CreatePlaylistDialog(app, emptyList(), onDismiss)
