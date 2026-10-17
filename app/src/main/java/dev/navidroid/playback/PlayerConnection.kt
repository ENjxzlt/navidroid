package dev.navidroid.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class QueueEntry(val title: String, val artist: String?)

data class PlayerUiState(
    val hasItem: Boolean = false,
    val mediaId: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val starred: Boolean = false,
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Favoriten-Status der bekannten Titel; wird beim Einreihen aus den Song-Daten befüllt. */
    private val starredIds = HashSet<String>()
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
        val item = c.currentMediaItem
        _state.value = PlayerUiState(
            hasItem = item != null,
            mediaId = item?.mediaId,
            albumId = md.extras?.getString(EXTRA_ALBUM_ID),
            artistId = md.extras?.getString(EXTRA_ARTIST_ID),
            starred = item?.mediaId in starredIds,
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

    fun move(from: Int, to: Int) {
        if (from != to) controller?.moveMediaItem(from, to)
    }

    /** Entfernt alle Titel nach dem aktuellen. */
    fun clearUpcoming() {
        val c = controller ?: return
        val start = c.currentMediaItemIndex + 1
        if (start < c.mediaItemCount) c.removeMediaItems(start, c.mediaItemCount)
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

    /** Setzt bzw. entfernt den aktuellen Titel aus den Favoriten (optimistisch, bei Fehler zurück). */
    fun toggleStarCurrent() {
        val id = controller?.currentMediaItem?.mediaId ?: return
        val star = id !in starredIds
        setStarredLocal(id, star)
        scope.launch {
            runCatching { client.setStarred(id, star) }.onFailure { setStarredLocal(id, !star) }
        }
    }

    private fun setStarredLocal(id: String, star: Boolean) {
        if (star) starredIds += id else starredIds -= id
        update()
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
    }

    fun release() {
        scope.cancel()
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
    }

    private fun Song.toMediaItem(): MediaItem {
        if (starred != null) starredIds += id
        return buildItem()
    }

    private fun Song.buildItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(client.coverArtUrl(coverArt, 600)?.toUri())
                // Unbekannte Länge (0) nicht vorgeben, sonst überdeckt sie die echte Länge aus der Datei.
                .setDurationMs(if (duration > 0) duration * 1000L else null)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(Bundle().apply {
                    putString(EXTRA_ALBUM_ID, albumId)
                    putString(EXTRA_ARTIST_ID, artistId)
                })
                .build()
        )
        .build()

    companion object {
        private const val EXTRA_ALBUM_ID = "albumId"
        private const val EXTRA_ARTIST_ID = "artistId"
    }
}
