package dev.navidroid.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/** Zeitfenster der Daylist mit Farbverlauf für Kachel und Kopfbereich. */
enum class DaySlot(
    val key: String,
    val label: String,
    val startHour: Int,
    val endHour: Int,
    val adjective: String,
    val tag: String,
    val gradient: List<Long>,
) {
    EARLY("frueher-morgen", "morgen", 5, 9, "Verschlafener", "verschlafen", listOf(0xFFF2A65A, 0xFFE2677A, 0xFF8E5BC7)),
    MORNING("vormittag", "vormittag", 9, 12, "Entspannter", "entspannt", listOf(0xFFF7C66B, 0xFFE98A5B, 0xFFB5527A)),
    AFTERNOON("nachmittag", "nachmittag", 12, 17, "Sonniger", "sonnig", listOf(0xFFF5D04C, 0xFFF08A3E, 0xFFD9483B)),
    EVENING("abend", "abend", 17, 21, "Gemütlicher", "gemütlich", listOf(0xFFC2477B, 0xFF6B3FA0, 0xFF2B2F7A)),
    NIGHT("nacht", "nacht", 21, 5, "Verträumter", "verträumt", listOf(0xFF3B3F9E, 0xFF1E2A5C, 0xFF0E1530));

    val startLabel get() = "%02d:00".format(startHour)
    val endLabel get() = "%02d:00".format(endHour)
}

/** Aktuelles Zeitfenster; die Nacht nach Mitternacht gehört noch zum Vortag. */
data class DayMoment(val date: LocalDate, val slot: DaySlot) {
    val key get() = "$date-${slot.key}"
    val weekend get() = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    val weekday get() = WEEKDAYS[date.dayOfWeek.value - 1]

    /** z. B. „Samstagvormittag“ */
    val compound get() = weekday + slot.label

    /** Beschreibung für Claude, z. B. „Samstagvormittag (Wochenende), 09:00–12:00 Uhr“. */
    val prompt get() = "$compound (${if (weekend) "Wochenende" else "Werktag"}), ${slot.startLabel}–${slot.endLabel} Uhr"

    companion object {
        private val WEEKDAYS = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

        fun at(now: LocalDateTime): DayMoment {
            val h = now.hour
            return when {
                h < 5 -> DayMoment(now.toLocalDate().minusDays(1), DaySlot.NIGHT)
                h >= 21 -> DayMoment(now.toLocalDate(), DaySlot.NIGHT)
                else -> DayMoment(now.toLocalDate(), DaySlot.entries.first { h >= it.startHour && h < it.endHour })
            }
        }
    }
}

data class Daylist(
    val moment: DayMoment,
    val name: String,
    val description: String,
    val tags: List<String>,
    val songs: List<Song>,
    val byAi: Boolean,
)

/** Daylist ohne KI: ein Genre passend zum Zeitfenster ausgelost, Name aus Stimmung, Genre und Tageszeit. */
object LocalDaylist {
    fun build(songs: List<Song>, artistGenres: Map<String, List<String>>, moment: DayMoment, size: Int = 40): Daylist? {
        if (songs.isEmpty()) return null
        val rnd = Random(moment.key.hashCode())
        val byGenre = songs
            .flatMap { s -> MixRepository.genresOf(s, artistGenres).map { it.replaceFirstChar(Char::uppercase) to s } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 8 }
            .entries.sortedByDescending { it.value.size }
            .take(8)
        val genre = byGenre.takeIf { it.isNotEmpty() }?.let { it[rnd.nextInt(it.size)] }
        val pool = (genre?.value ?: songs).shuffled(rnd)
        var picked = MixRepository.capPerArtist(pool.distinctBy { it.id }, size)
        if (picked.size < size) picked = (picked + MixRepository.capPerArtist(songs.shuffled(rnd).filter { it !in picked }, size - picked.size))
        val spread = spread(picked)
        val name = buildString {
            append(moment.slot.adjective).append(' ')
            if (genre != null) append(genre.key).append('-').append(moment.compound) else append(moment.compound)
        }
        val artists = spread.mapNotNull { it.artist }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
        return Daylist(
            moment = moment,
            name = name,
            description = if (artists.isEmpty()) "Für deinen ${moment.compound}." else "${artists.joinToString(", ")} und mehr für deinen ${moment.compound}.",
            tags = listOfNotNull(moment.slot.tag, genre?.key?.lowercase(), if (moment.weekend) "wochenende" else null),
            songs = spread,
            byAi = false,
        )
    }

    private fun spread(list: List<Song>): List<Song> {
        val result = mutableListOf<Song>()
        val pending = list.toMutableList()
        while (pending.isNotEmpty()) {
            val i = pending.indexOfFirst { it.artist != result.lastOrNull()?.artist }.takeIf { it >= 0 } ?: 0
            result += pending.removeAt(i)
        }
        return result
    }
}
