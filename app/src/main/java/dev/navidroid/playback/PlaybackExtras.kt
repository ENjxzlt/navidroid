package dev.navidroid.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.navidroid.NaviApp
import dev.navidroid.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Lautstärke-Ausgleich (ReplayGain) und Überblenden.
 *
 * ExoPlayer kann nur leiser machen (Lautstärke ≤ 1), deshalb wird ein Titel mit positivem Gain nicht
 * angehoben, laute Titel aber abgesenkt. Überblenden ist ein Aus- und Einblenden der Lautstärke am
 * Titelende bzw. -anfang – zwischen aufeinanderfolgenden Titeln desselben Albums nicht (Live-Alben,
 * DJ-Mixe und Konzeptalben laufen nahtlos weiter).
 */
class LoudnessAndFade(private val player: Player, private val settings: AppSettings, scope: CoroutineScope) : Player.Listener {
    private var gainFactor = 1f
    private var fadeInFromMs = -1L

    init {
        player.addListener(this)
        apply(player.currentMediaItem)
        scope.launch {
            while (isActive) {
                delay(100)
                tick()
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        apply(mediaItem)
        val auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        fadeInFromMs = if (auto && settings.crossfade > 0 && !continuesAlbum(previousIndex(), player.currentMediaItemIndex)) 0L else -1L
    }

    private fun apply(item: MediaItem?) {
        val extras = item?.mediaMetadata?.extras
        val gain = when (settings.replayGain) {
            AppSettings.REPLAYGAIN_TRACK -> extras?.getFloat(EXTRA_TRACK_GAIN, Float.NaN)
            AppSettings.REPLAYGAIN_ALBUM -> extras?.getFloat(EXTRA_ALBUM_GAIN, Float.NaN)?.takeIf { !it.isNaN() }
                ?: extras?.getFloat(EXTRA_TRACK_GAIN, Float.NaN)
            else -> null
        }
        gainFactor = if (gain == null || gain.isNaN()) 1f else 10.0.pow(gain / 20.0).toFloat().coerceIn(0.05f, 1f)
    }

    private fun previousIndex(): Int = player.currentTimeline.let { tl ->
        if (tl.isEmpty) -1 else tl.getPreviousWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
    }

    private fun continuesAlbum(from: Int, to: Int): Boolean {
        if (from < 0 || to < 0 || from >= player.mediaItemCount || to >= player.mediaItemCount) return false
        val a = player.getMediaItemAt(from).mediaMetadata.extras?.getString(EXTRA_ALBUM_ID)
        val b = player.getMediaItemAt(to).mediaMetadata.extras?.getString(EXTRA_ALBUM_ID)
        return a != null && a == b && !player.shuffleModeEnabled
    }

    private fun tick() {
        val fadeMs = settings.crossfade * 1000L
        var fade = 1f
        if (fadeMs > 0 && player.isPlaying) {
            val pos = player.currentPosition
            val dur = player.duration
            if (fadeInFromMs >= 0 && pos < fadeMs) fade = (pos.toFloat() / fadeMs).coerceIn(0.05f, 1f)
            else fadeInFromMs = -1L
            val next = if (player.hasNextMediaItem()) player.nextMediaItemIndex else -1
            if (dur > 0 && next >= 0 && dur - pos < fadeMs && !continuesAlbum(player.currentMediaItemIndex, next)) {
                fade = minOf(fade, ((dur - pos).toFloat() / fadeMs).coerceIn(0.05f, 1f))
            }
        }
        val target = gainFactor * fade
        if (kotlin.math.abs(player.volume - target) > 0.01f) player.volume = target
    }
}

/**
 * Autoplay: kurz bevor der letzte Titel der Warteschlange läuft, ähnliche Titel anhängen
 * (Navidrome getSimilarSongs – über das Plugin „NaviDroid Extras“ mit Deezer-Radio).
 */
class Autoplay(private val player: Player, private val app: NaviApp, private val scope: CoroutineScope) : Player.Listener {
    private var lastSeed: String? = null
    private var job: Job? = null

    init {
        player.addListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = check()

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = check()

    private fun check() {
        if (!app.settings.autoplay || player.repeatMode != Player.REPEAT_MODE_OFF || player.hasNextMediaItem()) return
        val item = player.currentMediaItem ?: return
        // Nach einer Hörspiel-Folge keine „ähnliche Musik“
        if (app.audiobooks.isAudiobook(item.mediaMetadata.extras?.getString(EXTRA_ALBUM_ID))) return
        if (item.mediaId == lastSeed || job?.isActive == true) return
        lastSeed = item.mediaId
        job = scope.launch {
            val have = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toHashSet()
            val similar = runCatching { app.client.similarSongs(item.mediaId, 30) }.getOrDefault(emptyList())
                .ifEmpty {
                    val artistId = item.mediaMetadata.extras?.getString(EXTRA_ARTIST_ID)
                    if (artistId != null) runCatching { app.client.similarSongsByArtist(artistId, 30) }.getOrDefault(emptyList()) else emptyList()
                }
                .ifEmpty { runCatching { app.client.randomSongs(20) }.getOrDefault(emptyList()) }
                .filter { it.id !in have }
                .let { app.audiobooks.withoutAudiobooks(it) }
                .filter { app.offline.online || app.offline.isDownloaded(it.id) }
                .take(20)
            if (similar.isEmpty() || player.hasNextMediaItem()) return@launch
            player.addMediaItems(similar.map { PlaybackService.withStream(app, it.toMediaItem(app.client)) })
        }
    }
}

/**
 * Hörspiele im Dienst: Position als Navidrome-Lesezeichen merken (alle 15 s, beim Pausieren und Springen,
 * beim Kapitelwechsel), am Ende der Folge löschen; eigene Wiedergabegeschwindigkeit nur für Hörspiele.
 */
class AudiobookPlayback(private val player: Player, private val app: NaviApp, private val scope: CoroutineScope) : Player.Listener {
    /** Laufendes Hörspiel-Kapitel (null = Musik). */
    private var current: dev.navidroid.data.Song? = null

    init {
        player.addListener(this)
        current = songOf(player.currentMediaItem)
        applySpeed()
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) save()
            }
        }
    }

