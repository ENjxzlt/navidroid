package dev.navidroid.lidarr

import dev.navidroid.data.KeyValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HelperException(message: String) : IOException(message)

data class HelperConfig(val url: String, val token: String, val videos: Boolean)

enum class RequestSource { Ask, Lidarr, YouTube }

class HelperStore(private val prefs: KeyValue) {
    var requestSource: RequestSource
        get() = runCatching { RequestSource.valueOf(prefs.getString("source") ?: "Ask") }.getOrDefault(RequestSource.Ask)
        set(value) = prefs.edit { putString("source", value.name) }

    @Volatile
    var config: HelperConfig? = load()
        private set

    fun save(c: HelperConfig) {
        prefs.edit { putString("url", c.url); putString("token", c.token); putBoolean("videos", c.videos) }
        config = c
    }

    fun clear() {
        val source = prefs.getString("source")
        prefs.edit { clear(); putString("source", source) }
        config = null
    }

    private fun load(): HelperConfig? {
        val url = prefs.getString("url") ?: return null
        val token = prefs.getString("token") ?: return null
        return HelperConfig(url, token, prefs.getBoolean("videos", false))
    }
}

@Serializable
data class HelperHealth(
    val version: String = "",
    val ytDlp: String = "",
    val lidarr: String? = null,
    val lidarrError: String? = null,
    val ai: Boolean = false,
    val manual: Boolean = false,
    val library: Boolean = false,
    val audiobooks: Boolean = false,
)

@Serializable
data class SearchHit(
    val url: String,
    val kind: String = "playlist",
    val title: String = "",
    val uploader: String? = null,
    val count: Int = 0,
    val durationSec: Int = 0,
    val thumbnail: String? = null,
    val matched: Int = 0,
    val total: Int = 0,
    val extra: Int = 0,
)

@Serializable
data class PlaylistInfo(
    val kind: String = "playlist",
    val title: String = "",
    val uploader: String? = null,
    val count: Int = 0,
    val durationSec: Int = 0,
    val thumbnail: String? = null,
)

@Serializable
data class HelperVideo(val id: String, val index: Int = 0, val title: String = "", val seconds: Double? = null)

@Serializable
data class HelperAlbum(val id: Int, val title: String = "", val artist: String = "", val year: Int? = null, val trackCount: Int = 0)

@Serializable
data class MappingRow(
    val trackId: Int,
    val trackNumber: Int = 0,
    val title: String = "",
    val videoId: String? = null,
    val videoTitle: String? = null,
    val videoIndex: Int? = null,
    val score: Double = 0.0,
)

@Serializable
data class HelperPreview(
    val playlist: PlaylistInfo = PlaylistInfo(),
    val videos: List<HelperVideo> = emptyList(),
    val album: HelperAlbum? = null,
    val mapping: List<MappingRow> = emptyList(),
    val unmatchedVideos: List<HelperVideo> = emptyList(),
    val targetFolder: String? = null,
)

@Serializable
data class JobStep(val key: String, val label: String = "", val done: Int = 0, val total: Int = 0, val state: String = "todo")

@Serializable
data class HelperJob(
    val id: String,
    val albumId: Int = 0,
    val title: String = "",
    val artist: String = "",
    val videos: Boolean = false,
    val steps: List<JobStep> = emptyList(),
    val status: String = "queued",
    val error: String? = null,
    val skipped: List<SkippedTrack> = emptyList(),
    val createdAt: Long = 0,
) {
    val active: Boolean get() = status == "queued" || status == "running"
}

@Serializable
data class AiTrack(
    val id: String,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val year: Int? = null,
)

@Serializable
data class AiMissing(val artist: String = "", val album: String = "", val reason: String = "")

@Serializable
data class AiPlaylist(
    val name: String = "",
    val description: String = "",
    val tracks: List<AiTrack> = emptyList(),
    val missing: List<AiMissing> = emptyList(),
)

@Serializable
data class AiDaylist(
    val name: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val tracks: List<AiTrack> = emptyList(),
    val slotKey: String = "",
)

@Serializable
data class Suggestion(
    val key: String,
    val title: String = "",
    val artist: String = "",
    val year: Int? = null,
    val cover: String? = null,
    val reason: String = "",
    val source: String = "",
    val foreignAlbumId: String? = null,
)

@Serializable
data class Suggestions(val forYou: List<Suggestion> = emptyList(), val newReleases: List<Suggestion> = emptyList())

