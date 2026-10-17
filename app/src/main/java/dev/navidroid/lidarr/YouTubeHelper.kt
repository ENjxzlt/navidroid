package dev.navidroid.lidarr

import android.content.Context
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

class HelperStore(context: Context) {
    private val prefs = context.getSharedPreferences("youtube_helper", Context.MODE_PRIVATE)

    @Volatile
    var config: HelperConfig? = load()
        private set

    fun save(c: HelperConfig) {
        prefs.edit().putString("url", c.url).putString("token", c.token).putBoolean("videos", c.videos).apply()
        config = c
    }

    fun clear() {
        prefs.edit().clear().apply()
        config = null
    }

    private fun load(): HelperConfig? {
        val url = prefs.getString("url", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        return HelperConfig(url, token, prefs.getBoolean("videos", false))
    }
}

@Serializable
data class HelperHealth(val version: String = "", val ytDlp: String = "", val lidarr: String? = null, val lidarrError: String? = null)

@Serializable
data class PlaylistInfo(
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
    val createdAt: Long = 0,
) {
    val active: Boolean get() = status == "queued" || status == "running"
}

@Serializable
private data class VideoMatch(val id: String, val url: String)

/** Spricht mit dem NaviDroid-Helfer (Docker-Dienst mit yt-dlp und ffmpeg) auf dem Server. */
class HelperClient(http: OkHttpClient, private val store: HelperStore) {
    // Das Auslesen großer Playlists kann dauern.
    private val http = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val videoCache = HashMap<String, String?>()

    val configured: Boolean get() = store.config != null
    val videosEnabled: Boolean get() = store.config?.videos == true

    private fun config() = store.config ?: throw HelperException("YouTube-Helfer ist nicht eingerichtet")

    /** Header für die Video-Wiedergabe mit ExoPlayer. */
    fun authHeaders(): Map<String, String> = store.config?.let { mapOf("X-Helper-Token" to it.token) }.orEmpty()

    private suspend fun send(cfg: HelperConfig, method: String, path: String, body: String? = null, query: Map<String, String> = emptyMap()): String =
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
                .method(method, body?.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                http.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.code == 401) throw HelperException("Token wurde vom Helfer abgelehnt")
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
        throw HelperException("Unerwartete Antwort – ist das der NaviDroid-Helfer?")
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

    suspend fun jobs(): List<HelperJob> = decode(send(config(), "GET", "api/jobs"))

    /** Liefert die absolute Video-URL zu einem Titel oder null, wenn der Helfer keines hat. */
    suspend fun findVideo(artist: String, title: String): String? {
        val cfg = store.config ?: return null
        if (!cfg.videos) return null
        val key = "$artist|$title"
        synchronized(videoCache) { if (videoCache.containsKey(key)) return videoCache[key] }
        val result = runCatching {
            val match = decode<VideoMatch>(send(cfg, "GET", "api/videos/find", query = mapOf("artist" to artist, "title" to title)))
            cfg.url.trimEnd('/') + match.url
        }.getOrNull()
        synchronized(videoCache) { videoCache[key] = result }
        return result
    }
}
