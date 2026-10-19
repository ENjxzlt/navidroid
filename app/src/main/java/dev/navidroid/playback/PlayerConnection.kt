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

/** Ein Lautsprecher außerhalb des Handys (über Music Assistant). */
data class RemoteTarget(val id: String, val name: String)

data class RemoteState(val playing: Boolean, val elapsedMs: Long, val volume: Int?, val queueIndex: Int?)

interface RemoteBackend {
    suspend fun play(playerId: String, songIds: List<String>)
    suspend fun command(playerId: String, name: String, value: Double? = null)
    suspend fun state(playerId: String): RemoteState
}

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
    val remote: RemoteTarget? = null,
    val remoteVolume: Int? = null,
    val speed: Float = 1f,
)

/** UI-seitige Verbindung zum PlaybackService über einen MediaController. */
class PlayerConnection(context: Context, private val client: SubsonicClient, private val backend: RemoteBackend? = null) {
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

    // ---- Wiedergabe auf einem anderen Gerät ----
    private var remote: RemoteTarget? = null
    private var remotePlaying = false
    private var remoteVolume: Int? = null
    private var remoteElapsedMs = 0L
    private var remoteStamp = 0L
    /** Lokale Warteschlangen-Positionen in der Reihenfolge, in der sie an das Gerät geschickt wurden. */
    private var remoteOrder: List<Int> = emptyList()
    private var pollJob: kotlinx.coroutines.Job? = null
    private val _remoteError = MutableStateFlow<String?>(null)
    val remoteError: StateFlow<String?> = _remoteError.asStateFlow()

    val positionMs: Long
        get() = if (remote != null) {
            remoteElapsedMs + if (remotePlaying) System.currentTimeMillis() - remoteStamp else 0L
        } else controller?.currentPosition ?: 0L

    private fun remoteCall(block: suspend RemoteBackend.(String) -> Unit) {
        val target = remote ?: return
        val b = backend ?: return
        scope.launch {
            try {
                b.block(target.id)
                _remoteError.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _remoteError.value = e.message ?: "Gerät nicht erreichbar"
            }
        }
    }

    /** Reihenfolge ab dem aktuellen Titel (berücksichtigt Zufallswiedergabe). */
    private fun upcomingOrder(c: MediaController): List<Int> {
        val timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val order = mutableListOf<Int>()
        var i = c.currentMediaItemIndex
        while (i != C.INDEX_UNSET && order.size < 500) {
            order += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        }
        return order
    }

    private fun sendQueueToRemote(startPositionMs: Long = 0L) {
        val c = controller ?: return
        remoteOrder = upcomingOrder(c)
        val ids = remoteOrder.map { c.getMediaItemAt(it).mediaId }
        if (ids.isEmpty()) return
        remotePlaying = true
        remoteElapsedMs = startPositionMs
        remoteStamp = System.currentTimeMillis()
        remoteCall {
            play(it, ids)
            if (startPositionMs > 5_000) command(it, "seek", startPositionMs / 1000.0)
        }
        update()
    }

    /** Wiedergabe auf ein anderes Gerät übertragen. */
    fun connectRemote(target: RemoteTarget) {
        val c = controller ?: return
        val position = if (remote == null) c.currentPosition else positionMs
        val wasPlaying = if (remote == null) c.isPlaying else remotePlaying
        if (remote != null) remoteCall { command(it, "stop") }
        c.pause()
        remote = target
        if (c.mediaItemCount > 0 && wasPlaying) sendQueueToRemote(position) else update()
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                val t = remote ?: break
                val st = runCatching { backend?.state(t.id) }.getOrNull() ?: continue
                remotePlaying = st.playing
                remoteVolume = st.volume
                remoteElapsedMs = st.elapsedMs
                remoteStamp = System.currentTimeMillis()
                // Aktuellen Titel in der App mitführen
                st.queueIndex?.let { qi -> remoteOrder.getOrNull(qi) }?.let { local ->
                    if (local != c.currentMediaItemIndex && local < c.mediaItemCount) c.seekTo(local, 0L)
                }
                update()
            }
        }
    }

    /** Zurück aufs Handy: Gerät anhalten, hier an derselben Stelle weiterspielen. */
    fun disconnectRemote() {
        val c = controller ?: return
        if (remote == null) return
        val position = positionMs
        val wasPlaying = remotePlaying
        remoteCall { command(it, "pause") }
        pollJob?.cancel()
        remote = null
        remoteVolume = null
        if (c.mediaItemCount > 0) {
            c.seekTo(c.currentMediaItemIndex, position)
            c.prepare()
            if (wasPlaying) c.play()
        }
        update()
    }

    fun setRemoteVolume(volume: Int) {
        remoteVolume = volume
        remoteCall { command(it, "volume", volume.toDouble()) }
        update()
    }

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
            remote = remote,
            remoteVolume = remoteVolume,
            speed = c.playbackParameters.speed,
        ).let { st -> if (remote != null) st.copy(isPlaying = remotePlaying, isBuffering = false) else st }
    }

    /** [startPositionMs]: z. B. die gemerkte Stelle einer Hörspiel-Folge. */
    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, startPositionMs: Long = 0L) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(songs.indices)
        c.setMediaItems(songs.map { it.toMediaItem() }, start, if (shuffle) 0L else startPositionMs)
        if (remote != null) {
            sendQueueToRemote(if (shuffle) 0L else startPositionMs)
            return
        }
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
        if (remote != null) {
            remotePlaying = !remotePlaying
            remoteStamp = System.currentTimeMillis()
            remoteCall { command(it, if (remotePlaying) "play" else "pause") }
            update()
            return
        }
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun next() {
        if (remote != null) {
            controller?.seekToNextMediaItem()
            remoteElapsedMs = 0; remoteStamp = System.currentTimeMillis()
            remoteCall { command(it, "next") }
            return
        }
        controller?.seekToNext()
    }

    fun previous() {
        if (remote != null) {
            controller?.seekToPreviousMediaItem()
            remoteElapsedMs = 0; remoteStamp = System.currentTimeMillis()
            remoteCall { command(it, "previous") }
            return
        }
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        if (remote != null) {
            remoteElapsedMs = positionMs; remoteStamp = System.currentTimeMillis()
            remoteCall { command(it, "seek", positionMs / 1000.0) }
            return
        }
        controller?.seekTo(positionMs)
    }

    fun skipTo(index: Int) {
        val c = controller ?: return
        c.seekToDefaultPosition(index)
        if (remote != null) {
            sendQueueToRemote()
            return
        }
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

    /** Relativ springen (Hörspiele: ±30 s), innerhalb des Titels. */
    fun seekBy(deltaMs: Long) {
        val target = (positionMs + deltaMs).coerceAtLeast(0L)
        val d = state.value.durationMs
        seekTo(if (d > 0) target.coerceAtMost(d - 1_000L).coerceAtLeast(0L) else target)
    }

    /** Wiedergabegeschwindigkeit (der Dienst stellt sie bei Musik wieder auf 1,0). */
    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
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
        return toMediaItem(client)
    }

}