@Serializable
data class YtRelease(
    val key: String,
    val title: String = "",
    val artist: String = "",
    val artistLine: String? = null,
    val cover: String? = null,
    val url: String = "",
    val alsoKeys: List<String> = emptyList(),
)

@Serializable
data class YtChannel(val id: String = "", val name: String = "")

@Serializable
data class YtReleases(val working: Boolean = false, val channel: YtChannel? = null, val items: List<YtRelease> = emptyList())

/** Treffer der Suche nach einem offiziellen Musikvideo; [auto] = sicher genug für automatisches Laden. */
@Serializable
data class VideoCandidate(
    val videoId: String,
    val title: String = "",
    val channel: String = "",
    val seconds: Double? = null,
    val thumbnail: String? = null,
    val score: Int = 0,
    val auto: Boolean = false,
)

/** Titel, zu dem ein Musikvideo nachgeladen werden soll; ohne [videoId] sucht der Helfer selbst. */
data class VideoRequest(val song: dev.navidroid.data.Song, val videoId: String? = null)

@Serializable
private data class VideoStatus(val have: List<Boolean> = emptyList())

/** Hörspiel-Serie, deren neue Folgen der Helfer automatisch von YouTube lädt. */
@Serializable
data class AudiobookSeries(
    val id: String,
    val name: String = "",
    val channel: YtChannel? = null,
    val imported: Int = 0,
    val lastCheck: Long? = null,
    val lastError: String? = null,
)

@Serializable
data class AudiobookSeriesList(val enabled: Boolean = false, val working: Boolean = false, val series: List<AudiobookSeries> = emptyList())

@Serializable
data class CoverHit(val url: String, val source: String = "", val title: String = "", val artist: String = "", val size: Int = 0)

@Serializable
data class HistoryAlbum(val id: String, val name: String? = null, val artist: String? = null, val coverArt: String? = null, val plays: Int = 0)

@Serializable
data class HistorySummary(val counts: Map<String, Int> = emptyMap(), val recentAlbums: List<HistoryAlbum> = emptyList(), val total: Int = 0)

@Serializable
data class SongInfo(val summary: String = "", val facts: List<String> = emptyList(), val known: Boolean = true)

@Serializable
data class LyricsTranslation(val language: String = "", val lines: List<String> = emptyList())

@Serializable
data class DupVariant(val key: String, val album: String = "", val folder: String = "", val tracks: Int = 0, val formats: List<String> = emptyList(), val bytes: Long = 0, val missingTitles: Int = 0)

@Serializable
data class DupGroup(val key: String, val artist: String = "", val title: String = "", val variants: List<DupVariant> = emptyList())

@Serializable
data class LossyAlbum(val albumId: Int, val title: String = "", val artist: String = "", val year: String = "", val quality: String = "", val tracks: Int = 0, val cover: String? = null)

@Serializable
data class QualityOverview(val albums: List<LossyAlbum> = emptyList(), val upgradesAllowed: Boolean = false)

@Serializable
data class LosslessRelease(val guid: String, val indexerId: Int, val title: String = "", val quality: String = "", val size: Long = 0, val seeders: Int? = null, val indexer: String = "", val rejections: List<String> = emptyList())

@Serializable
data class AlbumFilesInfo(val tracks: Int = 0, val bytes: Long = 0, val matched: Int = 0)

@Serializable
data class LibraryEditResult(val changed: Int = 0, val errors: List<String> = emptyList())

@Serializable
data class LibraryDeleteResult(val id: String = "", val tracks: Int = 0, val bytes: Long = 0, val lidarr: Boolean = false)

@Serializable
data class TrashEntry(
    val id: String,
    val artist: String = "",
    val album: String = "",
    val deletedAt: Double = 0.0,
    val bytes: Long = 0,
    val tracks: Int = 0,
    val daysLeft: Int = 0,
)

@Serializable
data class UploadResult(val status: String = "", val bytes: Long = 0)

/** Bezug auf ein Album im Musikordner: Künstler, Name und die Titel aus Navidrome (für die Zuordnung). */
data class AlbumRef(val artist: String, val album: String, val songs: List<dev.navidroid.data.Song>)

/** Neue Werte; null = unverändert. Titeländerungen beziehen sich auf den Index in [AlbumRef.songs]. */
data class AlbumChanges(
    val album: String? = null,
    val albumArtist: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val tracks: List<Triple<Int, String?, Int?>> = emptyList(),
)

