package dev.navidroid.data

import java.time.LocalDate
import kotlin.math.absoluteValue
import kotlin.random.Random

data class Mix(val id: String, val name: String, val description: String, val songs: List<Song>, val hue: Float)

/**
 * Tägliche Mixe wie bei Spotify, berechnet aus der eigenen Bibliothek:
 * je Genre ein Mix (Lieblinge und selten Gehörtes gemischt), dazu „Lieblinge“ und „Entdecken“.
 */
class MixRepository(
    private val client: SubsonicClient?,
    private val artistGenres: suspend () -> Map<String, List<String>>,
    /** Eigene Wiedergabezahlen (Helfer), falls Navidrome nicht zählt. */
    private val playCounts: suspend () -> Map<String, Int> = { emptyMap() },
) {
    @Volatile
    private var cached: Pair<LocalDate, List<Mix>>? = null

    fun mix(id: String): Mix? = cached?.second?.firstOrNull { it.id == id }

    @Volatile
    private var lib: Triple<LocalDate, List<Song>, Map<String, List<String>>>? = null

    /** Ganze Bibliothek samt Künstler-Genres, einmal am Tag geladen (für Mixe und Daylist). */
    suspend fun library(force: Boolean = false): Pair<List<Song>, Map<String, List<String>>> {
        val today = LocalDate.now()
        lib?.let { (day, songs, genres) -> if (!force && day == today && songs.isNotEmpty()) return songs to genres }
        val counts = runCatching { playCounts() }.getOrDefault(emptyMap())
        val songs = client?.allSongs().orEmpty().map { s -> counts[s.id]?.let { n -> if (n > s.playCount) s.copy(playCount = n.toLong()) else s } ?: s }
        // Ohne Genre-Tags in den Dateien: Genres des Künstlers aus Lidarr verwenden
        val genres = if (songs.any { !it.genre.isNullOrBlank() }) emptyMap() else artistGenres()
        lib = Triple(today, songs, genres)
        return songs to genres
    }

    /** Titel zu einem Stichwort (Genre), z. B. von der Daylist. */
    suspend fun songsForTag(tag: String, size: Int = 40): List<Song> {
        val (songs, genres) = library()
        val t = tag.lowercase().trim()
        val pool = songs.filter { s -> genresOf(s, genres).any { g -> g.lowercase().let { it.contains(t) || t.contains(it) } } }
        val rnd = Random(System.currentTimeMillis())
        return spread(pool.shuffled(rnd).distinctBy { it.id }.let { capPerArtist(it, size) })
    }

    suspend fun mixes(force: Boolean = false): List<Mix> {
        val today = LocalDate.now()
        cached?.let { (day, list) -> if (!force && day == today) return list }
        // Legt das Navidrome-Plugin „NaviDroid Extras“ die Mixe als Playlists an, gelten diese – dann sind sie in allen Apps gleich.
        pluginMixes()?.let { cached = today to it; return it }
        val (songs, genres) = library(force)
        val result = build(songs, today, genres)
        cached = today to result
        return result
    }

    private suspend fun pluginMixes(): List<Mix>? {
        val c = client ?: return null
        val lists = runCatching { c.playlists() }.getOrNull()?.filter { it.comment?.startsWith(PLUGIN_MIX) == true }
        if (lists.isNullOrEmpty()) return null
        return lists
            .sortedBy { if (it.name == "Lieblinge" || it.name == "Entdecken") 1 else 0 }
            .mapNotNull { pl ->
                val songs = runCatching { c.playlist(pl.id).entry }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val base = pl.name.removePrefix("Mix · ")
                val name = if (base == "Lieblinge" || base == "Entdecken") base else "$base Mix"
                Mix("pl-${pl.id}", name, pl.comment.orEmpty().substringAfter("täglich neu · ", ""), songs, hueOf(name))
            }
            .takeIf { it.isNotEmpty() }
    }

    internal fun build(songs: List<Song>, day: LocalDate, artistGenres: Map<String, List<String>>): List<Mix> {
        if (songs.isEmpty()) return emptyList()
        val mixes = mutableListOf<Mix>()
        val seed = day.toEpochDay()

        fun pick(pool: List<Song>, key: String, size: Int = 30): List<Song> {
            val rnd = Random(seed * 31 + key.hashCode())
            val artists = pool.mapNotNull { it.artist }.toSet().size
            // Kein Künstler mehr als ein Drittel des Mixes (bei wenigen Künstlern entsprechend mehr)
            val cap = maxOf(3, size / 3, (size + artists - 1) / maxOf(1, artists))
            val ordered = pool.sortedByDescending { it.playCount }.take(size).shuffled(rnd) + pool.shuffled(rnd)
            val perArtist = HashMap<String?, Int>()
            val chosen = LinkedHashSet<Song>()
            for (s in ordered) {
                if (chosen.size >= size) break
                if (s in chosen) continue
                val n = perArtist.getOrDefault(s.artist, 0)
                if (n >= cap) continue
                perArtist[s.artist] = n + 1
                chosen += s
            }
            // Nicht zu viele Titel desselben Künstlers direkt hintereinander
            return spread(chosen.toList().shuffled(rnd))
        }

        fun genresOf(s: Song): List<String> = Companion.genresOf(s, artistGenres)
        val candidates = songs
            .flatMap { s -> genresOf(s).map { it to s } }
            .groupBy({ it.first.replaceFirstChar(Char::uppercase) }, { it.second })
            .filterValues { it.size >= 8 }
            .entries.sortedByDescending { it.value.size }
        // Genres so wählen, dass jeder Mix andere Künstler in den Vordergrund stellt
        val byGenre = mutableListOf<Map.Entry<String, List<Song>>>()
        val coveredArtists = mutableSetOf<String>()
        for (entry in candidates) {
            val artists = entry.value.mapNotNull { it.artist?.lowercase() }.toSet()
            val newShare = if (artists.isEmpty()) 0.0 else artists.count { it !in coveredArtists }.toDouble() / artists.size
            if (byGenre.isEmpty() || newShare >= 0.4) {
                byGenre += entry
                coveredArtists += artists
            }
            if (byGenre.size == 5) break
        }
        byGenre.forEach { (genre, pool) ->
            val picked = pick(pool, genre)
            mixes += Mix("genre-$genre", "$genre Mix", artistsLine(picked), picked, hueOf(genre))
        }
        val played = songs.filter { it.playCount > 0 }
        if (played.size >= 10) {
            val top = played.sortedByDescending { it.playCount }.take(40).shuffled(Random(seed))
            mixes += Mix("favorites", "Lieblinge", "Was du am häufigsten hörst", spread(top.take(30)), 0.95f)
        }
        val unplayed = songs.filter { it.playCount == 0L }
        if (unplayed.size >= 10) {
            val discover = unplayed.shuffled(Random(seed + 7)).take(30)
            mixes += Mix("discover", "Entdecken", "Titel aus deiner Sammlung, die du noch nie gehört hast", spread(discover), 0.55f)
        }
        if (mixes.isEmpty()) {
            val all = songs.shuffled(Random(seed)).take(30)
            mixes += Mix("library", "Bibliothek Mix", artistsLine(all), all, 0.6f)
        }
        return mixes
    }

    private fun artistsLine(songs: List<Song>): String {
        val artists = songs.mapNotNull { it.artist }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        return when {
            artists.isEmpty() -> ""
            artists.size <= 3 -> artists.joinToString(", ")
            else -> artists.take(3).joinToString(", ") + " und mehr"
        }
    }

    internal fun spread(list: List<Song>): List<Song> {
        val result = mutableListOf<Song>()
        val pending = list.toMutableList()
        while (pending.isNotEmpty()) {
            val i = pending.indexOfFirst { it.artist != result.lastOrNull()?.artist }.takeIf { it >= 0 } ?: 0
            result += pending.removeAt(i)
        }
        return result
    }

    private fun hueOf(key: String) = (key.hashCode().absoluteValue % 360) / 360f

    companion object {
        /** Kommentar-Kennung der Playlists vom Navidrome-Plugin. */
        const val PLUGIN_MIX = "NaviDroid-Mix"
        const val PLUGIN_DAYLIST = "NaviDroid-Daylist"

        fun isPluginPlaylist(comment: String?) = comment?.startsWith(PLUGIN_MIX) == true || comment?.startsWith(PLUGIN_DAYLIST) == true

        fun genresOf(s: Song, artistGenres: Map<String, List<String>>): List<String> {
            val own = (s.genre ?: "").split(';', '/', ',').map { it.trim() }.filter { it.isNotEmpty() }
            if (own.isNotEmpty()) return own
            val artist = (s.artist ?: "").lowercase()
            return artistGenres[artist] ?: artistGenres.entries.firstOrNull { artist.startsWith(it.key) }?.value ?: emptyList()
        }

        /** Höchstens ein Drittel pro Künstler (bei wenigen Künstlern entsprechend mehr). */
        fun capPerArtist(ordered: List<Song>, size: Int): List<Song> {
            val artists = ordered.mapNotNull { it.artist }.toSet().size
            val cap = maxOf(3, size / 3, (size + artists - 1) / maxOf(1, artists))
            val per = HashMap<String?, Int>()
            val out = ArrayList<Song>()
            for (s in ordered) {
                if (out.size >= size) break
                val n = per.getOrDefault(s.artist, 0)
                if (n >= cap) continue
                per[s.artist] = n + 1
                out += s
            }
            return out
        }
    }
}
