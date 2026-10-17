package dev.navidroid.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import dev.navidroid.NaviApp
import dev.navidroid.data.AppSettings
import dev.navidroid.data.Song
import dev.navidroid.offline.CollectionProgress
import dev.navidroid.offline.OfflineCollection
import dev.navidroid.offline.OfflineManager
import dev.navidroid.ui.theme.Navi
import java.util.Locale

private val offlineYellow = Color(0xFFEDCF83)

@Composable
fun rememberOffline(): OfflineManager = (LocalContext.current.applicationContext as NaviApp).offline

/** Fragt beim ersten Download nach der Benachrichtigungs-Erlaubnis (für den Fortschritt) und startet ihn dann. */
@Composable
fun rememberDownloadStarter(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    return { action ->
        val needs = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            pending = action
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.GERMANY, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    bytes > 0 -> "${maxOf(1, bytes / 1000)} KB"
    else -> "0 MB"
}

/** Zeile unter dem Titel im Kopfbereich, z. B. „Lädt herunter · 6 von 17 · 112 MB“. */
fun downloadLine(p: CollectionProgress?): String? = when {
    p == null -> null
    p.complete -> "Heruntergeladen · ${formatBytes(p.bytes)}"
    else -> "Lädt herunter · ${p.done} von ${p.total} · ${formatBytes(p.bytes)}"
}

/** Runder Download-Knopf mit Fortschrittsring. */
@Composable
fun DownloadToggle(progress: CollectionProgress?, onDownload: () -> Unit, onRemove: () -> Unit, size: Int = 48) {
    val accent = MaterialTheme.colorScheme.primary
    var confirm by remember { mutableStateOf(false) }
    val start = rememberDownloadStarter()
    val label = when {
        progress == null -> "Herunterladen"
        progress.complete -> "Heruntergeladen – antippen zum Entfernen"
        else -> "Download läuft – antippen zum Abbrechen"
    }
    Surface(
        onClick = { if (progress == null) start(onDownload) else confirm = true },
        shape = CircleShape,
        color = if (progress?.complete == true) accent else Color.Transparent,
        border = if (progress == null) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)) else null,
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                progress == null -> Icon(Icons.Rounded.ArrowDownward, label, tint = Navi.Text)
                progress.complete -> Icon(Icons.Rounded.ArrowDownward, label, tint = MaterialTheme.colorScheme.onPrimary)
                else -> {
                    CircularProgressIndicator(
                        progress = { (progress.percent / 100f).coerceIn(0.02f, 1f) },
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                        color = accent,
                        trackColor = Color.White.copy(alpha = 0.18f),
                        strokeWidth = 3.dp,
                        strokeCap = StrokeCap.Round,
                    )
                    Icon(Icons.Rounded.ArrowDownward, label, tint = accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    if (confirm) {
        val done = progress?.complete == true
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (done) "Download entfernen?" else "Download abbrechen?") },
            text = { Text(if (done) "Die Titel sind dann nicht mehr offline verfügbar." else "Bereits geladene Titel werden wieder gelöscht.") },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text(if (done) "Entfernen" else "Abbrechen") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Behalten") } },
        )
    }
}

/** Kleiner Hinweis in einer Titelzeile: Häkchen, Fortschritt oder „wartet“. */
@Composable
fun SongDownloadMark(songId: String) {
    val offline = rememberOffline()
    val d = offline.songs[songId] ?: return
    val accent = MaterialTheme.colorScheme.primary
    when {
        d.done -> Box(Modifier.size(14.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowDownward, "Heruntergeladen", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(10.dp))
        }
        d.active -> CircularProgressIndicator(
            progress = { (d.percent / 100f).coerceIn(0.02f, 1f) },
            modifier = Modifier.size(14.dp),
            color = accent,
            trackColor = Navi.Surface2,
            strokeWidth = 2.dp,
        )
        d.failed -> Text("Fehler", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        else -> Text("wartet", style = MaterialTheme.typography.labelSmall, color = Navi.Faint)
    }
}

/** Gelbes Band, wenn keine Verbindung besteht. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().background(Color(0xFF3A2E12))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = offlineYellow, modifier = Modifier.size(18.dp))
        Text("Offline – nur heruntergeladene Musik", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = offlineYellow)
    }
}

private enum class DlFilter(val label: String) { All("Alle"), Playlists("Playlists"), Albums("Alben"), Mixes("Mixe") }

/** Seite „Heruntergeladen“ (eigene Route) mit Zurück-Knopf. */
@Composable
fun DownloadsScreen(app: NaviApp, nav: NavController) {
    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        if (!app.offline.online) OfflineBanner()
        DownloadsContent(app, nav, onBack = { nav.popBackStack() }, padTop = app.offline.online)
    }
}