@Serializable
data class ManualTrackPreview(
    val videoId: String,
    val index: Int = 0,
    val title: String = "",
    val source: String = "",
    val seconds: Double? = null,
)

@Serializable
data class ManualPreview(val playlist: PlaylistInfo = PlaylistInfo(), val tracks: List<ManualTrackPreview> = emptyList())

data class ManualTrack(val videoId: String, val title: String, val number: Int)

@Serializable
data class MaPlayer(
    val id: String,
    val name: String = "",
    val provider: String? = null,
    val type: String? = null,
    val available: Boolean = true,
    val state: String? = null,
    val volume: Double? = null,
    val title: String? = null,
    val artist: String? = null,
    val duration: Double? = null,
    val elapsed: Double? = null,
    val queueIndex: Int? = null,
)

@Serializable
data class SkippedTrack(val title: String = "", val reason: String = "")

@Serializable
private data class VideoMatch(val id: String, val url: String)

/** Spricht mit dem NaviDroid-Helfer (Docker-Dienst mit yt-dlp und ffmpeg) auf dem Server. */
class HelperClient(http: OkHttpClient, private val store: HelperStore) {
    // Das Auslesen großer Playlists kann dauern.
    private val http = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    // Claude braucht für eine Playlist gern eine Minute oder mehr.
    private val slowHttp = http.newBuilder().readTimeout(300, TimeUnit.SECONDS).build()
    // Große Dateien über langsame Verbindungen
    private val uploadHttp = http.newBuilder().writeTimeout(10, TimeUnit.MINUTES).readTimeout(5, TimeUnit.MINUTES).build()

    // coerceInputValues: null in Feldern mit Standardwert (z. B. Länge) nicht als Fehler behandeln
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val videoCache = HashMap<String, String>()
    private val videoMissAt = HashMap<String, Long>()

    val configured: Boolean get() = store.config != null

    var requestSource: RequestSource
        get() = store.requestSource
        set(value) {
            store.requestSource = value
        }
    val videosEnabled: Boolean get() = store.config?.videos == true

    private fun config() = store.config ?: throw HelperException("YouTube-Helfer ist nicht eingerichtet")

    /** Header für die Video-Wiedergabe mit ExoPlayer. */
    fun authHeaders(): Map<String, String> = store.config?.let { mapOf("X-Helper-Token" to it.token) }.orEmpty()

