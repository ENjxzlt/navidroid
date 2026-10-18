package dev.navidroid.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URI
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

enum class UploadState { Waiting, Running, Done, Exists, Failed }

/** Eine ausgewählte Datei mit den (bearbeitbaren) Angaben für den Helfer. */
class UploadFile(
    val file: File,
    title: String,
    track: Int,
    val disc: Int,
    val trackArtist: String?,
    val genre: String?,
    val hadTags: Boolean,
) {
    val fileName: String get() = file.name
    val size: Long = file.length()
    var title by mutableStateOf(title)
    var track by mutableStateOf(track)
    var state by mutableStateOf(UploadState.Waiting)
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
}

class UploadAlbum(artist: String, album: String, year: String, cover: ByteArray?) {
    var artist by mutableStateOf(artist)
    var album by mutableStateOf(album)
    var year by mutableStateOf(year)
    var cover by mutableStateOf(cover)
    val files = mutableStateListOf<UploadFile>()
    val discs get() = files.maxOfOrNull { it.disc } ?: 1
    val totalBytes get() = files.sumOf { it.size }
    val sentBytes get() = files.sumOf { f -> if (f.state == UploadState.Done || f.state == UploadState.Exists) f.size else (f.size * f.progress).toLong() }
    val complete get() = files.isNotEmpty() && files.all { it.hadTags }
}

/**
 * Eigene Dateien über den Helfer in den Musikordner hochladen – wie in der App: Tags werden lokal gelesen
 * (hier mit ffprobe, sonst aus Ordner- und Dateinamen), nach Album gruppiert und Datei für Datei gesendet.
 * Fehler einzelner Dateien halten die übrigen nicht auf. Läuft weiter, wenn man die Seite verlässt.
 */
