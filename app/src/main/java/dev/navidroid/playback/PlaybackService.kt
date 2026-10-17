package dev.navidroid.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val client = (application as NaviApp).client
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(Callback(client))
            .setSessionActivity(openApp)
            .build()
        Scrobbler(player, client, scope)
    }

    /**
     * MediaItems vom Controller kommen ohne URI an – die Stream-URL wird hier
     * aus der Song-ID (mediaId) aufgebaut.
     */
    private class Callback(private val client: SubsonicClient) : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { it.buildUpon().setUri(client.streamUrl(it.mediaId)).build() }.toMutableList()
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
        }
    }

    private fun send(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }
}