    private suspend fun send(
        cfg: HelperConfig,
        method: String,
        path: String,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
        client: OkHttpClient = http,
        raw: okhttp3.RequestBody? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val base = cfg.url.toHttpUrlOrNull() ?: throw HelperException("Ungültige Helfer-URL")
            val url = base.newBuilder()
                .apply {
                    path.trim('/').split('/').forEach { addPathSegment(it) }
                    query.forEach { (k, v) -> addQueryParameter(k, v) }
                }
                .build()
            val request = Request.Builder()
                .url(url)
                .header("X-Helper-Token", cfg.token)
                .method(method, raw ?: body?.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.code == 401) throw HelperException("Token wurde vom Helfer abgelehnt")
                    // Unbekannter Pfad bzw. Methode: Der Helfer ist älter als die App
                    if (resp.code == 405 || (resp.code == 404 && detail(text) == "Not Found")) {
                        throw HelperException("Der Helfer ist zu alt für diese Funktion – bitte das Helfer-Image aktualisieren (docker compose pull && docker compose up -d)")
                    }
                    if (!resp.isSuccessful) throw HelperException(detail(text) ?: "Helfer-Fehler (HTTP ${resp.code})")
                    text
                }
            } catch (e: HelperException) {
                throw e
            } catch (e: IOException) {
                throw HelperException("Helfer nicht erreichbar: ${e.message}")
            }
        }

    private fun detail(text: String): String? = runCatching {
        ((json.parseToJsonElement(text) as JsonObject)["detail"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString(text)
    } catch (e: SerializationException) {
        throw HelperException("Unerwartete Antwort vom Helfer (${e.message?.take(120)})")
    }

    suspend fun health(url: String, token: String): HelperHealth =
        decode(send(HelperConfig(LidarrStore.normalizeUrl(url), token.trim(), false), "GET", "api/health"))

    suspend fun preview(playlistUrl: String, albumId: Int?): HelperPreview {
        val body = buildJsonObject {
            put("url", playlistUrl.trim())
            if (albumId != null) put("albumId", albumId)
        }
        return decode(send(config(), "POST", "api/preview", body.toString()))
    }

    suspend fun startJob(playlistUrl: String, albumId: Int, videos: Boolean, mapping: List<MappingRow>): HelperJob {
        val body = buildJsonObject {
            put("url", playlistUrl.trim())
            put("albumId", albumId)
            put("videos", videos)
            put("mapping", buildJsonArray {
                mapping.filter { it.videoId != null }.forEach { m ->
                    add(buildJsonObject {
                        put("trackId", m.trackId)
                        put("videoId", m.videoId)
                    })
                }
            })
        }
        return decode(send(config(), "POST", "api/jobs", body.toString()))
    }

    suspend fun search(query: String, albumId: Int?): List<SearchHit> {
        val body = buildJsonObject {
            put("query", query)
            if (albumId != null) put("albumId", albumId)
        }
        return decode(send(config(), "POST", "api/search", body.toString()))
    }

    suspend fun jobs(): List<HelperJob> = decode(send(config(), "GET", "api/jobs"))

    // ---- Music Assistant (über den Helfer im Heimnetz) ----

    private fun maAuth(url: String, token: String) = buildJsonObject {
        put("url", url)
        put("token", token)
    }

    suspend fun maPlayers(url: String, token: String): List<MaPlayer> =
        decode(send(config(), "POST", "api/ma/players", maAuth(url, token).toString()))

    suspend fun maPlay(url: String, token: String, playerId: String, navidrome: dev.navidroid.data.Credentials, songIds: List<String>) {
        val body = buildJsonObject {
            put("ma", maAuth(url, token))
            put("playerId", playerId)
            put("navidrome", buildJsonObject {
                put("url", navidrome.serverUrl)
                put("user", navidrome.username)
                put("token", navidrome.token)
                put("salt", navidrome.salt)
            })
            put("songIds", buildJsonArray { songIds.forEach { add(JsonPrimitive(it)) } })
        }
        send(config(), "POST", "api/ma/play", body.toString())
    }

    suspend fun maCommand(url: String, token: String, playerId: String, command: String, value: Double?) {
        val body = buildJsonObject {
            put("ma", maAuth(url, token))
            put("playerId", playerId)
            put("command", command)
            if (value != null) put("value", value)
        }
        send(config(), "POST", "api/ma/command", body.toString())
    }

    suspend fun maState(url: String, token: String, playerId: String): MaPlayer {
        val body = buildJsonObject {
            put("ma", maAuth(url, token))
            put("playerId", playerId)
        }
        return decode(send(config(), "POST", "api/ma/state", body.toString()))
    }

    /** Künstlername (klein) → Genres aus Lidarr; leer, wenn der Helfer fehlt. */
    suspend fun artistGenres(): Map<String, List<String>> =
        if (!configured) emptyMap() else runCatching { decode<Map<String, List<String>>>(send(config(), "GET", "api/artist-genres")) }.getOrDefault(emptyMap())

    suspend fun manualPreview(url: String, artist: String): ManualPreview {
        val body = buildJsonObject {
            put("url", url.trim())
            put("artist", artist.trim())
        }
        return decode(send(config(), "POST", "api/manual/preview", body.toString()))
    }

    suspend fun startManualJob(url: String, artist: String, album: String, year: Int?, thumbnail: String?, tracks: List<ManualTrack>): HelperJob {
        val body = buildJsonObject {
            put("url", url.trim())
            put("artist", artist.trim())
            put("album", album.trim())
            if (year != null) put("year", year)
            if (thumbnail != null) put("thumbnail", thumbnail)
            put("tracks", buildJsonArray {
                tracks.forEach { t ->
                    add(buildJsonObject {
                        put("videoId", t.videoId)
                        put("title", t.title)
                        put("number", t.number)
                    })
                }
            })
        }
        return decode(send(config(), "POST", "api/manual/jobs", body.toString()))
    }

    /** KI-Playlist über den Claude-Worker; der Helfer liest dafür die Bibliothek mit den Navidrome-Zugangsdaten. */
    suspend fun aiPlaylist(
        navidrome: dev.navidroid.data.Credentials,
        wish: String,
        count: Int,
        preferRare: Boolean,
        suggestMissing: Boolean,
        previous: List<String>? = null,
        refine: String? = null,
    ): AiPlaylist {
        val body = buildJsonObject {
            put("navidrome", buildJsonObject {
                put("url", navidrome.serverUrl)
                put("user", navidrome.username)
                put("token", navidrome.token)
                put("salt", navidrome.salt)
            })
            put("prompt", wish)
            put("count", count)
            put("preferRare", preferRare)
            put("suggestMissing", suggestMissing)
            if (previous != null) put("previous", buildJsonArray { previous.forEach { add(JsonPrimitive(it)) } })
            if (refine != null) put("refine", refine)
        }
        return decode(send(config(), "POST", "api/ai/playlist", body.toString(), client = slowHttp))
    }

    /** Daylist für ein Zeitfenster; der Helfer erstellt sie einmal pro Fenster über den Claude-Worker. */
    suspend fun aiDaylist(navidrome: dev.navidroid.data.Credentials, slotKey: String, moment: String, count: Int = 40): AiDaylist {
        val body = buildJsonObject {
            put("navidrome", buildJsonObject {
                put("url", navidrome.serverUrl)
                put("user", navidrome.username)
                put("token", navidrome.token)
                put("salt", navidrome.salt)
            })
            put("slotKey", slotKey)
            put("moment", moment)
            put("count", count)
        }
        return decode(send(config(), "POST", "api/ai/daylist", body.toString(), client = slowHttp))
    }

    // ---- Hörverlauf (Navidrome zählt hier keine Wiedergaben) ----

    suspend fun recordPlay(user: String, song: dev.navidroid.data.Song, ts: Long) {
        val body = buildJsonObject {
            put("user", user)
            put("ts", ts)
            put("song", buildJsonObject {
                put("id", song.id)
                put("title", song.title)
                song.artist?.let { put("artist", it) }
                song.album?.let { put("album", it) }
                song.albumId?.let { put("albumId", it) }
                song.coverArt?.let { put("coverArt", it) }
                song.genre?.let { put("genre", it) }
                put("duration", song.duration)
            })
        }
        send(config(), "POST", "api/history/play", body.toString())
    }

    suspend fun historySummary(user: String): HistorySummary =
        decode(send(config(), "GET", "api/history/summary", query = mapOf("user" to user)))

    // ---- Claude: Über diesen Song, Übersetzung ----

    suspend fun songInfo(artist: String, title: String, album: String?): SongInfo {
        val body = buildJsonObject { put("artist", artist); put("title", title); album?.let { put("album", it) } }
        return decode(send(config(), "POST", "api/ai/song-info", body.toString(), client = slowHttp))
    }

    suspend fun translateLyrics(songId: String, artist: String, title: String, lines: List<String>): LyricsTranslation {
        val body = buildJsonObject {
            put("songId", songId); put("artist", artist); put("title", title)
            put("lines", buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } })
        }
        return decode(send(config(), "POST", "api/ai/translate-lyrics", body.toString(), client = slowHttp))
    }

    // ---- Doppelte Alben, Qualität ----

    suspend fun duplicates(): List<DupGroup> = decode(send(config(), "GET", "api/library/duplicates", client = slowHttp))

    suspend fun resolveDuplicates(keep: String, remove: List<String>, merge: Boolean) {
        val body = buildJsonObject {
            put("keep", keep)
            put("merge", merge)
            put("remove", buildJsonArray { remove.forEach { add(JsonPrimitive(it)) } })
        }
        send(config(), "POST", "api/library/duplicates/resolve", body.toString(), client = slowHttp)
    }

    suspend fun qualityOverview(): QualityOverview = decode(send(config(), "GET", "api/library/quality", client = slowHttp))

    suspend fun losslessReleases(albumId: Int): List<LosslessRelease> =
        decode(send(config(), "GET", "api/library/quality/$albumId/releases", client = slowHttp))

    suspend fun grabRelease(guid: String, indexerId: Int) {
        send(config(), "POST", "api/library/quality/grab", buildJsonObject { put("guid", guid); put("indexerId", indexerId) }.toString())
    }

    // ---- Einstellungen zwischen Geräten abgleichen ----

    /** Gespeicherte Einstellungen des Benutzers und ihr Zeitstempel (ms); 0 = noch nie gespeichert. */
    suspend fun getSettings(user: String): Pair<JsonObject, Long> {
        val o = json.parseToJsonElement(send(config(), "GET", "api/settings", query = mapOf("user" to user))) as JsonObject
        val data = o["data"] as? JsonObject ?: JsonObject(emptyMap())
        val ts = (o["updatedAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        return data to ts
    }

    suspend fun putSettings(user: String, data: JsonObject, updatedAt: Long) {
        val body = buildJsonObject { put("user", user); put("data", data); put("updatedAt", updatedAt) }
        send(config(), "PUT", "api/settings", body.toString())
    }

    // ---- Vorschläge ----

    suspend fun suggestions(): Suggestions = decode(send(config(), "GET", "api/suggestions", client = slowHttp))

    suspend fun dismissSuggestion(key: String) {
        send(config(), "POST", "api/suggestions/dismiss", buildJsonObject { put("key", key) }.toString())
    }

    // ---- YouTube-Veröffentlichungen ----

    suspend fun youtubeReleases(artist: String? = null): YtReleases =
        decode(send(config(), "GET", "api/youtube/releases", query = artist?.let { mapOf("artist" to it) }.orEmpty(), client = slowHttp))

    suspend fun dismissYoutubeRelease(key: String) {
        send(config(), "POST", "api/youtube/releases/dismiss", buildJsonObject { put("key", key) }.toString())
    }

    suspend fun importYoutubeRelease(key: String): HelperJob =
        decode(send(config(), "POST", "api/youtube/releases/import", buildJsonObject { put("key", key) }.toString(), client = slowHttp))

    suspend fun setYoutubeChannel(artist: String, channel: String): YtReleases =
        decode(send(config(), "POST", "api/youtube/channel", buildJsonObject { put("artist", artist); put("channel", channel) }.toString(), client = slowHttp))

    // ---- Musikvideos nachladen ----

    private fun videoSongJson(song: dev.navidroid.data.Song, videoId: String? = null) = buildJsonObject {
        put("artist", song.artist.orEmpty())
        put("title", song.title)
        put("album", song.album.orEmpty())
        put("track", song.track ?: 0)
        if (song.duration > 0) put("seconds", song.duration)
        if (videoId != null) put("videoId", videoId)
    }

    /** Welche der Titel schon ein Video haben (gleiche Reihenfolge). */
    suspend fun videoStatus(songs: List<dev.navidroid.data.Song>): List<Boolean> {
        if (songs.isEmpty()) return emptyList()
        val body = buildJsonObject { put("items", kotlinx.serialization.json.JsonArray(songs.map { videoSongJson(it) })) }
        return decode<VideoStatus>(send(config(), "POST", "api/videos/status", body.toString())).have
    }

    suspend fun videoCandidates(song: dev.navidroid.data.Song): List<VideoCandidate> =
        decode(send(config(), "POST", "api/videos/candidates", videoSongJson(song).toString(), client = slowHttp))

    /** Musikvideos nachladen; erscheint als Auftrag unter Anfragen → Downloads. */
    suspend fun fetchVideos(title: String, requests: List<VideoRequest>): HelperJob {
        val body = buildJsonObject {
            put("title", title)
            put("items", kotlinx.serialization.json.JsonArray(requests.map { videoSongJson(it.song, it.videoId) }))
        }
        val job = decode<HelperJob>(send(config(), "POST", "api/videos/fetch", body.toString()))
        // Gemerkte „kein Video“-Antworten verwerfen, damit der Player neue Videos findet
        synchronized(videoCache) { videoMissAt.clear() }
        return job
    }

    // ---- Hörspiel-Serien ----

    suspend fun audiobookSeries(): AudiobookSeriesList = decode(send(config(), "GET", "api/audiobooks"))

    suspend fun addAudiobookSeries(name: String, channel: String, importExisting: Boolean): AudiobookSeries =
        decode(send(config(), "POST", "api/audiobooks/series", buildJsonObject {
            put("name", name); put("channel", channel); put("importExisting", importExisting)
        }.toString(), client = slowHttp))

    suspend fun removeAudiobookSeries(id: String) {
        send(config(), "DELETE", "api/audiobooks/series/$id")
    }

    suspend fun checkAudiobookSeries(): AudiobookSeriesList = decode(send(config(), "POST", "api/audiobooks/check", "{}"))

    // ---- Bibliothek bearbeiten ----

    private fun refJson(ref: AlbumRef) = buildJsonObject {
        put("artist", ref.artist)
        put("album", ref.album)
        put("songs", buildJsonArray {
            ref.songs.forEach { s ->
                add(buildJsonObject {
                    put("title", s.title)
                    put("track", s.track ?: 0)
                    put("disc", s.discNumber ?: 1)
                })
            }
        })
    }

    suspend fun albumFiles(ref: AlbumRef): AlbumFilesInfo =
        decode(send(config(), "POST", "api/library/album/files", refJson(ref).toString()))

    suspend fun editAlbum(ref: AlbumRef, c: AlbumChanges): LibraryEditResult {
        val body = buildJsonObject {
            put("ref", refJson(ref))
            c.album?.let { put("album", it) }
            c.albumArtist?.let { put("albumArtist", it) }
            c.year?.let { put("year", it) }
            c.genre?.let { put("genre", it) }
            put("tracks", buildJsonArray {
                c.tracks.forEach { (i, title, number) ->
                    add(buildJsonObject {
                        put("index", i)
                        title?.let { put("title", it) }
                        number?.let { put("track", it) }
                    })
                }
            })
        }
        return decode(send(config(), "POST", "api/library/album/edit", body.toString(), client = slowHttp))
    }

    suspend fun setAlbumCover(artist: String, album: String, image: ByteArray, mime: String) {
        send(
            config(), "POST", "api/library/album/cover",
            query = mapOf("artist" to artist, "album" to album),
            client = slowHttp,
            raw = image.toRequestBody(mime.toMediaType()),
        )
    }

    suspend fun searchCovers(artist: String, album: String): List<CoverHit> =
        decode(send(config(), "GET", "api/covers/search", query = mapOf("artist" to artist, "album" to album), client = slowHttp))

    suspend fun setAlbumCoverUrl(artist: String, album: String, url: String) {
        val body = buildJsonObject { put("artist", artist); put("album", album); put("url", url) }
        send(config(), "POST", "api/library/album/cover-url", body.toString(), client = slowHttp)
    }

    suspend fun deleteAlbum(ref: AlbumRef, unmonitor: Boolean): LibraryDeleteResult {
        val body = buildJsonObject {
            put("ref", refJson(ref))
            put("unmonitor", unmonitor)
        }
        return decode(send(config(), "POST", "api/library/album/delete", body.toString(), client = slowHttp))
    }

    suspend fun trash(): List<TrashEntry> = decode(send(config(), "GET", "api/library/trash"))

    suspend fun restoreTrash(id: String) {
        send(config(), "POST", "api/library/trash/$id/restore", "{}", client = slowHttp)
    }

    /** Lädt eine Datei hoch; [body] meldet den Fortschritt selbst. */
    suspend fun upload(query: Map<String, String>, body: okhttp3.RequestBody): UploadResult =
        decode(send(config(), "POST", "api/library/upload", query = query, client = uploadHttp, raw = body))

    suspend fun uploadCover(artist: String, album: String, year: Int?, image: ByteArray, mime: String) {
        send(
            config(), "POST", "api/library/upload/cover",
            query = buildMap { put("artist", artist); put("album", album); year?.let { put("year", "$it") } },
            client = slowHttp,
            raw = image.toRequestBody(mime.toMediaType()),
        )
    }

    suspend fun retryJob(id: String): HelperJob = decode(send(config(), "POST", "api/jobs/$id/retry", "{}"))

    suspend fun deleteJob(id: String) {
        send(config(), "DELETE", "api/jobs/$id")
    }

    /** Liefert die absolute Video-URL zu einem Titel oder null, wenn der Helfer keines hat. */
    suspend fun findVideo(artist: String, title: String): String? {
        val cfg = store.config ?: return null
        if (!cfg.videos) return null
        val key = "$artist|$title"
        synchronized(videoCache) {
            videoCache[key]?.let { return it }
            // „Kein Video“ nur kurz merken – es kann inzwischen nachgeladen worden sein
            videoMissAt[key]?.let { if (System.currentTimeMillis() - it < 5 * 60_000L) return null }
        }
        val result = runCatching {
            val match = decode<VideoMatch>(send(cfg, "GET", "api/videos/find", query = mapOf("artist" to artist, "title" to title)))
            cfg.url.trimEnd('/') + match.url
        }.getOrNull()
        synchronized(videoCache) {
            if (result != null) videoCache[key] = result else videoMissAt[key] = System.currentTimeMillis()
        }
        return result
    }
}
