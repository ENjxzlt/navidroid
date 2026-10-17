package dev.navidroid.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import coil.imageLoader
import dev.navidroid.BuildConfig
import dev.navidroid.NaviApp
import dev.navidroid.data.AppSettings
import dev.navidroid.lidarr.LidarrSettingsDialog
import dev.navidroid.lidarr.RequestSource
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val okGreen = Color(0xFF7BD88F)

@Composable
fun SettingsScreen(app: NaviApp, nav: NavController, onCheckUpdates: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = app.settings
    val creds = app.session.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var helperStatus by remember { mutableStateOf<String?>(null) }
    var helperOk by remember { mutableStateOf(false) }
    var aiOk by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var onlineLyrics by remember { mutableStateOf(app.lyrics.onlineLyrics) }
    var videos by remember { mutableStateOf(app.helper.videosEnabled) }
    var source by remember { mutableStateOf(app.helper.requestSource) }

    LaunchedEffect(refresh) {
        val cfg = app.helperStore.config
        if (cfg == null) {
            helperStatus = "Nicht eingerichtet"
            helperOk = false
            return@LaunchedEffect
        }
        helperStatus = "Prüfe …"
        try {
            val h = app.helper.health(cfg.url, cfg.token)
            helperOk = true
            aiOk = h.ai
            helperStatus = "Verbunden · Helfer ${h.version} · yt-dlp ${h.ytDlp}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helperOk = false
            helperStatus = e.message ?: "Nicht erreichbar"
        }
    }

    Column(Modifier.fillMaxSize().background(Navi.Bg).verticalScroll(rememberScrollState())) {
        Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() })
            Text("Einstellungen", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 12.dp))
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            // Konto
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Navi.Surface).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text(creds?.username?.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(creds?.username.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(creds?.serverUrl?.toUri()?.host.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                }
                TextButton(onClick = onLogout) { Text("Abmelden") }
            }

            Section("Wiedergabe") {
                ValueRow("Streaming-Qualität WLAN", null, AppSettings.label(s.wifiBitrate)) { dialog = "wifi" }
                ValueRow("Streaming-Qualität Mobil", "Spart Datenvolumen", AppSettings.label(s.mobileBitrate)) { dialog = "mobile" }
            }

            Section("Downloads") {
                ValueRow("Heruntergeladen", "${app.offline.downloadedCount} Titel · ${formatBytes(app.offline.totalBytes)}", null) { nav.navigate(Routes.DOWNLOADS) }
                ToggleRow("Nur im WLAN laden", "Mobil wird erst weitergeladen, wenn WLAN da ist", app.offline.wifiOnly) { app.offline.wifiOnly = it }
                ValueRow("Download-Qualität", "Gilt für neue Downloads", AppSettings.label(app.offline.quality)) { dialog = "dlquality" }
                ToggleRow("Playlists & Mixe aktuell halten", "Neue Titel automatisch nachladen", app.offline.autoSync) { app.offline.autoSync = it }
            }

            if (app.helper.configured) {
                Section("Bibliothek") {
                    ValueRow("Eigene Dateien hochladen", "MP3, FLAC, M4A … in deinen Musikordner", null) { nav.navigate(Routes.UPLOAD) }
                    ValueRow("Papierkorb", "Gelöschte Alben 30 Tage lang wiederherstellen", null) { nav.navigate(Routes.TRASH) }
                }
            }

            Section("Songtexte & Anzeige") {
                ToggleRow("Online-Songtexte (LRCLIB)", "Wenn Navidrome keinen Text hat", onlineLyrics) {
                    onlineLyrics = it
                    app.lyrics.onlineLyrics = it
                }
                ToggleRow("Farben aus dem Cover", "Akzent folgt dem laufenden Titel", s.coverColors) { s.coverColors = it }
            }

            Section("Anfragen") {
                StatusRow(
                    "Lidarr",
                    app.lidarrStore.config?.url?.toUri()?.host?.let { "Eingerichtet · $it" } ?: "Nicht eingerichtet",
                    app.lidarrStore.config != null,
                ) { dialog = "lidarr" }
                ValueRow(
                    "Beim Anfordern",
                    null,
                    when (source) { RequestSource.Ask -> "Fragen"; RequestSource.Lidarr -> "Lidarr"; RequestSource.YouTube -> "YouTube" },
                ) { dialog = "source" }
                StatusRow("YouTube-Helfer", helperStatus.orEmpty(), helperOk) { dialog = "lidarr" }
                ToggleRow("Musikvideos im Player", "Vom Helfer geladene Videos zeigen", videos, enabled = app.helperStore.config != null) {
                    videos = it
                    app.helperStore.config?.let { c -> app.helperStore.save(c.copy(videos = it)) }
                }
            }

            Section("Geräte") {
                StatusRow(
                    "Music Assistant",
                    if (s.maConfigured) "Eingerichtet · ${s.maUrl.toUri().host}" else "Für Echo, Google Cast, DLNA und mehr",
                    s.maConfigured,
                ) { dialog = "ma" }
            }

            Section("KI") {
                StatusRow(
                    "Claude-Worker",
                    when {
                        !helperOk -> "Braucht den YouTube-Helfer"
                        aiOk -> "Verbunden über den Helfer"
                        else -> "Im Helfer nicht eingerichtet"
                    },
                    helperOk && aiOk,
                ) {}
            }

            Section("App") {
                StatusRow("Updates", "NaviDroid ${BuildConfig.VERSION_NAME} · nach Updates suchen", true) { onCheckUpdates() }
                ValueRow("Cache leeren", "Cover und Songtexte", null) {
                    context.imageLoader.memoryCache?.clear()
                    context.imageLoader.diskCache?.clear()
                    app.lyrics.clearCache()
                    Toast.makeText(context, "Cache geleert", Toast.LENGTH_SHORT).show()
                }
                ValueRow("Über NaviDroid", "Quellcode und Lizenzen auf GitHub", null) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/ENjxzlt/navidroid".toUri()))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (dialog) {
        "wifi" -> ChoiceDialog("Streaming-Qualität WLAN", AppSettings.bitrateOptions, s.wifiBitrate, { dialog = null }) { s.wifiBitrate = it }
        "dlquality" -> ChoiceDialog("Download-Qualität", AppSettings.bitrateOptions, app.offline.quality, { dialog = null }) { app.offline.quality = it }
        "mobile" -> ChoiceDialog("Streaming-Qualität Mobil", AppSettings.bitrateOptions, s.mobileBitrate, { dialog = null }) { s.mobileBitrate = it }
        "source" -> ChoiceDialog(
            "Beim Anfordern",
            listOf(RequestSource.Ask to "Jedes Mal fragen", RequestSource.Lidarr to "Immer über Lidarr", RequestSource.YouTube to "Immer über YouTube"),
            source,
            { dialog = null },
        ) { source = it; app.helper.requestSource = it }
        "lidarr" -> LidarrSettingsDialog(app, onDismiss = { dialog = null }, onChanged = { refresh++ })
        "ma" -> MusicAssistantDialog(app, onDismiss = { dialog = null })
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Navi.Muted, modifier = Modifier.padding(start = 4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Navi.Surface)) { content() }
    }
}

