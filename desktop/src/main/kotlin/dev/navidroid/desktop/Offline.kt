package dev.navidroid.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.navidroid.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class DownloadState { None, Queued, Loading, Done }

/** Eintrag im Offline-Index (Paths.config/offline.json). Die Titel stehen mit drin, damit offline alles anzeigbar bleibt. */
@Serializable
data class OfflineCollection(
    val kind: String,
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val songIds: List<String> = emptyList(),
    val songs: List<Song> = emptyList(),
    val addedAt: Long = 0,
)

/** Fortschritt des laufenden Downloads für die Oberfläche. */
data class OfflineProgress(val name: String, val done: Int, val total: Int, val bytesPerSecond: Long, val remainingSeconds: Long?)

/**
 * Offline-Musik: lädt Alben, Playlists und Mixe auf die Platte (Standard ~/Musik/NaviDroid).
 * Zustände sind Compose-State, damit Knöpfe und Badges sich selbst aktualisieren.
 */
class OfflineManager(private val app: DesktopApp) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val indexFile = File(Paths.config, "offline.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val writer = Dispatchers.IO.limitedParallelism(1)

    /** Heruntergeladene Sammlungen, neueste zuerst. */
    val collections = mutableStateListOf<OfflineCollection>()
    /** Song-ID → Datei auf der Platte. */
    private val files = mutableStateMapOf<String, File>()
    /** Warteschlange: (Sammlung, Song-ID). */
    private val queue = ArrayDeque<Pair<String, String>>()
    private val queued = mutableStateMapOf<String, Boolean>()
    private val failed = HashSet<String>()
    private var worker: Job? = null
    /** Sammlung, aus der gerade geladen wird. */
    private var activeCollection by mutableStateOf<String?>(null)

    var progress by mutableStateOf<OfflineProgress?>(null)
        private set
    var paused by mutableStateOf(false)
        private set
    /** false, wenn der Server nicht erreichbar ist (Offline-Modus). */
    var online by mutableStateOf(true)
        private set

    val dir: File get() = (app.settings.downloadDir.takeIf { it.isNotBlank() }?.let(::File) ?: defaultDir).apply { mkdirs() }

    init {
        collections += runCatching { json.decodeFromString<List<OfflineCollection>>(indexFile.readText()) }.getOrDefault(emptyList())
        scanFiles()
        scope.launch { watchServer() }
        scope.launch {
            delay(5_000)
            resume()
            while (isActive) {
                if (app.settings.autoSyncDownloads && online) runCatching { syncNow() }
                delay(30 * 60_000L)
            }
        }
    }

    /** file://-URL eines heruntergeladenen Titels oder null. */
    fun localUrl(songId: String): String? = files[songId]?.takeIf { it.isFile }?.toURI()?.toString()
    fun isSongDownloaded(songId: String): Boolean = files.containsKey(songId)

    /** Zustand einer Sammlung (Album-, Playlist- oder Mix-ID). */
    fun state(collectionId: String): DownloadState {
        val c = collection(collectionId) ?: return DownloadState.None
        return when {
            c.songIds.all { files.containsKey(it) } -> DownloadState.Done
            activeCollection == c.id -> DownloadState.Loading
            else -> DownloadState.Queued
        }
    }

    fun collection(id: String): OfflineCollection? = collections.firstOrNull { it.id == id }

    /** Titel einer heruntergeladenen Sammlung (für die Anzeige ohne Server). */
    fun songs(collectionId: String): List<Song> = collection(collectionId)?.songs.orEmpty()

    fun doneCount(c: OfflineCollection): Int = c.songIds.count { files.containsKey(it) }

    /** kind: "album" | "playlist" | "mix" */
    fun download(kind: String, id: String, name: String, coverArt: String?, songs: List<Song>) {
        val old = collection(id)
        val c = OfflineCollection(kind, id, name, coverArt ?: songs.firstOrNull()?.coverArt, songs.map { it.id }, songs, old?.addedAt ?: System.currentTimeMillis())
        save(c, old)
    }

    fun remove(collectionId: String) {
        val c = collection(collectionId) ?: return
        collections.remove(c)
        writeIndex()
        queue.removeAll { it.first == collectionId }
        refreshQueued()
        deleteOrphans(c.songIds)
    }

    val downloadedCount: Int get() = files.size
    val totalBytes: Long get() = files.values.sumOf { it.length() }
    val freeBytes: Long get() = dir.usableSpace

    fun pause() { paused = true }
    fun resumeDownloads() { paused = false; startWorker() }

    /** Neuen Speicherort setzen; vorhandene Dateien werden mitgenommen. */
    fun relocate(target: File) {
        val from = dir
        if (target.absoluteFile == from.absoluteFile) return
        scope.launch {
            val moved = withContext(Dispatchers.IO) {
                target.mkdirs()
                files.mapValues { (_, f) ->
                    runCatching {
                        val dest = File(target, f.name)
                        Files.move(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        dest
                    }.getOrDefault(f)
                }
            }
            files.putAll(moved)
            app.updateSettings(app.settings.copy(downloadDir = target.absolutePath))
        }
    }

    // ---- intern ----

    private fun save(c: OfflineCollection, previous: OfflineCollection?) {
        val i = collections.indexOfFirst { it.id == c.id }
        if (i >= 0) collections[i] = c else collections.add(0, c)
        writeIndex()
        c.songIds.forEach { enqueue(c.id, it) }
        previous?.let { p -> deleteOrphans(p.songIds - c.songIds.toSet()) }
        startWorker()
    }

    private fun enqueue(collectionId: String, songId: String) {
        if (files.containsKey(songId) || queue.any { it.second == songId }) return
        queue.addLast(collectionId to songId)
        queued[songId] = true
    }

    private fun refreshQueued() {
        val ids = queue.mapTo(HashSet()) { it.second }
        queued.keys.filter { it !in ids }.forEach { queued.remove(it) }
    }

    /** Nach dem Start und nach Rückkehr des Servers: offene Titel wieder einreihen. */
    private fun resume() {
        failed.clear()
        collections.forEach { c -> c.songIds.forEach { enqueue(c.id, it) } }
        startWorker()
    }

    private fun startWorker() {
        if (worker?.isActive == true || queue.isEmpty()) return
        worker = scope.launch {
            var bytesWindow = 0L
            var windowStart = System.currentTimeMillis()
            var speed = 0L
            var avgBytes = 0L
            var loaded = 0
            while (queue.isNotEmpty()) {
                while (paused || !online) {
                    progress = progress?.copy(bytesPerSecond = 0, remainingSeconds = null)
                    delay(1_000)
                }
                val (cid, songId) = queue.first()
                val c = collection(cid)
                if (c == null || files.containsKey(songId) || songId in failed) {
                    queue.removeFirst(); refreshQueued(); continue
                }
                activeCollection = cid
                fun update() {
                    val done = doneCount(c)
                    val rest = (c.songIds.size - done).toLong()
                    val eta = if (speed > 0 && avgBytes > 0) rest * avgBytes / speed else null
                    progress = OfflineProgress(c.name, done, c.songIds.size, speed, eta)
                }
                update()
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        fetch(songId) { n ->
                            bytesWindow += n
                            val now = System.currentTimeMillis()
                            if (now - windowStart >= 1_000) {
                                speed = bytesWindow * 1000 / (now - windowStart)
                                bytesWindow = 0
                                windowStart = now
                            }
                        }
                    }
                }
                result.onSuccess { f ->
                    loaded++
                    avgBytes += (f.length() - avgBytes) / loaded
                    files[songId] = f
                    // Inzwischen entfernt? Dann gleich wieder weg.
                    if (collections.none { songId in it.songIds }) deleteOrphans(listOf(songId))
                }.onFailure {
                    if (online) failed += songId
                }
                if (queue.firstOrNull()?.second == songId) queue.removeFirst()
                refreshQueued()
                update()
            }
            activeCollection = null
            progress = null
        }
    }

    /** Lädt einen Titel in eine .part-Datei und benennt sie danach um. */
    private fun fetch(songId: String, onBytes: (Long) -> Unit): File {
        val target = dir
        val url = app.client.streamUrl(songId, app.settings.downloadBitrate)
        app.http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
            val body = res.body ?: throw java.io.IOException("Leere Antwort")
            val type = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty().lowercase()
            // Subsonic meldet Fehler als JSON/XML mit Status 200
            if ("json" in type || "xml" in type || type.startsWith("text/")) throw java.io.IOException("Server-Fehler")
            val base = safeName(songId)
            val part = File(target, "$base.part")
            part.outputStream().use { out ->
                val input = body.byteStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    onBytes(n.toLong())
                }
            }
            val file = File(target, "$base.${extension(type)}")
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return file
        }
    }

    private fun deleteOrphans(ids: Collection<String>) {
        val still = collections.flatMapTo(HashSet()) { it.songIds }
        val gone = ids.filter { it !in still }.mapNotNull { id -> files.remove(id) }
        if (gone.isNotEmpty()) scope.launch(writer) { gone.forEach { it.delete() } }
    }

    private fun scanFiles() {
        val known = collections.flatMapTo(HashSet()) { it.songIds }.associateBy(::safeName)
        dir.listFiles()?.forEach { f ->
            if (!f.isFile || f.name.endsWith(".part")) return@forEach
            known[f.nameWithoutExtension]?.let { files[it] = f }
        }
    }

    private fun writeIndex() {
        val snapshot = collections.toList()
        scope.launch(writer) {
            runCatching {
                val tmp = File(Paths.config, "offline.json.tmp")
                tmp.writeText(json.encodeToString(snapshot))
                Files.move(tmp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** Playlists und Mixe mit dem Server abgleichen und neue Titel laden. */
    private suspend fun syncNow() {
        if (app.session.current == null) return
        failed.clear()
        collections.toList().forEach { c ->
            runCatching {
                when (c.kind) {
                    "playlist" -> {
                        val pl = app.client.playlist(c.id)
                        if (pl.entry.map { it.id } != c.songIds) save(c.copy(name = pl.name, coverArt = pl.coverArt ?: c.coverArt, songIds = pl.entry.map { it.id }, songs = pl.entry), c)
                    }
                    "mix" -> {
                        val songs = if (c.id == "daylist") app.daylist.current?.songs
                        else app.mixes.mixes().firstOrNull { it.id == c.id }?.songs
                        if (songs != null && songs.isNotEmpty() && songs.map { it.id } != c.songIds) save(c.copy(songIds = songs.map { it.id }, songs = songs), c)
                    }
                }
            }
        }
        collections.forEach { c -> c.songIds.forEach { enqueue(c.id, it) } }
        startWorker()
    }

    /** Server regelmäßig anpingen; nach der Rückkehr Downloads fortsetzen. */
    private suspend fun watchServer() {
        while (true) {
            val creds = app.session.current
            if (creds != null) {
                val ok = runCatching { app.client.ping(creds) }.isSuccess
                if (ok && !online) { online = true; resume() } else online = ok
            } else online = true
            delay(if (online) 60_000 else 15_000)
        }
    }

    companion object {
        val defaultDir: File = File(System.getProperty("user.home"), "Musik/NaviDroid")

        private fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

        private fun extension(type: String) = when {
            "flac" in type -> "flac"
            "mpeg" in type || "mp3" in type -> "mp3"
            "opus" in type -> "opus"
            "ogg" in type || "vorbis" in type -> "ogg"
            "mp4" in type || "m4a" in type || "aac" in type -> "m4a"
            "wav" in type -> "wav"
            "aiff" in type -> "aiff"
            "dsf" in type || "dsd" in type -> "dsf"
            else -> "audio"
        }
    }
}

private fun offlineBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.GERMANY, "%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(java.util.Locale.GERMANY, "%.1f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%d KB".format(b / 1024)
    else -> "$b B"
}

private val qualities = listOf(0 to "Original", 320 to "MP3 320 kbit/s", 256 to "MP3 256 kbit/s", 192 to "MP3 192 kbit/s", 128 to "MP3 128 kbit/s")

/** Hinweis oben im Fenster, wenn der Server nicht erreichbar ist. */
@Composable
fun OfflineBanner(app: DesktopApp) {
    if (app.offline.online) return
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF3A2E12)).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.WifiOff, null, tint = Color(0xFFF2D58A), modifier = Modifier.size(16.dp))
        Text(
            "Server nicht erreichbar – du hörst heruntergeladene Musik. Nicht geladene Titel sind ausgegraut.",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            color = Color(0xFFF2D58A),
        )
    }
}

