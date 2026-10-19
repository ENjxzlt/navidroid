package dev.navidroid.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.navidroid.data.Song
import dev.navidroid.data.SubsonicClient

const val EXTRA_ALBUM_ID = "albumId"
const val EXTRA_ARTIST_ID = "artistId"
const val EXTRA_TRACK_GAIN = "trackGain"
const val EXTRA_ALBUM_GAIN = "albumGain"
const val EXTRA_COVER_ART = "coverArt"
const val EXTRA_GENRE = "genre"

/** MediaItem für einen Navidrome-Titel; die Stream-URL setzt der PlaybackService. */
fun Song.toMediaItem(client: SubsonicClient): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setTrackNumber(track)
            .setArtworkUri(client.coverArtUrl(coverArt, 600)?.toUri())
            // Unbekannte Länge (0) nicht vorgeben, sonst überdeckt sie die echte Länge aus der Datei.
            .setDurationMs(if (duration > 0) duration * 1000L else null)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(Bundle().apply {
                putString(EXTRA_ALBUM_ID, albumId)
                putString(EXTRA_ARTIST_ID, artistId)
                putString(EXTRA_COVER_ART, coverArt)
                putString(EXTRA_GENRE, genre)
                replayGain?.trackGain?.let { putFloat(EXTRA_TRACK_GAIN, it) }
                replayGain?.albumGain?.let { putFloat(EXTRA_ALBUM_GAIN, it) }
            })
            .build()
    )
    .build()