@Composable
fun DownloadsContent(app: NaviApp, nav: NavController, onBack: (() -> Unit)?, padTop: Boolean = true) {
    val offline = app.offline
    val player = LocalPlayer.current
    var filter by rememberSaveable { mutableStateOf(DlFilter.All) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val list = offline.collections.filter {
        when (filter) {
            DlFilter.All -> true
            DlFilter.Playlists -> it.kind == OfflineCollection.PLAYLIST
            DlFilter.Albums -> it.kind == OfflineCollection.ALBUM
            DlFilter.Mixes -> it.kind == OfflineCollection.MIX
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(
                Modifier.then(if (padTop) Modifier.statusBarsPadding() else Modifier).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onBack?.let { RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", it) }
                Text(
                    "Heruntergeladen",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f).padding(start = if (onBack != null) 12.dp else 0.dp),
                )
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Optionen", tint = Navi.Muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Fehlgeschlagene erneut laden") }, onClick = { menu = false; offline.retryFailed() })
                        DropdownMenuItem(text = { Text("Alle Downloads löschen", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; dialog = "clear" })
                    }
                }
            }
        }
        item {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Navi.Surface).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val used = offline.totalBytes
                val free = offline.freeBytes
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${formatBytes(used)} belegt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${offline.downloadedCount} Titel · ${formatBytes(free)} frei", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                }
                LinearProgressIndicator(
                    progress = { if (used + free > 0) (used.toFloat() / (used + free)).coerceAtLeast(0.01f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Navi.Surface2,
                    drawStopIndicator = {},
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { SettingChip(if (offline.wifiOnly) "Nur WLAN" else "WLAN und mobil") { offline.wifiOnly = !offline.wifiOnly } }
                    item { SettingChip("Qualität: ${AppSettings.label(offline.quality)}") { dialog = "quality" } }
                    item { SettingChip(if (offline.autoSync) "Playlists & Mixe aktuell halten" else "Nicht automatisch aktualisieren") { offline.autoSync = !offline.autoSync } }
                }
            }
        }
        item {
            ChipRow(DlFilter.entries.map { it to it.label }, filter, { filter = it }, Modifier.padding(vertical = 8.dp))
        }
        if (list.isEmpty()) {
            item {
                Text(
                    if (offline.collections.isEmpty()) "Noch nichts heruntergeladen. Tipp auf einem Album, einer Playlist oder einem Mix auf den Pfeil."
                    else "Nichts in dieser Auswahl.",
                    color = Navi.Muted,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
        items(list, key = { it.id }) { c ->
            val p = offline.progress(c)
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable { openCollection(app, nav, c) }.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (c.kind == OfflineCollection.MIX) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Navi.Surface2), contentAlignment = Alignment.Center) {
                        Text("MIX", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    CoverArt(c.coverArt, 300, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                }
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.size(14.dp).clip(CircleShape).background(if (p.complete) MaterialTheme.colorScheme.primary else offlineYellow),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.ArrowDownward, null, tint = Navi.Bg, modifier = Modifier.size(10.dp))
                        }
                        Text(collectionLine(c, p), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Optionen", tint = Navi.Faint) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Abspielen") }, onClick = { menu = false; player.play(playable(offline, c)) })
                        DropdownMenuItem(text = { Text("Zufällig abspielen") }, onClick = { menu = false; player.play(playable(offline, c), shuffle = true) })
                        DropdownMenuItem(text = { Text("Download entfernen", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; offline.remove(c.id) })
                    }
                }
            }
        }
    }

    when (dialog) {
        "quality" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Download-Qualität") },
            text = {
                Column {
                    AppSettings.bitrateOptions.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { offline.quality = value; dialog = null },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = offline.quality == value, onClick = { offline.quality = value; dialog = null })
                            Text(label)
                        }
                    }
                    Text("Gilt für neue Downloads.", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Schließen") } },
        )
        "clear" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Alle Downloads löschen?") },
            text = { Text("${formatBytes(offline.totalBytes)} werden freigegeben. Die Musik auf dem Server bleibt unverändert.") },
            confirmButton = { TextButton(onClick = { dialog = null; offline.removeAll() }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun SettingChip(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(15.dp), color = Navi.Surface2, modifier = Modifier.height(30.dp)) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFFD5D8DE))
        }
    }
}

