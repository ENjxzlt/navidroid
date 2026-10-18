package dev.navidroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.navidroid.lidarr.DupGroup
import dev.navidroid.lidarr.LossyAlbum
import dev.navidroid.lidarr.QualityOverview
import dev.navidroid.lidarr.TrashEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Card = Color(0xFF181A1F)

/** Navidrome neu einlesen lassen und warten, bis es fertig ist (wie rescanLibrary in der App); danach Listen neu laden. */
suspend fun rescanAfterLibraryChange(app: DesktopApp) {
    runCatching { app.client.startScan() }
    delay(1_500)
    for (i in 0 until 30) {
        if (!runCatching { app.client.scanning() }.getOrDefault(false)) break
        delay(2_000)
    }
    app.version++
}

/** Neu einlesen läuft unabhängig von der Seite weiter. */
private val toolScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private fun rescanInBackground(app: DesktopApp) {
    toolScope.launch { rescanAfterLibraryChange(app) }
}

/** Im Programm ausgeblendete Doppel-Gruppen (der Helfer kennt kein „Ignorieren“; gilt bis zum Neustart). */
private val ignoredDuplicates = mutableStateListOf<String>()

/** Bibliothek-Werkzeuge: Hochladen, doppelte Alben, Qualität verbessern, Papierkorb. tab: upload | duplicates | quality | trash */
@Composable
fun LibraryScreen(app: DesktopApp, tab: String) {
    if (!app.helper.configured) {
        HelperMissingHint(app, "Bibliothek-Werkzeuge", "Hochladen, doppelte Alben, Qualität und Papierkorb laufen über deinen Helfer. Richte ihn in den Einstellungen ein.")
        return
    }
    var current by remember(tab) { mutableStateOf(tab) }
    var dupReload by remember { mutableIntStateOf(0) }
    var trashReload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) { delay(6_000); message = null } }
    // Zählwerte für die Reiter, darum schon beim Öffnen laden
    val dups = rememberLoad("lib-dups:$dupReload") { app.helper.duplicates() }
    val quality = rememberLoad("lib-quality") { app.helper.qualityOverview() }
    val trash = rememberLoad("lib-trash:$trashReload") { app.helper.trash() }
    fun count(l: Load<*>, n: (Any?) -> Int): String = (l as? Load.Ok<*>)?.let { n(it.value).takeIf { c -> c > 0 }?.toString() }.orEmpty()

    val tabs = listOf(
        Triple("upload", "Hochladen", UploadManager.of(app).files.size.takeIf { it > 0 }?.toString().orEmpty()),
        Triple("duplicates", "Doppelte Alben", count(dups.first) { v -> (v as List<*>).count { (it as DupGroup).key !in ignoredDuplicates } }),
        Triple("quality", "Qualität verbessern", count(quality.first) { v -> (v as QualityOverview).albums.size }),
        Triple("trash", "Papierkorb", count(trash.first) { v -> (v as List<*>).size }),
    )
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 56.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Bibliothek", style = MaterialTheme.typography.displaySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { (key, label, n) ->
                val sel = key == current
                Row(
                    Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(if (sel) accent else Navi.Surface2)
                        .clickable { current = key }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val color = if (sel) MaterialTheme.colorScheme.onPrimary else Navi.Text
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color)
                    if (n.isNotEmpty()) Text(n, style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.75f))
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(start = 12.dp), maxLines = 2) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (current) {
                "duplicates" -> DuplicatesTab(app, dups, onDone = { msg -> message = msg; dupReload++; trashReload++ }, onMessage = { message = it })
                "quality" -> QualityTab(app, quality, onMessage = { message = it })
                "trash" -> TrashTab(app, trash, onDone = { msg -> message = msg; trashReload++; dupReload++ }, onMessage = { message = it })
                else -> UploadTab(app)
            }
        }
    }
}

@Composable
private fun Hint(text: String, color: Color = Navi.Muted) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Navi.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ToolRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(Navi.Surface2), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Navi.Faint)
    }
}

// ---------------------------------------------------------------- Doppelte Alben

