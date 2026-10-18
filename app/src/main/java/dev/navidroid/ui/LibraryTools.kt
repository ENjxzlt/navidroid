package dev.navidroid.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import dev.navidroid.NaviApp
import dev.navidroid.lidarr.DupGroup
import dev.navidroid.lidarr.LossyAlbum
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun ToolHeader(title: String, nav: NavController) {
    Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() })
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Einstellungen → Bibliothek → Doppelte Alben */
@Composable
fun DuplicatesScreen(app: NaviApp, nav: NavController) {
    var reload by remember { mutableIntStateOf(0) }
    val loader = rememberLoad("dups:$reload") { app.helper.duplicates() }
    var open by remember { mutableStateOf<DupGroup?>(null) }
    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        ToolHeader("Doppelte Alben", nav)
        LoadContent(loader) { groups ->
            if (groups.isEmpty()) return@LoadContent EmptyHint("Keine doppelten Alben gefunden")
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        "Gleiches Album mehrfach, z. B. Deluxe und normale Version oder einmal aus Lidarr und einmal von YouTube.",
                        style = MaterialTheme.typography.bodySmall, color = Navi.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(groups, key = { it.key }) { g ->
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Navi.Surface)
                            .clickable { open = g }.padding(14.dp),
                    ) {
                        Text("${g.artist} – ${g.title}", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                        g.variants.forEach { v ->
                            Text(
                                "${v.album} · ${v.tracks} Titel · ${v.formats.joinToString("/").uppercase()} · ${formatBytes(v.bytes)}",
                                style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
    open?.let { g -> ResolveDialog(app, g, onDone = { open = null; reload++ }, onDismiss = { open = null }) }
}

@Composable
private fun ResolveDialog(app: NaviApp, g: DupGroup, onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keep by remember { mutableStateOf(g.variants.first().key) }
    var merge by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("${g.title} zusammenführen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Behalten:", style = MaterialTheme.typography.labelLarge)
                g.variants.forEach { v ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { keep = v.key }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = keep == v.key, onClick = { keep = v.key })
                        Column {
                            Text(v.album, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(
                                "${v.tracks} Titel · ${v.formats.joinToString("/").uppercase()}" + if (v.missingTitles > 0) " · ${v.missingTitles} fehlen" else "",
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
            }
        },
        confirmButton = {
            Button(enabled = !working, onClick = {
                working = true
                scope.launch {
                    try {
                        app.helper.resolveDuplicates(keep, g.variants.map { it.key }.filter { it != keep }, merge)
                        Snack.show("„${g.title}“ aufgeräumt")
                        onDone()
                        rescanLibrary(app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        working = false
                        Toast.makeText(context, e.message ?: "Fehler", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text(if (working) "…" else if (merge) "Zusammenführen" else "Aufräumen") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Abbrechen") } },
    )
}

/** Einstellungen → Bibliothek → Qualität verbessern */
@Composable
fun QualityScreen(app: NaviApp, nav: NavController) {
    val loader = rememberLoad("quality") { app.helper.qualityOverview() }
    var open by remember { mutableStateOf<LossyAlbum?>(null) }
    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        ToolHeader("Qualität verbessern", nav)
        LoadContent(loader) { q ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        if (q.upgradesAllowed) "Alben, die nur als MP3 vorliegen. Antippen, um bei Lidarr nach FLAC zu suchen."
                        else "Alben, die nur als MP3 vorliegen. Hinweis: In Lidarr sind Upgrades ausgeschaltet – ein geladenes FLAC würde Lidarr nicht importieren.",
                        style = MaterialTheme.typography.bodySmall, color = if (q.upgradesAllowed) Navi.Muted else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (q.albums.isEmpty()) item { EmptyHint("Alles liegt verlustfrei vor") }
                items(q.albums, key = { it.albumId }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { open = a }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Navi.Surface2)) {
                            if (a.cover != null) AsyncImage(a.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${a.artist} · ${a.year} · ${a.tracks} Titel", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1)
                        }
                        Text(a.quality, style = MaterialTheme.typography.labelMedium, color = Navi.Muted)
                    }
                }
            }
        }
    }
    open?.let { a -> ReleasesDialog(app, a) { open = null } }
}

@Composable
private fun ReleasesDialog(app: NaviApp, a: LossyAlbum, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoad("releases:${a.albumId}") { app.helper.losslessReleases(a.albumId) }
    val grabbed = remember { mutableStateMapOf<String, Boolean>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("FLAC für „${a.title}“") },
        text = {
            Box(Modifier.fillMaxWidth()) {
                when (val st = loader.state) {
                    Load.Loading -> Column {
                        CenteredProgress(Modifier.fillMaxWidth().padding(16.dp))
                        Text("Lidarr fragt die Indexer …", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                    }
                    is Load.Err -> Text(st.message, color = MaterialTheme.colorScheme.error)
                    is Load.Ok -> if (st.value.isEmpty()) Text("Keine verlustfreie Version gefunden.", color = Navi.Muted) else
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(st.value, key = { it.guid }) { r ->
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Navi.Surface2).padding(10.dp)) {
                                    Text(r.title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${r.quality} · ${formatBytes(r.size)} · ${r.seeders ?: "?"} Seeder · ${r.indexer}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                                    r.rejections.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                                    OutlinedButton(
                                        enabled = grabbed[r.guid] == null,
                                        onClick = {
                                            grabbed[r.guid] = false
                                            scope.launch {
                                                try {
                                                    app.helper.grabRelease(r.guid, r.indexerId)
                                                    grabbed[r.guid] = true
                                                    Toast.makeText(context, "Lidarr lädt die FLAC-Version", Toast.LENGTH_SHORT).show()
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    grabbed.remove(r.guid)
                                                    Toast.makeText(context, e.message ?: "Fehler", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.padding(top = 6.dp),
                                    ) { Text(when (grabbed[r.guid]) { true -> "Wird geladen"; false -> "…"; null -> "Laden" }) }
                                }
                            }
                        }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}
