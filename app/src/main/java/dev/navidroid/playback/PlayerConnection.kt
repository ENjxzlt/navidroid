package dev.navidroid.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.navidroid.data.Song
import dev.navidroid.data.SubsonicClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QueueEntry(val title: String, val artist: String?)

data class PlayerUiState(
    val hasItem: Boolean = false,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = 0,
)

/** UI-seitige Verbindung zum PlaybackService über einen MediaController. */
class PlayerConnection(context: Context, private val client: SubsonicClient) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val future: ListenableFuture<MediaController> = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }

    init {
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let {
                controller = it
                it.addListener(listener)
                update()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0L

    private fun update() {
        val c = controller ?: return
        val md = c.mediaMetadata
        val queue = (0 until c.mediaItemCount).map { i ->
            val m = c.getMediaItemAt(i).mediaMetadata
            QueueEntry(m.title?.toString().orEmpty(), m.artist?.toString())
        }
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: md.durationMs ?: 0L
        _state.value = PlayerUiState(
            hasItem = c.currentMediaItem != null,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString(),
            album = md.albumTitle?.toString(),
            artworkUri = md.artworkUri,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = duration,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
        )
    }

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(songs.indices)
        c.setMediaItems(songs.map { it.toMediaItem() }, start, 0L)
        c.prepare()
        c.play()
    }

    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(songs)
        c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
    }

    fun enqueue(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(songs)
        c.addMediaItems(songs.map { it.toMediaItem() })
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun next() {
        controller?.seekToNext()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun skipTo(index: Int) {
        val c = controller ?: return
        c.seekToDefaultPosition(index)
        c.play()
    }

    fun removeAt(index: Int) {
        controller?.removeMediaItem(index)
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
    }

    fun release() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
    }

    private fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(client.coverArtUrl(coverArt, 600)?.toUri())
                .setDurationMs(duration * 1000L)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()
}
