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
                .filter { app.offline.online || app.offline.isDownloaded(it.id) }
                .take(20)
            if (similar.isEmpty() || player.hasNextMediaItem()) return@launch
            player.addMediaItems(similar.map { PlaybackService.withStream(app, it.toMediaItem(app.client)) })
        }
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
