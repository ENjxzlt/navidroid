package dev.navidroid.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.navidroid.data.Album
import dev.navidroid.lidarr.AlbumChanges
import dev.navidroid.lidarr.AlbumFilesInfo
import dev.navidroid.lidarr.AlbumRef
import dev.navidroid.lidarr.CoverHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

private val DialogBg = Color(0xFF181A1F)
private val PanelBg = Color(0xFF121317)
private val DangerText = Color(0xFFF19A9A)
private val DangerBorder = Color(0xFF6B2E2E)
private val DangerFill = Color(0xFFE06C6C)

private fun Album.albumRef() = AlbumRef(artist.orEmpty(), name, song)

/** Kacheln liefern nur Kopfdaten; Bearbeiten/Löschen brauchen die Titel für die Zuordnung der Dateien. */
@Composable
private fun rememberFullAlbum(app: DesktopApp, album: Album) =
    rememberLoad("album-full:${album.id}") { if (album.song.isNotEmpty()) album else app.client.album(album.id) }

/** Navidrome neu einlesen lassen und warten (höchstens eine Minute), danach Listen neu laden. */
private suspend fun rescanAfterAlbumChange(app: DesktopApp) {
    runCatching { app.client.startScan() }
    delay(1_500)
    for (i in 0 until 30) {
        if (!runCatching { app.client.scanning() }.getOrDefault(false)) break
        delay(2_000)
    }
    app.version++
}

/** Bild wählen, auf höchstens 1400 px verkleinern und als JPEG liefern (null = abgebrochen). */
private suspend fun pickCoverImage(): ByteArray? {
    val dialog = FileDialog(null as Frame?, "Cover wählen", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.lowercase().substringAfterLast('.') in setOf("jpg", "jpeg", "png", "bmp", "gif") }
        isVisible = true
    }
    val name = dialog.file ?: return null
    val file = File(dialog.directory, name)
    return withContext(Dispatchers.IO) {
        val img = ImageIO.read(file) ?: error("Kein unterstütztes Bild (JPEG oder PNG)")
        val scale = minOf(1.0, 1400.0 / maxOf(img.width, img.height))
        val w = (img.width * scale).toInt().coerceAtLeast(1)
        val h = (img.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(img, 0, 0, w, h, java.awt.Color.WHITE, null)
            dispose()
        }
        ByteArrayOutputStream().also { ImageIO.write(out, "jpg", it) }.toByteArray()
    }
}

private fun ByteArray.toBitmap(): ImageBitmap? = runCatching { org.jetbrains.skia.Image.makeFromEncoded(this).toComposeImageBitmap() }.getOrNull()

private fun albumBytesLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}

/** Nach Umbenennen vergibt Navidrome eine neue Album-ID: suchen, Offline-Kopie umziehen, Seite wechseln. */
private suspend fun followRenamedAlbum(app: DesktopApp, old: Album, name: String, artist: String) {
    val newId = runCatching {
        app.client.search(name).album.firstOrNull { it.name.equals(name, true) && it.artist.orEmpty().equals(artist, true) }?.id
    }.getOrNull() ?: return
    if (newId == old.id) return
    if (app.offline.state(old.id) == DownloadState.Done) {
        runCatching { app.client.album(newId) }.getOrNull()?.let { app.offline.download("album", it.id, it.name, it.coverArt, it.song) }
        app.offline.remove(old.id)
    }
    if (app.nav.current == Screen.AlbumPage(old.id)) {
        app.nav.back()
        app.nav.go(Screen.AlbumPage(newId))
    }
}

@Composable
private fun DialogFrame(onDismiss: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier.clip(RoundedCornerShape(20.dp)).background(DialogBg)) { content() }
    }
}

