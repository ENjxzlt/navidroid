package dev.navidroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.navidroid.data.Album
import dev.navidroid.data.MixRepository
import dev.navidroid.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Farbe der Kontextmenüs (wie im Design). */
private val MenuBg = Color(0xFF23262E)
private val Danger = Color(0xFFFF8A80)

// ---------------------------------------------------------------- Rechtsklick

/** Merkt sich, wo rechts geklickt wurde; das Menü öffnet sich genau dort. */
class ContextMenuState {
    var expanded by mutableStateOf(false)
    var position by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberContextMenu() = remember { ContextMenuState() }

/** Rechtsklick öffnet [state]; wird vor den Kindern ausgewertet, damit ein clickable darunter nichts merkt. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.contextMenu(state: ContextMenuState): Modifier = onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { e ->
    if (e.buttons.isSecondaryPressed) {
        state.position = e.changes.first().position
        state.expanded = true
        e.changes.forEach { it.consume() }
    }
}

/** Unsichtbarer Anker an der Klickposition; muss im selben Box liegen, der [contextMenu] trägt. */
@Composable
fun ContextMenuAnchor(state: ContextMenuState, content: @Composable () -> Unit) {
    Box(Modifier.offset { IntOffset(state.position.x.roundToInt(), state.position.y.roundToInt()) }.size(1.dp)) { content() }
}

@Composable
private fun NaviMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MenuBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.width(260.dp),
        content = content,
    )
}

@Composable
private fun MenuItem(label: String, color: Color = Navi.Text, trailing: String? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = trailing?.let { { Text(it, color = Navi.Muted) } },
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(8.dp)),
    )
}

// ---------------------------------------------------------------- Favoriten

/** Lokal geänderte Favoriten, bis die Listen neu geladen sind. */
private val starOverrides = mutableStateMapOf<String, Boolean>()

fun isStarred(song: Song): Boolean = starOverrides[song.id] ?: (song.starred != null)

