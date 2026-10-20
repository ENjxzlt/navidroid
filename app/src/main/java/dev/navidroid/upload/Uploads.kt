package dev.navidroid.upload

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.navidroid.NaviApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

enum class UploadState { Waiting, Running, Done, Exists, Failed }

/** Eine ausgewählte Datei mit den (bearbeitbaren) Angaben für den Helfer. */
class UploadFile(
    val uri: Uri,
    val fileName: String,
    val size: Long,
    title: String,
    track: Int,
    val disc: Int,
    val trackArtist: String?,
    val genre: String?,
    val hadTags: Boolean,
) {
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
    /** Nur Hörspiele: Folgennummer (der Albumname wird dann „Folge N: Titel“). */
    var episode by mutableStateOf("")
    val files = mutableStateListOf<UploadFile>()
    val discs get() = files.maxOfOrNull { it.disc } ?: 1
}

/**
 * Eigene Dateien in den Musikordner hochladen (über den Helfer). Die Tags werden auf dem Handy gelesen,
 * nach Album gruppiert und können vor dem Hochladen korrigiert werden. Der Upload läuft im Hintergrund
 * mit Benachrichtigung ([UploadService]) und Datei für Datei; Fehler halten die übrigen nicht auf.
 */
class UploadManager(private val app: NaviApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    val albums = mutableStateListOf<UploadAlbum>()
    var reading by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set

    /** Ziel: Hörspiel-Bibliothek (Helfer: AUDIOBOOK_DIR) statt Musikordner. */
    var audiobook by mutableStateOf(false)
        private set

    fun useAudiobook(on: Boolean) {
        if (running) return
        audiobook = on
        if (on) albums.forEach { prefillEpisode(it) }
    }

    /** Folgennummer aus dem Albumnamen übernehmen („Folge 12: und der …“). */
    private fun prefillEpisode(a: UploadAlbum) {
        if (a.episode.isNotBlank()) return
        val n = dev.navidroid.data.AudiobookRepository.episodeNumber(a.album) ?: return
        a.episode = "$n"
        a.album = dev.navidroid.data.AudiobookRepository.withoutEpisodeNumber(a.album)
    }

    private fun albumName(a: UploadAlbum): String {
        val title = a.album.trim().ifEmpty { "Unbekannt" }
        val n = a.episode.trim().toIntOrNull()
        return if (audiobook && n != null) "Folge $n: $title" else title
    }

    val files get() = albums.flatMap { it.files }
    val totalBytes get() = files.sumOf { it.size.coerceAtLeast(0) }
    val sentBytes get() = files.sumOf { f -> if (f.state == UploadState.Done || f.state == UploadState.Exists) f.size else (f.size * f.progress).toLong() }
    val doneCount get() = files.count { it.state == UploadState.Done || it.state == UploadState.Exists || it.state == UploadState.Failed }

    fun add(context: Context, uris: List<Uri>) {
        if (running) return
        if (finished) clear()
        reading = true
        scope.launch {
            val parsed = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { read(context, it) }.getOrNull() } }
            parsed.forEach { (key, meta, file) ->
                val album = albums.firstOrNull { norm(it.artist) == key.first && norm(it.album) == key.second }
                    ?: UploadAlbum(meta.artist, meta.album, meta.year, meta.cover).also { albums += it }
                if (album.cover == null) album.cover = meta.cover
                if (album.files.none { it.uri == file.uri }) album.files += file
            }
            // Fehlende Nummern aus der Reihenfolge ergänzen, Titel sortieren
            albums.forEach { a ->
                val sorted = a.files.sortedWith(compareBy({ it.disc }, { if (it.track > 0) it.track else Int.MAX_VALUE }, { it.fileName }))
                var next = 1
                sorted.forEach { f -> if (f.track <= 0) f.track = next; next = f.track + 1 }
                a.files.clear()
                a.files += sorted
                if (audiobook) prefillEpisode(a)
            }
            reading = false
        }
    }

    fun remove(file: UploadFile) {
        albums.forEach { it.files.remove(file) }
        albums.removeAll { it.files.isEmpty() }
    }

    fun clear() {
        if (running) return
        albums.clear()
        finished = false
    }

    fun start(context: Context) {
        if (running || files.isEmpty()) return
        running = true
        finished = false
        ContextCompat.startForegroundService(context, Intent(context, UploadService::class.java))
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
                            val r = app.helper.upload(query(album, f), body(context, f))
                            f.state = if (r.status == "exists") UploadState.Exists else UploadState.Done
                            if (f.state == UploadState.Done) anyOk = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            f.state = UploadState.Failed
                            f.error = e.message
                        }
                    }
                    val cover = album.cover
                    if (anyOk && cover != null) {
                        runCatching {
                            app.helper.uploadCover(album.artist.trim(), albumName(album), album.year.toIntOrNull(), cover, "image/jpeg", if (audiobook) "audiobook" else "music")
                        }
                    }
                }
                dev.navidroid.ui.rescanLibrary(app)
            } finally {
                running = false
                finished = true
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun query(a: UploadAlbum, f: UploadFile) = buildMap {
        put("filename", f.fileName)
        put("artist", a.artist.trim().ifEmpty { "Unbekannt" })
        put("album", albumName(a))
        put("title", f.title.trim().ifEmpty { f.fileName.substringBeforeLast('.') })
        put("track", "${f.track}")
        put("disc", "${f.disc}")
        put("discs", "${a.discs}")
        a.year.trim().toIntOrNull()?.let { put("year", "$it") }
        if (audiobook) put("root", "audiobook") // Genre setzt der Helfer auf „Hörspiel“
        else f.genre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
        f.trackArtist?.takeIf { it.isNotBlank() && !it.equals(a.artist.trim(), ignoreCase = true) }?.let { put("trackArtist", it) }
    }

    /** Streamt die Datei direkt vom Handy und meldet den Fortschritt. */
    private fun body(context: Context, f: UploadFile) = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = if (f.size > 0) f.size else -1
        override fun writeTo(sink: BufferedSink) {
            val input = context.contentResolver.openInputStream(f.uri) ?: error("Datei nicht mehr verfügbar")
            input.source().use { src ->
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

    private data class Meta(val artist: String, val album: String, val year: String, val cover: ByteArray?)

    private fun read(context: Context, uri: Uri): Triple<Pair<String, String>, Meta, UploadFile> {
        var name = uri.lastPathSegment ?: "Datei"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            fun tag(key: Int) = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val year = (tag(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: tag(MediaMetadataRetriever.METADATA_KEY_DATE))?.take(4)?.filter(Char::isDigit).orEmpty()
            val track = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            val disc = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 1
            val genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val cover = runCatching { r.embeddedPicture }.getOrNull()
            val mainArtist = albumArtist ?: artist ?: ""
            val albumName = album ?: ""
            val fallbackTitle = name.substringBeforeLast('.').replace(Regex("""^\s*\d+\s*[-._ ]+\s*"""), "").trim()
            val file = UploadFile(
                uri, name, size,
                title = title ?: fallbackTitle,
                track = track,
                disc = disc.coerceAtLeast(1),
                trackArtist = artist,
                genre = genre,
                hadTags = title != null && mainArtist.isNotEmpty() && albumName.isNotEmpty(),
            )
            return Triple(norm(mainArtist) to norm(albumName), Meta(mainArtist, albumName, year, cover), file)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
