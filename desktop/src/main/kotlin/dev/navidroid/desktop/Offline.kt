package dev.navidroid.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.navidroid.data.Song

enum class DownloadState { None, Queued, Loading, Done }

/**
 * Offline-Musik: lädt Alben, Playlists und Mixe auf die Platte (Standard ~/Musik/NaviDroid).
 * Zustände sind Compose-State, damit Knöpfe und Badges sich selbst aktualisieren.
 */
class OfflineManager(private val app: DesktopApp) {
    /** file://-URL eines heruntergeladenen Titels oder null. */
    fun localUrl(songId: String): String? = null
    fun isSongDownloaded(songId: String): Boolean = false
    /** Zustand einer Sammlung (Album-, Playlist- oder Mix-ID). */
    fun state(collectionId: String): DownloadState = DownloadState.None
    /** kind: "album" | "playlist" | "mix" */
    fun download(kind: String, id: String, name: String, coverArt: String?, songs: List<Song>) {}
    fun remove(collectionId: String) {}
    val downloadedCount: Int get() = 0
    val totalBytes: Long get() = 0
}

/** Seite „Downloads“ (Design „Offline & Downloads“). */
@Composable
fun DownloadsScreen(app: DesktopApp) { Text("Downloads") }