/** Seite „Downloads“ (Design „Offline & Downloads“). */
@Composable
fun DownloadsScreen(app: DesktopApp) {
    val off = app.offline
    var filter by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<OfflineCollection?>(null) }
    val list = off.collections.filter { filter.isEmpty() || it.kind == filter }
    LazyVerticalGrid(
        GridCells.Adaptive(180.dp),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 56.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { DownloadsHeader(app) }
        off.progress?.let { p -> item(span = { GridItemSpan(maxLineSpan) }) { ProgressCard(app, p) } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to "Alle", "album" to "Alben", "playlist" to "Playlists", "mix" to "Mixe").forEach { (k, label) ->
                    Chip(label, filter == k) { filter = k }
                }
            }
        }
        if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Noch nichts heruntergeladen", style = MaterialTheme.typography.titleLarge)
                Text("Lade Alben, Playlists oder Mixe über ihren Download-Knopf, um sie ohne Server zu hören.", color = Navi.Muted)
            }
        }
        items(list, key = { it.id }) { c -> CollectionTile(app, c, onRemove = { confirm = c }) }
    }
    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("„${c.name}“ entfernen?") },
            text = { Text("Die heruntergeladenen Dateien werden von der Platte gelöscht, sofern keine andere Sammlung sie braucht. Auf dem Server bleibt alles erhalten.") },
            confirmButton = { TextButton(onClick = { off.remove(c.id); confirm = null }) { Text("Entfernen") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun OutlinePill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).border(1.dp, Navi.Outline, RoundedCornerShape(19.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun DownloadsHeader(app: DesktopApp) {
    val off = app.offline
    var qualityMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Downloads", style = MaterialTheme.typography.displaySmall)
            val path = off.dir.absolutePath.replaceFirst(System.getProperty("user.home"), "~")
            Text(
                "${off.downloadedCount} Titel · ${offlineBytes(off.totalBytes)} · ${offlineBytes(off.freeBytes)} frei · $path",
                style = MaterialTheme.typography.bodyMedium, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            OutlinePill("Qualität: " + (qualities.firstOrNull { it.first == app.settings.downloadBitrate }?.second ?: "${app.settings.downloadBitrate} kbit/s")) { qualityMenu = true }
            DropdownMenu(qualityMenu, { qualityMenu = false }) {
                qualities.forEach { (v, label) ->
                    DropdownMenuItem(text = { Text(label, color = if (v == app.settings.downloadBitrate) MaterialTheme.colorScheme.primary else Navi.Text) }, onClick = {
                        qualityMenu = false
                        app.updateSettings(app.settings.copy(downloadBitrate = v))
                    })
                }
            }
        }
        OutlinePill("Speicherort ändern …") {
            val chooser = javax.swing.JFileChooser(off.dir).apply {
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Speicherort für Downloads"
            }
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile?.let(off::relocate)
        }
    }
}

@Composable
private fun ProgressCard(app: DesktopApp, p: OfflineProgress) {
    val off = app.offline
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF181A1F)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (off.paused || !off.online) CircularProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f }, Modifier.size(36.dp), strokeWidth = 3.dp, trackColor = Navi.Outline)
            else CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp, trackColor = Navi.Outline)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Lädt „${p.name}“ · ${p.done} von ${p.total} Titeln", style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = when {
                !off.online -> "Wartet auf den Server"
                off.paused -> "Pausiert"
                p.bytesPerSecond > 0 -> "${offlineBytes(p.bytesPerSecond)}/s" + (p.remainingSeconds?.let { s -> " · noch etwa " + if (s < 60) "1 Min" else "${(s + 59) / 60} Min" } ?: "")
                else -> "Startet …"
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
        }
        Box(
            Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(Navi.Surface2)
                .clickable { if (off.paused) off.resumeDownloads() else off.pause() }.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(if (off.paused) "Fortsetzen" else "Pausieren", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun CollectionTile(app: DesktopApp, c: OfflineCollection, onRemove: () -> Unit) {
    val off = app.offline
    val state = off.state(c.id)
    val accent = MaterialTheme.colorScheme.primary
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val open = {
        when (c.kind) {
            "album" -> app.nav.go(Screen.AlbumPage(c.id))
            "playlist" -> app.nav.go(Screen.PlaylistPage(c.id))
            "song" -> c.songs.firstOrNull()?.albumId?.let { app.nav.go(Screen.AlbumPage(it)) } ?: Unit
            else -> app.nav.go(if (c.id == "daylist") Screen.Daylist else Screen.MixPage(c.id))
        }
    }
    val done = off.doneCount(c)
    val artist = c.songs.mapNotNull { it.artist }.distinct().singleOrNull()
    val sub = when (state) {
        DownloadState.Loading -> listOf(kindLabel(c.kind), "$done / ${c.songIds.size} geladen")
        DownloadState.Queued -> listOf(kindLabel(c.kind), if (done > 0) "$done / ${c.songIds.size} · wartet" else "wartet")
        else -> when (c.kind) {
            "album" -> listOfNotNull("Album", artist, "${c.songIds.size} Titel")
            "song" -> listOfNotNull("Titel", artist)
            "mix" -> listOf("Mix", if (app.settings.autoSyncDownloads) "hält sich aktuell" else "${c.songIds.size} Titel")
            else -> listOf("Playlist", "${c.songIds.size} Titel")
        }
    }.joinToString(" · ")
    ContextMenuArea(items = {
        listOf(ContextMenuItem("Öffnen") { open() }, ContextMenuItem("Download entfernen") { onRemove() })
    }) {
        Column(
            Modifier.alpha(if (state == DownloadState.Queued) 0.55f else 1f).clip(RoundedCornerShape(12.dp)).hoverable(hover).clickable(onClick = open),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                Cover(app.client.coverArtUrl(c.coverArt, 400), Modifier.fillMaxSize(), 12)
                val badgeBg = when (state) { DownloadState.Done -> accent; DownloadState.Loading -> Navi.Text; else -> Color(0xFF5C5F66) }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(28.dp).clip(RoundedCornerShape(14.dp)).background(badgeBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        when (state) { DownloadState.Done -> "Heruntergeladen"; DownloadState.Loading -> "Wird geladen"; else -> "Wartet" },
                        tint = Navi.Bg, modifier = Modifier.size(16.dp),
                    )
                }
                if (hovered) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp).clip(RoundedCornerShape(15.dp)).background(Color.Black.copy(alpha = 0.55f)),
                    ) { Icon(Icons.Rounded.Close, "Download entfernen", tint = Navi.Text, modifier = Modifier.size(16.dp)) }
                }
            }
            Text(c.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun kindLabel(kind: String) = when (kind) { "album" -> "Album"; "playlist" -> "Playlist"; "song" -> "Titel"; else -> "Mix" }