private fun collectionLine(c: OfflineCollection, p: CollectionProgress): String {
    val kind = when (c.kind) {
        OfflineCollection.ALBUM -> "Album"
        OfflineCollection.PLAYLIST -> "Playlist"
        OfflineCollection.MIX -> "Mix"
        else -> "Titel"
    }
    val parts = mutableListOf(kind)
    if (c.kind == OfflineCollection.ALBUM) c.subtitle?.let { parts += it }
    parts += if (p.complete) "${p.total} Titel" else "${p.done} von ${p.total}"
    parts += formatBytes(p.bytes)
    return parts.joinToString(" · ")
}

/** Ohne Verbindung nur fertig geladene Titel abspielen. */
fun playable(offline: OfflineManager, c: OfflineCollection): List<Song> =
    if (offline.online) c.songs else c.songs.filter { offline.isDownloaded(it.id) }

private fun openCollection(app: NaviApp, nav: NavController, c: OfflineCollection) {
    when {
        !app.offline.online || c.kind == OfflineCollection.SONGS -> nav.navigate("offline/${c.id}")
        c.kind == OfflineCollection.ALBUM -> nav.openAlbum(c.refId)
        c.kind == OfflineCollection.PLAYLIST -> nav.openPlaylist(c.refId)
        c.kind == OfflineCollection.MIX && c.refId == dev.navidroid.data.DaylistRepository.OFFLINE_ID -> nav.navigate(Routes.DAYLIST)
        c.kind == OfflineCollection.MIX -> nav.navigate("mix/${c.refId}")
    }
}

/** Offline-Ansicht einer Sammlung – funktioniert ganz ohne Server. */
@Composable
fun OfflineCollectionScreen(app: NaviApp, id: String, nav: NavController) {
    val offline = app.offline
    val player = LocalPlayer.current
    val c = offline.collection(id)
    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        if (!offline.online) OfflineBanner()
        if (c == null) {
            Box(Modifier.then(if (offline.online) Modifier.statusBarsPadding() else Modifier).padding(start = 20.dp, top = 8.dp)) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() })
            }
            EmptyHint("Nicht mehr heruntergeladen")
            return@Column
        }
        val songs = playable(offline, c)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(
                    Modifier.then(if (offline.online) Modifier.statusBarsPadding() else Modifier).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() })
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                        CoverArt(c.coverArt, 600, Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(c.name, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            c.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Navi.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.DownloadDone, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Text(downloadLine(offline.progress(c)).orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = { player.play(songs) },
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(6.dp))
                                Text("Abspielen", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                        RoundIconButton(Icons.Rounded.Shuffle, "Zufällig abspielen", { player.play(songs, shuffle = true) }, container = Color.White.copy(alpha = 0.1f), size = 48)
                    }
                }
            }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                SongRow(song, onClick = { player.play(songs, i) }, showCover = true)
            }
        }
    }
}
