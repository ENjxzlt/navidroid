package dev.navidroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DaylistTest {
    @Test
    fun slotsFollowTheClock() {
        val sat = LocalDate.of(2026, 10, 17)
        assertEquals(DaySlot.EARLY, DayMoment.at(sat.atTime(7, 30)).slot)
        assertEquals(DaySlot.MORNING, DayMoment.at(sat.atTime(9, 0)).slot)
        assertEquals(DaySlot.AFTERNOON, DayMoment.at(sat.atTime(16, 59)).slot)
        assertEquals(DaySlot.EVENING, DayMoment.at(sat.atTime(17, 0)).slot)
        assertEquals(DaySlot.NIGHT, DayMoment.at(sat.atTime(23, 0)).slot)
        // Nach Mitternacht läuft noch die Nacht vom Vortag
        val late = DayMoment.at(LocalDateTime.of(2026, 10, 18, 2, 0))
        assertEquals(DayMoment(sat, DaySlot.NIGHT), late)
        assertEquals("2026-10-17-nacht", late.key)
        assertEquals("Samstagnacht", late.compound)
        assertTrue(late.prompt.contains("Wochenende"))
    }

    @Test
    fun localDaylistPicksGenreAndNamesIt() {
        val songs = (1..60).map { i ->
            Song(id = "s$i", title = "T$i", artist = "A${i % 12}", genre = if (i <= 40) "Indie" else "Jazz", duration = 200)
        }
        val moment = DayMoment(LocalDate.of(2026, 10, 18), DaySlot.EARLY)
        val d = LocalDaylist.build(songs, emptyMap(), moment)
        assertNotNull(d)
        d!!
        assertEquals(40, d.songs.size)
        assertEquals(40, d.songs.map { it.id }.toSet().size)
        assertTrue(d.name, d.name.startsWith("Verschlafener ") && d.name.endsWith("-Sonntagmorgen"))
        assertTrue(d.tags.contains("verschlafen") && d.tags.contains("wochenende"))
        // Gleiches Zeitfenster → gleiche Daylist
        assertEquals(d.songs, LocalDaylist.build(songs, emptyMap(), moment)!!.songs)
        // Nie zwei vom selben Künstler hintereinander, wenn es sich vermeiden lässt
        assertTrue(d.songs.zipWithNext().none { (a, b) -> a.artist == b.artist })
    }
}
