package dev.navidroid.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.navidroid.NaviApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

@Serializable
private data class SavedDaylist(
    val date: String,
    val slot: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val songs: List<Song>,
    val byAi: Boolean,
)

/**
 * Hält die Daylist des aktuellen Zeitfensters. Mit Claude-Worker erstellt sie der Helfer (einmal pro Fenster),
 * sonst – oder wenn das scheitert – wird sie lokal aus Genres und Tageszeit gebaut.
 */
class DaylistRepository(private val app: NaviApp) {
    private val file = File(app.filesDir, "daylist.json")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    var current by mutableStateOf<Daylist?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        current = runCatching {
            val s = json.decodeFromString<SavedDaylist>(file.readText())
            Daylist(DayMoment(LocalDate.parse(s.date), DaySlot.entries.first { it.key == s.slot }), s.name, s.description, s.tags, s.songs, s.byAi)
        }.getOrNull()
    }

    /** Lädt eine neue Daylist, sobald ein neues Zeitfenster beginnt. Mehrfaches Aufrufen ist unbedenklich. */
    fun ensure() {
        val moment = DayMoment.at(LocalDateTime.now())
        if (current?.moment == moment || job?.isActive == true || !app.offline.online || app.session.current == null) return
        loading = true
        error = null
        job = scope.launch {
            try {
                val list = fromHelper(moment) ?: local(moment)
                if (list != null) {
                    current = list
                    save(list)
                    // Heruntergeladene Daylist auf den neuen Stand bringen
                    if (app.offline.autoSync && app.offline.collection("mix:$OFFLINE_ID") != null) {
                        app.offline.downloadMix(OFFLINE_ID, list.name, list.description, list.songs)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Daylist konnte nicht erstellt werden"
            } finally {
                loading = false
            }
        }
    }

    private suspend fun fromHelper(moment: DayMoment): Daylist? {
        val creds = app.session.current ?: return null
        if (!app.helper.configured) return null
        return try {
            val r = app.helper.aiDaylist(creds, moment.key, moment.prompt)
            val songs = app.audiobooks.withoutAudiobooks(r.tracks.map { t ->
                Song(id = t.id, title = t.title, artist = t.artist, album = t.album, albumId = t.albumId, coverArt = t.coverArt, duration = t.duration, year = t.year)
            })
            if (songs.isEmpty()) null else Daylist(moment, r.name, r.description, r.tags, songs, byAi = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // kein Claude-Worker oder Fehler: lokale Daylist
        }
    }

    private suspend fun local(moment: DayMoment): Daylist? {
        val (songs, genres) = app.mixes.library()
        return withContext(Dispatchers.Default) { LocalDaylist.build(songs, genres, moment) }
    }

    private suspend fun save(d: Daylist) = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(json.encodeToString(SavedDaylist(d.moment.date.toString(), d.moment.slot.key, d.name, d.description, d.tags, d.songs, d.byAi)))
        }
    }

    companion object {
        /** ID der Daylist bei den Offline-Downloads (als Mix). */
        const val OFFLINE_ID = "daylist"
    }
}
