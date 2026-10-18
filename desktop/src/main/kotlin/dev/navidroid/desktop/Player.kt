package dev.navidroid.desktop

import dev.navidroid.data.Song
import dev.navidroid.data.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

enum class Repeat { Off, All, One }

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
) {
    val current: Song? get() = queue.getOrNull(index)
}

/**
 * Wiedergabe über libVLC (muss installiert sein: Paket „vlc“). Streams kommen direkt von Navidrome
 * in Originalqualität. Meldet „Spielt gerade“ und Scrobbles wie die App.
 */
class DesktopPlayer(private val client: SubsonicClient, private val settings: () -> DesktopSettings) {
    private val _state = MutableStateFlow(PlayerState(volume = settings().volume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    private var factory: MediaPlayerFactory? = null
    private var mp: MediaPlayer? = null
    private var original: List<Song> = emptyList()
    private var submitted = false
    var onTrackChanged: (() -> Unit)? = null
    /** Titel zu Ende gehört (für den Hörverlauf im Helfer). */
    var onPlayed: ((Song, Long) -> Unit)? = null

    init {
        try {
            val f = MediaPlayerFactory("--no-video", "--quiet", "--network-caching=3000")
            val p = f.mediaPlayers().newMediaPlayer()
            p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer) {
                    scope.launch { onFinished() }
                }

                override fun playing(mediaPlayer: MediaPlayer) {
                    scope.launch { _state.update { it.copy(playing = true, buffering = false, error = null) } }
                }

                override fun paused(mediaPlayer: MediaPlayer) {
                    scope.launch { _state.update { it.copy(playing = false) } }
                }

                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                    scope.launch { _state.update { it.copy(buffering = newCache < 100f) } }
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    scope.launch {
                        _state.update { it.copy(error = "Titel konnte nicht abgespielt werden", playing = false) }
                        onFinished()
                    }
                }
            })
            p.audio().setVolume(_state.value.volume)
            factory = f
            mp = p
        } catch (e: Throwable) {
            _state.update { it.copy(error = "VLC nicht gefunden – bitte das Paket „vlc“ installieren (${e.message})") }
        }
        scope.launch {
            while (isActive) {
                delay(250)
                val p = mp ?: continue
                val pos = p.status().time().coerceAtLeast(0)
                val len = p.status().length().takeIf { it > 0 } ?: ((_state.value.current?.duration ?: 0) * 1000L)
                _state.update { it.copy(positionMs = pos, durationMs = len) }
                scrobbleCheck(pos, len)
            }
        }
    }

    val available get() = mp != null

    fun play(songs: List<Song>, start: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        original = songs
        val queue = if (shuffle) songs.shuffled() else songs
        val index = if (shuffle) 0 else start.coerceIn(songs.indices)
        _state.update { it.copy(queue = queue, index = index, shuffle = shuffle) }
        load(index)
    }

    fun playNext(songs: List<Song>) {
        if (_state.value.current == null) return play(songs)
        _state.update { s -> s.copy(queue = s.queue.toMutableList().apply { addAll(s.index + 1, songs) }) }
    }

    fun enqueue(songs: List<Song>) {
        if (_state.value.current == null) return play(songs)
        _state.update { s -> s.copy(queue = s.queue + songs) }
    }

    fun skipTo(index: Int) {
        if (index in _state.value.queue.indices) {
            _state.update { it.copy(index = index) }
            load(index)
        }
    }

    fun removeAt(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices || index == s.index) return
        _state.update { it.copy(queue = it.queue.filterIndexed { i, _ -> i != index }, index = if (index < it.index) it.index - 1 else it.index) }
    }

    fun toggle() {
        val p = mp ?: return
        when {
            _state.value.current == null -> Unit
            p.status().isPlaying -> p.controls().pause()
            p.status().state() == uk.co.caprica.vlcj.player.base.State.ENDED || p.status().state() == uk.co.caprica.vlcj.player.base.State.STOPPED -> load(_state.value.index)
            else -> p.controls().play()
        }
    }

    fun pause() = mp?.controls()?.setPause(true)

    fun resume() = mp?.controls()?.setPause(false)

    fun next() {
        val s = _state.value
        val n = s.index + 1
        when {
            n < s.queue.size -> skipTo(n)
            s.repeat == Repeat.All && s.queue.isNotEmpty() -> skipTo(0)
        }
    }

    fun previous() {
        val s = _state.value
        if (s.positionMs > 3_000 || s.index == 0) seek(0) else skipTo(s.index - 1)
    }

    fun seek(ms: Long) {
        mp?.controls()?.setTime(ms.coerceAtLeast(0))
        _state.update { it.copy(positionMs = ms) }
    }

    fun setVolume(v: Int) {
        val vol = v.coerceIn(0, 100)
        mp?.audio()?.setVolume(vol)
        _state.update { it.copy(volume = vol) }
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
    }

    fun cycleRepeat() = _state.update { it.copy(repeat = Repeat.entries[(it.repeat.ordinal + 1) % Repeat.entries.size]) }

    private fun load(index: Int) {
        val p = mp ?: return
        val song = _state.value.queue.getOrNull(index) ?: return
        submitted = false
        val url = runCatching { client.streamUrl(song.id, 0) }.getOrNull() ?: return
        _state.update { it.copy(positionMs = 0, durationMs = song.duration * 1000L, buffering = true, error = null) }
        p.media().play(url)
        onTrackChanged?.invoke()
        scope.launch { runCatching { client.scrobble(song.id, submission = false) } }
        autoplayIfLast()
    }

    private fun onFinished() {
        val s = _state.value
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

    fun release() {
        runCatching { mp?.release() }
        runCatching { factory?.release() }
    }
}