@Composable
private fun DuplicatesTab(app: DesktopApp, load: Pair<Load<List<DupGroup>>, () -> Unit>, onDone: (String) -> Unit, onMessage: (String) -> Unit) {
    var open by remember { mutableStateOf<DupGroup?>(null) }
    LoadBox(load) { all ->
        val groups = all.filter { it.key !in ignoredDuplicates }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Hint("Gleiches Album mehrfach in der Bibliothek, z. B. Deluxe und normale Version oder einmal aus Lidarr und einmal von YouTube. Beim Zusammenführen bleibt die gewählte Version, der Rest geht in den Papierkorb.") }
            if (groups.isEmpty()) item { EmptyHint("Keine doppelten Alben gefunden") }
            items(groups, key = { it.key }) { g ->
                ToolRow {
                    IconTile(Icons.Rounded.ContentCopy, 56)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(g.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            g.artist + " · " + g.variants.joinToString(" und ") { v -> "${v.album} (${v.formats.joinToString("/").uppercase()}, ${v.tracks} Titel)" },
                            style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(onClick = { open = g }, shape = RoundedCornerShape(18.dp), modifier = Modifier.height(36.dp)) { Text("Zusammenführen", fontWeight = FontWeight.ExtraBold) }
                    OutlinedButton(onClick = { ignoredDuplicates += g.key }, shape = RoundedCornerShape(18.dp), modifier = Modifier.height(36.dp)) { Text("Ignorieren") }
                }
            }
        }
    }
    open?.let { g -> ResolveDialog(app, g, onDone = { open = null; onDone(it) }, onError = onMessage, onDismiss = { open = null }) }
}

