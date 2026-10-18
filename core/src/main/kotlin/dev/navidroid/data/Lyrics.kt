package dev.navidroid.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class LyricWord(val text: String, val startMs: Long, val endMs: Long)

@Serializable
data class LyricLine(val startMs: Long, val endMs: Long, val text: String, val words: List<LyricWord>)

@Serializable
data class Lyrics(val synced: Boolean, val lines: List<LyricLine>, val source: String) {
    /** Index der Zeile, die zur Position gehört, oder -1 vor der ersten Zeile. */
    fun lineAt(positionMs: Long): Int {
        if (!synced) return -1
        var result = -1
        for (i in lines.indices) {
            if (lines[i].startMs <= positionMs) result = i else break
        }
        return result
    }
}

object LyricsParser {
    private val lineTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val metaTag = Regex("""^\[[a-zA-Z]+:.*]$""")

    private fun toMs(m: MatchResult): Long {
        val (min, sec, frac) = m.destructured
        val fracMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs
    }

    /** Liest LRC inkl. erweitertem LRC mit Wort-Zeitstempeln (<mm:ss.xx>). */
    fun parseLrc(text: String, durationMs: Long, source: String): Lyrics? {
        val raw = mutableListOf<Pair<Long, String>>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !metaTag.matches(it) }.forEach { l ->
            val stamps = lineTag.findAll(l).toList()
            if (stamps.isEmpty()) return@forEach
            val content = l.substring(stamps.last().range.last + 1).trim()
            stamps.forEach { raw += toMs(it) to content }
        }
        if (raw.isEmpty()) return null
        return build(raw.sortedBy { it.first }, durationMs, source)
    }

    fun fromStructured(lyrics: StructuredLyrics, durationMs: Long, source: String): Lyrics {
        if (!lyrics.synced) return plain(lyrics.line.joinToString("\n") { it.value }, source)
        val raw = lyrics.line.map { ((it.start ?: 0L) - lyrics.offset).coerceAtLeast(0) to it.value.trim() }
        return build(raw.sortedBy { it.first }, durationMs, source)
    }

    fun plain(text: String, source: String) = Lyrics(
        synced = false,
        lines = text.lines().map { LyricLine(0, 0, it.trim(), emptyList()) },
        source = source,
    )

    private fun build(raw: List<Pair<Long, String>>, durationMs: Long, source: String): Lyrics {
        val lines = raw.mapIndexed { i, (start, content) ->
            val nextStart = raw.getOrNull(i + 1)?.first ?: maxOf(durationMs, start + 6_000)
            val end = maxOf(nextStart, start + 1)
            val words = wordsOf(content, start, end)
            LyricLine(start, end, words.joinToString(" ") { it.text }, words)
        }
        return Lyrics(synced = true, lines = lines, source = source)
    }

    private fun wordsOf(content: String, start: Long, end: Long): List<LyricWord> {
        val tags = wordTag.findAll(content).toList()
        if (tags.isNotEmpty()) {
            // Erweitertes LRC: Jeder Zeitstempel markiert den Beginn des folgenden Wortes.
            val parts = mutableListOf<Pair<Long, String>>()
            val lead = content.substring(0, tags.first().range.first).trim()
            if (lead.isNotEmpty()) parts += start to lead
            tags.forEachIndexed { i, m ->
                val textEnd = tags.getOrNull(i + 1)?.range?.first ?: content.length
                val t = content.substring(m.range.last + 1, textEnd).trim()
                if (t.isNotEmpty()) t.split(Regex("\\s+")).forEach { parts += toMs(m) to it }
            }
            return parts.mapIndexed { i, (s, t) ->
                LyricWord(t, s, parts.getOrNull(i + 1)?.first?.takeIf { it > s } ?: end)
            }
        }
        // Nur Zeilen-Timing: Wörter nach Länge über die Zeile verteilen, wie gesungen meist etwas
        // schneller als die ganze Pause bis zur nächsten Zeile.
        val tokens = content.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val chars = tokens.sumOf { it.length + 1 }
        val span = minOf((end - start) * 92 / 100, chars * 85L + 400).coerceAtLeast(1)
        var t = start
        return tokens.map { w ->
            val d = span * (w.length + 1) / chars
            LyricWord(w, t, t + d).also { t += d }
        }
    }
}

