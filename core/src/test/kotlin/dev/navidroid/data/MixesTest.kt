package dev.navidroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MixesTest {
    // Verteilung wie in der echten Bibliothek, Genres aus Lidarr
    private val counts = mapOf(
        "Fleetwood Mac" to 238, "Radiohead" to 157, "Tyler, The Creator" to 130, "Mitski" to 74, "Tame Impala" to 60,
        "beabadoobee" to 40, "Kraftklub" to 40, "Frank Ocean" to 34, "Clairo" to 34, "Ikkimel" to 21, "Kasi" to 14,
    )
    private val genres = mapOf(
        "tyler, the creator" to listOf("Hip Hop", "Hardcore Hip Hop", "Alternative Hip Hop"),
        "frank ocean" to listOf("Contemporary R&B", "Pop", "R&B", "Alternative R&B"),
        "radiohead" to listOf("Alternative Rock", "Art Rock", "Rock", "Electronic"),
        "tame impala" to listOf("Psychedelic Rock", "Neo-Psychedelia", "Alternative Rock", "Indie Rock"),
        "kraftklub" to listOf("Indie Rock", "Rap Rock"),
        "fleetwood mac" to listOf("Rock", "Soft Rock", "Blues Rock", "Pop Rock"),
        "beabadoobee" to listOf("Indie Pop", "Indie Rock", "Bedroom Pop", "Pop"),
        "mitski" to listOf("Indie Rock", "Rock", "Singer-Songwriter"),
        "clairo" to listOf("Bedroom Pop", "Indie Pop", "Pop"),
        "ikkimel" to listOf("Hip Hop", "Pop Rap"),
    )
    private val songs = counts.flatMap { (artist, n) -> (1..n).map { Song(id = "$artist-$it", title = "T$it", artist = artist) } }

    @Test
    fun mixesUseArtistGenresAndDifferFromEachOther() {
        val mixes = MixRepository(null, { genres }).build(songs, LocalDate.of(2026, 10, 17), genres)
        val names = mixes.map { it.name }
        println(mixes.joinToString("\n") { "${it.name}: ${it.description} (${it.songs.size})" })
        assertTrue(names.size >= 3)
        assertTrue(names.any { "Hip Hop" in it })
        mixes.forEach { m ->
            assertTrue(m.songs.size in 8..30)
            // keine zwei gleichen Künstler direkt hintereinander, solange es anders geht
            val repeats = m.songs.zipWithNext().count { (a, b) -> a.artist == b.artist }
            assertTrue("${m.name} hat $repeats Wiederholungen", repeats <= m.songs.size / 3)
        }
        // stabil innerhalb eines Tages
        assertEquals(mixes.map { it.songs.map { s -> s.id } }, MixRepository(null, { genres }).build(songs, LocalDate.of(2026, 10, 17), genres).map { it.songs.map { s -> s.id } })
    }
}
