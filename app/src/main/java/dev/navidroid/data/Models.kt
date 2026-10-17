package dev.navidroid.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Envelope(@SerialName("subsonic-response") val response: SubsonicResponse)

@Serializable
data class SubsonicResponse(
    val status: String = "",
    val error: ApiError? = null,
    val albumList2: AlbumList? = null,
    val artists: ArtistsIndex? = null,
    val artist: Artist? = null,
    val album: Album? = null,
    val playlists: Playlists? = null,
    val playlist: Playlist? = null,
    val searchResult3: SearchResult? = null,
    val randomSongs: SongList? = null,
    val lyricsList: LyricsList? = null,
    val scanStatus: ScanStatus? = null,
)

@Serializable
data class ScanStatus(val scanning: Boolean = false, val count: Long = 0)

@Serializable
data class ApiError(val code: Int = 0, val message: String? = null)

@Serializable
data class AlbumList(val album: List<Album> = emptyList())

@Serializable
data class ArtistsIndex(val index: List<ArtistIndex> = emptyList())

@Serializable
data class ArtistIndex(val name: String = "", val artist: List<Artist> = emptyList())

@Serializable
data class Artist(
    val id: String,
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val album: List<Album> = emptyList(),
)

@Serializable
data class Album(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val starred: String? = null,
    val musicBrainzId: String? = null,
    val genre: String? = null,
    val playCount: Long = 0,
    val played: String? = null,
    val song: List<Song> = emptyList(),
)

@Serializable
data class Song(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val duration: Int = 0,
    val coverArt: String? = null,
    val year: Int? = null,
    val starred: String? = null,
    val genre: String? = null,
    val playCount: Long = 0,
    val played: String? = null,
)

@Serializable
data class Playlists(val playlist: List<Playlist> = emptyList())

@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val entry: List<Song> = emptyList(),
)

@Serializable
data class SearchResult(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

@Serializable
data class SongList(val song: List<Song> = emptyList())

// OpenSubsonic: getLyricsBySongId
@Serializable
data class LyricsList(val structuredLyrics: List<StructuredLyrics> = emptyList())

@Serializable
data class StructuredLyrics(
    val lang: String? = null,
    val synced: Boolean = false,
    val offset: Long = 0,
    val line: List<LyricsLine> = emptyList(),
)

@Serializable
data class LyricsLine(val start: Long? = null, val value: String = "")