@Serializable
private data class LrcLibResult(
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val instrumental: Boolean = false,
)

data class SongRef(val id: String, val title: String, val artist: String?, val album: String?, val durationMs: Long)

/**
 * Lädt Songtexte zuerst von Navidrome, sonst (falls erlaubt) von LRCLIB.
 * Synchronisierte Texte werden bevorzugt.
 */
class LyricsRepository(
    private val http: OkHttpClient,
    private val client: SubsonicClient,
    filesDir: File,
    private val readOnline: () -> Boolean,
    private val writeOnline: (Boolean) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, Lyrics?>()
    private val offlineDir = File(filesDir, "offline/lyrics")

    var onlineLyrics: Boolean
        get() = readOnline()
        set(value) {
            writeOnline(value)
            synchronized(cache) { cache.clear() }
        }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    suspend fun load(song: SongRef): Lyrics? {
        synchronized(cache) { if (cache.containsKey(song.id)) return cache[song.id] }
        // Für heruntergeladene Titel liegt der Text auf dem Gerät.
        withContext(Dispatchers.IO) {
            val f = java.io.File(offlineDir, "${song.id}.json")
            if (f.exists()) runCatching { json.decodeFromString<Lyrics>(f.readText()) }.getOrNull() else null
        }?.let { saved ->
            synchronized(cache) { cache[song.id] = saved }
            return saved
        }
        val fromServer = runCatching { client.lyrics(song.id) }.getOrDefault(emptyList())
            .filter { it.line.isNotEmpty() }
        val serverSynced = fromServer.firstOrNull { it.synced }
        val result = when {
            serverSynced != null -> LyricsParser.fromStructured(serverSynced, song.durationMs, "Navidrome")
            else -> {
                val online = if (onlineLyrics) runCatching { lrcLib(song) }.getOrNull() else null
                val onlineSynced = online?.syncedLyrics?.let { LyricsParser.parseLrc(it, song.durationMs, "LRCLIB") }
                onlineSynced
                    ?: fromServer.firstOrNull()?.let { LyricsParser.fromStructured(it, song.durationMs, "Navidrome") }
                    ?: online?.plainLyrics?.takeIf { it.isNotBlank() }?.let { LyricsParser.plain(it, "LRCLIB") }
            }
        }
        synchronized(cache) { cache[song.id] = result }
        return result
    }

    /** Lädt den Songtext und legt ihn für die Offline-Nutzung in [dir] ab. */
    suspend fun saveOffline(song: SongRef, dir: java.io.File) {
        val f = java.io.File(dir, "${song.id}.json")
        if (f.exists()) return
        val lyrics = load(song) ?: return
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            f.writeText(json.encodeToString(Lyrics.serializer(), lyrics))
        }
    }

    private suspend fun lrcLib(song: SongRef): LrcLibResult? = withContext(Dispatchers.IO) {
        val artist = song.artist ?: return@withContext null
        val exact = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", song.title)
            .addQueryParameter("artist_name", artist)
            .apply { song.album?.let { addQueryParameter("album_name", it) } }
            .apply { if (song.durationMs > 0) addQueryParameter("duration", "${song.durationMs / 1000}") }
            .build()
        get(exact.toString())?.let { return@withContext json.decodeFromString<LrcLibResult>(it) }
        val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", song.title)
            .addQueryParameter("artist_name", artist)
            .build()
        val hits = get(search.toString())?.let { json.decodeFromString<List<LrcLibResult>>(it) }.orEmpty()
        hits.firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: hits.firstOrNull { !it.plainLyrics.isNullOrBlank() }
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NaviDroid (https://github.com/ENjxzlt/navidroid)")
            .build()
        return http.newCall(request).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
    }
}
