package dev.navidroid.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.navidroid.MainActivity
import dev.navidroid.NaviApp
import dev.navidroid.data.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val app = application as NaviApp
        val client = app.client
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Heruntergeladene Titel kommen aus dem Offline-Speicher; gestreamte Titel werden dort nicht abgelegt.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    CacheDataSource.Factory()
                        .setCache(app.offline.cache)
                        .setUpstreamDataSourceFactory(
                            DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
                        )
                        .setCacheWriteDataSinkFactory(null)
                )
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // MediaLibrarySession: zusätzlich Menüs für Android Auto und Sprachsteuerung (siehe AutoLibrary)
        session = MediaLibrarySession.Builder(this, player, AutoLibrary(app, scope))
            .setSessionActivity(openApp)
            .build()
        Scrobbler(player, client, scope) { item, ts -> recordHistory(app, item, ts) }
        LoudnessAndFade(player, app.settings, scope)
        ShuffleQueue(player)
        AudiobookPlayback(player, app, scope)
        Autoplay(player, app, scope)
        SleepTimer.attach(player, scope)
        NowPlayingWidget.attach(this, player, scope)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // Knöpfe im Widget
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = session?.player
        when (intent?.action) {
            NowPlayingWidget.ACTION_TOGGLE -> player?.let {
                if (it.isPlaying) it.pause() else {
                    if (it.playbackState == Player.STATE_IDLE) it.prepare()
                    it.play()
                }
            }
            NowPlayingWidget.ACTION_NEXT -> player?.seekToNext()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        /** Mobilfunk (getaktet) bekommt die mobile Qualität, sonst die WLAN-Qualität. */
        fun currentBitrate(app: NaviApp): Int {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            return if (cm?.isActiveNetworkMetered == true) app.settings.mobileBitrate else app.settings.wifiBitrate
        }

        /** Stream-URL setzen; gleicher Cache-Schlüssel wie beim Download – dann liest der Player die Datei vom Gerät. */
        fun withStream(app: NaviApp, item: MediaItem, bitrate: Int = currentBitrate(app)): MediaItem =
            item.buildUpon()
                .setUri(app.client.streamUrl(item.mediaId, bitrate))
                .setCustomCacheKey(if (app.offline.isDownloaded(item.mediaId)) item.mediaId else null)
                .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** Meldet "Spielt gerade" beim Titelwechsel und scrobbelt nach der Hälfte (max. 4 Minuten). */
private class Scrobbler(
    private val player: Player,
    private val client: SubsonicClient,
    private val scope: CoroutineScope,
    private val onPlayed: suspend (MediaItem, Long) -> Unit,
) : Player.Listener {
    private var currentId: String? = null
    private var submitted = false

    init {
        player.addListener(this)
        scope.launch {
            while (isActive) {
                delay(5_000)
                check()
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        currentId = mediaItem?.mediaId
        submitted = false
        currentId?.let { id -> send { client.scrobble(id, submission = false) } }
    }

    private fun check() {
        val id = currentId ?: return
        if (submitted || !player.isPlaying) return
        val duration = player.duration.takeIf { it > 0 } ?: return
        if (player.currentPosition >= minOf(duration / 2, 240_000L)) {
            submitted = true
            val now = System.currentTimeMillis()
            send { client.scrobble(id, submission = true, timeMs = now) }
            player.currentMediaItem?.let { item -> send { onPlayed(item, now) } }
        }
    }

    private fun send(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }
}

/** Eigener Hörverlauf im Helfer (für Lieblinge, Mixe, Daylist und „Zuletzt gespielt“). */
private suspend fun recordHistory(app: NaviApp, item: MediaItem, ts: Long) {
    val user = app.session.current?.username ?: return
    if (!app.helper.configured) return
    val md = item.mediaMetadata
    val song = dev.navidroid.data.Song(
        id = item.mediaId,
        title = md.title?.toString().orEmpty(),
        artist = md.artist?.toString(),
        album = md.albumTitle?.toString(),
        albumId = md.extras?.getString(EXTRA_ALBUM_ID),
        coverArt = md.extras?.getString(EXTRA_COVER_ART),
        genre = md.extras?.getString(EXTRA_GENRE),
        duration = ((md.durationMs ?: 0L) / 1000).toInt(),
    )
    runCatching { app.helper.recordPlay(user, song, ts) }
}
