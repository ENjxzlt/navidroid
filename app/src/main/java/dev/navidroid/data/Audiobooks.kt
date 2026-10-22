package dev.navidroid.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hörspiele und Hörbücher liegen in einer eigenen Navidrome-Bibliothek. Diese Klasse weiß, welche Alben
 * dazugehören (auch offline, die IDs werden gemerkt), damit Musik-Funktionen – Mixe, Daylist, Autoplay,
 * Bibliothek – sie auslassen und der Player sie als Hörspiel behandelt (Weiterhören, Geschwindigkeit).
 *
 * Die Hörposition liegt als Navidrome-Lesezeichen auf dem Server, je Folge (Album) genau eines.
 */
class AudiobookRepository(context: Context, private val client: SubsonicClient) {
    private val prefs = context.getSharedPreferences("audiobooks", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** Gewählte Bibliothek: [AUTO] = nach dem Namen erkennen, [OFF] = keine. */
    var folderSetting by mutableStateOf(prefs.getInt("folder", AUTO))
        private set

    /** Bibliothek, die gerade als Hörspiel-Bibliothek gilt (null = keine). */
    var folder by mutableStateOf<MusicFolder?>(null)
        private set
    var folders by mutableStateOf<List<MusicFolder>>(emptyList())
        private set
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var bookmarks by mutableStateOf<List<Bookmark>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    @Volatile
    private var albumIds: Set<String> = prefs.getStringSet("albums", emptySet()).orEmpty().toHashSet()
    @Volatile
    private var artistIds: Set<String> = prefs.getStringSet("artists", emptySet()).orEmpty().toHashSet()
    private var loaded = false

    val enabled: Boolean get() = albumIds.isNotEmpty() || folder != null

    fun isAudiobook(albumId: String?): Boolean = albumId != null && albumId in albumIds
    fun isAudiobookArtist(artistId: String?): Boolean = artistId != null && artistId in artistIds

    /** Für Musik-Funktionen: Hörspiele herausnehmen. */
    fun withoutAudiobooks(songs: List<Song>): List<Song> = if (albumIds.isEmpty()) songs else songs.filterNot { isAudiobook(it.albumId) }
    fun albumsWithoutAudiobooks(list: List<Album>): List<Album> = if (albumIds.isEmpty()) list else list.filterNot { isAudiobook(it.id) }

    /** Lädt Bibliotheken und Hörspiel-Alben (einmal pro App-Start, mit [force] neu). Gibt die Album-IDs zurück. */
    suspend fun ensure(force: Boolean = false): Set<String> = mutex.withLock {
        if (loaded && !force) return albumIds
        try {
            val all = client.musicFolders()
            folders = all
            val chosen = when (folderSetting) {
                OFF -> null
                AUTO -> if (all.size > 1) all.firstOrNull { NAME.containsMatchIn(it.name) } else null
                else -> all.firstOrNull { it.id == folderSetting }
            }
            folder = chosen
            val list = chosen?.let { client.allAlbums(it.id) }.orEmpty()
            albums = list
            albumIds = list.map { it.id }.toHashSet()
            artistIds = list.mapNotNull { it.artistId }.toHashSet()
            prefs.edit().putStringSet("albums", albumIds).putStringSet("artists", artistIds).apply()
            error = null
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Hörspiele konnten nicht geladen werden"
        }
        albumIds
    }

    suspend fun choose(folderId: Int) {
        folderSetting = folderId
        prefs.edit().putInt("folder", folderId).apply()
        ensure(force = true)
    }

    /** Lesezeichen der Hörspiel-Folgen, zuletzt gehörte zuerst. */
    suspend fun refreshBookmarks(): List<Bookmark> {
        val list = runCatching { client.bookmarks() }.getOrNull() ?: return bookmarks
        bookmarks = list.filter { isAudiobook(it.entry.albumId) }.sortedByDescending { it.changed.orEmpty() }
        return bookmarks
    }

    fun bookmarkFor(albumId: String): Bookmark? = bookmarks.firstOrNull { it.entry.albumId == albumId }

    /** Vom Player: Position merken (eine pro Folge – das Lesezeichen des vorigen Kapitels wird ersetzt). */
    suspend fun savePosition(song: Song, positionMs: Long) {
        val old = bookmarks.filter { it.entry.albumId == song.albumId && it.entry.id != song.id }
        old.forEach { runCatching { client.deleteBookmark(it.entry.id) } }
        runCatching { client.createBookmark(song.id, positionMs) }.onSuccess {
            bookmarks = listOf(Bookmark(positionMs, java.time.Instant.now().toString(), song)) +
                bookmarks.filterNot { it.entry.albumId == song.albumId }
        }
    }

    /** Folge zu Ende gehört (oder „von vorn“): Lesezeichen der Folge entfernen. */
    suspend fun clear(albumId: String, songId: String? = null) {
        val ids = (bookmarks.filter { it.entry.albumId == albumId }.map { it.entry.id } + listOfNotNull(songId)).distinct()
        ids.forEach { runCatching { client.deleteBookmark(it) } }
        bookmarks = bookmarks.filterNot { it.entry.albumId == albumId }
    }

    companion object {
        const val AUTO = -1
        const val OFF = -2
        private val NAME = Regex("h(ö|oe)r(spiel|buch|bücher|buecher)|audio ?books?", RegexOption.IGNORE_CASE)

        /** Folgennummer aus dem Albumnamen („Folge 230: …“, „012/und der …“). */
        fun episodeNumber(name: String): Int? = EPISODE.find(name)?.groupValues?.get(1)?.toIntOrNull()

        /** Albumname ohne vorangestellte Folgennummer („Folge 12: und der …“ → „und der …“). */
        fun withoutEpisodeNumber(name: String): String =
            Regex("""^\D{0,20}?(?<!\d)\d{1,3}(?!\d)\s*[:./_\-–]*\s*""").replaceFirst(name, "").ifBlank { name }

        // Ziffern statt \b abgrenzen – in Ordnernamen wird aus „Folge 12:“ „Folge 12_“ (wie im Helfer)
        private val EPISODE = Regex("""^\D{0,20}?(?<!\d)(\d{1,3})(?!\d)""")

        /** Fortschritt einer Folge (0..1) aus Lesezeichen und Albumlänge (Kapitel als gleich lang geschätzt). */
        fun progress(album: Album, b: Bookmark): Float {
            val total = album.duration.takeIf { it > 0 } ?: return 0f
            val per = total.toFloat() / album.songCount.coerceAtLeast(1)
            val before = ((b.entry.track ?: 1) - 1).coerceAtLeast(0) * per
            return ((before + b.position / 1000f) / total).coerceIn(0f, 1f)
        }
    }
}
