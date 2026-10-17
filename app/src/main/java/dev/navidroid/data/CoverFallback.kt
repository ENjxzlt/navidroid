package dev.navidroid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Ersatz-Cover aus dem Cover Art Archive (MusicBrainz), wenn Navidrome für ein Album kein Bild hat.
 * 1. Navidrome kennt die MusicBrainz-Release-ID (OpenSubsonic `musicBrainzId`) → Cover des Releases.
 * 2. Sonst: Album über Name und Künstler bei MusicBrainz suchen → Cover der Release-Gruppe.
 */
class CoverFallback(private val client: SubsonicClient, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val albums = HashMap<String, AlbumKey?>()
    private val searches = HashMap<String, String?>()
    private val rateLimit = Mutex()
    private var lastRequest = 0L

    private data class AlbumKey(val mbid: String?, val name: String, val artist: String?)

    fun forMbid(mbid: String?, size: Int = 500): String? =
        mbid?.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release/$it/front-${caaSize(size)}" }

    /** Für Alben mit bekannten Daten (Kacheln, Album-Ansicht). */
    suspend fun forAlbumInfo(mbid: String?, name: String, artist: String?, size: Int = 500): String? =
        forMbid(mbid, size) ?: searchReleaseGroup(name, artist)?.let { "https://coverartarchive.org/release-group/$it/front-${caaSize(size)}" }

    /** Für Titel: Album einmal bei Navidrome nachschlagen, danach aus dem Speicher. */
    suspend fun forAlbum(albumId: String?, size: Int = 500): String? {
        if (albumId.isNullOrBlank()) return null
        val known = synchronized(albums) { albums.containsKey(albumId) to albums[albumId] }
        val key = if (known.first) {
            known.second
        } else {
            runCatching { client.album(albumId).let { AlbumKey(it.musicBrainzId, it.name, it.artist) } }.getOrNull()
                .also { synchronized(albums) { albums[albumId] = it } }
        } ?: return null
        return forAlbumInfo(key.mbid, key.name, key.artist, size)
    }

    private suspend fun searchReleaseGroup(name: String, artist: String?): String? {
        if (name.isBlank()) return null
        val cacheKey = "${artist.orEmpty().lowercase()}|${name.lowercase()}"
        synchronized(searches) { if (searches.containsKey(cacheKey)) return searches[cacheKey] }
        val clean = { s: String -> s.replace("\"", " ").trim() }
        val query = buildString {
            append("releasegroup:\"").append(clean(name)).append('"')
            if (!artist.isNullOrBlank()) append(" AND artist:\"").append(clean(artist)).append('"')
        }
        val url = "https://musicbrainz.org/ws/2/release-group".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", "1")
            .build()
        val id = runCatching {
            // MusicBrainz erlaubt etwa eine Anfrage pro Sekunde.
            rateLimit.withLock {
                val wait = 1_100 - (System.currentTimeMillis() - lastRequest)
                if (wait > 0) delay(wait)
                lastRequest = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "NaviDroid (https://github.com/ENjxzlt/navidroid)")
                        .header("Accept", "application/json")
                        .build()
                    http.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        val root = json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonObject
                        val first = (root?.get("release-groups") as? JsonArray)?.firstOrNull() as? JsonObject
                        val score = (first?.get("score") as? JsonPrimitive)?.intOrNull ?: 0
                        if (score >= 90) (first?.get("id") as? JsonPrimitive)?.contentOrNull else null
                    }
                }
            }
        }.getOrNull()
        synchronized(searches) { searches[cacheKey] = id }
        return id
    }

    private fun caaSize(size: Int) = when {
        size <= 250 -> 250
        size <= 500 -> 500
        else -> 1200
    }
}