@Composable
private fun RowBase(onClick: (() -> Unit)?, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun ValueRow(label: String, sub: String?, value: String?, onClick: () -> Unit) {
    RowBase(onClick) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Navi.Muted) }
        }
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFFC9CDD4)) }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Navi.Faint)
    }
}

@Composable
private fun StatusRow(label: String, status: String, ok: Boolean, onClick: () -> Unit) {
    RowBase(onClick) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (ok) okGreen else Navi.Muted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Navi.Faint)
    }
}

@Composable
private fun ToggleRow(label: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    RowBase({ if (enabled) onChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = if (enabled) Navi.Text else Navi.Faint)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Navi.Muted) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect(value); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value); onDismiss() })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun MusicAssistantDialog(app: NaviApp, onDismiss: () -> Unit) {
    val s = app.settings
    var url by remember { mutableStateOf(s.maUrl.ifBlank { "http://192.168.178.199:8095" }) }
    var token by remember { mutableStateOf(s.maToken) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Music Assistant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Darüber spielt NaviDroid auf Echo-, Google-Cast-, DLNA- und AirPlay-Lautsprechern. Den Token erstellst du in Music Assistant unter Einstellungen → Profil → Langlebige Zugriffstoken. Die Verbindung läuft über den YouTube-Helfer im Heimnetz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Navi.Muted,
                )
                OutlinedTextField(url, { url = it }, label = { Text("Adresse im Heimnetz") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                s.maUrl = url.trim().trimEnd('/')
                s.maToken = token.trim()
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (s.maConfigured) TextButton(onClick = { s.maUrl = ""; s.maToken = ""; onDismiss() }) { Text("Trennen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}
