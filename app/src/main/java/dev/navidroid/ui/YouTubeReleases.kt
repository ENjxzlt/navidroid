package dev.navidroid.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.navidroid.NaviApp
import dev.navidroid.lidarr.YtRelease
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ytRed = Color(0xFFFF0033)

/** Stand je Release (false = wird gestartet, true = in der Warteschlange) und Ausgeblendetes. */
object YtBus {
    val state = mutableStateMapOf<String, Boolean>()
    val hidden = mutableStateMapOf<String, Boolean>()
    var version by mutableIntStateOf(0)
}

private fun importRelease(app: NaviApp, r: YtRelease, toast: (String) -> Unit) {
    if (YtBus.state.containsKey(r.key)) return
    YtBus.state[r.key] = false
    MainScope().launch {
        try {
            app.helper.importYoutubeRelease(r.key)
            YtBus.state[r.key] = true
            toast("„${r.title}“ wird geladen – Fortschritt unter Anfragen → Downloads")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtBus.state.remove(r.key)
            toast(e.message ?: "Import fehlgeschlagen")
        }
    }
}

private fun hide(app: NaviApp, r: YtRelease) {
    YtBus.hidden[r.key] = true
    MainScope().launch { runCatching { app.helper.dismissYoutubeRelease(r.key) } }
}

@Composable
private fun YouTubeBadge() {
    Box(Modifier.size(width = 26.dp, height = 18.dp).clip(RoundedCornerShape(5.dp)).background(ytRed), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** Startseite: „Neu auf YouTube“. */
fun LazyListScope.youtubeReleasesRow(app: NaviApp) {
    if (!app.helper.configured) return
    item(key = "yt-releases") {
        val loader = rememberLoad("yt-releases:${YtBus.version}") { app.helper.youtubeReleases() }
        val res = (loader.state as? Load.Ok)?.value ?: return@item
        // Der Helfer sucht im Hintergrund: in Kürze erneut fragen
        if (res.working) LaunchedEffect(YtBus.version) { delay(25_000); YtBus.version++ }
        val items = res.items.filter { YtBus.hidden[it.key] != true }
        if (items.isEmpty()) return@item
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                YouTubeBadge()
                Text("Neu auf YouTube", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("nicht in deiner Bibliothek", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(items, key = { it.key }) { r -> ReleaseCard(app, r) }
            }
        }
    }
}

@Composable
private fun ReleaseCard(app: NaviApp, r: YtRelease) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val st = YtBus.state[r.key]
    Column(Modifier.width(148.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Navi.Surface2)) {
            if (r.cover != null) AsyncImage(r.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Surface(onClick = { hide(app, r) }, shape = CircleShape, color = Color.Black.copy(alpha = 0.45f), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Ausblenden", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
            Surface(
                onClick = { importRelease(app, r) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } },
                shape = RoundedCornerShape(18.dp),
                color = if (st == true) accent else Color.White,
                shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).height(36.dp),
            ) {
                Row(Modifier.padding(horizontal = if (st == true) 12.dp else 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (st) {
                        false -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                        true -> {
                            Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                            Text("Wird geladen", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
                        }
                        null -> Icon(Icons.Rounded.ArrowDownward, "Holen", tint = Color.Black, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Text(r.title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.artistLine ?: r.artist, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Nur auf YouTube", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = accent)
    }
}

/** Künstlerseite: Veröffentlichungen auf YouTube, die nicht in der Bibliothek sind. */
@Composable
fun YouTubeArtistSection(app: NaviApp, artist: String) {
    if (!app.helper.configured) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val loader = rememberLoad("yt-artist:$artist:$reload") { app.helper.youtubeReleases(artist) }
    var editing by remember { mutableStateOf(false) }
    val res = (loader.state as? Load.Ok)?.value
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            YouTubeBadge()
            Text("Auf YouTube, nicht in deiner Bibliothek", style = MaterialTheme.typography.titleMedium)
        }
        when {
            loader.state is Load.Loading -> CenteredProgress(Modifier.fillMaxWidth().padding(16.dp))
            loader.state is Load.Err -> Text((loader.state as Load.Err).message, color = Navi.Muted, modifier = Modifier.padding(vertical = 8.dp))
            res != null -> {
                val items = res.items.filter { YtBus.hidden[it.key] != true }
                if (items.isEmpty()) {
                    Text(
                        if (res.channel == null) "Kein offizieller YouTube-Kanal gefunden." else "Alles von YouTube ist schon in deiner Bibliothek.",
                        color = Navi.Muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items.forEach { r ->
                    val st = YtBus.state[r.key]
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Navi.Surface2)) {
                            if (r.cover != null) AsyncImage(r.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            r.artistLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1) }
                        }
                        if (st == true) {
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary) {
                                Text("Wird geladen", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                        } else {
                            OutlinedButton(onClick = { importRelease(app, r) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }, enabled = st == null) {
                                Text(if (st == false) "…" else "Holen")
                            }
                        }
                    }
                }
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Kanal: " + (res.channel?.name ?: "keiner") + " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = Navi.Muted,
                    )
                    Text(
                        "ändern",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { editing = true },
                    )
                }
            }
        }
    }
    if (editing) {
        var link by remember { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) editing = false },
            title = { Text("YouTube-Kanal für $artist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Link zum offiziellen Kanal einfügen (z. B. youtube.com/@name). Leer lassen, um automatisch zu suchen.", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                    OutlinedTextField(link, { link = it }, singleLine = true, placeholder = { Text("https://www.youtube.com/@…") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            app.helper.setYoutubeChannel(artist, link.trim())
                            editing = false
                            reload++
                            YtBus.version++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Fehler", Toast.LENGTH_LONG).show()
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(if (saving) "Sucht …" else "Speichern") }
            },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !saving) { Text("Abbrechen") } },
        )
    }
}