private fun toggleStar(app: DesktopApp, song: Song) {
    val now = !isStarred(song)
    starOverrides[song.id] = now
    MainScope().launch {
        try {
            app.client.setStarred(song.id, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            starOverrides[song.id] = !now
        }
    }
}

// ---------------------------------------------------------------- Zur Playlist hinzufügen

/** Untermenü: eigene Playlists (ohne Mixe/Daylist des Plugins) und „Neue Playlist …“. */
@Composable
private fun PlaylistPickerItems(app: DesktopApp, songs: suspend () -> List<Song>, onBack: () -> Unit, onNew: () -> Unit, onDone: () -> Unit) {
    val playlists = rememberLoad("playlists:${app.version}") { app.client.playlists() }
    DropdownMenuItem(
        text = { Text("Zur Playlist hinzufügen", style = MaterialTheme.typography.labelLarge, color = Navi.Muted) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", tint = Navi.Muted, modifier = Modifier.size(18.dp)) },
        onClick = onBack,
    )
    HorizontalDivider(color = Navi.Outline, modifier = Modifier.padding(vertical = 4.dp))
    MenuItem("+ Neue Playlist …", color = MaterialTheme.colorScheme.primary) { onNew() }
    when (val s = playlists.first) {
        Load.Loading -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        is Load.Err -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        is Load.Ok -> s.value.filterNot { MixRepository.isPluginPlaylist(it.comment) }.forEach { pl ->
            MenuItem(pl.name) {
                onDone()
                MainScope().launch {
                    runCatching {
                        val ids = songs().map { it.id }
                        if (ids.isNotEmpty()) app.client.updatePlaylist(pl.id, add = ids)
                    }
                    app.version++
                }
            }
        }
    }
}

/** „Neue Playlist“ – optional gleich mit Titeln; danach wird sie geöffnet. */
@Composable
fun CreatePlaylistDialog(app: DesktopApp, songs: List<Song>, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun create() {
        val n = name.trim()
        if (n.isEmpty() || busy) return
        busy = true
        MainScope().launch {
            try {
                val pl = app.client.createPlaylist(n, songs.map { it.id })
                app.version++
                onDismiss()
                pl?.id?.let { app.nav.go(Screen.PlaylistPage(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Playlist konnte nicht erstellt werden"
                busy = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Navi.Surface,
        title = { Text("Neue Playlist") },
        text = {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (songs.isNotEmpty()) Text(if (songs.size == 1) "Mit „${songs[0].title}“" else "Mit ${songs.size} Titeln", color = Navi.Muted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { create() }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = ::create, enabled = name.isNotBlank() && !busy) { Text(if (busy) "Wird erstellt …" else "Erstellen", fontWeight = FontWeight.ExtraBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Abbrechen") } },
    )
}

// ---------------------------------------------------------------- Song-Menü

/**
 * Menü für einen Titel (Rechtsklick oder ⋮). Bleibt immer komponiert, damit die Dialoge
 * („Über diesen Song“, „Neue Playlist“) das Schließen des Menüs überleben.
 */
@Composable
fun SongContextMenu(app: DesktopApp, song: Song, expanded: Boolean, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    var info by remember { mutableStateOf(false) }
    var newPlaylist by remember { mutableStateOf(false) }
    val close = { page = 0; onDismiss() }
    NaviMenu(expanded, close) {
        if (page == 1) {
            PlaylistPickerItems(app, { listOf(song) }, onBack = { page = 0 }, onNew = { close(); newPlaylist = true }, onDone = close)
            return@NaviMenu
        }
        MenuItem("Als Nächstes abspielen") { close(); app.player.playNext(listOf(song)) }
        MenuItem("Zur Warteschlange") { close(); app.player.enqueue(listOf(song)) }
        MenuItem("Zur Playlist hinzufügen", trailing = "›") { page = 1 }
        HorizontalDivider(color = Navi.Outline, modifier = Modifier.padding(vertical = 4.dp))
        MenuItem("Song-Radio starten") { close(); app.startRadio(song) }
        if (app.helper.configured) MenuItem("Über diesen Song") { close(); info = true }
        song.albumId?.let { id -> MenuItem("Zum Album") { close(); app.nav.go(Screen.AlbumPage(id)) } }
        song.artistId?.let { id -> MenuItem("Zum Künstler") { close(); app.nav.go(Screen.ArtistPage(id)) } }
        HorizontalDivider(color = Navi.Outline, modifier = Modifier.padding(vertical = 4.dp))
        val downloaded = app.offline.isSongDownloaded(song.id)
        when {
            // Einzeln geladene Titel lassen sich hier wieder entfernen, Titel aus Alben/Playlists auf deren Seite
            downloaded && app.offline.state(song.id) == DownloadState.Done -> MenuItem("Download entfernen") { close(); app.offline.remove(song.id) }
            downloaded -> Unit
            else -> MenuItem("Herunterladen") { close(); app.offline.download("song", song.id, song.title, song.coverArt, listOf(song)) }
        }
        MenuItem(if (isStarred(song)) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") { close(); toggleStar(app, song) }
    }
    if (info) SongInfoDialog(app, song) { info = false }
    if (newPlaylist) CreatePlaylistDialog(app, listOf(song)) { newPlaylist = false }
}

/** Titelzeile wie in der App: Klick spielt, Rechtsklick/⋮ öffnet das Song-Menü, Haken = offline verfügbar. */
@Composable
fun SongListRow(app: DesktopApp, song: Song, index: Int, showCover: Boolean = false, onPlay: () -> Unit) {
    val playerState by app.player.state.collectAsStateCompat()
    val current = playerState.current?.id == song.id
    val accent = MaterialTheme.colorScheme.primary
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val ctx = rememberContextMenu()
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().contextMenu(ctx)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if (current || ctx.expanded) Navi.Surface else if (hovered) Navi.Surface.copy(alpha = 0.6f) else Color.Transparent)
                .hoverable(hover).clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (current) Icon(Icons.Rounded.GraphicEq, "Läuft", tint = accent, modifier = Modifier.size(18.dp))
                else Text("${song.track?.takeIf { !showCover } ?: (index + 1)}", style = MaterialTheme.typography.bodyMedium, color = Navi.Faint)
            }
            if (showCover) Cover(app.client.coverArtUrl(song.coverArt, 120), Modifier.size(40.dp), 6)
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = if (current) accent else Navi.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (app.offline.isSongDownloaded(song.id)) Icon(Icons.Rounded.CheckCircle, "Heruntergeladen", tint = accent, modifier = Modifier.size(13.dp))
                    Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showCover) Text(song.album.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.7f))
            if (song.duration > 0) Text(formatTime(song.duration * 1000L), style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Optionen", tint = Navi.Faint) }
                SongContextMenu(app, song, menu) { menu = false }
            }
        }
        ContextMenuAnchor(ctx) { SongContextMenu(app, song, ctx.expanded) { ctx.expanded = false } }
    }
}

// ---------------------------------------------------------------- Album-Menü

private suspend fun songsOf(app: DesktopApp, album: Album): List<Song> = album.song.ifEmpty { app.client.album(album.id).song }

/**
 * Menü für ein Album. [full] = mit Abspielen/Zufällig/Warteschlange (Kacheln); auf der Albumseite
 * gibt es dafür eigene Knöpfe. [onDeleted] läuft nach dem Löschen (z. B. zurück navigieren).
 */
@Composable
fun AlbumContextMenu(app: DesktopApp, album: Album, expanded: Boolean, onDismiss: () -> Unit, full: Boolean = true, onDeleted: () -> Unit = {}) {
    var page by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var covers by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var newPlaylist by remember { mutableStateOf<List<Song>?>(null) }
    val close = { page = 0; onDismiss() }
    fun withSongs(block: (List<Song>) -> Unit) {
        MainScope().launch { runCatching { songsOf(app, album) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(block) }
    }
    NaviMenu(expanded, close) {
        if (page == 1) {
            PlaylistPickerItems(
                app, { songsOf(app, album) }, onBack = { page = 0 },
                onNew = { close(); withSongs { newPlaylist = it } }, onDone = close,
            )
            return@NaviMenu
        }
        if (full) {
            MenuItem("Abspielen") { close(); withSongs { app.player.play(it) } }
            MenuItem("Zufällig") { close(); withSongs { app.player.play(it, shuffle = true) } }
            MenuItem("Als Nächstes abspielen") { close(); withSongs { app.player.playNext(it) } }
            MenuItem("Zur Warteschlange") { close(); withSongs { app.player.enqueue(it) } }
        }
        MenuItem("Zur Playlist hinzufügen", trailing = "›") { page = 1 }
        when (app.offline.state(album.id)) {
            DownloadState.None -> MenuItem("Herunterladen") { close(); withSongs { app.offline.download("album", album.id, album.name, album.coverArt, it) } }
            DownloadState.Done -> MenuItem("Download entfernen") { close(); app.offline.remove(album.id) }
            else -> MenuItem("Download abbrechen") { close(); app.offline.remove(album.id) }
        }
        if (full) album.artistId?.let { id -> MenuItem("Zum Künstler") { close(); app.nav.go(Screen.ArtistPage(id)) } }
        if (app.helper.configured) {
            HorizontalDivider(color = Navi.Outline, modifier = Modifier.padding(vertical = 4.dp))
            MenuItem("Bearbeiten …") { close(); editing = true }
            MenuItem("Cover suchen …") { close(); covers = true }
            MenuItem("Löschen …", color = Danger) { close(); deleting = true }
        }
    }
    if (editing) AlbumEditDialog(app, album, onDismiss = { editing = false }, onDelete = { editing = false; deleting = true })
    if (covers) AlbumCoverDialog(app, album) { covers = false }
    if (deleting) AlbumDeleteDialog(app, album, onDismiss = { deleting = false }, onDeleted = onDeleted)
    newPlaylist?.let { CreatePlaylistDialog(app, it) { newPlaylist = null } }
}

/** Runder „…“-Knopf mit Album-Menü (Kopf der Albumseite). */
@Composable
fun AlbumMoreButton(app: DesktopApp, album: Album, onDeleted: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.08f)).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.MoreVert, "Album-Optionen", tint = Navi.Text) }
        AlbumContextMenu(app, album, open, { open = false }, full = false, onDeleted = onDeleted)
    }
}
