package dev.navidroid

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.navidroid.playback.PlayerConnection
import dev.navidroid.ui.LocalPlayer
import dev.navidroid.ui.NaviRoot
import dev.navidroid.ui.theme.NaviTheme

class MainActivity : ComponentActivity() {
    private var player: PlayerConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val app = application as NaviApp
        val connection = PlayerConnection(this, app.client, MusicAssistantBackend(app)).also { player = it }
        setContent {
            NaviTheme {
                CompositionLocalProvider(LocalPlayer provides connection) {
                    NaviRoot(app)
                }
            }
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

/** Fernwiedergabe über Music Assistant, angesprochen über den YouTube-Helfer im Heimnetz. */
private class MusicAssistantBackend(private val app: NaviApp) : dev.navidroid.playback.RemoteBackend {
    private fun ma() = app.settings.takeIf { it.maConfigured } ?: throw IllegalStateException("Music Assistant ist nicht eingerichtet")

    override suspend fun play(playerId: String, songIds: List<String>) {
        val s = ma()
        val creds = app.session.current ?: throw IllegalStateException("Nicht angemeldet")
        app.helper.maPlay(s.maUrl, s.maToken, playerId, creds, songIds)
    }

    override suspend fun command(playerId: String, name: String, value: Double?) {
        val s = ma()
        app.helper.maCommand(s.maUrl, s.maToken, playerId, name, value)
    }

    override suspend fun state(playerId: String): dev.navidroid.playback.RemoteState {
        val s = ma()
        val p = app.helper.maState(s.maUrl, s.maToken, playerId)
        return dev.navidroid.playback.RemoteState(
            playing = p.state == "playing",
            elapsedMs = ((p.elapsed ?: 0.0) * 1000).toLong(),
            volume = p.volume?.toInt(),
            queueIndex = p.queueIndex,
        )
    }
}
