package dev.navidroid.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.navidroid.NaviApp
import dev.navidroid.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Menüstruktur für Android Auto (und andere Mediensteuerungen wie Google Assistant):
 * Start (Daylist, Mixe) · Playlists · Alben (zuletzt gespielt, neu) · Heruntergeladen.
 *
 * IDs: Ordner wie "playlists" oder "album:<id>", Titel als "<songId>@<ordner>" – so spielt ein Tipp
 * auf einen Titel den ganzen Ordner ab diesem Titel.
 */
class AutoLibrary(private val app: NaviApp, private val scope: CoroutineScope) : MediaLibrarySession.Callback {

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch {
            try {
                f.set(block())
            } catch (e: CancellationException) {
                f.cancel(false)
            } catch (e: Throwable) {
                f.setException(e)
            }
        }
        return f
    }

    private fun folder(id: String, title: String, subtitle: String? = null, art: String? = null, playable: Boolean = false, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(art?.toUri())
                    .setIsBrowsable(true)
                    .setIsPlayable(playable)
                    .setMediaType(type)
                    .build()
            )
            .build()

    private fun Song.item(parent: String): MediaItem {
        val base = toMediaItem(app.client)
        return base.buildUpon().setMediaId("$id@$parent").build()
    }

    // ---- Wurzel und Ordner ----

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "NaviDroid"), params))

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(folder(mediaId, mediaId), null))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val items = runCatching { children(parentId) }.getOrDefault(emptyList())
        val from = (page * pageSize).coerceAtMost(items.size)
        val to = (from + pageSize).coerceAtMost(items.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
    }

    private suspend fun children(parentId: String): List<MediaItem> {
        val offline = !app.offline.online
        return when {
            parentId == ROOT -> if (offline) listOf(folder(DOWNLOADS, "Heruntergeladen")) else listOf(
                folder(HOME, "Start"),
                folder(PLAYLISTS, "Playlists", type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(ALBUMS, "Alben", type = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folder(DOWNLOADS, "Heruntergeladen"),
            )
            parentId == HOME -> buildList {
                app.daylist.current?.let { d -> add(folder(DAYLIST, "Daylist", d.name, playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST)) }
                runCatching { app.mixes.mixes() }.getOrDefault(emptyList()).forEach { m ->
                    add(folder("mix:${m.id}", m.name, m.description, app.client.coverArtUrl(m.songs.firstOrNull()?.coverArt, 300), playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST))
                }
            }
            parentId == PLAYLISTS -> app.client.playlists().map { p ->
                folder("playlist:${p.id}", p.name, "${p.songCount} Titel", app.client.coverArtUrl(p.coverArt, 300), playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
            parentId == ALBUMS -> app.audiobooks.albumsWithoutAudiobooks(app.client.albumList("recent", 25, 0) + app.client.albumList("newest", 25, 0)).distinctBy { it.id }.map { a ->
                folder("album:${a.id}", a.name, a.artist, app.client.coverArtUrl(a.coverArt, 300), playable = true, type = MediaMetadata.MEDIA_TYPE_ALBUM)
            }
            parentId == DOWNLOADS -> app.offline.collections.map { c ->
                folder("dl:${c.id}", c.name, c.subtitle, app.client.coverArtUrl(c.coverArt, 300), playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
            else -> songsOf(parentId).map { it.item(parentId) }
        }
    }

    /** Titel eines abspielbaren Ordners. */
    private suspend fun songsOf(id: String): List<Song> {
        val songs = when {
            id == DAYLIST -> app.daylist.current?.songs.orEmpty()
            id.startsWith("mix:") -> id.removePrefix("mix:").let { mid -> app.mixes.mix(mid)?.songs ?: app.mixes.mixes().firstOrNull { it.id == mid }?.songs.orEmpty() }
            id.startsWith("playlist:") -> app.client.playlist(id.removePrefix("playlist:")).entry
            id.startsWith("album:") -> app.client.album(id.removePrefix("album:")).song
            id.startsWith("dl:") -> app.offline.collection(id.removePrefix("dl:"))?.songs.orEmpty()
            else -> emptyList()
        }
        return if (app.offline.online) songs else songs.filter { app.offline.isDownloaded(it.id) }
    }

    // ---- Abspielen ----

    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
        future { resolve(mediaItems).first.toMutableList() }

    override fun onSetMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        val (items, start) = resolve(mediaItems)
        MediaSession.MediaItemsWithStartPosition(items, if (start >= 0) start else startIndex.coerceAtLeast(0), startPositionMs)
    }

    /**
     * Aus Auto-IDs echte Titel machen: Ordner → alle Titel, "<songId>@<ordner>" → ganzer Ordner ab dem Titel,
     * Suchanfrage (Assistant) → Treffer. Aus der App kommen schon Song-IDs.
     */
    private suspend fun resolve(items: List<MediaItem>): Pair<List<MediaItem>, Int> {
        val bitrate = PlaybackService.currentBitrate(app)
        fun stream(songs: List<Song>) = songs.map { PlaybackService.withStream(app, it.toMediaItem(app.client), bitrate) }
        if (items.size == 1) {
            val item = items[0]
            val id = item.mediaId
            val query = item.requestMetadata.searchQuery
            when {
                !query.isNullOrBlank() && id.isEmpty() -> {
                    val r = app.client.search(query)
                    val songs = r.song.ifEmpty { r.album.firstOrNull()?.let { app.client.album(it.id).song }.orEmpty() }
                        .ifEmpty { r.artist.firstOrNull()?.let { app.client.similarSongsByArtist(it.id, 50) }.orEmpty() }
                    return stream(songs) to 0
                }
                "@" in id -> {
                    val songId = id.substringBefore("@")
                    val songs = songsOf(id.substringAfter("@"))
                    return stream(songs) to songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
                }
                id == DAYLIST || id.contains(":") -> return stream(songsOf(id)) to 0
            }
        }
        return items.map { PlaybackService.withStream(app, it, bitrate) } to -1
    }

    // ---- Suche (Android Auto Suchfeld) ----

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        lastQuery = query
        scope.launch {
            val n = runCatching { app.client.search(query).song.size }.getOrDefault(0)
            session.notifySearchResultChanged(browser, query, n, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    private var lastQuery = ""

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val songs = app.client.search(query).song
        LibraryResult.ofItemList(ImmutableList.copyOf(songs.map { PlaybackService.withStream(app, it.toMediaItem(app.client)) }), params)
    }

    companion object {
        const val ROOT = "root"
        const val HOME = "home"
        const val PLAYLISTS = "playlists"
        const val ALBUMS = "albums"
        const val DOWNLOADS = "downloads"
        const val DAYLIST = "daylist"
    }
}

@Suppress("unused")
private fun Bundle?.orEmptyBundle() = this ?: Bundle.EMPTY
