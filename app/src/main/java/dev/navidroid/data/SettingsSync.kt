package dev.navidroid.data

import dev.navidroid.NaviApp
import dev.navidroid.lidarr.RequestSource
import dev.navidroid.lidarr.SyncFormat
import dev.navidroid.lidarr.SyncFormat.bool
import dev.navidroid.lidarr.SyncFormat.int
import dev.navidroid.lidarr.SyncFormat.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gleicht die Einstellungen über den Helfer mit dem Linux-Client ab (neuere gewinnen):
 * Lidarr, Music Assistant, Anfrage-Quelle, Qualität, Autoplay, Lautstärke-Ausgleich, Überblenden, Songtexte.
 */
class SettingsSync(private val app: NaviApp) {
    private val prefs = app.getSharedPreferences("sync", android.content.Context.MODE_PRIVATE)
    private var localStamp: Long
        get() = prefs.getLong("updatedAt", 0L)
        set(v) = prefs.edit().putLong("updatedAt", v).apply()

    private fun user() = app.session.current?.username

    private fun collect(): JsonObject = buildJsonObject {
        app.lidarrStore.config?.let { put("lidarr", SyncFormat.lidarr(it)) }
        val s = app.settings
        if (s.maUrl.isNotBlank()) { put("maUrl", s.maUrl); put("maToken", s.maToken) }
        put("requestSource", app.helper.requestSource.name)
        put("wifiBitrate", s.wifiBitrate)
        put("mobileBitrate", s.mobileBitrate)
        put("autoplay", s.autoplay)
        put("replayGain", s.replayGain)
        put("crossfade", s.crossfade)
        put("coverColors", s.coverColors)
        put("onlineLyrics", app.lyrics.onlineLyrics)
    }

    private fun apply(d: JsonObject) {
        SyncFormat.lidarr(d["lidarr"] as? JsonObject)?.let { app.lidarrStore.save(it) }
        val s = app.settings
        d.str("maUrl")?.let { s.maUrl = it }
        d.str("maToken")?.let { s.maToken = it }
        d.str("requestSource")?.let { v -> runCatching { app.helper.requestSource = RequestSource.valueOf(v) } }
        d.int("wifiBitrate")?.let { s.wifiBitrate = it }
        d.int("mobileBitrate")?.let { s.mobileBitrate = it }
        d.bool("autoplay")?.let { s.autoplay = it }
        d.int("replayGain")?.let { s.replayGain = it }
        d.int("crossfade")?.let { s.crossfade = it }
        d.bool("coverColors")?.let { s.coverColors = it }
        d.bool("onlineLyrics")?.let { app.lyrics.onlineLyrics = it }
    }

    /** Beim Start: neuere Einstellungen vom Helfer übernehmen, sonst die eigenen hochladen. */
    suspend fun sync() {
        val user = user() ?: return
        if (!app.helper.configured) return
        val (remote, ts) = runCatching { app.helper.getSettings(user) }.getOrNull() ?: return
        if (ts > localStamp) {
            apply(remote)
            localStamp = ts
        } else {
            push()
        }
    }

    /** Nach Änderungen in den Einstellungen. */
    suspend fun push() {
        val user = user() ?: return
        if (!app.helper.configured) return
        val now = System.currentTimeMillis()
        runCatching { app.helper.putSettings(user, collect(), now) }.onSuccess { localStamp = now }
    }
}
