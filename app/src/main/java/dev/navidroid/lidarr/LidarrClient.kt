package dev.navidroid.lidarr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class LidarrException(message: String) : IOException(message)

data class LidarrConfig(
    val url: String,
    val apiKey: String,
    val qualityProfileId: Int,
    val metadataProfileId: Int,
    val rootFolderPath: String,
)

class LidarrStore(context: Context) {
    private val prefs = context.getSharedPreferences("lidarr", Context.MODE_PRIVATE)

    @Volatile
    var config: LidarrConfig? = load()
        private set

    fun save(c: LidarrConfig) {
        prefs.edit()
            .putString("url", c.url)
            .putString("apiKey", c.apiKey)
            .putInt("quality", c.qualityProfileId)
            .putInt("metadata", c.metadataProfileId)
            .putString("root", c.rootFolderPath)
            .apply()
        config = c
    }

    fun clear() {
        prefs.edit().clear().apply()
        config = null
    }

    private fun load(): LidarrConfig? {
        val url = prefs.getString("url", null) ?: return null
        val key = prefs.getString("apiKey", null) ?: return null
        val root = prefs.getString("root", null) ?: return null
        return LidarrConfig(url, key, prefs.getInt("quality", 1), prefs.getInt("metadata", 1), root)
    }

    companion object {
        /** Lidarr läuft meist ohne TLS im LAN (Port 8686), daher http als Standard. */
        fun normalizeUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            return if ("://" in trimmed) trimmed else "http://$trimmed"
        }
    }
}

data class Option<T>(val value: T, val label: String)

/** Ergebnis einer Künstlersuche. [raw] wird beim Hinzufügen unverändert an Lidarr zurückgeschickt. */
data class LidarrArtist(val raw: JsonObject) {
    val id: Int get() = raw.int("id") ?: 0
    val inLibrary: Boolean get() = id > 0
    val name: String get() = raw.str("artistName").orEmpty()
    val foreignId: String get() = raw.str("foreignArtistId").orEmpty()
    val disambiguation: String? get() = raw.str("disambiguation")?.takeIf { it.isNotBlank() }
    val type: String? get() = raw.str("artistType")?.takeIf { it.isNotBlank() }
    val imageUrl: String? get() = raw.imageUrl("poster")
}

data class LidarrAlbum(val raw: JsonObject) {
    val id: Int get() = raw.int("id") ?: 0
    val inLibrary: Boolean get() = id > 0
    val monitored: Boolean get() = raw.bool("monitored") ?: false
    val title: String get() = raw.str("title").orEmpty()
    val foreignId: String get() = raw.str("foreignAlbumId").orEmpty()
    val artistName: String get() = (raw["artist"] as? JsonObject)?.str("artistName").orEmpty()
    val year: String? get() = raw.str("releaseDate")?.take(4)?.takeIf { it.all(Char::isDigit) && it != "0001" }
    val type: String? get() = raw.str("albumType")?.takeIf { it.isNotBlank() }
    val imageUrl: String? get() = raw.str("remoteCover") ?: raw.imageUrl("cover")
    private val stats: JsonObject? get() = raw["statistics"] as? JsonObject
    val trackFileCount: Int get() = stats?.int("trackFileCount") ?: 0
    val trackCount: Int get() = stats?.int("trackCount") ?: 0
    val complete: Boolean get() = trackCount > 0 && trackFileCount >= trackCount
}

data class QueueItem(
    val title: String,
    val artist: String?,
    val status: String,
    val progress: Float?,
    val timeLeft: String?,
    val error: String?,
)

class LidarrClient(http: OkHttpClient, private val store: LidarrStore) {
    // Lidarrs Suche fragt MusicBrainz ab und kann einige Sekunden dauern.
    private val http = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun config(): LidarrConfig = store.config ?: throw LidarrException("Lidarr ist nicht eingerichtet")

