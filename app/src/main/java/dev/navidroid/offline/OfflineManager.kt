@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.navidroid.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import coil.imageLoader
import coil.request.ImageRequest
import dev.navidroid.NaviApp
import dev.navidroid.data.Album
import dev.navidroid.data.Playlist
import dev.navidroid.data.Song
import dev.navidroid.data.SongRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

/** Etwas, das offline verfügbar ist: ein Album, eine Playlist, ein Mix oder die Sammlung einzelner Titel. */
@Serializable
data class OfflineCollection(
    val id: String,
    val kind: String,
    val refId: String,
    val name: String,
    val subtitle: String? = null,
    val coverArt: String? = null,
    val songs: List<Song> = emptyList(),
    val addedAt: Long = 0,
) {
    companion object {
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val MIX = "mix"
        const val SONGS = "songs"
        const val SINGLES_ID = "songs:single"
    }
}

/** Stand eines einzelnen Titels. */
data class SongDownload(val state: Int, val percent: Float, val bytes: Long) {
    val done get() = state == Download.STATE_COMPLETED
    val active get() = state == Download.STATE_DOWNLOADING
    val waiting get() = state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
    val failed get() = state == Download.STATE_FAILED
}

/** Fortschritt einer Sammlung für die Oberfläche. */
data class CollectionProgress(val done: Int, val total: Int, val bytes: Long, val percent: Float) {
    val complete get() = total > 0 && done == total
}

