package dev.navidroid.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.navidroid.NaviApp
import dev.navidroid.lidarr.MaPlayer
import dev.navidroid.playback.RemoteTarget
import dev.navidroid.ui.theme.Navi

/** Ob das Geräte-Blatt offen ist (aus Mini-Player und Player erreichbar). */
object DeviceBus {
    var open by mutableStateOf(false)
}

private fun iconFor(p: MaPlayer): ImageVector {
    val provider = (p.provider ?: "").lowercase()
    val type = (p.type ?: "").lowercase()
    return when {
        "cast" in provider || "chromecast" in provider -> Icons.Rounded.Cast
        "tv" in type || "dlna" in provider -> Icons.Rounded.Tv
        else -> Icons.Rounded.Speaker
    }
}

private fun kindFor(p: MaPlayer): String {
    val provider = (p.provider ?: "").lowercase()
    return when {
        "alexa" in provider -> "Echo / Alexa"
        "cast" in provider -> "Google Cast"
        "dlna" in provider -> "DLNA"
        "airplay" in provider -> "AirPlay"
        "sonos" in provider -> "Sonos"
        "squeezelite" in provider || "slimproto" in provider -> "Squeezebox"
        "hass" in provider -> "Home Assistant"
        else -> p.provider ?: "Music Assistant"
    } + " · über Music Assistant"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(app: NaviApp) {
    val player = LocalPlayer.current
    val context = LocalContext.current
    val s by player.state.collectAsStateWithLifecycle()
    val error by player.remoteError.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary
    val maReady = app.settings.maConfigured && app.helper.configured
    val players = rememberLoad("ma-players:$maReady") {
        if (maReady) app.helper.maPlayers(app.settings.maUrl, app.settings.maToken) else emptyList()
    }
    val close = { DeviceBus.open = false }

    ModalBottomSheet(onDismissRequest = close, containerColor = Navi.Surface) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AKTUELLES GERÄT", style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(accent), contentAlignment = Alignment.Center) {
                    Icon(if (s.remote != null) Icons.Rounded.Speaker else Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(s.remote?.name ?: "Dieses Handy", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = accent)
                    Text(if (s.remote != null) "über Music Assistant" else "Lautsprecher, Kopfhörer oder Bluetooth", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                }
                Icon(Icons.Rounded.GraphicEq, null, tint = accent)
            }
            if (s.remote != null) {
                var volume by remember(s.remote) { mutableFloatStateOf((s.remoteVolume ?: 50) / 100f) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Lautstärke", tint = Navi.Muted, modifier = Modifier.size(20.dp))
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        onValueChangeFinished = { player.setRemoteVolume((volume * 100).toInt()) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Text("GERÄT AUSWÄHLEN", style = MaterialTheme.typography.labelSmall, color = Navi.Muted, modifier = Modifier.padding(top = 8.dp))
            DeviceRow(Icons.Rounded.PhoneAndroid, "Dieses Handy", "Lautsprecher / Kopfhörer", s.remote == null) {
                player.disconnectRemote()
                close()
            }
            DeviceRow(Icons.Rounded.Bluetooth, "Bluetooth & Ausgabe", "Systemauswahl von Android", false) {
                player.disconnectRemote()
                val intent = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                    .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                close()
            }
            when {
                !maReady -> Text(
                    "Für Echo, Google Cast, DLNA und AirPlay: Music Assistant in den Einstellungen verbinden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Navi.Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                else -> when (val l = players.state) {
                    Load.Loading -> CenteredProgress(Modifier.fillMaxWidth().padding(12.dp))
                    is Load.Err -> {
                        Text(l.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = players.reload) { Text("Erneut versuchen") }
                    }
                    is Load.Ok -> l.value.forEach { p ->
                        DeviceRow(iconFor(p), p.name, kindFor(p), s.remote?.id == p.id) {
                            player.connectRemote(RemoteTarget(p.id, p.name))
                            close()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, kind: String, active: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (active) accent else Navi.Muted)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = if (active) accent else Navi.Text)
            Text(kind, style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
        }
    }
}
