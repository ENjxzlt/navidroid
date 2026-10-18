package dev.navidroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dev.navidroid.lidarr.RequestSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.Locale

private val Card = Color(0xFF181A1F)
private val Field = Color(0xFF121317)
private val Ok = Color(0xFF7BD88F)
private val Warn = Color(0xFFF2A65A)

private const val REPO = "https://github.com/ENjxzlt/navidroid"

private val bitrates = listOf(0 to "Original", 320 to "320 kbit/s", 256 to "256 kbit/s", 192 to "192 kbit/s", 128 to "128 kbit/s")
private val replayGains = listOf(0 to "Aus", 1 to "Pro Titel", 2 to "Pro Album")
private val crossfades = listOf(0 to "Aus", 2 to "2 s", 4 to "4 s", 6 to "6 s", 8 to "8 s", 12 to "12 s")
private val sources = listOf(RequestSource.Ask to "Jedes Mal fragen", RequestSource.Lidarr to "Immer über Lidarr", RequestSource.YouTube to "Immer über YouTube")

/** Zustand einer Statuszeile; ok = null heißt „wird geprüft“. */
private data class Status(val text: String, val ok: Boolean?, val sub: String? = null)

private data class Release(val version: String, val url: String)

/** Einstellungen als eigene Seite (Design „Einstellungen“). */
@Composable
fun SettingsScreen(app: DesktopApp) {
    val scope = rememberCoroutineScope()
    val s = app.settings
    var maDialog by remember { mutableStateOf(false) }

    // Jede Änderung speichern und an das Handy weitergeben
    fun change(block: DesktopSettings.() -> DesktopSettings) {
        app.updateSettings(app.settings.block())
        scope.launch { runCatching { app.syncSettings(pushLocal = true) } }
    }

    // ---- Status von Lidarr, Helfer und Music Assistant
    var lidarr by remember { mutableStateOf<Status?>(null) }
    var helper by remember { mutableStateOf<Status?>(null) }
    var ai by remember { mutableStateOf<Status?>(null) }
    var ma by remember { mutableStateOf<Status?>(null) }
    LaunchedEffect(app.version, app.showSettings) {
        if (app.showSettings) return@LaunchedEffect
        val cfg = app.lidarrStore.config
        lidarr = if (cfg == null) Status("Nicht eingerichtet", false) else {
            lidarr = Status("Prüfe …", null)
            runCatching { app.lidarr.connect(cfg.url, cfg.apiKey) }.fold(
                { o ->
                    val profile = o.qualityProfiles.firstOrNull { it.value == cfg.qualityProfileId }?.label
                    Status("Verbunden", true, listOfNotNull(host(cfg.url), profile?.let { "Profil $it" }).joinToString(" · "))
                },
                { Status("Nicht erreichbar", false, host(cfg.url)) },
            )
        }
        val h = app.helperStore.config
        if (h == null) {
            helper = Status("Nicht eingerichtet", false)
            ai = Status("Helfer fehlt", false)
        } else {
            helper = Status("Prüfe …", null)
            ai = Status("Prüfe …", null)
            runCatching { app.helper.health(h.url, h.token) }.fold(
                { r ->
                    helper = Status("Verbunden", true, listOf(host(h.url), r.version).filter { it.isNotBlank() }.joinToString(" · "))
                    ai = if (r.ai) Status("Bereit", true) else Status("Kein KI-Zugang", false)
                },
                {
                    helper = Status("Nicht erreichbar", false, host(h.url))
                    ai = Status("Helfer fehlt", false)
                },
            )
        }
    }
    LaunchedEffect(s.maUrl, s.maToken, app.version) {
        ma = when {
            s.maUrl.isBlank() -> Status("Nicht eingerichtet", false)
            !app.helper.configured -> Status("Helfer fehlt", false, host(s.maUrl))
            else -> {
                ma = Status("Prüfe …", null)
                runCatching { app.helper.maPlayers(s.maUrl, s.maToken) }.fold(
                    { Status(if (it.size == 1) "1 Gerät" else "${it.size} Geräte", true, host(s.maUrl)) },
                    { Status("Nicht erreichbar", false, host(s.maUrl)) },
                )
            }
        }
    }

    // ---- Cache und Updates
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var cacheVersion by remember { mutableStateOf(0) }
    LaunchedEffect(cacheVersion) { cacheBytes = withContext(Dispatchers.IO) { dirSize(File(Paths.cache, "images")) } }
    var update by remember { mutableStateOf<Status?>(null) }
    var release by remember { mutableStateOf<Release?>(null) }

    val sections = buildList<@Composable () -> Unit> {
        add {
            Section("WIEDERGABE") {
                ChoiceRow("Streaming-Qualität", "Gilt für diesen Rechner", bitrates, s.bitrate) { v -> change { copy(bitrate = v) } }
                ToggleRow("Autoplay", "Nach dem letzten Titel mit ähnlicher Musik weiterspielen", s.autoplay) { v -> change { copy(autoplay = v) } }
                ChoiceRow("Lautstärke-Ausgleich", "Laute Titel leiser machen (ReplayGain)", replayGains, s.replayGain) { v -> change { copy(replayGain = v) } }
                ChoiceRow("Überblenden", "Nicht innerhalb eines Albums", crossfades, s.crossfade) { v -> change { copy(crossfade = v) } }
            }
        }
        add {
            Section("DOWNLOADS") {
                val dir = s.downloadDir.ifBlank { "~/Musik/NaviDroid" }
                LinkRow("Heruntergeladen", "${app.offline.downloadedCount} Titel · ${formatBytes(app.offline.totalBytes)} · ${shortPath(dir)}") { app.nav.go(Screen.Downloads) }
                ChoiceRow("Download-Qualität", "Gilt für neue Downloads", bitrates, s.downloadBitrate) { v -> change { copy(downloadBitrate = v) } }
                ToggleRow("Playlists & Mixe aktuell halten", "Neue Titel automatisch nachladen", s.autoSyncDownloads) { v -> change { copy(autoSyncDownloads = v) } }
                FolderRow(shortPath(dir), onPick = { chooseFolder(s.downloadDir)?.let { p -> change { copy(downloadDir = p) } } }, onReset = { change { copy(downloadDir = "") } })
            }
        }
        if (app.helper.configured) add {
            Section("BIBLIOTHEK") {
                LinkRow("Eigene Dateien hochladen", "Musik vom Rechner auf den Server bringen") { app.nav.go(Screen.Library("upload")) }
                LinkRow("Doppelte Alben", "Gleiche Alben finden und aufräumen") { app.nav.go(Screen.Library("duplicates")) }
                LinkRow("Qualität verbessern", "Alben in besserer Qualität neu holen") { app.nav.go(Screen.Library("quality")) }
                LinkRow("Papierkorb", "Gelöschte Alben wiederherstellen") { app.nav.go(Screen.Library("trash")) }
            }
        }
        add {
            Section("SONGTEXTE & ANZEIGE") {
                ToggleRow("Online-Songtexte (LRCLIB)", "Wenn Navidrome keinen Text hat", s.onlineLyrics) { v ->
                    change { copy(onlineLyrics = v) }
                    app.lyrics.clearCache()
                }
                ToggleRow("Farben aus dem Cover", "Akzent folgt dem laufenden Titel", s.coverColors) { v -> change { copy(coverColors = v) } }
                val h = app.helperStore.config
                ToggleRow(
                    "Musikvideos im Player",
                    if (h == null) "Braucht den NaviDroid-Helfer" else "Vom Helfer geladene Videos zeigen",
                    s.videos && h != null, enabled = h != null,
                ) { v ->
                    app.helperStore.config?.let { app.helperStore.save(it.copy(videos = v)) }
                    change { copy(videos = v) }
                }
            }
        }
        add {
            Section("ANFRAGEN") {
                StatusRow("Lidarr", lidarr?.sub ?: "Für Album-Anfragen", lidarr) { app.showSettings = true }
                StatusRow("NaviDroid-Helfer", helper?.sub ?: "YouTube, Abgleich mit dem Handy, KI", helper) { app.showSettings = true }
                var source by remember { mutableStateOf(app.helperStore.requestSource) }
                ChoiceRow("Quelle für Anfragen", "Wo neue Alben geholt werden", sources, source) { v ->
                    app.helperStore.requestSource = v
                    source = v
                }
            }
        }
        add {
            Section("GERÄTE & KI") {
                StatusRow("Music Assistant", ma?.sub?.let { "Auf Lautsprechern im Haus abspielen · $it" } ?: "Auf Lautsprechern im Haus abspielen", ma) { maDialog = true }
                StatusRow("Claude-Worker", "Daylist, KI-Playlists, Song-Infos, Übersetzung", ai) { if (!app.helper.configured) app.showSettings = true }
            }
        }
        add {
            Section("APP") {
                StatusRow(
                    "Updates", "NaviDroid ${app.appVersion} · nach Updates suchen", update,
                    trailing = release?.let { r -> { TextButton(onClick = { openUrl(r.url) }) { Text("Zur Seite") } } },
                ) {
                    if (update?.ok == null && update != null) return@StatusRow
                    update = Status("Suche …", null)
                    release = null
                    scope.launch {
                        val r = runCatching { newestRelease(app) }
                        val newest = r.getOrNull()
                        update = when {
                            r.isFailure -> Status("Fehlgeschlagen", false)
                            newest == null -> Status("Keine Version gefunden", false)
                            isNewer(newest.version, app.appVersion) -> { release = newest; Status("Update ${newest.version} verfügbar", false) }
                            else -> Status("Aktuell", true)
                        }
                    }
                }
                LinkRow("Cache leeren", "Cover und Songtexte · ${cacheBytes?.let(::formatBytes) ?: "…"}") {
                    scope.launch {
                        clearCaches(app)
                        cacheVersion++
                    }
                }
                LinkRow("Über NaviDroid", "Quellcode und Lizenzen auf GitHub") { openUrl(REPO) }
                val c = app.session.current
                LinkRow("Abmelden", c?.let { "${it.username} auf ${host(it.serverUrl)}" } ?: "Vom Server abmelden") {
                    app.player.pause()
                    app.session.clear()
                    app.loggedIn = false
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 40.dp, end = 40.dp, top = 60.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Header(app) { scope.launch { runCatching { app.syncSettings(pushLocal = true) } } }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Zwei Spalten ab etwa 2 × 380 dp, sonst eine
            if (maxWidth >= 780.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    for (col in 0..1) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            sections.filterIndexed { i, _ -> i % 2 == col }.forEach { it() }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { sections.forEach { it() } }
            }
        }
    }

    if (maDialog) MusicAssistantDialog(app, { maDialog = false }) { url, token -> change { copy(maUrl = url, maToken = token) } }
}

@Composable
private fun Header(app: DesktopApp, onSync: () -> Unit) {
    val configured = app.helper.configured
    val synced = app.settings.syncedAt
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Einstellungen", style = MaterialTheme.typography.displaySmall)
            Text(
                if (!configured) "Ohne Helfer nur auf diesem Rechner gespeichert"
                else "Wird über den Helfer mit deinem Handy abgeglichen" + (if (synced > 0) " · zuletzt ${ago(synced)}" else ""),
                style = MaterialTheme.typography.bodyMedium, color = Navi.Muted,
            )
        }
        if (configured) {
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Navi.Surface2).clickable(onClick = onSync).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Dot(if (synced > 0) Ok else Warn)
                Text(if (synced > 0) "Abgeglichen" else "Jetzt abgleichen", style = MaterialTheme.typography.labelMedium, color = Navi.Muted)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp))
        content()
    }
}