    private fun songOf(item: MediaItem?): dev.navidroid.data.Song? {
        val md = item?.mediaMetadata ?: return null
        val albumId = md.extras?.getString(EXTRA_ALBUM_ID)
        if (!app.audiobooks.isAudiobook(albumId)) return null
        return dev.navidroid.data.Song(
            id = item.mediaId,
            title = md.title?.toString().orEmpty(),
            album = md.albumTitle?.toString(),
            albumId = albumId,
            artist = md.artist?.toString(),
            track = md.trackNumber,
            duration = ((md.durationMs ?: 0L) / 1000).toInt(),
            coverArt = md.extras?.getString(EXTRA_COVER_ART),
        )
    }

    private fun save() {
        val song = current ?: return
        val pos = player.currentPosition
        scope.launch { app.audiobooks.savePosition(song, pos) }
    }

    private fun applySpeed() {
        val target = if (current != null) app.settings.audiobookSpeed else 1f
        if (player.playbackParameters.speed != target) player.setPlaybackSpeed(target)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val prev = current
        val next = songOf(mediaItem)
        current = next
        applySpeed()
        if (prev != null && prev.albumId != next?.albumId && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            // Folge zu Ende gehört
            scope.launch { app.audiobooks.clear(prev.albumId!!, prev.id) }
        }
        if (next != null) {
            if (prev != null && prev.albumId == next.albumId && prev.id != next.id) {
                scope.launch { runCatching { app.client.deleteBookmark(prev.id) } }
            }
            save()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val song = current ?: return
        if (playbackState == Player.STATE_ENDED) scope.launch { app.audiobooks.clear(song.albumId!!, song.id) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying && player.playbackState != Player.STATE_ENDED) save()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) save()
    }

    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
        // In der App gewählte Geschwindigkeit merken; Musik bleibt bei 1,0
        if (current != null) app.settings.audiobookSpeed = playbackParameters.speed else applySpeed()
    }
}

/** Einschlaf-Timer; App und Dienst laufen im selben Prozess und teilen diesen Zustand. */
object SleepTimer {
    var endsAt by androidx.compose.runtime.mutableStateOf<Long?>(null)
        private set
    var endOfTrack by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun start(minutes: Int) {
        endOfTrack = false
        endsAt = System.currentTimeMillis() + minutes * 60_000L
    }

    fun atEndOfTrack() {
        endsAt = null
        endOfTrack = true
    }

