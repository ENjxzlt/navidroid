package dev.navidroid.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.navidroid.data.DayMoment
import dev.navidroid.data.Daylist
import dev.navidroid.data.KeyValue
import dev.navidroid.data.LocalDaylist
import dev.navidroid.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.File
import java.time.LocalDateTime

/** [KeyValue] als JSON-Datei unter ~/.config/navidroid (Datei nur für den eigenen Benutzer lesbar). */
class FileKeyValue(name: String) : KeyValue {
    private val file = File(Paths.config, "$name.json")
    private val values: MutableMap<String, Any?> = runCatching {
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues<String, kotlinx.serialization.json.JsonElement, Any?> { (_, v) ->
            val p = v as? JsonPrimitive
            p?.booleanOrNull ?: p?.intOrNull ?: p?.contentOrNull
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    override fun getString(key: String) = values[key] as? String
    override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default

    override fun edit(block: KeyValue.Editor.() -> Unit) {
        object : KeyValue.Editor {
            override fun putString(key: String, value: String?) { values[key] = value }
            override fun putInt(key: String, value: Int) { values[key] = value }
            override fun putBoolean(key: String, value: Boolean) { values[key] = value }
            override fun clear() { values.clear() }
        }.block()
        val obj = JsonObject(values.filterValues { it != null }.mapValues { (_, v) ->
            when (v) { is Boolean -> JsonPrimitive(v); is Int -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
        })
        file.writeText(obj.toString())
        runCatching { java.nio.file.Files.setPosixFilePermissions(file.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }
}

/** Daylist wie in der App: vom Helfer (Claude), sonst lokal aus Genres und Tageszeit. */
class DesktopDaylist(private val app: DesktopApp) {
    var current by mutableStateOf<Daylist?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    suspend fun ensure() {
        val moment = DayMoment.at(LocalDateTime.now())
        if (current?.moment == moment || loading) return
        loading = true
        try {
            val creds = app.session.current ?: return
            val fromHelper = if (app.helper.configured) runCatching {
                val r = app.helper.aiDaylist(creds, moment.key, moment.prompt)
                Daylist(moment, r.name, r.description, r.tags, r.tracks.map { t ->
                    Song(id = t.id, title = t.title, artist = t.artist, album = t.album, albumId = t.albumId, coverArt = t.coverArt, duration = t.duration, year = t.year)
                }, true)
            }.getOrNull() else null
            current = fromHelper ?: run {
                val (songs, genres) = app.mixes.library()
                withContext(Dispatchers.Default) { LocalDaylist.build(songs, genres, moment) }
            }
        } finally {
            loading = false
        }
    }
}

/**
 * Akzentfarbe aus dem Cover (wie in der App): die kräftigste helle Farbe, aufgehellt.
 * Graue Cover behalten die Standardfarbe.
 */
object CoverAccent {
    private val cache = mutableStateMapOf<String, Color>()

    fun cached(url: String?): Color? = url?.let { cache[it] }

    suspend fun load(http: OkHttpClient, url: String?): Color? {
        if (url == null) return null
        cache[url]?.let { return it }
        val color = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() } ?: return@runCatching null
                val bmp = Bitmap.makeFromImage(Image.makeFromEncoded(bytes))
                var best: FloatArray? = null
                var bestScore = 0f
                val step = maxOf(1, bmp.width / 40)
                for (x in 0 until bmp.width step step) for (y in 0 until bmp.height step step) {
                    val c = bmp.getColor(x, y)
                    val hsb = java.awt.Color.RGBtoHSB((c shr 16) and 255, (c shr 8) and 255, c and 255, null)
                    val score = hsb[1] * hsb[2]
                    if (hsb[1] > 0.25f && hsb[2] > 0.35f && score > bestScore) { bestScore = score; best = hsb }
                }
                best?.let { hsb ->
                    val rgb = java.awt.Color.HSBtoRGB(hsb[0], (hsb[1] * 0.75f).coerceIn(0.35f, 0.7f), 0.95f)
                    Color(rgb or (0xFF shl 24))
                }
            }.getOrNull()
        }
        if (color != null) cache[url] = color
        return color
    }
}
