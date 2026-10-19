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
import dev.navidroid.offline.OfflineManager
import kotlinx.coroutines.launch
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
    lateinit var offline: OfflineManager
        private set
    lateinit var daylist: dev.navidroid.data.DaylistRepository
        private set
    lateinit var uploads: dev.navidroid.upload.UploadManager
        private set
    lateinit var settingsSync: dev.navidroid.data.SettingsSync
        private set
    lateinit var audiobooks: dev.navidroid.data.AudiobookRepository
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
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        lyrics = LyricsRepository(http, client, filesDir, { prefs.getBoolean("lrclib", true) }, { prefs.edit().putBoolean("lrclib", it).apply() })
        covers = CoverFallback(client, http)
        audiobooks = dev.navidroid.data.AudiobookRepository(this, client)
        mixes = MixRepository(
            client,
            { helper.artistGenres() },
            {
                val user = session.current?.username
                if (user != null && helper.configured) helper.historySummary(user).counts else emptyMap()
            },
            { audiobooks.ensure() },
        )
        lidarrStore = LidarrStore(dev.navidroid.data.PrefsKeyValue(this, "lidarr"))
        lidarr = LidarrClient(http, lidarrStore)
        helperStore = HelperStore(dev.navidroid.data.PrefsKeyValue(this, "youtube_helper"))
        helper = HelperClient(http, helperStore)
        offline = OfflineManager(this).also { it.start() }
        daylist = dev.navidroid.data.DaylistRepository(this)
        uploads = dev.navidroid.upload.UploadManager(this)
        settingsSync = dev.navidroid.data.SettingsSync(this)
        kotlinx.coroutines.MainScope().launch { settingsSync.sync() }
        kotlinx.coroutines.MainScope().launch { if (session.current != null) audiobooks.ensure() }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(http)
            .crossfade(true)
            .build()
}
