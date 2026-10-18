package dev.navidroid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class SubsonicException(message: String) : IOException(message)

/** Minimaler Client für die Subsonic-API, die Navidrome unter /rest bereitstellt. */
class SubsonicClient(
    private val http: OkHttpClient,
    private val session: SessionStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun credentials(): Credentials = session.current ?: throw SubsonicException("Nicht angemeldet")

    private fun buildUrl(c: Credentials, endpoint: String, params: List<Pair<String, String>>): HttpUrl {
        val base = c.serverUrl.toHttpUrlOrNull() ?: throw SubsonicException("Ungültige Server-URL")
        return base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(endpoint)
            .addQueryParameter("u", c.username)
            .addQueryParameter("t", c.token)
            .addQueryParameter("s", c.salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
    }

    private suspend fun call(
        endpoint: String,
        vararg params: Pair<String, String?>,
        creds: Credentials? = null,
    ): SubsonicResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(creds ?: credentials(), endpoint, params.mapNotNull { (k, v) -> v?.let { k to it } })
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.code == 404) throw SubsonicException("Keine Subsonic-API unter dieser URL gefunden (HTTP 404)")
                if (!resp.isSuccessful) throw SubsonicException("HTTP-Fehler ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val r = try {
                    json.decodeFromString<Envelope>(body).response
                } catch (e: SerializationException) {
                    throw SubsonicException("Unerwartete Antwort – ist das ein Navidrome-Server?")
                } catch (e: IllegalArgumentException) {
                    throw SubsonicException("Unerwartete Antwort – ist das ein Navidrome-Server?")
                }
                if (r.status != "ok") throw SubsonicException(r.error?.message ?: "Serverfehler")
                r
            }
        } catch (e: SubsonicException) {
            throw e
        } catch (e: UnknownHostException) {
            throw SubsonicException("Server nicht gefunden")
        } catch (e: SocketTimeoutException) {
            throw SubsonicException("Zeitüberschreitung bei der Verbindung")
        } catch (e: ConnectException) {
            throw SubsonicException("Keine Verbindung zum Server möglich")
        } catch (e: SSLException) {
            throw SubsonicException("SSL-Fehler: ${e.message}")
        }
    }

    suspend fun ping(creds: Credentials) {
        call("ping", creds = creds)
    }

    suspend fun albumList(type: String, size: Int, offset: Int): List<Album> =
        call("getAlbumList2", "type" to type, "size" to "$size", "offset" to "$offset")
            .albumList2?.album.orEmpty()

    suspend fun artists(): List<ArtistIndex> = call("getArtists").artists?.index.orEmpty()

    suspend fun artist(id: String): Artist =
        call("getArtist", "id" to id).artist ?: throw SubsonicException("Künstler nicht gefunden")

    suspend fun album(id: String): Album =
        call("getAlbum", "id" to id).album ?: throw SubsonicException("Album nicht gefunden")

    suspend fun playlists(): List<Playlist> = call("getPlaylists").playlists?.playlist.orEmpty()

    suspend fun playlist(id: String): Playlist =
        call("getPlaylist", "id" to id).playlist ?: throw SubsonicException("Playlist nicht gefunden")

    suspend fun search(query: String): SearchResult =
        call(
            "search3",
            "query" to query,
            "artistCount" to "10",
            "albumCount" to "20",
            "songCount" to "50",
        ).searchResult3 ?: SearchResult()

    /** Neue Playlist; mit [playlistId] werden stattdessen deren Titel komplett ersetzt (Reihenfolge!). */
    suspend fun createPlaylist(name: String?, songIds: List<String>, playlistId: String? = null): Playlist? {
        val params = buildList<Pair<String, String?>> {
            add("name" to name)
            add("playlistId" to playlistId)
            songIds.forEach { add("songId" to it) }
        }
        return call("createPlaylist", *params.toTypedArray()).playlist
    }

    suspend fun updatePlaylist(id: String, name: String? = null, add: List<String> = emptyList(), removeIndexes: List<Int> = emptyList()) {
        val params = buildList<Pair<String, String?>> {
            add("playlistId" to id)
            add("name" to name)
            add.forEach { add("songIdToAdd" to it) }
            removeIndexes.forEach { add("songIndexToRemove" to it.toString()) }
        }
        call("updatePlaylist", *params.toTypedArray())
    }

    suspend fun deletePlaylist(id: String) {
        call("deletePlaylist", "id" to id)
    }

    /** Alle Titel der Bibliothek (search3 mit leerer Suche, seitenweise). */
    suspend fun allSongs(limit: Int = 20000): List<Song> {
        val songs = mutableListOf<Song>()
        var offset = 0
        while (songs.size < limit) {
            val page = call("search3", "query" to "", "artistCount" to "0", "albumCount" to "0", "songCount" to "500", "songOffset" to "$offset")
                .searchResult3?.song.orEmpty()
            songs += page
            if (page.size < 500) break
            offset += 500
        }
        return songs
    }

    /** Ähnliche Titel (Navidrome: über Agenten/Plugin, z. B. Deezer-Radio); nur Titel aus der Bibliothek. */
    suspend fun similarSongs(songId: String, count: Int = 50): List<Song> =
        call("getSimilarSongs", "id" to songId, "count" to "$count").similarSongs?.song.orEmpty()

    suspend fun similarSongsByArtist(artistId: String, count: Int = 50): List<Song> =
        call("getSimilarSongs2", "id" to artistId, "count" to "$count").similarSongs2?.song.orEmpty()

    suspend fun randomSongs(size: Int = 50): List<Song> =
        call("getRandomSongs", "size" to "$size").randomSongs?.song.orEmpty()

    suspend fun scrobble(id: String, submission: Boolean, timeMs: Long? = null) {
        call("scrobble", "id" to id, "submission" to "$submission", "time" to timeMs?.toString())
    }

    /** Navidrome neu einlesen lassen (nach Änderungen im Musikordner); braucht Admin-Rechte. */
    suspend fun startScan() {
        call("startScan")
    }

    suspend fun scanning(): Boolean = call("getScanStatus").scanStatus?.scanning == true

    suspend fun setStarred(id: String, starred: Boolean, isAlbum: Boolean = false) {
        call(if (starred) "star" else "unstar", (if (isAlbum) "albumId" else "id") to id)
    }

    /** OpenSubsonic-Erweiterung (Navidrome ≥ 0.53); ältere Server antworten mit einem Fehler. */
    suspend fun lyrics(songId: String): List<StructuredLyrics> =
        call("getLyricsBySongId", "id" to songId).lyricsList?.structuredLyrics.orEmpty()

    /** Stream-URL; mit [maxBitRate] (kbit/s) rechnet Navidrome auf MP3 herunter, 0 = Originaldatei. */
    fun streamUrl(id: String, maxBitRate: Int = 0): String {
        val params = buildList {
            add("id" to id)
            if (maxBitRate > 0) {
                add("maxBitRate" to "$maxBitRate")
                add("format" to "mp3")
            }
        }
        return buildUrl(credentials(), "stream", params).toString()
    }

    fun coverArtUrl(id: String?, size: Int): String? {
        val c = session.current ?: return null
        if (id.isNullOrEmpty()) return null
        return buildUrl(c, "getCoverArt", listOf("id" to id, "size" to "$size")).toString()
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "NaviDroid"
    }
}
