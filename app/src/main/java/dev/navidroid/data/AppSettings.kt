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

    val maConfigured: Boolean get() = _maUrl.isNotBlank() && _maToken.isNotBlank()

    companion object {
        val bitrateOptions = listOf(0 to "Original", 320 to "320 kbit/s", 256 to "256 kbit/s", 192 to "192 kbit/s", 128 to "128 kbit/s")
        fun label(kbps: Int) = bitrateOptions.firstOrNull { it.first == kbps }?.second ?: "$kbps kbit/s"
    }
}
