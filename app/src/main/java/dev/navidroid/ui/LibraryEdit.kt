package dev.navidroid.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.navidroid.NaviApp
import dev.navidroid.data.Album
import dev.navidroid.lidarr.AlbumChanges
import dev.navidroid.lidarr.AlbumRef
import dev.navidroid.lidarr.AlbumFilesInfo
import dev.navidroid.lidarr.TrashEntry
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Zählt hoch, wenn sich die Bibliothek geändert hat (Bearbeiten, Löschen, Hochladen) – Ansichten laden dann neu. */
object LibraryBus {
    var version by mutableIntStateOf(0)
        private set

    fun changed() {
        version++
    }
}

/** Snackbar über der ganzen App, auch nach dem Verlassen eines Screens. */
object Snack {
    val host = SnackbarHostState()
    private val scope = MainScope()

    fun show(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val r = host.showSnackbar(message, actionLabel = action, withDismissAction = action == null, duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

/** Navidrome neu einlesen lassen und warten, bis es fertig ist (höchstens eine Minute). */
suspend fun rescanLibrary(app: NaviApp) {
    runCatching { app.client.startScan() }
    delay(1_500)
    for (i in 0 until 30) {
        if (!runCatching { app.client.scanning() }.getOrDefault(false)) break
        delay(2_000)
    }
    LibraryBus.changed()
}

private fun Album.ref() = AlbumRef(artist.orEmpty(), name, song)

/** Bild aus der Galerie verkleinern (max. 1400 px) und als JPEG liefern. */
suspend fun readCoverImage(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Bild konnte nicht gelesen werden")
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Kein gültiges Bild")
    val scale = minOf(1f, 1400f / maxOf(bitmap.width, bitmap.height))
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
}

/** ⋮ oben rechts auf der Albumseite. */
@Composable
fun AlbumMenuButton(app: NaviApp, album: Album, nav: NavController, onAddToPlaylist: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val canEdit = app.helper.configured
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        MainScope().launch {
            try {
                val image = readCoverImage(context, uri)
                app.helper.setAlbumCover(album.artist.orEmpty(), album.name, image, "image/jpeg")
                Snack.show("Cover gespeichert – Navidrome liest es ein …")
                rescanLibrary(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Cover konnte nicht gespeichert werden", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }
    Box {
        RoundIconButton(Icons.Rounded.MoreVert, "Album-Optionen", { menu = true }, container = Color.Black.copy(alpha = 0.3f))
        if (busy) CircularProgressIndicator(Modifier.size(44.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (canEdit) {
                DropdownMenuItem(text = { Text("Album bearbeiten") }, onClick = { menu = false; nav.navigate("album/${album.id}/edit") })
                DropdownMenuItem(text = { Text("Cover suchen") }, onClick = { menu = false; searching = true })
                DropdownMenuItem(text = { Text("Cover aus der Galerie") }, onClick = {
                    menu = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
            }
            DropdownMenuItem(text = { Text("Zur Playlist hinzufügen") }, onClick = { menu = false; onAddToPlaylist() })
            if (canEdit) {
                DropdownMenuItem(text = { Text("Album löschen", color = Color(0xFFFF8A80)) }, onClick = { menu = false; deleting = true })
            }
        }
    }
    if (deleting) DeleteAlbumDialog(app, album, nav) { deleting = false }
    if (searching) CoverSearchDialog(app, album) { searching = false }
}

/** Cover online suchen (Deezer, Apple Music, Cover Art Archive) und übernehmen. */
@Composable
private fun CoverSearchDialog(app: NaviApp, album: Album, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val loader = rememberLoad("covers:${album.id}") { app.helper.searchCovers(album.artist.orEmpty(), album.name) }
    var saving by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (saving == null) onDismiss() },
        title = { Text("Cover für „${album.name}“") },
        text = {
            Box(Modifier.fillMaxWidth().height(420.dp)) {
                when (val st = loader.state) {
                    Load.Loading -> CenteredProgress()
                    is Load.Err -> Text(st.message, color = MaterialTheme.colorScheme.error)
                    is Load.Ok -> if (st.value.isEmpty()) {
                        Text("Keine Cover gefunden.", color = Navi.Muted)
                    } else {
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(st.value.size) { i ->
                                val hit = st.value[i]
                                Column(
                                    Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = saving == null) {
                                        saving = hit.url
                                        MainScope().launch {
                                            try {
                                                app.helper.setAlbumCoverUrl(album.artist.orEmpty(), album.name, hit.url)
                                                onDismiss()
                                                Snack.show("Cover gespeichert – Navidrome liest es ein …")
                                                rescanLibrary(app)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                saving = null
                                                Toast.makeText(context, e.message ?: "Cover konnte nicht gespeichert werden", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Navi.Surface2)) {
                                        coil.compose.AsyncImage(hit.url, hit.title, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        if (saving == hit.url) CircularProgressIndicator(Modifier.align(Alignment.Center))
                                    }
                                    Text(hit.source, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                                    Text(hit.title, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = saving == null) { Text("Schließen") } },
    )
}

@Composable
private fun DeleteAlbumDialog(app: NaviApp, album: Album, nav: NavController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<AlbumFilesInfo?>(null) }
    var infoError by remember { mutableStateOf<String?>(null) }
    val hasLidarr = app.lidarrStore.config != null
    var unmonitor by remember { mutableStateOf(hasLidarr) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(album.id) {
        try {
            info = app.helper.albumFiles(album.ref())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            infoError = e.message
        }
    }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("„${album.name}“ löschen?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    listOfNotNull(album.artist, info?.let { "${it.tracks} Titel" }, info?.let { formatBytes(it.bytes) }).joinToString(" · "),
                    color = Navi.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                infoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Das Album kommt in den Papierkorb und lässt sich 30 Tage lang wiederherstellen. Danach wird es endgültig gelöscht.")
                if (hasLidarr) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Navi.Surface2).clickable { unmonitor = !unmonitor }.padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = unmonitor, onCheckedChange = { unmonitor = it })
                        Column {
                            Text("Nicht erneut herunterladen", fontWeight = FontWeight.Bold)
                            Text("Stellt das Album in Lidarr auf „nicht überwachen“", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !working && infoError == null && info != null,
                onClick = {
                    working = true
                    MainScope().launch {
                        try {
                            val r = app.helper.deleteAlbum(album.ref(), unmonitor)
                            app.offline.remove("album:${album.id}")
                            album.song.forEach { app.offline.removeSong(it.id) }
                            onDismiss()
                            nav.popBackStack()
                            Snack.show("„${album.name}“ gelöscht", "Rückgängig") {
                                MainScope().launch {
                                    try {
                                        app.helper.restoreTrash(r.id)
                                        Snack.show("„${album.name}“ wiederhergestellt")
                                        rescanLibrary(app)
                                    } catch (e: Exception) {
                                        Snack.show(e.message ?: "Wiederherstellen fehlgeschlagen")
                                    }
                                }
                            }
                            rescanLibrary(app)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            working = false
                            Toast.makeText(context, e.message ?: "Löschen fehlgeschlagen", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8A80), contentColor = Color(0xFF2A0B08)),
            ) { Text(if (working) "Wird gelöscht …" else "In den Papierkorb", fontWeight = FontWeight.ExtraBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Abbrechen") } },
    )
}

private class TrackDraft(val index: Int, title: String, number: String) {
    var title by mutableStateOf(title)
    var number by mutableStateOf(number)
}

@Composable
fun AlbumEditScreen(app: NaviApp, id: String, nav: NavController) {
    val loader = rememberLoad("album-edit:$id:${LibraryBus.version}") { app.client.album(id) }
    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        when (val s = loader.state) {
            Load.Loading -> CenteredProgress()
            is Load.Err -> ErrorView(s.message, loader.reload)
            is Load.Ok -> AlbumEditForm(app, s.value, nav)
        }
    }
}

@Composable
private fun AlbumEditForm(app: NaviApp, album: Album, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(album.name) }
    var artist by remember { mutableStateOf(album.artist.orEmpty()) }
    var year by remember { mutableStateOf(album.year?.takeIf { it > 0 }?.toString().orEmpty()) }
    var genre by remember { mutableStateOf(album.genre.orEmpty()) }
    val tracks = remember { mutableStateListOf<TrackDraft>().apply { album.song.forEachIndexed { i, s -> add(TrackDraft(i, s.title, s.track?.toString().orEmpty())) } } }
    var saving by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { readCoverImage(context, uri) }
                .onSuccess { cover = it }
                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
        }
    }

    fun save() {
        val changes = AlbumChanges(
            album = name.trim().takeIf { it.isNotEmpty() && it != album.name },
            albumArtist = artist.trim().takeIf { it.isNotEmpty() && it != album.artist.orEmpty() },
            year = year.trim().toIntOrNull()?.takeIf { it != (album.year ?: 0) } ?: if (year.isBlank() && (album.year ?: 0) > 0) 0 else null,
            genre = genre.trim().takeIf { it != album.genre.orEmpty() },
            tracks = tracks.mapNotNull { t ->
                val song = album.song[t.index]
                val newTitle = t.title.trim().takeIf { it.isNotEmpty() && it != song.title }
                val newNumber = t.number.trim().toIntOrNull()?.takeIf { it != song.track }
                if (newTitle == null && newNumber == null) null else Triple(t.index, newTitle, newNumber)
            },
        )
        val nothing = changes == AlbumChanges() && cover == null
        if (nothing) {
            nav.popBackStack()
            return
        }
        saving = true
        scope.launch {
            try {
                val ref = album.ref()
                if (changes != AlbumChanges()) {
                    val r = app.helper.editAlbum(ref, changes)
                    if (r.errors.isNotEmpty()) Toast.makeText(context, r.errors.first(), Toast.LENGTH_LONG).show()
                }
                cover?.let { app.helper.setAlbumCover(changes.albumArtist ?: ref.artist, changes.album ?: ref.album, it, "image/jpeg") }
                rescanLibrary(app)
                // Neuer Name oder Künstler → Navidrome vergibt eine neue Album-ID
                val newName = changes.album ?: album.name
                val newArtist = changes.albumArtist ?: album.artist.orEmpty()
                val newId = if (changes.album != null || changes.albumArtist != null) {
                    runCatching {
                        app.client.search(newName).album.firstOrNull {
                            it.name.equals(newName, ignoreCase = true) && it.artist.orEmpty().equals(newArtist, ignoreCase = true)
                        }?.id
                    }.getOrNull()
                } else album.id
                if (newId != null && newId != album.id) {
                    app.offline.collection("album:${album.id}")?.let {
                        runCatching { app.offline.downloadAlbum(app.client.album(newId)) }
                        app.offline.remove("album:${album.id}")
                    }
                }
                Snack.show("Änderungen gespeichert")
                nav.popBackStack()
                if (newId != null && newId != album.id) {
                    nav.popBackStack()
                    nav.openAlbum(newId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saving = false
                Toast.makeText(context, e.message ?: "Speichern fehlgeschlagen", Toast.LENGTH_LONG).show()
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(
                Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoundIconButton(Icons.Rounded.Close, "Abbrechen", { nav.popBackStack() })
                Text("Album bearbeiten", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = ::save, enabled = !saving) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Speichern", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        item {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(88.dp).clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    val local = cover
                    if (local != null) {
                        val bmp = remember(local) { BitmapFactory.decodeByteArray(local, 0, local.size) }
                        androidx.compose.foundation.Image(
                            bmp.asImageBitmapSafe(),
                            null,
                            Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        CoverArt(album.coverArt, 300, Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)))
                    }
                    Surface(shape = CircleShape, color = Navi.Text, modifier = Modifier.align(Alignment.BottomEnd).size(30.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Edit, "Cover ändern", tint = Navi.Bg, modifier = Modifier.size(16.dp)) }
                    }
                }
                Text(
                    if (cover != null) "Neues Cover wird beim Speichern übernommen." else "Cover antippen, um ein Bild aus der Galerie zu wählen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Navi.Muted,
                )
            }
        }
        item { EditField("Albumtitel", name) { name = it } }
        item { EditField("Albumkünstler", artist) { artist = it } }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Jahr") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(110.dp))
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        item { SectionHeader("Titel", Modifier.padding(top = 20.dp, bottom = 8.dp)) }
        items(tracks, key = { it.index }) { t ->
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    t.number, { t.number = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.width(64.dp),
                )
                OutlinedTextField(t.title, { t.title = it }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        item {
            Text(
                "Wird in die Dateien geschrieben. Navidrome zeigt die Änderung nach wenigen Sekunden.",
                style = MaterialTheme.typography.bodySmall,
                color = Navi.Muted,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

private fun Bitmap?.asImageBitmapSafe(): androidx.compose.ui.graphics.ImageBitmap =
    (this ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImageBitmap()

@Composable
private fun EditField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
}

/** Einstellungen → Papierkorb */
@Composable
fun TrashScreen(app: NaviApp, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val loader = rememberLoad("trash:$reload") { app.helper.trash() }
    var restoring by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() })
            Text("Papierkorb", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 12.dp))
        }
        LoadContent(loader) { entries: List<TrashEntry> ->
            if (entries.isEmpty()) return@LoadContent EmptyHint("Der Papierkorb ist leer")
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        "Gelöschte Alben bleiben 30 Tage hier und werden danach endgültig entfernt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Navi.Muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(entries, key = { it.id }) { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.album, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${e.artist} · ${e.tracks} Titel · ${formatBytes(e.bytes)} · noch ${e.daysLeft} ${if (e.daysLeft == 1) "Tag" else "Tage"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Navi.Muted,
                                maxLines = 2,
                            )
                        }
                        OutlinedButton(
                            enabled = restoring == null,
                            onClick = {
                                restoring = e.id
                                scope.launch {
                                    try {
                                        app.helper.restoreTrash(e.id)
                                        Snack.show("„${e.album}“ wiederhergestellt")
                                        reload++
                                        rescanLibrary(app)
                                    } catch (ex: CancellationException) {
                                        throw ex
                                    } catch (ex: Exception) {
                                        Toast.makeText(context, ex.message ?: "Fehler", Toast.LENGTH_LONG).show()
                                    } finally {
                                        restoring = null
                                    }
                                }
                            },
                        ) { Text(if (restoring == e.id) "…" else "Wiederherstellen") }
                    }
                }
            }
        }
    }
}