    fun cancel() {
        endsAt = null
        endOfTrack = false
    }

    val active get() = endsAt != null || endOfTrack

    /** Im Dienst: pausiert zur gewählten Zeit (die letzten 10 s leiser werdend) bzw. am Titelende. */
    fun attach(player: Player, scope: CoroutineScope) {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (endOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    player.pause()
                    player.seekTo(0)
                    cancel()
                }
            }
        })
        scope.launch {
            while (isActive) {
                delay(500)
                val end = endsAt ?: continue
                val left = end - System.currentTimeMillis()
                if (left <= 0) {
                    player.pause()
                    player.volume = 1f
                    cancel()
                } else if (left < 10_000 && player.isPlaying) {
                    player.volume = minOf(player.volume, left / 10_000f)
                }
            }
        }
    }
}

/**
 * Zufallswiedergabe, bei der die Warteschlange stimmt: Statt intern eine verdeckte Reihenfolge zu
 * würfeln, wird beim Einschalten die Liste selbst gemischt (aktueller Titel vorne, alle übrigen
 * zufällig dahinter); ExoPlayer spielt mit einer festen Reihenfolge immer genau die Liste ab. Damit
 * zeigt die Warteschlange, was wirklich als Nächstes kommt, und Verschieben/„Als Nächstes“ wirken auch
 * bei Zufall. Beim Ausschalten kommt die ursprüngliche Reihenfolge zurück.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ShuffleQueue(private val player: androidx.media3.exoplayer.ExoPlayer) : Player.Listener {
    /** Reihenfolge vor dem Mischen. */
    private var original: List<MediaItem> = emptyList()
    private var busy = false

    init {
        player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.UnshuffledShuffleOrder(player.mediaItemCount))
        player.addListener(this)
        if (player.shuffleModeEnabled) shuffle()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        if (busy) return
        if (shuffleModeEnabled) shuffle() else restore()
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        if (busy || !player.shuffleModeEnabled || reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        // Ganz neue Liste bei eingeschaltetem Zufall (z. B. aus Android Auto): ebenfalls mischen.
        val items = items()
        if (items.isNotEmpty() && items.none { it in original }) shuffle()
    }

    private fun items() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

    private fun shuffle() {
        val items = items()
        original = items
        if (items.size < 2) return
        val cur = player.currentMediaItemIndex.coerceIn(items.indices)
        val rest = items.filterIndexed { i, _ -> i != cur }.shuffled()
        edit(cur, emptyList(), rest)
    }

    private fun restore() {
        val items = items()
        val orig = original
        original = emptyList()
        if (items.size < 2 || orig.isEmpty()) return
        val cur = player.currentMediaItemIndex.coerceIn(items.indices)
        val current = items[cur]
        // Neu hinzugekommene Titel: direkt hinter dem aktuellen = „Als Nächstes“ (bleibt dort), sonst ans Ende.
        val left = orig.toMutableList()
        val known = items.map { left.remove(it) }
        val playNext = items.indices.drop(cur + 1).takeWhile { !known[it] }.map { items[it] }
        val appended = items.indices.filter { !known[it] && it != cur && items[it] !in playNext }.map { items[it] }
        val kept = items.filterIndexed { i, _ -> known[i] }.toMutableList()
        // Ursprüngliche Reihenfolge der noch vorhandenen Titel
        val ordered = orig.filter { kept.remove(it) }
        val pos = ordered.indexOf(current)
        val before = if (pos >= 0) ordered.take(pos) else emptyList()
        val after = if (pos >= 0) ordered.drop(pos + 1) else ordered.filter { it != current }
        edit(cur, before, playNext + after + appended)
    }

    /** Ordnet die Liste um den laufenden Titel herum neu, ohne ihn anzutasten (kein Aussetzer). */
    private fun edit(cur: Int, before: List<MediaItem>, after: List<MediaItem>) {
        busy = true
        try {
            if (cur + 1 < player.mediaItemCount) player.removeMediaItems(cur + 1, player.mediaItemCount)
            if (cur > 0) player.removeMediaItems(0, cur)
            if (after.isNotEmpty()) player.addMediaItems(1, after)
            if (before.isNotEmpty()) player.addMediaItems(0, before)
        } finally {
            busy = false
        }
    }
}
