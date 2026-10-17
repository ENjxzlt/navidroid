package dev.navidroid.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import dev.navidroid.NaviApp
import dev.navidroid.data.Playlist
import dev.navidroid.data.Song
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Zähler, der nach jeder Playlist-Änderung steigt – damit geladene Listen neu geholt werden. */
object PlaylistBus {
    var version by mutableIntStateOf(0)
        private set

    fun changed() {
        version++
    }

    /** Offenes "Zur Playlist hinzufügen"-Blatt (null = geschlossen). */
    var addRequest by mutableStateOf<AddToPlaylistRequest?>(null)
}

data class AddToPlaylistRequest(val songs: List<Song>, val title: String, val subtitle: String, val coverArt: String?)

fun addToPlaylist(songs: List<Song>, title: String, subtitle: String, coverArt: String?) {
    if (songs.isNotEmpty()) PlaylistBus.addRequest = AddToPlaylistRequest(songs, title, subtitle, coverArt)
}

// ---- Zur Playlist hinzufügen ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(app: NaviApp, request: AddToPlaylistRequest) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    var playlists by remember { mutableStateOf<Load<List<Playlist>>>(Load.Loading) }
    val added = remember { mutableStateMapOf<String, Boolean>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val ids = remember(request) { request.songs.map { it.id } }
    val close = { PlaylistBus.addRequest = null }

    suspend fun reload() {
        playlists = try {
            Load.Ok(app.client.playlists())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Err(e.message ?: "Playlists konnten nicht geladen werden")
        }
    }
    LaunchedEffect(Unit) { reload() }

    fun create() {
        val name = newName.trim()
        if (name.isEmpty() || creating) return
        creating = true
        scope.launch {
            try {
                val pl = app.client.createPlaylist(name, ids)
                newName = ""
                pl?.id?.let { added[it] = true }
                PlaylistBus.changed()
                reload()
                Toast.makeText(context, "Playlist „$name“ erstellt", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Fehler", Toast.LENGTH_SHORT).show()
            } finally {
                creating = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = close, containerColor = Navi.Surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverArt(request.coverArt, 300, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text("Zur Playlist hinzufügen", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
                        Text(
                            listOf(request.title, request.subtitle).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Navi.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(Navi.Bg)
                        .border(1.dp, accent, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, tint = accent, modifier = Modifier.size(20.dp))
                    Box(Modifier.weight(1f)) {
                        if (newName.isEmpty()) Text("Neue Playlist", color = Navi.Muted, style = MaterialTheme.typography.bodyLarge)
                        BasicTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Navi.Text),
                            cursorBrush = SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { create() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Surface(
                        onClick = { create() },
                        enabled = newName.isNotBlank() && !creating,
                        shape = RoundedCornerShape(18.dp),
                        color = if (newName.isNotBlank()) accent else Navi.Outline,
                        modifier = Modifier.height(36.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            if (creating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text("Erstellen", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
            item { Text("DEINE PLAYLISTS", style = MaterialTheme.typography.labelSmall, color = Navi.Muted, modifier = Modifier.padding(top = 4.dp)) }
            when (val p = playlists) {
                Load.Loading -> item { CenteredProgress(Modifier.fillMaxWidth().padding(16.dp)) }
                is Load.Err -> item { Text(p.message, color = MaterialTheme.colorScheme.error) }
                is Load.Ok -> {
                    if (p.value.isEmpty()) item { Text("Noch keine Playlists – leg oben eine an.", color = Navi.Muted) }
                    items(p.value, key = { it.id }) { pl ->
                        val isAdded = added[pl.id] == true
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !isAdded && busy[pl.id] != true) {
                                busy[pl.id] = true
                                scope.launch {
                                    try {
                                        app.client.updatePlaylist(pl.id, add = ids)
                                        added[pl.id] = true
                                        PlaylistBus.changed()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Toast.makeText(context, e.message ?: "Fehler", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        busy.remove(pl.id)
                                    }
                                }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CoverArt(pl.coverArt, 300, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (isAdded) "Hinzugefügt" else "${pl.songCount} Titel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isAdded) accent else Navi.Muted,
                                )
                            }
                            when {
                                busy[pl.id] == true -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                isAdded -> Box(Modifier.size(28.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                                }
                                else -> Box(Modifier.size(28.dp).border(2.dp, Color(0xFF3A3F48), CircleShape))
                            }
                        }
                    }
                }
            }
            item {
                Surface(onClick = close, shape = RoundedCornerShape(26.dp), color = accent, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Fertig", style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

// ---- Dialoge ----

@Composable
fun PlaylistNameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun DeletePlaylistDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("„$name“ löschen?") },
        text = { Text("Die Playlist wird auf dem Server gelöscht. Die Titel selbst bleiben in deiner Bibliothek.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ---- Playlist bearbeiten ----

private class EditEntry(val uid: Int, val song: Song)

@Composable
fun PlaylistEditScreen(app: NaviApp, id: String, nav: NavController, onAddTracks: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    var original by remember { mutableStateOf<Playlist?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    val entries = remember { mutableStateListOf<EditEntry>() }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        try {
            val pl = app.client.playlist(id)
            original = pl
            name = pl.name
            entries.clear()
            pl.entry.forEachIndexed { i, s -> entries += EditEntry(i, s) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Playlist konnte nicht geladen werden"
        }
    }

    fun save() {
        val pl = original ?: return
        saving = true
        scope.launch {
            try {
                val newIds = entries.map { it.song.id }
                if (name.isNotBlank() && name != pl.name) app.client.updatePlaylist(pl.id, name = name.trim())
                if (newIds != pl.entry.map { it.id }) app.client.createPlaylist(null, newIds, playlistId = pl.id)
                PlaylistBus.changed()
                nav.popBackStack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Speichern fehlgeschlagen", Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        Column(
            Modifier.fillMaxWidth().background(Navi.Surface).statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { nav.popBackStack() }) { Text("Abbrechen", color = Color(0xFFC9CDD4)) }
                Text("Playlist bearbeiten", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Surface(
                    onClick = { save() },
                    enabled = original != null && !saving && name.isNotBlank(),
                    shape = RoundedCornerShape(20.dp),
                    color = accent,
                    modifier = Modifier.height(40.dp),
                ) {
                    Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text("Fertig", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverArt(original?.coverArt, 300, Modifier.size(88.dp).clip(RoundedCornerShape(14.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("NAME", style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Navi.Text, fontWeight = FontWeight.Bold),
                        cursorBrush = SolidColor(accent),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Navi.Bg)
                            .border(1.dp, Navi.Outline, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                    Text(
                        "${entries.size} Titel · ${formatLong(entries.sumOf { it.song.duration })}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Navi.Muted,
                    )
                }
            }
        }

        when {
            error != null -> ErrorView(error!!) { nav.popBackStack() }
            original == null -> CenteredProgress()
            else -> ReorderableList(entries, Modifier.weight(1f))
        }

        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                onClick = onAddTracks,
                shape = RoundedCornerShape(24.dp),
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, Navi.Outline),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Titel hinzufügen", style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(onClick = { confirmDelete = true }, enabled = original != null, modifier = Modifier.fillMaxWidth()) {
                Text("Playlist löschen", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        DeletePlaylistDialog(
            name = original?.name.orEmpty(),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    try {
                        app.client.deletePlaylist(id)
                        PlaylistBus.changed()
                        nav.popBackStack(Routes.HOME, inclusive = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Löschen fehlgeschlagen", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }
}

/** Titelliste mit Ziehgriff zum Umsortieren und Minus zum Entfernen. */
@Composable
private fun ReorderableList(entries: MutableList<EditEntry>, modifier: Modifier) {
    val rowHeight = 64.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var draggingUid by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val accent = MaterialTheme.colorScheme.primary

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp)) {
        entries.forEach { entry ->
            key(entry.uid) {
                val dragging = draggingUid == entry.uid
                Row(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .fillMaxWidth()
                        .height(rowHeight)
                        .then(if (dragging) Modifier.shadow(12.dp, RoundedCornerShape(12.dp)) else Modifier)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (dragging) Navi.Surface2 else Navi.Bg)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconButton(onClick = { entries.remove(entry) }) {
                        Icon(Icons.Rounded.RemoveCircleOutline, "Entfernen", tint = MaterialTheme.colorScheme.error)
                    }
                    CoverArt(entry.song.coverArt, 300, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), albumCoverAlternative(entry.song.albumId))
                    Column(Modifier.weight(1f)) {
                        Text(entry.song.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier
                            .size(48.dp)
                            .pointerInput(entry.uid) {
                                detectDragGestures(
                                    onDragStart = { draggingUid = entry.uid; dragOffset = 0f },
                                    onDragEnd = { draggingUid = null; dragOffset = 0f },
                                    onDragCancel = { draggingUid = null; dragOffset = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val index = entries.indexOfFirst { it.uid == entry.uid }
                                        if (dragOffset > rowPx / 2 && index < entries.lastIndex) {
                                            entries.add(index + 1, entries.removeAt(index))
                                            dragOffset -= rowPx
                                        } else if (dragOffset < -rowPx / 2 && index > 0) {
                                            entries.add(index - 1, entries.removeAt(index))
                                            dragOffset += rowPx
                                        }
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.DragHandle, "Verschieben", tint = if (dragging) accent else Navi.Faint)
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            Text("Die Playlist ist leer.", color = Navi.Muted, modifier = Modifier.padding(16.dp))
        }
    }
}
