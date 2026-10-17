package dev.navidroid

import android.os.Bundle
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
        val app = application as NaviApp
        val connection = PlayerConnection(this, app.client).also { player = it }
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
