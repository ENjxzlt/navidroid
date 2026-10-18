package dev.navidroid.desktop

import dev.navidroid.data.Song
import dev.navidroid.data.SubsonicClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Repeat { Off, All, One }

/** Fernes Gerät (Music Assistant). */
data class RemoteTarget(val id: String, val name: String)

data class RemoteStatus(val playing: Boolean, val elapsedMs: Long, val durationMs: Long?, val volume: Int?, val queueIndex: Int?)

/** Fernwiedergabe – auf dem Desktop über Music Assistant und den Helfer. */
interface RemoteBackend {
    suspend fun play(playerId: String, songIds: List<String>)
    suspend fun command(playerId: String, name: String, value: Double? = null)
    suspend fun state(playerId: String): RemoteStatus
}

/** Einschlaf-Timer: feste Uhrzeit (epoch ms) oder am Ende des Titels. */
data class SleepTimer(val endsAt: Long? = null, val endOfTrack: Boolean = false, val minutes: Int = 0) {
    val active get() = endsAt != null || endOfTrack
}

data class PlayerState(
    val queue: List<Song> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 80,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.Off,
    val error: String? = null,
    /** Gerät, auf dem gerade gespielt wird; null = dieser Rechner. */
    val remote: RemoteTarget? = null,
    val remoteVolume: Int? = null,
    val remoteError: String? = null,
) {
    val current: Song? get() = queue.getOrNull(index)
}

/**
 * Wiedergabe über libVLC (muss installiert sein: Paket „vlc“). Streams kommen direkt von Navidrome
 * in Originalqualität. Meldet „Spielt gerade“ und Scrobbles wie die App.
 *
 * Zwei MediaPlayer, damit beim Überblenden der nächste Titel schon läuft, während der alte leiser wird.
 * Lautstärke = Nutzer × ReplayGain × Überblenden × Einschlaf-Timer.
 */