    private suspend fun send(
        url: String,
        apiKey: String,
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
    ): JsonElement = withContext(Dispatchers.IO) {
        val base = url.toHttpUrlOrNull() ?: throw LidarrException("Ungültige Lidarr-URL")
        val httpUrl = base.newBuilder()
            .apply {
                path.trim('/').split('/').forEach { addPathSegment(it) }
                query.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()
        val requestBody = body?.toString()?.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(httpUrl)
            .header("X-Api-Key", apiKey)
            .method(method, requestBody)
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 -> throw LidarrException("API-Key wurde abgelehnt")
                    resp.code == 404 && path.endsWith("system/status") ->
                        throw LidarrException("Unter dieser URL wurde kein Lidarr gefunden")
                    !resp.isSuccessful -> throw LidarrException(errorMessage(text) ?: "Lidarr-Fehler (HTTP ${resp.code})")
                }
                if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
            }
        } catch (e: LidarrException) {
            throw e
        } catch (e: SerializationException) {
            throw LidarrException("Unerwartete Antwort – ist das ein Lidarr-Server?")
        } catch (e: UnknownHostException) {
            throw LidarrException("Lidarr-Server nicht gefunden")
        } catch (e: SocketTimeoutException) {
            throw LidarrException("Zeitüberschreitung bei Lidarr")
        } catch (e: ConnectException) {
            throw LidarrException("Keine Verbindung zu Lidarr möglich")
        } catch (e: SSLException) {
            throw LidarrException("SSL-Fehler: ${e.message}")
        }
    }

    private suspend fun send(method: String, path: String, query: Map<String, String> = emptyMap(), body: JsonElement? = null) =
        config().let { send(it.url, it.apiKey, method, path, query, body) }

    private fun errorMessage(text: String): String? = runCatching {
        when (val e = json.parseToJsonElement(text)) {
            is JsonArray -> (e.firstOrNull() as? JsonObject)?.str("errorMessage")
            is JsonObject -> e.str("message") ?: e.str("errorMessage")
            else -> null
        }
    }.getOrNull()

    // ---- Einrichtung ----

    data class ServerOptions(
        val version: String,
        val qualityProfiles: List<Option<Int>>,
        val metadataProfiles: List<Option<Int>>,
        val rootFolders: List<Option<String>>,
    )

    /** Prüft URL und API-Key und lädt die Auswahlmöglichkeiten fürs Hinzufügen. */
    suspend fun connect(url: String, apiKey: String): ServerOptions {
        val status = send(url, apiKey, "GET", "api/v1/system/status") as? JsonObject
            ?: throw LidarrException("Unerwartete Antwort – ist das ein Lidarr-Server?")
        suspend fun profiles(path: String) = (send(url, apiKey, "GET", path) as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o -> o.int("id")?.let { Option(it, o.str("name").orEmpty()) } }
        return ServerOptions(
            version = status.str("version").orEmpty(),
            qualityProfiles = profiles("api/v1/qualityprofile"),
            metadataProfiles = profiles("api/v1/metadataprofile"),
            rootFolders = (send(url, apiKey, "GET", "api/v1/rootfolder") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { o -> o.str("path")?.let { Option(it, o.str("name")?.takeIf(String::isNotBlank) ?: it) } },
        )
    }

    // ---- Suche ----

    suspend fun searchArtists(term: String): List<LidarrArtist> =
        (send("GET", "api/v1/artist/lookup", mapOf("term" to term)) as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::LidarrArtist) }

    suspend fun searchAlbums(term: String): List<LidarrAlbum> =
        (send("GET", "api/v1/album/lookup", mapOf("term" to term)) as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::LidarrAlbum) }

    // ---- Anfragen ----

    /** Fügt den Künstler mit allen Alben hinzu und startet die Suche nach fehlenden Alben. */
    suspend fun requestArtist(artist: LidarrArtist) {
        val c = config()
        val body = buildJsonObject {
            artist.raw.forEach { (k, v) -> put(k, v) }
            put("qualityProfileId", c.qualityProfileId)
            put("metadataProfileId", c.metadataProfileId)
            put("rootFolderPath", c.rootFolderPath)
            put("monitored", true)
            put("monitorNewItems", "all")
            putJsonObject("addOptions") {
                put("monitor", "all")
                put("searchForMissingAlbums", true)
            }
        }
        send("POST", "api/v1/artist", body = body)
    }

    /** Sucht bei einem vorhandenen Künstler nach allen fehlenden, überwachten Alben. */
    suspend fun searchArtist(artist: LidarrArtist) {
        send("POST", "api/v1/command", body = buildJsonObject {
            put("name", "ArtistSearch")
            put("artistId", artist.id)
        })
    }

    /**
     * Fordert ein einzelnes Album an. Ist der Künstler noch unbekannt, legt Lidarr ihn
     * ohne weitere überwachte Alben an.
     */
    suspend fun requestAlbum(album: LidarrAlbum) {
        if (album.inLibrary) {
            send("PUT", "api/v1/album/monitor", body = buildJsonObject {
                put("albumIds", buildJsonArray { add(JsonPrimitive(album.id)) })
                put("monitored", true)
            })
            send("POST", "api/v1/command", body = buildJsonObject {
                put("name", "AlbumSearch")
                put("albumIds", buildJsonArray { add(JsonPrimitive(album.id)) })
            })
            return
        }
        val c = config()
        val artistRaw = album.raw["artist"] as? JsonObject ?: throw LidarrException("Album ohne Künstlerangabe")
        val artist = buildJsonObject {
            artistRaw.forEach { (k, v) -> put(k, v) }
            if ((artistRaw.int("id") ?: 0) == 0) {
                put("qualityProfileId", c.qualityProfileId)
                put("metadataProfileId", c.metadataProfileId)
                put("rootFolderPath", c.rootFolderPath)
                put("monitored", true)
                put("monitorNewItems", "none")
                putJsonObject("addOptions") {
                    put("monitor", "none")
                    put("searchForMissingAlbums", false)
                }
            }
        }
        val body = buildJsonObject {
            album.raw.forEach { (k, v) -> put(k, v) }
            put("artist", artist)
            put("monitored", true)
            putJsonObject("addOptions") { put("searchForNewAlbum", true) }
        }
        send("POST", "api/v1/album", body = body)
    }

    // ---- Downloads ----

    suspend fun queue(): List<QueueItem> {
        val page = send(
            "GET",
            "api/v1/queue",
            mapOf("page" to "1", "pageSize" to "100", "includeArtist" to "true", "includeAlbum" to "true"),
        ) as? JsonObject
        return (page?.get("records") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { r ->
            val size = r.double("size") ?: 0.0
            val left = r.double("sizeleft") ?: 0.0
            val messages = (r["statusMessages"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("messages") as? JsonArray }
                .flatten()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            QueueItem(
                title = (r["album"] as? JsonObject)?.str("title") ?: r.str("title").orEmpty(),
                artist = (r["artist"] as? JsonObject)?.str("artistName"),
                status = statusLabel(r.str("trackedDownloadState") ?: r.str("status")),
                progress = if (size > 0) ((size - left) / size).toFloat().coerceIn(0f, 1f) else null,
                timeLeft = r.str("timeleft")?.substringBefore('.'),
                error = r.str("errorMessage") ?: messages.firstOrNull(),
            )
        }
    }

    private fun statusLabel(state: String?): String = when (state?.lowercase()) {
        "downloading" -> "Lädt herunter"
        "queued", "delay" -> "In Warteschlange"
        "paused" -> "Pausiert"
        "importpending" -> "Wartet auf Import"
        "importing" -> "Wird importiert"
        "imported", "completed" -> "Fertig"
        "failedpending", "failed" -> "Fehlgeschlagen"
        "warning" -> "Warnung"
        else -> state.orEmpty()
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.imageUrl(preferred: String): String? {
    val images = (this["images"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val image = images.firstOrNull { it.str("coverType") == preferred } ?: images.firstOrNull()
    return image?.let { it.str("remoteUrl") ?: it.str("url") }?.takeIf { it.startsWith("http") }
}