class UploadManager(private val app: DesktopApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    val albums = mutableStateListOf<UploadAlbum>()
    var reading by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    var scanning by mutableStateOf(false)
        private set

    val files get() = albums.flatMap { it.files }
    val totalBytes get() = albums.sumOf { it.totalBytes }
    val sentBytes get() = albums.sumOf { it.sentBytes }
    val doneCount get() = files.count { it.state == UploadState.Done || it.state == UploadState.Exists || it.state == UploadState.Failed }

    fun add(paths: List<File>) {
        if (running) return
        if (finished) clear()
        reading = true
        val known = files.map { it.file.absolutePath }.toSet()
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                val audio = paths.flatMap { collect(it) }.distinctBy { it.absolutePath }.filter { it.absolutePath !in known }
                audio.mapNotNull { runCatching { TagReader.read(it) }.getOrNull() }
            }
            parsed.forEach { r ->
                val key = norm(r.artist) to norm(r.album)
                val album = albums.firstOrNull { norm(it.artist) == key.first && norm(it.album) == key.second }
                    ?: UploadAlbum(r.artist, r.album, r.year, null).also { albums += it }
                album.files += r.file
            }
            // Fehlende Nummern aus der Reihenfolge ergänzen, Titel sortieren
            albums.forEach { a ->
                val sorted = a.files.sortedWith(compareBy({ it.disc }, { if (it.track > 0) it.track else Int.MAX_VALUE }, { it.fileName }))
                var next = 1
                sorted.forEach { f -> if (f.track <= 0) f.track = next; next = f.track + 1 }
                a.files.clear()
                a.files += sorted
            }
            // Cover: eingebettetes Bild, sonst cover.jpg & Co. im Ordner
            albums.filter { it.cover == null }.forEach { a ->
                a.cover = withContext(Dispatchers.IO) { TagReader.cover(a.files.map { it.file }) }
            }
            reading = false
        }
    }

    fun remove(file: UploadFile) {
        if (running) return
        albums.forEach { it.files.remove(file) }
        albums.removeAll { it.files.isEmpty() }
    }

    fun removeAlbum(album: UploadAlbum) {
        if (running) return
        albums.remove(album)
    }

    fun clear() {
        if (running) return
        albums.clear()
        finished = false
    }

    fun start() {
        if (running || files.isEmpty()) return
        running = true
        finished = false
        job = scope.launch {
            try {
                for (album in albums.toList()) {
                    var anyOk = false
                    for (f in album.files.toList()) {
                        if (f.state == UploadState.Done || f.state == UploadState.Exists) continue
                        f.state = UploadState.Running
                        f.error = null
                        f.progress = 0f
                        try {
                            val r = withContext(Dispatchers.IO) { app.helper.upload(query(album, f), body(f)) }
                            f.state = if (r.status == "exists") UploadState.Exists else UploadState.Done
                            if (f.state == UploadState.Done) anyOk = true
                        } catch (e: CancellationException) {
                            f.state = UploadState.Waiting
                            throw e
                        } catch (e: Exception) {
                            f.state = UploadState.Failed
                            f.error = e.message
                        }
                    }
                    val cover = album.cover
                    if (anyOk && cover != null) {
                        runCatching { app.helper.uploadCover(album.artist.trim(), album.album.trim(), album.year.toIntOrNull(), cover, imageMime(cover)) }
                    }
                }
                running = false
                finished = true
                scanning = true
                rescanAfterLibraryChange(app)
            } finally {
                running = false
                finished = true
                scanning = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun query(a: UploadAlbum, f: UploadFile) = buildMap {
        put("filename", f.fileName)
        put("artist", a.artist.trim().ifEmpty { "Unbekannt" })
        put("album", a.album.trim().ifEmpty { "Unbekannt" })
        put("title", f.title.trim().ifEmpty { f.fileName.substringBeforeLast('.') })
        put("track", "${f.track}")
        put("disc", "${f.disc}")
        put("discs", "${a.discs}")
        a.year.trim().toIntOrNull()?.let { put("year", "$it") }
        f.genre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
        f.trackArtist?.takeIf { it.isNotBlank() && !it.equals(a.artist.trim(), ignoreCase = true) }?.let { put("trackArtist", it) }
    }

    /** Streamt die Datei und meldet den Fortschritt. */
    private fun body(f: UploadFile) = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = f.size
        override fun writeTo(sink: BufferedSink) {
            if (!f.file.exists()) error("Datei nicht mehr vorhanden")
            f.file.source().use { src ->
                var sent = 0L
                var posted = 0f
                val buffer = okio.Buffer()
                while (true) {
                    val n = src.read(buffer, 64 * 1024)
                    if (n == -1L) break
                    sink.write(buffer, n)
                    sent += n
                    if (f.size > 0) {
                        val p = sent.toFloat() / f.size
                        if (p - posted >= 0.01f || p >= 1f) {
                            posted = p
                            scope.launch { f.progress = p }
                        }
                    }
                }
            }
        }
    }

    private fun collect(f: File): List<File> = when {
        f.isDirectory -> f.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in AUDIO_EXT }.sortedBy { it.path }.toList()
        f.isFile && f.extension.lowercase() in AUDIO_EXT -> listOf(f)
        else -> emptyList()
    }

    companion object {
        /** Dateitypen, die der Helfer annimmt. */
        val AUDIO_EXT = setOf("mp3", "flac", "m4a", "mp4", "ogg", "opus", "oga", "wav", "aac", "wma", "aiff", "aif")
        private val managers = WeakHashMap<DesktopApp, UploadManager>()
        fun of(app: DesktopApp): UploadManager = managers.getOrPut(app) { UploadManager(app) }
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}

private fun norm(s: String) = UploadManager.norm(s)

private fun imageMime(b: ByteArray) = if (b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"

/** Liest Tags lokal mit ffprobe (aus FFmpeg). Ohne ffprobe: Künstler/Album aus den Ordnernamen, Titel aus dem Dateinamen. */
object TagReader {
    class Result(val artist: String, val album: String, val year: String, val file: UploadFile)

    private val json = Json { ignoreUnknownKeys = true }
    private val hasFfprobe: Boolean by lazy { runCatching { run(listOf("ffprobe", "-version"), 5) != null }.getOrDefault(false) }
    private val hasFfmpeg: Boolean by lazy { runCatching { run(listOf("ffmpeg", "-version"), 5) != null }.getOrDefault(false) }

    val available: Boolean get() = hasFfprobe

    private fun run(cmd: List<String>, timeoutSec: Long): ByteArray? {
        val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.use { it.readBytes() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        return if (p.exitValue() == 0) out else null
    }

    private fun tags(file: File): Map<String, String> {
        if (!hasFfprobe) return emptyMap()
        val out = run(listOf("ffprobe", "-v", "quiet", "-print_format", "json", "-show_entries", "format_tags:stream_tags", file.absolutePath), 20) ?: return emptyMap()
        val root = json.parseToJsonElement(out.decodeToString()) as? JsonObject ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        fun take(o: JsonObject?) = o?.forEach { (k, v) ->
            val value = (v as? JsonPrimitive)?.contentOrNull?.trim()
            if (!value.isNullOrEmpty()) result.putIfAbsent(k.lowercase(), value)
        }
        // Ogg/Opus legen die Tags im Stream ab, die übrigen im Container
        take((root["format"] as? JsonObject)?.get("tags") as? JsonObject)
        (root["streams"] as? JsonArray)?.forEach { take((it as? JsonObject)?.get("tags") as? JsonObject) }
        return result
    }

    fun read(file: File): Result {
        val t = tags(file)
        fun tag(vararg keys: String) = keys.firstNotNullOfOrNull { t[it] }
        val title = tag("title")
        val artist = tag("artist")
        val albumArtist = tag("album_artist", "albumartist", "album artist")
        val album = tag("album")
        val year = tag("date", "year", "originaldate", "tdrc", "tyer")?.take(4)?.filter(Char::isDigit).orEmpty()
        val track = tag("track", "tracknumber")?.substringBefore('/')?.trim()?.toIntOrNull()
        val disc = tag("disc", "discnumber")?.substringBefore('/')?.trim()?.toIntOrNull() ?: 1
        val genre = tag("genre")
        val base = file.name.substringBeforeLast('.')
        val fallbackTitle = base.replace(Regex("""^\s*\d+\s*[-._ ]+\s*"""), "").trim().ifEmpty { base }
        // Ohne Tags: …/Künstler/Album/01 Titel.mp3
        val folder = file.parentFile
        val folderAlbum = folder?.name.orEmpty()
        val folderArtist = folder?.parentFile?.name.orEmpty()
        val mainArtist = albumArtist ?: artist ?: folderArtist
        val albumName = album ?: folderAlbum
        val f = UploadFile(
            file,
            title = title ?: fallbackTitle,
            track = track ?: Regex("""^\s*(\d{1,3})""").find(base)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            disc = disc.coerceAtLeast(1),
            trackArtist = artist,
            genre = genre,
            hadTags = title != null && (albumArtist ?: artist) != null && album != null,
        )
        return Result(mainArtist, albumName, year, f)
    }

    /** Cover eines Albums: eingebettetes Bild der ersten Datei, sonst ein Bild im Ordner. */
    fun cover(files: List<File>): ByteArray? {
        if (hasFfmpeg) {
            for (f in files.take(3)) {
                val img = runCatching {
                    run(listOf("ffmpeg", "-v", "quiet", "-i", f.absolutePath, "-an", "-map", "0:v:0", "-frames:v", "1", "-c:v", "copy", "-f", "image2pipe", "-"), 20)
                }.getOrNull()
                if (img != null && img.size > 100 && isImage(img)) return img
            }
        }
        val dirs = files.mapNotNull { it.parentFile }.distinct()
        for (dir in dirs) {
            val images = dir.listFiles { x -> x.isFile && x.extension.lowercase() in setOf("jpg", "jpeg", "png") }.orEmpty()
            val pick = images.firstOrNull { it.nameWithoutExtension.lowercase() in setOf("cover", "folder", "front", "album") } ?: images.singleOrNull()
            if (pick != null && pick.length() in 1..15L * 1024 * 1024) return runCatching { pick.readBytes() }.getOrNull()?.takeIf(::isImage)
        }
        return null
    }

    private fun isImage(b: ByteArray) = b.size > 4 && ((b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) || (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()))
}

private val okGreen = Color(0xFF7BD88F)
private val warn = Color(0xFFEDCF83)

fun formatLibraryBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Dateiauswahl (Dateien und Ordner, mehrere). */
private fun chooseFiles(): List<File> {
    val chooser = JFileChooser(File(System.getProperty("user.home"), "Musik").takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).apply {
        dialogTitle = "Musik zum Hochladen auswählen"
        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        isMultiSelectionEnabled = true
        fileFilter = FileNameExtensionFilter("Audiodateien", *UploadManager.AUDIO_EXT.toTypedArray())
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFiles.toList() else emptyList()
}

private fun chooseImage(): ByteArray? {
    val chooser = JFileChooser(File(System.getProperty("user.home"))).apply {
        dialogTitle = "Cover auswählen"
        fileFilter = FileNameExtensionFilter("Bilder (JPG, PNG)", "jpg", "jpeg", "png")
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile?.takeIf { it.length() in 1..15L * 1024 * 1024 }?.readBytes()
}

/** Dateien aus einem Drag & Drop (Dateiliste oder text/uri-list). */
private fun droppedFiles(event: DragAndDropEvent): List<File> {
    @OptIn(ExperimentalComposeUiApi::class)
    val t = event.awtTransferable
    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        return (t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>()
    }
    val uriList = runCatching { DataFlavor("text/uri-list;class=java.lang.String") }.getOrNull()
    if (uriList != null && t.isDataFlavorSupported(uriList)) {
        val text = t.getTransferData(uriList) as? String ?: return emptyList()
        return text.lines().map { it.trim() }.filter { it.startsWith("file:") }.mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
    }
    return emptyList()
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun UploadTab(app: DesktopApp) {
    val up = UploadManager.of(app)
    val accent = MaterialTheme.colorScheme.primary
    var hovering by remember { mutableStateOf(false) }
    val target = remember(up) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val files = runCatching { droppedFiles(event) }.getOrDefault(emptyList())
                if (files.isEmpty() || up.running) return false
                up.add(files)
                return true
            }
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // Ablagefläche
        Column(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                .background(accent.copy(alpha = if (hovering) 0.14f else 0.06f))
                .border(2.dp, accent.copy(alpha = if (hovering) 1f else 0.7f), RoundedCornerShape(18.dp))
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.FileUpload, null, tint = accent, modifier = Modifier.size(44.dp))
            Text(if (hovering) "Loslassen zum Hinzufügen" else "Dateien oder Ordner hierher ziehen", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "MP3, FLAC, M4A, OGG, OPUS · landen sortiert in Künstler/Album in deinem Musikordner",
                style = MaterialTheme.typography.bodyMedium, color = Navi.Muted, textAlign = TextAlign.Center,
            )
            Button(onClick = { chooseFiles().takeIf { it.isNotEmpty() }?.let(up::add) }, enabled = !up.running, shape = RoundedCornerShape(21.dp), modifier = Modifier.height(42.dp)) {
                Text("Dateien auswählen …", fontWeight = FontWeight.ExtraBold)
            }
            if (!TagReader.available) {
                Text(
                    "ffprobe (FFmpeg) nicht gefunden – Künstler und Album werden aus den Ordnernamen übernommen. Bitte vor dem Hochladen prüfen.",
                    style = MaterialTheme.typography.bodySmall, color = warn, textAlign = TextAlign.Center, modifier = Modifier.width(380.dp),
                )
            }
        }
        // Erkannte Alben
        Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            UploadSummary(up)
            if (up.albums.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (up.reading) "Tags werden gelesen …" else "Noch nichts ausgewählt. Die Tags werden gelesen und nach Album geordnet; du kannst alles vor dem Hochladen prüfen.",
                        color = Navi.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.width(360.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(up.albums, key = { System.identityHashCode(it) }) { a -> UploadAlbumCard(up, a) }
                    if (up.reading) item { Text("Weitere Dateien werden gelesen …", color = Navi.Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun UploadSummary(up: UploadManager) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                "ERKANNT · ${up.albums.size} ${if (up.albums.size == 1) "ALBUM" else "ALBEN"}",
                style = MaterialTheme.typography.labelSmall, color = Navi.Muted,
            )
            if (up.files.isNotEmpty()) {
                Text(
                    when {
                        up.running -> "${up.doneCount} von ${up.files.size} Titeln · ${formatLibraryBytes(up.sentBytes)} von ${formatLibraryBytes(up.totalBytes)}"
                        up.scanning -> "Fertig · Navidrome liest die neuen Titel ein …"
                        up.finished -> "Fertig · ${up.files.count { it.state == UploadState.Done }} hochgeladen"
                        else -> "${up.files.size} Dateien · ${formatLibraryBytes(up.totalBytes)}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (up.running) accent else Navi.Muted,
                )
            }
        }
        when {
            up.running -> TextButton(onClick = up::cancel) { Text("Abbrechen") }
            up.finished -> OutlinedButton(onClick = up::clear, shape = RoundedCornerShape(18.dp)) { Text("Leeren") }
            up.files.isNotEmpty() -> {
                TextButton(onClick = up::clear) { Text("Leeren") }
                Button(onClick = up::start, enabled = !up.reading, shape = RoundedCornerShape(18.dp)) {
                    Text("${up.files.size} Titel hochladen", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun UploadAlbumCard(up: UploadManager, a: UploadAlbum) {
    val accent = MaterialTheme.colorScheme.primary
    var edit by remember { mutableStateOf(false) }
    val failed = a.files.count { it.state == UploadState.Failed }
    val allDone = a.files.all { it.state == UploadState.Done || it.state == UploadState.Exists }
    val active = a.files.any { it.state == UploadState.Running }
    val formats = a.files.map { it.fileName.substringAfterLast('.', "").uppercase() }.distinct().joinToString("/")
    val status = when {
        active -> "lädt hoch"
        failed > 0 && a.files.none { it.state == UploadState.Waiting } -> "$failed Titel fehlgeschlagen"
        allDone && up.scanning -> "fertig, Navidrome liest ein"
        allDone -> if (a.files.all { it.state == UploadState.Exists }) "schon vorhanden" else "fertig"
        !a.complete -> "Tags fehlen, Albumname bitte prüfen"
        up.running -> "wartet"
        else -> null
    }
    val progress = if (a.totalBytes > 0) a.sentBytes.toFloat() / a.totalBytes else 0f
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF181A1F)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val cover = a.cover
            val bmp = remember(cover) { cover?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() } }
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Navi.Surface2)
                    .clickable(enabled = !up.running) { chooseImage()?.let { a.cover = it } },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) Image(bmp, "Cover", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("+ BILD", style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${a.artist.ifBlank { "Unbekannt" }} – ${a.album.ifBlank { "Unbekannt" }}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull("${a.files.size} Dateien", formats.takeIf { it.isNotEmpty() }, formatLibraryBytes(a.totalBytes), status).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        failed > 0 -> MaterialTheme.colorScheme.error
                        status == "Tags fehlen, Albumname bitte prüfen" -> warn
                        else -> Navi.Muted
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Navi.Outline)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (allDone) okGreen else accent))
                }
            }
            if (!up.running && !up.finished) {
                IconButton(onClick = { edit = !edit }) { Icon(Icons.Rounded.Edit, "Bearbeiten", tint = if (edit) accent else Navi.Faint, modifier = Modifier.size(18.dp)) }
                IconButton(onClick = { up.removeAlbum(a) }) { Icon(Icons.Rounded.Close, "Album entfernen", tint = Navi.Faint, modifier = Modifier.size(18.dp)) }
            }
        }
        if (edit && !up.running && !up.finished) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(a.artist, { a.artist = it }, label = { Text("Künstler") }, singleLine = true, isError = a.artist.isBlank(), modifier = Modifier.weight(1f))
                OutlinedTextField(a.album, { a.album = it }, label = { Text("Album") }, singleLine = true, isError = a.album.isBlank(), modifier = Modifier.weight(1f))
                OutlinedTextField(a.year, { a.year = it.filter(Char::isDigit).take(4) }, label = { Text("Jahr") }, singleLine = true, modifier = Modifier.width(92.dp))
            }
        }
        // Titel: beim Bearbeiten und während/nach dem Hochladen (Status je Datei)
        if (edit || up.running || up.finished || failed > 0) {
            a.files.forEach { f -> UploadFileRow(up, a, f, edit && !up.running && !up.finished) }
        }
    }
}

@Composable
private fun UploadFileRow(up: UploadManager, a: UploadAlbum, f: UploadFile, editable: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${f.track}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = Navi.Faint, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = if (f.hadTags) Navi.Text else warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                f.error ?: if (f.hadTags) "${f.fileName.substringAfterLast('.', "").uppercase()} · ${formatLibraryBytes(f.size)}" else "ohne vollständige Tags · ${f.fileName}",
                style = MaterialTheme.typography.bodySmall,
                color = if (f.error != null) MaterialTheme.colorScheme.error else Navi.Muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (editable) {
            IconButton(onClick = { up.remove(f) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Entfernen", tint = Navi.Faint, modifier = Modifier.size(16.dp)) }
        } else {
            val (label, color) = when (f.state) {
                UploadState.Waiting -> "wartet" to Navi.Faint
                UploadState.Running -> "${(f.progress * 100).toInt()} %" to accent
                UploadState.Done -> "Fertig" to okGreen
                UploadState.Exists -> "Schon vorhanden" to Navi.Muted
                UploadState.Failed -> "Fehler" to MaterialTheme.colorScheme.error
            }
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = color)
        }
    }
}