/**
 * Offline-Downloads: Titel landen über den Media3-DownloadManager in einem eigenen Speicher im App-Ordner
 * (Schlüssel = Song-ID). Der Player liest fertig geladene Titel von dort statt aus dem Netz.
 * Welche Alben/Playlists/Mixe heruntergeladen sind, steht samt Titel-Infos in `offline/index.json`,
 * damit die App auch ohne Verbindung alles anzeigen kann.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class OfflineManager(private val app: NaviApp) {
    private val dir = File(app.filesDir, "offline")
    private val indexFile = File(dir, "index.json")
    private val prefs = app.getSharedPreferences("offline", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val writer = Dispatchers.IO.limitedParallelism(1)

    private val database by lazy { StandaloneDatabaseProvider(app) }

    val cache: SimpleCache by lazy { SimpleCache(File(dir, "media"), NoOpCacheEvictor(), database) }

    val manager: DownloadManager by lazy {
        val upstream = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
        DownloadManager(app, database, cache, upstream, Executors.newFixedThreadPool(2)).apply {
            maxParallelDownloads = 2
            requirements = requirementsFor(wifiOnly)
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    put(download)
                    if (download.state == Download.STATE_COMPLETED) prefetchExtras(download.request.id)
                }

                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    songs.remove(download.request.id)
                }
            })
        }
    }

    /** Song-ID → Stand. */
    val songs = mutableStateMapOf<String, SongDownload>()
    val collections = mutableStateListOf<OfflineCollection>()

    var online by mutableStateOf(true)
        private set

    private var _wifiOnly by mutableStateOf(prefs.getBoolean("wifiOnly", true))
    private var _quality by mutableStateOf(prefs.getInt("quality", 320))
    private var _autoSync by mutableStateOf(prefs.getBoolean("autoSync", true))

    var wifiOnly: Boolean
        get() = _wifiOnly
        set(v) {
            _wifiOnly = v
            prefs.edit().putBoolean("wifiOnly", v).apply()
            manager.requirements = requirementsFor(v)
        }

    /** Download-Qualität in kbit/s, 0 = Originaldatei. Gilt für neue Downloads. */
    var quality: Int
        get() = _quality
        set(v) { _quality = v; prefs.edit().putInt("quality", v).apply() }

    /** Heruntergeladene Playlists und Mixe beim Start mit dem Server abgleichen. */
    var autoSync: Boolean
        get() = _autoSync
        set(v) { _autoSync = v; prefs.edit().putBoolean("autoSync", v).apply() }

    private var synced = false

    fun start() {
        readIndex()
        manager // auf dem Hauptthread anlegen
        scope.launch {
            val all = withContext(Dispatchers.IO) {
                buildList {
                    manager.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) add(c.download) }
                }
            }
            all.forEach(::put)
            // Fortschritt laufender Downloads meldet der Manager nicht von selbst.
            while (isActive) {
                manager.currentDownloads.forEach(::put)
                delay(if (manager.currentDownloads.isEmpty()) 3_000 else 700)
            }
        }
        watchNetwork()
    }

    private fun requirementsFor(wifiOnly: Boolean) =
        Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    private fun put(d: Download) {
        songs[d.request.id] = SongDownload(d.state, d.percentDownloaded.takeIf { it >= 0 } ?: 0f, d.bytesDownloaded)
    }

    fun isDownloaded(songId: String) = songs[songId]?.done == true

    val totalBytes: Long get() = songs.values.sumOf { it.bytes }
    val downloadedCount: Int get() = songs.values.count { it.done }
    val freeBytes: Long get() = dir.apply { mkdirs() }.usableSpace

    fun collection(id: String) = collections.firstOrNull { it.id == id }

    fun progress(c: OfflineCollection): CollectionProgress {
        val states = c.songs.map { songs[it.id] }
        val done = states.count { it?.done == true }
        val percent = if (c.songs.isEmpty()) 0f else states.sumOf { (if (it?.done == true) 100f else it?.percent ?: 0f).toDouble() }.toFloat() / c.songs.size
        return CollectionProgress(done, c.songs.size, states.sumOf { it?.bytes ?: 0L }, percent)
    }

    /** Album bzw. Playlist aus dem Offline-Index, falls der Server nicht erreichbar ist. */
    fun album(id: String): Album? = collection("album:$id")?.let { c ->
        Album(id = c.refId, name = c.name, artist = c.subtitle, coverArt = c.coverArt, songCount = c.songs.size, duration = c.songs.sumOf { it.duration }, song = c.songs, artistId = c.songs.firstOrNull()?.artistId)
    }

    fun playlist(id: String): Playlist? = collection("playlist:$id")?.let { c ->
        Playlist(id = c.refId, name = c.name, comment = c.subtitle, coverArt = c.coverArt, songCount = c.songs.size, duration = c.songs.sumOf { it.duration }, entry = c.songs)
    }

    // ---- Herunterladen / Entfernen ----

    fun downloadAlbum(album: Album) = save(
        OfflineCollection("album:${album.id}", OfflineCollection.ALBUM, album.id, album.name, album.artist, album.coverArt, album.song, now())
    )

    fun downloadPlaylist(pl: Playlist) = save(
        OfflineCollection("playlist:${pl.id}", OfflineCollection.PLAYLIST, pl.id, pl.name, pl.comment?.takeIf { it.isNotBlank() } ?: pl.owner, pl.coverArt, pl.entry, now())
    )

    fun downloadMix(id: String, name: String, description: String, songs: List<Song>) = save(
        OfflineCollection("mix:$id", OfflineCollection.MIX, id, name, description, songs.firstOrNull()?.coverArt, songs, now())
    )

    fun downloadSongs(list: List<Song>) {
        val old = collection(OfflineCollection.SINGLES_ID)
        val merged = (old?.songs.orEmpty() + list).distinctBy { it.id }
        save(OfflineCollection(OfflineCollection.SINGLES_ID, OfflineCollection.SONGS, "single", "Einzelne Titel", null, merged.firstOrNull()?.coverArt, merged, old?.addedAt ?: now()))
    }

    fun remove(collectionId: String) {
        val c = collection(collectionId) ?: return
        collections.remove(c)
        writeIndex()
        removeOrphans(c.songs.map { it.id })
    }

    /** Einzelnen Titel entfernen (aus allen Sammlungen). */
    fun removeSong(songId: String) {
        val changed = collections.map { c -> if (c.songs.any { it.id == songId }) c.copy(songs = c.songs.filterNot { it.id == songId }) else c }
        collections.clear()
        collections += changed.filter { it.songs.isNotEmpty() || it.kind != OfflineCollection.SONGS }
        writeIndex()
        removeOrphans(listOf(songId))
    }

    fun removeAll() {
        collections.clear()
        writeIndex()
        send({ DownloadService.sendRemoveAllDownloads(app, OfflineDownloadService::class.java, false) }) { manager.removeAllDownloads() }
        File(dir, "lyrics").deleteRecursively()
    }

    private fun save(c: OfflineCollection) {
        val i = collections.indexOfFirst { it.id == c.id }
        val previous = if (i >= 0) collections[i] else null
        if (i >= 0) collections[i] = c else collections.add(0, c)
        writeIndex()
        c.songs.forEach { song -> if (songs[song.id]?.let { !it.failed } != true) request(song.id) }
        previous?.let { p -> removeOrphans(p.songs.map { it.id } - c.songs.map { it.id }.toSet()) }
        prefetchCovers(c)
    }

    private fun request(songId: String) {
        val url = app.client.streamUrl(songId, quality)
        val req = DownloadRequest.Builder(songId, url.toUri()).setCustomCacheKey(songId).build()
        send({ DownloadService.sendAddDownload(app, OfflineDownloadService::class.java, req, false) }) { manager.addDownload(req) }
    }

    /** Fehlgeschlagene Titel neu anstoßen. */
    fun retryFailed() {
        songs.filterValues { it.failed }.keys.forEach(::request)
    }

    /**
     * Über den Dienst schicken (läuft mit Benachrichtigung weiter); im Hintergrund darf Android den Dienst
     * nicht starten – dann direkt an den Manager, solange die App ohnehin läuft.
     */
    private fun send(viaService: () -> Unit, direct: () -> Unit) {
        try {
            viaService()
        } catch (e: IllegalStateException) {
            direct()
        } catch (e: SecurityException) {
            direct()
        }
    }

    private fun removeOrphans(ids: Collection<String>) {
        val still = collections.flatMapTo(HashSet()) { c -> c.songs.map { it.id } }
        ids.filter { it !in still }.forEach { id ->
            send({ DownloadService.sendRemoveDownload(app, OfflineDownloadService::class.java, id, false) }) { manager.removeDownload(id) }
            File(dir, "lyrics/$id.json").delete()
        }
    }

    private fun now() = System.currentTimeMillis()

    // ---- Cover und Songtexte ----

    private fun prefetchCovers(c: OfflineCollection) {
        val ids = (listOf(c.coverArt) + c.songs.map { it.coverArt }).filterNotNull().distinct()
        val loader = app.imageLoader
        ids.forEach { id ->
            listOf(300, 600).forEach { size ->
                app.client.coverArtUrl(id, size)?.let { loader.enqueue(ImageRequest.Builder(app).data(it).build()) }
            }
        }
    }

    private fun prefetchExtras(songId: String) {
        val song = collections.firstNotNullOfOrNull { c -> c.songs.firstOrNull { it.id == songId } } ?: return
        scope.launch {
            runCatching {
                app.lyrics.saveOffline(SongRef(song.id, song.title, song.artist, song.album, song.duration * 1000L), File(dir, "lyrics"))
            }
        }
    }

    // ---- Abgleich von Playlists und Mixen ----

    private fun syncNow() {
        if (synced || !autoSync || app.session.current == null) return
        synced = true
        scope.launch {
            retryFailed()
            collections.toList().forEach { c ->
                runCatching {
                    when (c.kind) {
                        OfflineCollection.PLAYLIST -> {
                            val pl = app.client.playlist(c.refId)
                            if (pl.entry.map { it.id } != c.songs.map { it.id }) save(c.copy(name = pl.name, coverArt = pl.coverArt, songs = pl.entry))
                        }
                        OfflineCollection.MIX -> {
                            val mix = app.mixes.mixes().firstOrNull { it.id == c.refId } ?: return@runCatching
                            if (mix.songs.map { it.id } != c.songs.map { it.id }) save(c.copy(name = mix.name, subtitle = mix.description, songs = mix.songs))
                        }
                    }
                }
            }
        }
    }

    // ---- Netzwerk ----

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        fun check() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val now = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true ||
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            scope.launch {
                online = now
                if (now) syncNow()
            }
        }
        check()
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = check()
            override fun onLost(network: Network) = check()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = check()
        })
    }

    // ---- Index ----

    private fun readIndex() {
        val list = runCatching { json.decodeFromString<List<OfflineCollection>>(indexFile.readText()) }.getOrDefault(emptyList())
        collections.clear()
        collections += list
    }

    private fun writeIndex() {
        val snapshot = collections.toList()
        scope.launch(writer) {
            dir.mkdirs()
            val tmp = File(dir, "index.json.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            tmp.renameTo(indexFile)
        }
    }
}