@Composable
private fun ResolveDialog(app: DesktopApp, g: DupGroup, onDone: (String) -> Unit, onError: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var keep by remember { mutableStateOf(g.variants.first().key) }
    var merge by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        containerColor = Navi.Surface,
        title = { Text("${g.title} zusammenführen") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Behalten:", style = MaterialTheme.typography.labelLarge)
                g.variants.forEach { v ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { keep = v.key }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = keep == v.key, onClick = { keep = v.key })
                        Column {
                            Text(v.album, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(
                                "${v.tracks} Titel · ${v.formats.joinToString("/").uppercase()} · ${formatLibraryBytes(v.bytes)}" + if (v.missingTitles > 0) " · ${v.missingTitles} fehlen" else "",
                                style = MaterialTheme.typography.bodySmall, color = Navi.Muted,
                            )
                            Text(v.folder.removePrefix("/music/"), style = MaterialTheme.typography.bodySmall, color = Navi.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { merge = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = merge, onClick = { merge = true })
                    Text("Zusammenführen: fehlende Titel übernehmen, nur echte Doppel in den Papierkorb", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { merge = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !merge, onClick = { merge = false })
                    Text("Nur diese Version behalten, die anderen ganz in den Papierkorb", style = MaterialTheme.typography.bodySmall)
                }
                Text("Der Papierkorb hält alles 30 Tage lang.", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !working, onClick = {
                working = true
                error = null
                scope.launch {
                    try {
                        app.helper.resolveDuplicates(keep, g.variants.map { it.key }.filter { it != keep }, merge)
                        onDone("„${g.title}“ aufgeräumt")
                        rescanInBackground(app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        working = false
                        error = e.message ?: "Fehler"
                        onError(e.message ?: "Fehler")
                    }
                }
            }) { Text(if (working) "…" else if (merge) "Zusammenführen" else "Aufräumen") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Abbrechen") } },
    )
}

// ---------------------------------------------------------------- Qualität verbessern

/** Alben, für die schon eine FLAC-Version geholt wird (bis zum Neustart). */
private val grabbedAlbums = mutableStateListOf<Int>()

@Composable
private fun QualityTab(app: DesktopApp, load: Pair<Load<QualityOverview>, () -> Unit>, onMessage: (String) -> Unit) {
    var open by remember { mutableStateOf<LossyAlbum?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    LoadBox(load) { q ->
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                if (q.upgradesAllowed) Hint("Alben, die nur als MP3 da sind. „Als FLAC holen“ sucht über Lidarr eine verlustfreie Version.")
                else Hint(
                    "Alben, die nur als MP3 da sind. Hinweis: In Lidarr sind Upgrades ausgeschaltet – ein geladenes FLAC würde Lidarr nicht importieren.",
                    MaterialTheme.colorScheme.error,
                )
            }
            if (q.albums.isEmpty()) item { EmptyHint("Alles liegt verlustfrei vor") }
            items(q.albums, key = { it.albumId }) { a ->
                val grabbed = a.albumId in grabbedAlbums
                ToolRow {
                    Cover(a.cover, Modifier.size(48.dp), 8)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(a.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(a.artist, a.year.takeIf { it.isNotBlank() }, "${a.tracks} Titel").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (a.quality.isNotBlank()) {
                        Box(Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(Navi.Outline).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            Text(a.quality, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    OutlinedButton(
                        onClick = { open = a },
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accent),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                        modifier = Modifier.height(36.dp),
                    ) { Text(if (grabbed) "Wird geladen …" else "Als FLAC holen") }
                }
            }
        }
    }
    open?.let { a -> ReleasesDialog(app, a, onMessage) { open = null } }
}

@Composable
private fun ReleasesDialog(app: DesktopApp, a: LossyAlbum, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val loader = rememberLoad("releases:${a.albumId}") { app.helper.losslessReleases(a.albumId) }
    val grabbed = remember { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Navi.Surface,
        title = { Text("FLAC für „${a.title}“") },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 460.dp)) {
                when (val st = loader.first) {
                    Load.Loading -> Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                        Text("Lidarr fragt die Indexer …", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(top = 12.dp))
                    }
                    is Load.Err -> Text(st.message, color = MaterialTheme.colorScheme.error)
                    is Load.Ok -> if (st.value.isEmpty()) Text("Keine verlustfreie Version gefunden.", color = Navi.Muted) else
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(st.value, key = { it.guid }) { r ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Navi.Surface2).padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("${r.quality} · ${formatLibraryBytes(r.size)} · ${r.seeders ?: "?"} Seeder · ${r.indexer}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                                        r.rejections.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                                    }
                                    OutlinedButton(
                                        enabled = grabbed[r.guid] == null,
                                        shape = RoundedCornerShape(18.dp),
                                        onClick = {
                                            grabbed[r.guid] = false
                                            error = null
                                            scope.launch {
                                                try {
                                                    app.helper.grabRelease(r.guid, r.indexerId)
                                                    grabbed[r.guid] = true
                                                    if (a.albumId !in grabbedAlbums) grabbedAlbums += a.albumId
                                                    onMessage("Lidarr lädt die FLAC-Version von „${a.title}“")
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    grabbed.remove(r.guid)
                                                    error = e.message ?: "Fehler"
                                                }
                                            }
                                        },
                                    ) { Text(when (grabbed[r.guid]) { true -> "Wird geladen"; false -> "…"; null -> "Laden" }) }
                                }
                            }
                        }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

// ---------------------------------------------------------------- Papierkorb

@Composable
private fun TrashTab(app: DesktopApp, load: Pair<Load<List<TrashEntry>>, () -> Unit>, onDone: (String) -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf<String?>(null) }
    val date = remember { SimpleDateFormat("d.M.", Locale.GERMANY) }
    LoadBox(load) { entries ->
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Hint("Gelöschte Alben bleiben 30 Tage hier und lassen sich wiederherstellen; danach werden sie endgültig entfernt.") }
            if (entries.isEmpty()) item { EmptyHint("Der Papierkorb ist leer") }
            items(entries, key = { it.id }) { e ->
                ToolRow {
                    Box(Modifier.alpha(0.6f)) { IconTile(Icons.Rounded.Delete, 48) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(e.album, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                e.artist.takeIf { it.isNotBlank() },
                                e.deletedAt.takeIf { it > 0 }?.let { "gelöscht am ${date.format(Date((it * 1000).toLong()))}" },
                                e.tracks.takeIf { it > 0 }?.let { "$it Titel" },
                                formatLibraryBytes(e.bytes),
                                "noch ${e.daysLeft} ${if (e.daysLeft == 1) "Tag" else "Tage"}",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 2,
                        )
                    }
                    Button(
                        enabled = restoring == null,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navi.Surface2, contentColor = Navi.Text),
                        modifier = Modifier.height(36.dp),
                        onClick = {
                            restoring = e.id
                            scope.launch {
                                try {
                                    app.helper.restoreTrash(e.id)
                                    onDone("„${e.album}“ wiederhergestellt")
                                    rescanInBackground(app)
                                } catch (ex: CancellationException) {
                                    throw ex
                                } catch (ex: Exception) {
                                    onMessage(ex.message ?: "Wiederherstellen fehlgeschlagen")
                                } finally {
                                    restoring = null
                                }
                            }
                        },
                    ) { Text(if (restoring == e.id) "…" else "Wiederherstellen") }
                }
            }
        }
    }
}
