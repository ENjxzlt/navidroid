package dev.navidroid.data

/**
 * Ersatz-Cover aus dem Cover Art Archive (MusicBrainz), wenn Navidrome für ein Album kein Bild hat.
 * Navidrome liefert die MusicBrainz-Release-ID eines Albums (OpenSubsonic `musicBrainzId`).
 */
class CoverFallback(private val client: SubsonicClient) {
    private val albumMbids = HashMap<String, String?>()

    fun forMbid(mbid: String?, size: Int = 500): String? =
        mbid?.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release/$it/front-${caaSize(size)}" }

    /** Sucht die MusicBrainz-ID über das Album (einmal pro Album, danach aus dem Speicher). */
    suspend fun forAlbum(albumId: String?, size: Int = 500): String? {
        if (albumId.isNullOrBlank()) return null
        val cached = synchronized(albumMbids) { albumMbids[albumId] to albumMbids.containsKey(albumId) }
        val mbid = if (cached.second) {
            cached.first
        } else {
            runCatching { client.album(albumId).musicBrainzId }.getOrNull()
                .also { synchronized(albumMbids) { albumMbids[albumId] = it } }
        }
        return forMbid(mbid, size)
    }

    private fun caaSize(size: Int) = when {
        size <= 250 -> 250
        size <= 500 -> 500
        else -> 1200
    }
}