class DesktopPlayer(private val client: SubsonicClient, private val settings: () -> DesktopSettings) {
    private val _state = MutableStateFlow(PlayerState(volume = settings().volume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _sleep = MutableStateFlow(SleepTimer())
    val sleep: StateFlow<SleepTimer> = _sleep.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    private var factory: MediaPlayerFactory? = null
    /** Aktiver Player. */
    private var mp: MediaPlayer? = null
    /** Zweiter Player fürs Überblenden. */
    private var spare: MediaPlayer? = null
    /** Ausblendender (alter) Titel während des Überblendens. */
    private var fading: MediaPlayer? = null
    private var fadeStart = 0L
    private var fadeLen = 1L
    private var fadingGain = 1f
    private var currentGain = 1f
    private var original: List<Song> = emptyList()
    private var submitted = false
    var onTrackChanged: (() -> Unit)? = null
    /** Titel zu Ende gehört (für den Hörverlauf im Helfer). */
    var onPlayed: ((Song, Long) -> Unit)? = null
    /** Lokale Datei (Offline-Download) statt Stream, falls vorhanden. */
    var localUrl: (Song) -> String? = { null }
    /** Fernwiedergabe (Music Assistant); ohne bleibt nur „Dieser Rechner“. */
    var remoteBackend: RemoteBackend? = null

    // Fernwiedergabe
    private var remoteStart = 0
    private var remoteCount = 0
    private var remoteSent = false
    private var remoteDirty = false
    private var remotePlaying = false
    private var remoteElapsed = 0L
    private var remoteStamp = 0L
    private var remoteQuietUntil = 0L
    private var pollJob: Job? = null

    init {
        try {
            val f = MediaPlayerFactory("--no-video", "--quiet", "--network-caching=3000")
            val listener = object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer) {
                    scope.launch {
                        when {
                            mediaPlayer === fading -> stopFading()
                            mediaPlayer === mp && _state.value.remote == null -> onFinished()
                        }
                    }
                }

                override fun playing(mediaPlayer: MediaPlayer) {
                    scope.launch { if (isLocalActive(mediaPlayer)) _state.update { it.copy(playing = true, buffering = false, error = null) } }
                }

                override fun paused(mediaPlayer: MediaPlayer) {
                    scope.launch { if (isLocalActive(mediaPlayer)) _state.update { it.copy(playing = false) } }
                }

                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                    scope.launch { if (isLocalActive(mediaPlayer)) _state.update { it.copy(buffering = newCache < 100f) } }
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    scope.launch {
                        if (mediaPlayer === fading) return@launch stopFading()
                        if (!isLocalActive(mediaPlayer)) return@launch
                        _state.update { it.copy(error = "Titel konnte nicht abgespielt werden", playing = false) }
                        onFinished()
                    }
                }
            }
            val a = f.mediaPlayers().newMediaPlayer()
            val b = f.mediaPlayers().newMediaPlayer()
            a.events().addMediaPlayerEventListener(listener)
            b.events().addMediaPlayerEventListener(listener)
            a.audio().setVolume(_state.value.volume)
            factory = f
            mp = a
            spare = b
        } catch (e: Throwable) {
            _state.update { it.copy(error = "VLC nicht gefunden – bitte das Paket „vlc“ installieren (${e.message})") }
        }
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(100)
                tick++
                runCatching { onTick(tick % 2 == 0) }
            }
        }
    }

    val available get() = mp != null

    private fun isLocalActive(p: MediaPlayer) = p === mp && _state.value.remote == null

    /** Aktuelle Position, genauer als [state] (für das mitlaufende Video). */
    fun positionNow(): Long {
        val s = _state.value
        if (s.remote != null) return remotePosition()
        val p = mp ?: return s.positionMs
        return if (p.status().state() == State.PLAYING || p.status().state() == State.PAUSED) p.status().time().coerceAtLeast(0) else s.positionMs
    }

    private fun onTick(updatePosition: Boolean) {
        val s = _state.value
        checkSleep()
        if (s.remote != null) {
            if (updatePosition) _state.update { it.copy(positionMs = remotePosition().let { p -> if (it.durationMs > 0) p.coerceAtMost(it.durationMs) else p }) }
            return
        }
        val p = mp ?: return
        val st = p.status().state()
        // Vorbereitet, aber noch nicht gestartet (z. B. nach der Rückkehr vom Fern-Gerät): Position nicht überschreiben
        if (st != State.NOTHING_SPECIAL && st != State.OPENING) {
            val pos = p.status().time().coerceAtLeast(0)
            val len = p.status().length().takeIf { it > 0 } ?: ((s.current?.duration ?: 0) * 1000L)
            if (updatePosition) {
                _state.update { it.copy(positionMs = pos, durationMs = len) }
                scrobbleCheck(pos, len)
            }
            crossfadeCheck(pos, len)
        }
        applyVolumes()
    }

    // ---- Lautstärke: ReplayGain, Überblenden, Einschlaf-Timer ----

    /** Wie in der App: Album- bzw. Titel-Gain; angehoben wird nur so weit, wie der Spitzenpegel es erlaubt. */
    private fun gainFor(song: Song?): Float {
        val rg = song?.replayGain ?: return 1f
        val (gain, peak) = when (settings().replayGain) {
            1 -> rg.trackGain to rg.trackPeak
            2 -> (rg.albumGain ?: rg.trackGain) to (if (rg.albumGain != null) rg.albumPeak else rg.trackPeak)
            else -> return 1f
        }
        if (gain == null || gain.isNaN()) return 1f
        val factor = 10.0.pow(gain / 20.0).toFloat()
        val limit = if (peak != null && peak > 0f) 1f / peak else 1f
        return minOf(factor, limit).coerceIn(0.05f, 2f)
    }

    private fun sleepFactor(): Float {
        val sl = _sleep.value
        sl.endsAt?.let { end ->
            val left = end - System.currentTimeMillis()
            if (left < 10_000) return (left / 10_000f).coerceIn(0f, 1f)
        }
        if (sl.endOfTrack) {
            val s = _state.value
            val left = s.durationMs - s.positionMs
            if (s.durationMs > 0 && left < 10_000) return (left / 10_000f).coerceIn(0.05f, 1f)
        }
        return 1f
    }

    private fun applyVolumes() {
        val user = _state.value.volume / 100f
        val sleepF = sleepFactor()
        var fadeIn = 1f
        fading?.let { f ->
            val t = ((System.currentTimeMillis() - fadeStart).toFloat() / fadeLen).coerceIn(0f, 1f)
            setVol(f, user * fadingGain * (1f - t) * sleepF)
            fadeIn = t
            if (t >= 1f) stopFading()
        }
        mp?.let { setVol(it, user * currentGain * fadeIn * sleepF) }
    }

    private fun setVol(p: MediaPlayer, factor: Float) {
        val v = (factor * 100).roundToInt().coerceIn(0, 200)
        if (p.audio().volume() != v) p.audio().setVolume(v)
    }

    private fun stopFading() {
        val f = fading ?: return
        fading = null
        runCatching { f.controls().stop() }
    }

    /** Überblenden wie in der App – aber nicht zwischen aufeinanderfolgenden Titeln desselben Albums. */
    private fun crossfadeCheck(pos: Long, len: Long) {
        val s = _state.value
        val cf = settings().crossfade * 1000L
        if (cf <= 0 || fading != null || !s.playing || len <= 0 || s.repeat == Repeat.One || _sleep.value.active) return
        val remaining = len - pos
        if (remaining > cf || remaining < 800) return
        val next = nextIndex() ?: return
        val cur = s.current ?: return
        val nextSong = s.queue[next]
        if (!s.shuffle && cur.albumId != null && cur.albumId == nextSong.albumId) return
        startCrossfade(next, remaining)
    }

    private fun startCrossfade(next: Int, remaining: Long) {
        val sp = spare ?: return
        val old = mp ?: return
        val song = _state.value.queue.getOrNull(next) ?: return
        val url = urlFor(song) ?: return
        fading = old
        fadingGain = currentGain
        fadeStart = System.currentTimeMillis()
        fadeLen = remaining.coerceAtLeast(1000)
        mp = sp
        spare = old
        submitted = false
        currentGain = gainFor(song)
        _state.update { it.copy(index = next, positionMs = 0, durationMs = song.duration * 1000L, buffering = true, error = null) }
        sp.audio().setVolume(0)
        sp.media().play(url)
        trackStarted(song)
    }

    private fun nextIndex(): Int? {
        val s = _state.value
        return when {
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeat == Repeat.All && s.queue.isNotEmpty() -> 0
            else -> null
        }
    }

    // ---- Einschlaf-Timer ----

    fun sleepIn(minutes: Int) = _sleep.update { SleepTimer(endsAt = System.currentTimeMillis() + minutes * 60_000L, minutes = minutes) }

    fun sleepAtEndOfTrack() = _sleep.update { SleepTimer(endOfTrack = true) }

    fun cancelSleep() = _sleep.update { SleepTimer() }

    private fun checkSleep() {
        val end = _sleep.value.endsAt ?: return
        if (System.currentTimeMillis() >= end) {
            cancelSleep()
            pause()
        }
    }

    // ---- Steuerung ----

    fun play(songs: List<Song>, start: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        original = songs
        val queue = if (shuffle) songs.shuffled() else songs
        val index = if (shuffle) 0 else start.coerceIn(songs.indices)
        _state.update { it.copy(queue = queue, index = index, shuffle = shuffle) }
        if (_state.value.remote != null) sendQueueToRemote(0) else load(index)
    }

    fun playNext(songs: List<Song>) {
        if (_state.value.current == null) return play(songs)
        _state.update { s -> s.copy(queue = s.queue.toMutableList().apply { addAll(s.index + 1, songs) }) }
        remoteDirty = true
    }

    fun enqueue(songs: List<Song>) {
        if (_state.value.current == null) return play(songs)
        _state.update { s -> s.copy(queue = s.queue + songs) }
    }

    fun skipTo(index: Int) {
        if (index in _state.value.queue.indices) {
            _state.update { it.copy(index = index) }
            if (_state.value.remote != null) sendQueueToRemote(0) else load(index)
        }
    }

    fun removeAt(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices || index == s.index) return
        _state.update { it.copy(queue = it.queue.filterIndexed { i, _ -> i != index }, index = if (index < it.index) it.index - 1 else it.index) }
        remoteDirty = true
    }

    fun toggle() {
        if (_state.value.remote != null) return remoteToggle()
        val p = mp ?: return
        when {
            _state.value.current == null -> Unit
            p.status().isPlaying -> { stopFading(); p.controls().pause() }
            p.status().state() == State.ENDED || p.status().state() == State.STOPPED || p.status().state() == State.ERROR -> load(_state.value.index)
            else -> p.controls().play()
        }
    }

    fun pause() {
        if (_state.value.remote != null) {
            if (remoteSent && remotePlaying) remoteToggle()
            return
        }
        stopFading()
        mp?.controls()?.setPause(true)
    }

    fun resume() {
        if (_state.value.remote != null) {
            if (!remoteSent || !remotePlaying) remoteToggle()
            return
        }
        val p = mp ?: return
        val st = p.status().state()
        if (st == State.ENDED || st == State.STOPPED) load(_state.value.index) else p.controls().play()
    }

    fun next() {
        val s = _state.value
        val n = nextIndex() ?: return
        if (s.remote != null && n == s.index + 1 && !remoteDirty && n - remoteStart < remoteCount) {
            _state.update { it.copy(index = n, positionMs = 0, durationMs = it.queue[n].duration * 1000L) }
            remoteElapsed = 0; remoteStamp = System.currentTimeMillis(); remoteQuietUntil = remoteStamp + 3_000
            remoteCall { command(it, "next") }
            onTrackChanged?.invoke()
            return
        }
        skipTo(n)
    }

    fun previous() {
        val s = _state.value
        if (s.positionMs > 3_000 || s.index == 0) return seek(0)
        if (s.remote != null && s.index - 1 >= remoteStart && !remoteDirty) {
            val n = s.index - 1
            _state.update { it.copy(index = n, positionMs = 0, durationMs = it.queue[n].duration * 1000L) }
            remoteElapsed = 0; remoteStamp = System.currentTimeMillis(); remoteQuietUntil = remoteStamp + 3_000
            remoteCall { command(it, "previous") }
            onTrackChanged?.invoke()
            return
        }
        skipTo(s.index - 1)
    }

    fun seek(ms: Long) {
        val target = ms.coerceAtLeast(0)
        if (_state.value.remote != null) {
            remoteElapsed = target; remoteStamp = System.currentTimeMillis(); remoteQuietUntil = remoteStamp + 3_000
            remoteCall { command(it, "seek", target / 1000.0) }
        } else {
            stopFading()
            mp?.controls()?.setTime(target)
        }
        _state.update { it.copy(positionMs = target) }
    }

    fun setVolume(v: Int) {
        val vol = v.coerceIn(0, 100)
        _state.update { it.copy(volume = vol) }
        applyVolumes()
    }

    fun toggleShuffle() {
        val s = _state.value
        val cur = s.current ?: return
        if (!s.shuffle) {
            val rest = s.queue.filterIndexed { i, _ -> i != s.index }.shuffled()
            _state.update { it.copy(queue = listOf(cur) + rest, index = 0, shuffle = true) }
        } else {
            val idx = original.indexOfFirst { it.id == cur.id }
            _state.update { it.copy(queue = if (idx >= 0) original else it.queue, index = if (idx >= 0) idx else it.index, shuffle = false) }
        }
        remoteDirty = true
    }

    fun cycleRepeat() = _state.update { it.copy(repeat = Repeat.entries[(it.repeat.ordinal + 1) % Repeat.entries.size]) }

    private fun urlFor(song: Song): String? =
        localUrl(song) ?: runCatching { client.streamUrl(song.id, settings().bitrate) }.getOrNull()

    /** Lädt einen Titel; [paused] bereitet ihn nur vor (Start mit Abspielen). */
    private fun load(index: Int, startMs: Long = 0, paused: Boolean = false) {
        val p = mp ?: return
        if (_state.value.remote != null) return
        val song = _state.value.queue.getOrNull(index) ?: return
        stopFading()
        submitted = false
        val url = urlFor(song) ?: return
        currentGain = gainFor(song)
        _state.update { it.copy(positionMs = startMs, durationMs = song.duration * 1000L, buffering = !paused, playing = if (paused) false else it.playing, error = null) }
        applyVolumes()
        val opts = if (startMs > 0) arrayOf(":start-time=${startMs / 1000.0}") else emptyArray()
        if (paused) p.media().prepare(url, *opts) else p.media().play(url, *opts)
        trackStarted(song)
    }

    private fun trackStarted(song: Song) {
        onTrackChanged?.invoke()
        scope.launch { runCatching { client.scrobble(song.id, submission = false) } }
        autoplayIfLast()
    }

    private fun onFinished() {
        val s = _state.value
        if (_sleep.value.endOfTrack) {
            // Einschlaf-Timer „Am Ende des Titels“: zum nächsten Titel, aber nicht abspielen
            cancelSleep()
            val n = if (s.repeat == Repeat.One) null else nextIndex()
            _state.update { it.copy(index = n ?: it.index, positionMs = 0, playing = false) }
            if (n != null) onTrackChanged?.invoke()
            return
        }
        when {
            s.repeat == Repeat.One -> load(s.index)
            s.index + 1 < s.queue.size -> skipTo(s.index + 1)
            s.repeat == Repeat.All && s.queue.isNotEmpty() -> skipTo(0)
            else -> _state.update { it.copy(playing = false) }
        }
    }

    private fun scrobbleCheck(pos: Long, len: Long) {
        val song = _state.value.current ?: return
        if (submitted || !_state.value.playing || len <= 0) return
        if (pos >= minOf(len / 2, 240_000L)) {
            submitted = true
            val now = System.currentTimeMillis()
            scope.launch { runCatching { client.scrobble(song.id, submission = true, timeMs = now) } }
            onPlayed?.invoke(song, now)
        }
    }

    /** Wie in der App: am Ende der Warteschlange mit ähnlicher Musik weiter. */
    private fun autoplayIfLast() {
        val s = _state.value
        val song = s.current ?: return
        if (!settings().autoplay || s.repeat != Repeat.Off || s.index != s.queue.lastIndex) return
        scope.launch {
            val have = s.queue.map { it.id }.toHashSet()
            val more = runCatching { client.similarSongs(song.id, 30) }.getOrDefault(emptyList())
                .ifEmpty { runCatching { client.randomSongs(20) }.getOrDefault(emptyList()) }
                .filter { it.id !in have }.take(20)
            if (more.isNotEmpty() && _state.value.index == _state.value.queue.lastIndex) _state.update { it.copy(queue = it.queue + more) }
        }
    }

    // ---- Fernwiedergabe (Music Assistant) ----

    private fun remotePosition(): Long = remoteElapsed + if (remotePlaying) System.currentTimeMillis() - remoteStamp else 0L

    private fun remoteCall(block: suspend RemoteBackend.(String) -> Unit) {
        val target = _state.value.remote ?: return
        val b = remoteBackend ?: return
        scope.launch {
            try {
                b.block(target.id)
                _state.update { it.copy(remoteError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(remoteError = e.message ?: "Gerät nicht erreichbar") }
            }
        }
    }

    /** Warteschlange ab dem aktuellen Titel ans Gerät geben (wie in der App). */
    private fun sendQueueToRemote(startMs: Long) {
        val s = _state.value
        val cur = s.current ?: return
        val ids = s.queue.drop(s.index).take(500).map { it.id }
        remoteStart = s.index
        remoteCount = ids.size
        remoteSent = true
        remoteDirty = false
        remotePlaying = true
        remoteElapsed = startMs
        remoteStamp = System.currentTimeMillis()
        remoteQuietUntil = remoteStamp + 4_000
        _state.update { it.copy(playing = true, buffering = false, positionMs = startMs, durationMs = cur.duration * 1000L) }
        remoteCall {
            play(it, ids)
            if (startMs > 5_000) command(it, "seek", startMs / 1000.0)
        }
        onTrackChanged?.invoke()
    }

    private fun remoteToggle() {
        if (_state.value.current == null) return
        if (!remoteSent) return sendQueueToRemote(_state.value.positionMs)
        remoteElapsed = remotePosition()
        remotePlaying = !remotePlaying
        remoteStamp = System.currentTimeMillis()
        remoteQuietUntil = remoteStamp + 3_000
        _state.update { it.copy(playing = remotePlaying) }
        remoteCall { command(it, if (remotePlaying) "play" else "pause") }
    }

    /** Wiedergabe auf ein Gerät übertragen; der Rechner pausiert. */
    fun connectRemote(target: RemoteTarget) {
        val s = _state.value
        if (s.remote?.id == target.id) return
        val position = s.positionMs
        val wasPlaying = s.playing
        if (s.remote != null) remoteCall { command(it, "stop") }
        stopFading()
        mp?.controls()?.setPause(true)
        remoteSent = false
        remotePlaying = false
        remoteElapsed = position
        remoteStamp = System.currentTimeMillis()
        _state.update { it.copy(remote = target, remoteVolume = null, remoteError = null, playing = false, buffering = false) }
        if (s.current != null && wasPlaying) sendQueueToRemote(position)
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val t = _state.value.remote ?: break
                val b = remoteBackend ?: break
                val st = try {
                    b.state(t.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                if (System.currentTimeMillis() < remoteQuietUntil || _state.value.remote?.id != t.id) continue
                _state.update { it.copy(remoteVolume = st.volume) }
                if (!remoteSent) continue
                remotePlaying = st.playing
                remoteElapsed = st.elapsedMs
                remoteStamp = System.currentTimeMillis()
                // Aktuellen Titel mitführen
                val local = st.queueIndex?.let { remoteStart + it }?.takeIf { it in _state.value.queue.indices }
                val old = _state.value.index
                _state.update {
                    it.copy(
                        index = local ?: it.index,
                        playing = st.playing,
                        positionMs = st.elapsedMs,
                        durationMs = st.durationMs?.takeIf { d -> d > 0 } ?: ((it.queue.getOrNull(local ?: it.index)?.duration ?: 0) * 1000L),
                    )
                }
                if (local != null && local != old) {
                    onTrackChanged?.invoke()
                    if (_sleep.value.endOfTrack && local > old) {
                        cancelSleep()
                        remoteToggle()
                    }
                }
            }
        }
    }

    /** Zurück auf diesen Rechner: Gerät anhalten, hier an derselben Stelle weiter. */
    fun disconnectRemote() {
        val s = _state.value
        if (s.remote == null) return
        val position = remotePosition()
        val wasPlaying = remoteSent && remotePlaying
        if (remoteSent) remoteCall { command(it, "pause") }
        pollJob?.cancel()
        pollJob = null
        remoteSent = false
        _state.update { it.copy(remote = null, remoteVolume = null, remoteError = null, playing = false) }
        if (s.current != null) load(s.index, if (s.durationMs > 0) position.coerceAtMost(s.durationMs) else position, paused = !wasPlaying)
    }

    fun setRemoteVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        _state.update { it.copy(remoteVolume = v) }
        remoteQuietUntil = System.currentTimeMillis() + 3_000
        remoteCall { command(it, "volume", v.toDouble()) }
    }

    fun release() {
        pollJob?.cancel()
        runCatching { scope.cancel() }
        runCatching { mp?.release() }
        runCatching { spare?.release() }
        runCatching { factory?.release() }
    }
}