/** Grundzeile: Titel, Untertitel, rechts ein Bedienelement; die ganze Zeile ist anklickbar. */
@Composable
private fun SettingRow(label: String, sub: String, enabled: Boolean = true, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var m = Modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (hovered && enabled && onClick != null) Navi.Surface2.copy(alpha = 0.5f) else Color.Transparent).hoverable(hover)
    if (onClick != null && enabled) m = m.clickable(onClick = onClick)
    Row(m.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = if (enabled) Navi.Text else Navi.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun ToggleRow(label: String, sub: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(label, sub, enabled, { onChange(!on) }) {
        val accent = MaterialTheme.colorScheme.primary
        Box(
            Modifier.width(40.dp).height(22.dp).clip(RoundedCornerShape(11.dp))
                .background(if (on) accent else Navi.Outline.copy(alpha = if (enabled) 1f else 0.5f)).padding(horizontal = 3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(16.dp).clip(RoundedCornerShape(8.dp)).background(if (enabled) Navi.Text else Navi.Faint)) }
    }
}

/** Wert mit kleinem Auswahlmenü. */
@Composable
private fun <T> ChoiceRow(label: String, sub: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow(label, sub, onClick = { open = true }) {
        Box {
            ValueButton(options.firstOrNull { it.first == selected }?.second ?: selected.toString()) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, text) ->
                    DropdownMenuItem(
                        text = { Text(text, color = if (v == selected) MaterialTheme.colorScheme.primary else Navi.Text) },
                        onClick = { open = false; if (v != selected) onSelect(v) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(path: String, onPick: () -> Unit, onReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow("Speicherort", "Ordner für Offline-Musik", onClick = { open = true }) {
        Box {
            ValueButton(path) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Ordner wählen …") }, onClick = { open = false; onPick() })
                DropdownMenuItem(text = { Text("Standard (~/Musik/NaviDroid)") }, onClick = { open = false; onReset() })
            }
        }
    }
}

@Composable
private fun ValueButton(text: String, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Navi.Outline, RoundedCornerShape(10.dp)).background(Field)
            .clickable(onClick = onClick).padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Navi.Text, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun StatusRow(label: String, sub: String, status: Status?, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    SettingRow(label, sub, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                status == null -> Unit
                status.ok == null -> {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                    Text(status.text, style = MaterialTheme.typography.labelMedium, color = Navi.Muted)
                }
                else -> {
                    val c = if (status.ok) Ok else Warn
                    Dot(c)
                    Text(status.text, style = MaterialTheme.typography.labelMedium, color = c)
                }
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun LinkRow(label: String, sub: String, onClick: () -> Unit) {
    SettingRow(label, sub, onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Navi.Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))

@Composable
private fun MusicAssistantDialog(app: DesktopApp, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val s = app.settings
    var url by remember { mutableStateOf(s.maUrl.ifBlank { "http://192.168.178.199:8095" }) }
    var token by remember { mutableStateOf(s.maToken) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Music Assistant") },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Darüber spielt NaviDroid auf Echo-, Google-Cast-, DLNA- und AirPlay-Lautsprechern. Den Token erstellst du in Music Assistant unter Einstellungen → Profil → Langlebige Zugriffstoken. Die Verbindung läuft über den Helfer im Heimnetz.",
                    style = MaterialTheme.typography.bodySmall, color = Navi.Muted,
                )
                OutlinedTextField(url, { url = it }, label = { Text("Adresse im Heimnetz") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(url.trim().trimEnd('/'), token.trim()); onDismiss() }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (s.maUrl.isNotBlank()) TextButton(onClick = { onSave("", ""); onDismiss() }) { Text("Trennen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

// ---------------------------------------------------------------- Hilfen

private fun host(url: String): String = runCatching { URI(url.trim()).host }.getOrNull() ?: url

private fun shortPath(p: String): String {
    val home = System.getProperty("user.home")
    return if (p.startsWith(home)) "~" + p.removePrefix(home) else p
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.GERMANY, "%.1f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "${b / (1024 * 1024)} MB"
    b >= 1L shl 10 -> "${b / 1024} KB"
    else -> "$b B"
}

private fun ago(ts: Long): String {
    val min = (System.currentTimeMillis() - ts) / 60_000
    return when {
        min < 1 -> "gerade eben"
        min < 60 -> "vor $min Min"
        min < 60 * 24 -> "vor ${min / 60} Std"
        else -> "vor ${min / (60 * 24)} Tagen"
    }
}

private fun dirSize(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

/** Bild-Cache (Coil) und zwischengespeicherte Songtexte; heruntergeladene Musik bleibt. */
private suspend fun clearCaches(app: DesktopApp) {
    app.lyrics.clearCache()
    val loader = SingletonImageLoader.get(PlatformContext.INSTANCE)
    loader.memoryCache?.clear()
    withContext(Dispatchers.IO) {
        runCatching { loader.diskCache?.clear() }
        File(Paths.cache, "images").listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }
}

private fun chooseFolder(current: String): String? {
    val start = current.ifBlank { System.getProperty("user.home") + "/Musik" }
    val chooser = javax.swing.JFileChooser(File(start).takeIf { it.exists() } ?: File(System.getProperty("user.home"))).apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Speicherort für Offline-Musik"
    }
    return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}

/** Öffnet eine Adresse im Browser; unter Linux notfalls über xdg-open. */
private fun openUrl(url: String) {
    Thread {
        val ok = runCatching {
            val d = java.awt.Desktop.getDesktop()
            if (java.awt.Desktop.isDesktopSupported() && d.isSupported(java.awt.Desktop.Action.BROWSE)) { d.browse(URI(url)); true } else false
        }.getOrDefault(false)
        if (!ok) runCatching { ProcessBuilder("xdg-open", url).start() }
    }.start()
}

private val releaseJson = Json { ignoreUnknownKeys = true }

/** Neueste Desktop-Version auf GitHub (Tags „desktop-v…“), ohne Entwürfe. Lädt nichts herunter. */
private suspend fun newestRelease(app: DesktopApp): Release? = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("https://api.github.com/repos/ENjxzlt/navidroid/releases")
        .header("Accept", "application/vnd.github+json").build()
    val body = app.http.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
        r.body?.string().orEmpty()
    }
    (releaseJson.parseToJsonElement(body) as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .filter { it["draft"]?.jsonPrimitive?.booleanOrNull != true }
        .mapNotNull { o ->
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!tag.startsWith("desktop-v")) return@mapNotNull null
            Release(tag.removePrefix("desktop-v"), o["html_url"]?.jsonPrimitive?.contentOrNull ?: "$REPO/releases/tag/$tag")
        }
        .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
}

private fun versionParts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

private fun compareVersions(a: String, b: String): Int {
    val x = versionParts(a)
    val y = versionParts(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

/** Aus der IDE („dev“) gilt jede veröffentlichte Version als neuer. */
private fun isNewer(remote: String, local: String) = local.firstOrNull()?.isDigit() != true || compareVersions(remote, local) > 0