@Composable
private fun PillButton(label: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    if (filled) {
        Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(22.dp), modifier = Modifier.height(44.dp)) {
            Text(label, fontWeight = FontWeight.ExtraBold)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Navi.Outline), modifier = Modifier.height(44.dp)) {
            Text(label, color = Navi.Text, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------- Cover-Raster

/** Auswahl im Cover-Raster: Treffer-URL oder eigene Datei. */
private sealed interface CoverChoice {
    data class Url(val url: String) : CoverChoice
    data class Bytes(val data: ByteArray) : CoverChoice
}

@Composable
private fun CoverPanel(app: DesktopApp, album: Album, selected: CoverChoice?, busy: Boolean, onSelect: (CoverChoice) -> Unit, modifier: Modifier) {
    var round by remember { mutableIntStateOf(0) }
    var fileError by remember { mutableStateOf<String?>(null) }
    val hits = rememberLoad("covers:${album.id}:$round") { app.helper.searchCovers(album.artist.orEmpty(), album.name) }
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.background(PanelBg).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cover", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    MainScope().launch {
                        try {
                            fileError = null
                            pickCoverImage()?.let { onSelect(CoverChoice.Bytes(it)) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            fileError = e.message ?: "Bild konnte nicht gelesen werden"
                        }
                    }
                },
                enabled = !busy, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Navi.Outline), modifier = Modifier.height(34.dp),
            ) { Text("Datei wählen …", style = MaterialTheme.typography.labelMedium, color = Navi.Text) }
            Button(
                onClick = { round++ }, enabled = !busy, shape = RoundedCornerShape(17.dp), modifier = Modifier.height(34.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navi.Surface2, contentColor = Navi.Text),
            ) { Text("Neu suchen", style = MaterialTheme.typography.labelMedium) }
        }
        Text("Treffer von Deezer, MusicBrainz und iTunes – oder ein eigenes Bild wählen.", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
        fileError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val own = selected as? CoverChoice.Bytes
        val ownBitmap = remember(own) { own?.data?.toBitmap() }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = hits.first) {
                Load.Loading -> if (own == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 3.dp) }
                is Load.Err -> if (own == null) Text(s.message, color = MaterialTheme.colorScheme.error)
                is Load.Ok -> if (s.value.isEmpty() && own == null) Text("Keine Cover gefunden.", color = Navi.Muted)
            }
            val list = (hits.first as? Load.Ok)?.value.orEmpty()
            if (list.isNotEmpty() || ownBitmap != null) {
                LazyVerticalGrid(GridCells.Adaptive(130.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                    if (ownBitmap != null) item(key = "own") {
                        CoverTile(true, accent, "Eigene Datei", enabled = !busy, onClick = {}) {
                            Image(ownBitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    items(list, key = { it.url }) { hit: CoverHit ->
                        val isSel = (selected as? CoverChoice.Url)?.url == hit.url
                        CoverTile(isSel, accent, listOfNotNull(hit.source.ifBlank { null }, hit.size.takeIf { it > 0 }?.let { "$it px" }).joinToString(" · "), enabled = !busy, onClick = { onSelect(CoverChoice.Url(hit.url)) }) {
                            AsyncImage(hit.url, hit.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverTile(selected: Boolean, accent: Color, label: String, enabled: Boolean, onClick: () -> Unit, image: @Composable () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).border(3.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Navi.Surface2)) { image() }
        Text(
            label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing), color = Navi.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(DialogBg).padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

private suspend fun applyCover(app: DesktopApp, artist: String, album: String, choice: CoverChoice) {
    when (choice) {
        is CoverChoice.Url -> app.helper.setAlbumCoverUrl(artist, album, choice.url)
        is CoverChoice.Bytes -> app.helper.setAlbumCover(artist, album, choice.data, "image/jpeg")
    }
}

// ---------------------------------------------------------------- Bearbeiten

/** Dialog „Album bearbeiten“: Tags links, Cover rechts (Design „Album bearbeiten“). */
@Composable
fun AlbumEditDialog(app: DesktopApp, album: Album, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val full = rememberFullAlbum(app, album)
    DialogFrame(onDismiss, Modifier.widthIn(max = 1040.dp).fillMaxWidth(0.92f).height(640.dp)) {
        LoadBox(full) { a -> AlbumEditForm(app, a, onDismiss, onDelete) }
    }
}

@Composable
private fun AlbumEditForm(app: DesktopApp, album: Album, onDismiss: () -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(album.name) }
    var artist by remember { mutableStateOf(album.artist.orEmpty()) }
    var year by remember { mutableStateOf(album.year?.takeIf { it > 0 }?.toString().orEmpty()) }
    var genre by remember { mutableStateOf(album.genre.orEmpty()) }
    var cover by remember { mutableStateOf<CoverChoice?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<AlbumFilesInfo?>(null) }
    LaunchedEffect(album.id) { files = runCatching { app.helper.albumFiles(album.albumRef()) }.getOrNull() }

    fun save() {
        val changes = AlbumChanges(
            album = name.trim().takeIf { it.isNotEmpty() && it != album.name },
            albumArtist = artist.trim().takeIf { it.isNotEmpty() && it != album.artist.orEmpty() },
            year = year.trim().toIntOrNull()?.takeIf { it != (album.year ?: 0) } ?: if (year.isBlank() && (album.year ?: 0) > 0) 0 else null,
            genre = genre.trim().takeIf { it != album.genre.orEmpty() },
        )
        val choice = cover
        if (changes == AlbumChanges() && choice == null) return onDismiss()
        saving = true
        error = null
        MainScope().launch {
            try {
                val ref = album.albumRef()
                if (changes != AlbumChanges()) {
                    val r = app.helper.editAlbum(ref, changes)
                    if (r.errors.isNotEmpty() && r.changed == 0) error(r.errors.first())
                }
                val newName = changes.album ?: ref.album
                val newArtist = changes.albumArtist ?: ref.artist
                choice?.let { applyCover(app, newArtist, newName, it) }
                onDismiss()
                rescanAfterAlbumChange(app)
                if (changes.album != null || changes.albumArtist != null) followRenamedAlbum(app, album, newName, newArtist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Speichern fehlgeschlagen"
                saving = false
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Album bearbeiten", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Ändert die Tags in allen ${files?.tracks ?: album.song.size} Dateien. Navidrome liest sie danach neu ein.",
                style = MaterialTheme.typography.bodyMedium, color = Navi.Muted,
            )
            OutlinedTextField(name, { name = it }, label = { Text("Albumname") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(artist, { artist = it }, label = { Text("Albumkünstler") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Jahr") }, singleLine = true, enabled = !saving, modifier = Modifier.width(120.dp))
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, singleLine = true, enabled = !saving, modifier = Modifier.weight(1f))
            }
            // Der Helfer setzt den Titel-Künstler mit, wo er dem bisherigen Albumkünstler entspricht (nicht abschaltbar)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = true, onCheckedChange = null, enabled = false, modifier = Modifier.padding(end = 10.dp))
                Column {
                    Text("Künstler auch für jeden Titel setzen", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD5D8DE))
                    Text("Bei allen Titeln, die den bisherigen Albumkünstler tragen", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (saving) "Wird gespeichert …" else "Speichern", filled = true, enabled = !saving, onClick = ::save)
                PillButton("Abbrechen", enabled = !saving, onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDelete, enabled = !saving, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, DangerBorder), modifier = Modifier.height(44.dp)) {
                    Text("Album löschen …", color = DangerText, fontWeight = FontWeight.Bold)
                }
            }
        }
        CoverPanel(app, album, cover, saving, { cover = it }, Modifier.weight(1f).fillMaxHeight())
    }
}

// ---------------------------------------------------------------- Cover suchen

/** „Cover suchen“ aus dem Album-Menü: Auswahl wird mit „Übernehmen“ gespeichert. */
@Composable
fun AlbumCoverDialog(app: DesktopApp, album: Album, onDismiss: () -> Unit) {
    var cover by remember { mutableStateOf<CoverChoice?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DialogFrame({ if (!saving) onDismiss() }, Modifier.widthIn(max = 720.dp).fillMaxWidth(0.8f).height(620.dp)) {
        Column(Modifier.fillMaxSize()) {
            CoverPanel(app, album, cover, saving, { cover = it }, Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error ?: "Cover für „${album.name}“", color = if (error != null) MaterialTheme.colorScheme.error else Navi.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                PillButton("Abbrechen", enabled = !saving, onClick = onDismiss)
                PillButton(if (saving) "Wird gespeichert …" else "Übernehmen", filled = true, enabled = !saving && cover != null) {
                    val choice = cover ?: return@PillButton
                    saving = true
                    MainScope().launch {
                        try {
                            applyCover(app, album.artist.orEmpty(), album.name, choice)
                            onDismiss()
                            rescanAfterAlbumChange(app)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Cover konnte nicht gespeichert werden"
                            saving = false
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Löschen

/** Album in den Papierkorb des Helfers; optional in Lidarr „nicht überwachen“. */
@Composable
fun AlbumDeleteDialog(app: DesktopApp, album: Album, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val full = rememberFullAlbum(app, album)
    val hasLidarr = app.lidarrStore.config != null
    var unmonitor by remember { mutableStateOf(hasLidarr) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<AlbumFilesInfo?>(null) }
    val loaded = (full.first as? Load.Ok)?.value
    LaunchedEffect(loaded) {
        if (loaded == null) return@LaunchedEffect
        try {
            info = app.helper.albumFiles(loaded.albumRef())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Dateien nicht gefunden"
        }
    }
    val loadError = (full.first as? Load.Err)?.message
    DialogFrame({ if (!working) onDismiss() }, Modifier.width(480.dp)) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("„${album.name}“ löschen?", style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(album.artist, info?.let { "${it.tracks} Titel" }, info?.let { albumBytesLabel(it.bytes) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Navi.Muted,
            )
            Text(
                "Das Album kommt in den Papierkorb und lässt sich 30 Tage lang wiederherstellen. Danach wird es endgültig gelöscht.",
                style = MaterialTheme.typography.bodyLarge, color = Color(0xFFD5D8DE),
            )
            if (hasLidarr) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !working) { unmonitor = !unmonitor }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(checked = unmonitor, onCheckedChange = { unmonitor = it }, enabled = !working)
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Nicht erneut herunterladen", fontWeight = FontWeight.Bold)
                        Text("Stellt das Album in Lidarr auf „nicht überwachen“", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                    }
                }
            }
            (error ?: loadError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Abbrechen", enabled = !working, onClick = onDismiss)
                Button(
                    onClick = {
                        val a = loaded ?: return@Button
                        working = true
                        error = null
                        MainScope().launch {
                            try {
                                app.helper.deleteAlbum(a.albumRef(), unmonitor)
                                app.offline.remove(a.id)
                                onDismiss()
                                onDeleted()
                                rescanAfterAlbumChange(app)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Löschen fehlgeschlagen"
                                working = false
                            }
                        }
                    },
                    enabled = !working && loaded != null && info != null,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DangerFill, contentColor = Navi.Bg),
                ) { Text(if (working) "Wird gelöscht …" else "In den Papierkorb", fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}
