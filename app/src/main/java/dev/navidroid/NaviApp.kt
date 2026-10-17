package dev.navidroid

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dev.navidroid.data.SessionStore
import dev.navidroid.data.SubsonicClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class NaviApp : Application(), ImageLoaderFactory {
    lateinit var session: SessionStore
        private set
    lateinit var client: SubsonicClient
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
        client = SubsonicClient(http, session)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(http)
            .crossfade(true)
            .build()
}
