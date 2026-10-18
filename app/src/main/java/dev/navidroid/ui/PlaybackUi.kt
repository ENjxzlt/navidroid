package dev.navidroid.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.navidroid.NaviApp
import dev.navidroid.data.Song
import dev.navidroid.playback.PlayerConnection
import dev.navidroid.playback.SleepTimer
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Song-Radio: der Titel und danach ähnliche Musik aus der Bibliothek. */
fun startRadio(app: NaviApp, player: PlayerConnection, seed: Song, context: Context) {
    MainScope().launch {
        try {
            val similar = app.client.similarSongs(seed.id, 50).filter { it.id != seed.id }
                .ifEmpty { seed.artistId?.let { app.client.similarSongsByArtist(it, 50) }.orEmpty().filter { it.id != seed.id } }
                .filter { app.offline.online || app.offline.isDownloaded(it.id) }
            if (similar.isEmpty()) {
                Toast.makeText(context, "Keine ähnlichen Titel gefunden", Toast.LENGTH_SHORT).show()
                return@launch
            }
            player.play(listOf(seed) + similar)
            Toast.makeText(context, "Radio zu „${seed.title}“", Toast.LENGTH_SHORT).show()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "Radio konnte nicht gestartet werden", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Text für den Menüeintrag, z. B. „Einschlaf-Timer · noch 23 Min.“ */
@Composable
fun sleepTimerLabel(): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(SleepTimer.endsAt) {
        while (SleepTimer.endsAt != null) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val end = SleepTimer.endsAt
    return when {
        SleepTimer.endOfTrack -> "Einschlaf-Timer · am Titelende"
        end != null -> "Einschlaf-Timer · noch ${((end - now) / 60_000 + 1).coerceAtLeast(1)} Min."
        else -> "Einschlaf-Timer …"
    }
}

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val options = listOf(5, 10, 15, 30, 45, 60, 90)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einschlaf-Timer") },
        text = {
            Column {
                options.forEach { m ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { SleepTimer.start(m); onDismiss() }.padding(vertical = 12.dp, horizontal = 8.dp)) {
                        Text("$m Minuten", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { SleepTimer.atEndOfTrack(); onDismiss() }.padding(vertical = 12.dp, horizontal = 8.dp)) {
                    Text("Am Ende des Titels", style = MaterialTheme.typography.bodyLarge)
                }
                if (SleepTimer.active) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { SleepTimer.cancel(); onDismiss() }.padding(vertical = 12.dp, horizontal = 8.dp)) {
                        Text("Timer ausschalten", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
                    }
                }
                Text("Die Musik wird zum Schluss leiser und hält dann an.", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}
