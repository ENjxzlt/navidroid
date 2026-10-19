package dev.navidroid.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App-Einstellungen, die in der Oberfläche sofort wirken (Compose-State, gespeichert in SharedPreferences). */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var _wifiBitrate by mutableIntStateOf(prefs.getInt("wifiBitrate", 0))
    private var _mobileBitrate by mutableIntStateOf(prefs.getInt("mobileBitrate", 192))
    private var _coverColors by mutableStateOf(prefs.getBoolean("coverColors", true))
    private var _maUrl by mutableStateOf(prefs.getString("maUrl", "") ?: "")
    private var _maToken by mutableStateOf(prefs.getString("maToken", "") ?: "")
    private var _autoplay by mutableStateOf(prefs.getBoolean("autoplay", true))
    private var _replayGain by mutableIntStateOf(prefs.getInt("replayGain", REPLAYGAIN_TRACK))
    private var _crossfade by mutableIntStateOf(prefs.getInt("crossfade", 0))
    private var _audiobookSpeed by androidx.compose.runtime.mutableFloatStateOf(prefs.getFloat("audiobookSpeed", 1f))

    /** Höchste Bitrate in kbit/s im WLAN bzw. mobil; 0 = Originaldatei. */
    var wifiBitrate: Int
        get() = _wifiBitrate
        set(v) { _wifiBitrate = v; prefs.edit().putInt("wifiBitrate", v).apply() }

    var mobileBitrate: Int
        get() = _mobileBitrate
        set(v) { _mobileBitrate = v; prefs.edit().putInt("mobileBitrate", v).apply() }

    var coverColors: Boolean
        get() = _coverColors
        set(v) { _coverColors = v; prefs.edit().putBoolean("coverColors", v).apply() }

    /** Music Assistant (für Lautsprecher wie Echo, Google Cast, DLNA). */
    var maUrl: String
        get() = _maUrl
        set(v) { _maUrl = v; prefs.edit().putString("maUrl", v).apply() }

    var maToken: String
        get() = _maToken
        set(v) { _maToken = v; prefs.edit().putString("maToken", v).apply() }

    /** Nach dem letzten Titel mit ähnlicher Musik weiterspielen. */
    var autoplay: Boolean
        get() = _autoplay
        set(v) { _autoplay = v; prefs.edit().putBoolean("autoplay", v).apply() }

    /** Lautstärke-Ausgleich: aus, pro Titel oder pro Album (ReplayGain-Tags). */
    var replayGain: Int
        get() = _replayGain
        set(v) { _replayGain = v; prefs.edit().putInt("replayGain", v).apply() }

    /** Überblenden zwischen Titeln in Sekunden, 0 = aus. */
    var crossfade: Int
        get() = _crossfade
        set(v) { _crossfade = v; prefs.edit().putInt("crossfade", v).apply() }

    /** Wiedergabegeschwindigkeit für Hörspiele (Musik läuft immer mit 1,0). */
    var audiobookSpeed: Float
        get() = _audiobookSpeed
        set(v) { _audiobookSpeed = v; prefs.edit().putFloat("audiobookSpeed", v).apply() }

    val maConfigured: Boolean get() = _maUrl.isNotBlank() && _maToken.isNotBlank()

    companion object {
        const val REPLAYGAIN_OFF = 0
        const val REPLAYGAIN_TRACK = 1
        const val REPLAYGAIN_ALBUM = 2
        val replayGainOptions = listOf(REPLAYGAIN_OFF to "Aus", REPLAYGAIN_TRACK to "Pro Titel", REPLAYGAIN_ALBUM to "Pro Album")
        val crossfadeOptions = listOf(0 to "Aus", 2 to "2 Sekunden", 4 to "4 Sekunden", 6 to "6 Sekunden", 8 to "8 Sekunden", 12 to "12 Sekunden")
        val bitrateOptions = listOf(0 to "Original", 320 to "320 kbit/s", 256 to "256 kbit/s", 192 to "192 kbit/s", 128 to "128 kbit/s")
        val speedOptions = listOf(0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
        fun label(kbps: Int) = bitrateOptions.firstOrNull { it.first == kbps }?.second ?: "$kbps kbit/s"
    }
}
