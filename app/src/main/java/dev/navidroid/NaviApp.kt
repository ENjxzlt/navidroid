package dev.navidroid

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dev.navidroid.data.AppSettings
import dev.navidroid.data.CoverFallback
import dev.navidroid.data.LyricsRepository
import dev.navidroid.data.MixRepository
import dev.navidroid.data.SessionStore
import dev.navidroid.data.SubsonicClient
import dev.navidroid.lidarr.HelperClient
import dev.navidroid.lidarr.HelperStore
import dev.navidroid.lidarr.LidarrClient
import dev.navidroid.lidarr.LidarrStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class NaviApp : Application(), ImageLoaderFactory {
    lateinit var session: SessionStore
        private set
    lateinit var client: SubsonicClient
        private set
    lateinit var lyrics: LyricsRepository
        private set
    lateinit var covers: CoverFallback
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var mixes: MixRepository
        private set
    lateinit var lidarrStore: LidarrStore
        private set
    lateinit var lidarr: LidarrClient
        private set
    lateinit var helperStore: HelperStore
        private set
    lateinit var helper: HelperClient
        private set

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        session = SessionStore(this)
        settings = AppSettings(this)
        client = SubsonicClient(http, session)
        lyrics = LyricsRepository(this, http, client)
        covers = CoverFallback(client, http)
        mixes = MixRepository(client) { helper.artistGenres() }
        lidarrStore = LidarrStore(this)
        lidarr = LidarrClient(http, lidarrStore)
        helperStore = HelperStore(this)
        helper = HelperClient(http, helperStore)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(http)
            .crossfade(true)
            .build()
}
