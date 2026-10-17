package dev.navidroid.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import dev.navidroid.lidarr.LidarrAlbum
import dev.navidroid.lidarr.RequestSource
import dev.navidroid.lidarr.Suggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.text.Normalizer

/** Was gerade angefordert wird bzw. wurde (Schlüssel → läuft/fertig) und was ausgeblendet ist. */
object SuggestionBus {
    val state = mutableStateMapOf<String, Boolean>() // false = läuft, true = angefordert
    val hidden = mutableStateMapOf<String, Boolean>()
    var version by mutableStateOf(0)
}

private fun norm(s: String?) = Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * Album anfordern: mit „Beim Anfordern = Lidarr“ direkt, sonst (Fragen/YouTube) im Anfragen-Tab,
 * wo die Quelle gewählt wird.
 */
fun requestAlbum(app: NaviApp, key: String, artist: String, title: String, foreignAlbumId: String?, openRequests: () -> Unit, toast: (String) -> Unit) {
    if (app.lidarrStore.config == null) {
        toast("Lidarr ist nicht eingerichtet")
        return
    }
    val source = if (app.helper.configured) app.helper.requestSource else RequestSource.Lidarr
    if (source != RequestSource.Lidarr) {
        RequestSearchBus.pendingQuery = "$artist $title"
        openRequests()
        return
    }
    if (SuggestionBus.state.containsKey(key)) return
    SuggestionBus.state[key] = false
    MainScope().launch {
        try {
            val hits = app.lidarr.searchAlbums("$artist $title")
            val album: LidarrAlbum = hits.firstOrNull { foreignAlbumId != null && it.foreignId == foreignAlbumId }
                ?: hits.firstOrNull { norm(it.title) == norm(title) && norm(it.artistName) == norm(artist) }
                ?: hits.firstOrNull { norm(it.artistName) == norm(artist) && norm(it.title).startsWith(norm(title)) }
                ?: throw IllegalStateException("„$title“ nicht bei Lidarr gefunden – im Anfragen-Tab suchen")
            app.lidarr.requestAlbum(album)
            SuggestionBus.state[key] = true
            toast("„$title“ angefordert")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SuggestionBus.state.remove(key)
            toast(e.message ?: "Anfrage fehlgeschlagen")
        }
    }
}

/** Zeilen „Vorschläge für dich“ und „Neu von deinen Künstlern“ auf der Startseite. */
fun LazyListScope.suggestionRows(app: NaviApp, openRequests: () -> Unit) {
    if (!app.helper.configured || app.lidarrStore.config == null) return
    item(key = "suggestions") {
        val loader = rememberLoad("suggestions:${SuggestionBus.version}") { app.helper.suggestions() }
        val s = (loader.state as? Load.Ok)?.value ?: return@item
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            SuggestionRow(app, "Vorschläge für dich", s.forYou, openRequests)
            SuggestionRow(app, "Neu von deinen Künstlern", s.newReleases, openRequests)
        }
    }
}

@Composable
private fun SuggestionRow(app: NaviApp, title: String, items: List<Suggestion>, openRequests: () -> Unit) {
    val visible = items.filter { SuggestionBus.hidden[it.key] != true }
    if (visible.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(visible, key = { it.key }) { s -> SuggestionCard(app, s, openRequests) }
        }
    }
}

@Composable
private fun SuggestionCard(app: NaviApp, s: Suggestion, openRequests: () -> Unit) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val st = SuggestionBus.state[s.key]
    Column(Modifier.width(148.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(dev.navidroid.ui.theme.Navi.Surface2)) {
            Icon(Icons.Rounded.LibraryAdd, null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.align(Alignment.Center))
            if (s.cover != null) AsyncImage(s.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSizeCompat())
            Surface(
                onClick = {
                    SuggestionBus.hidden[s.key] = true
                    MainScope().launch { runCatching { app.helper.dismissSuggestion(s.key) } }
                },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp),
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Ausblenden", tint = Color.White, modifier = Modifier.size(16.dp)) } }
            Surface(
                onClick = {
                    requestAlbum(app, s.key, s.artist, s.title, s.foreignAlbumId, openRequests) {
                        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                    }
                },
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
                            Text("Angefordert", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
                        }
                        null -> Icon(Icons.Rounded.ArrowDownward, "Holen", tint = Color.Black, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Text(s.title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(s.artist, style = MaterialTheme.typography.bodySmall, color = dev.navidroid.ui.theme.Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(s.reason, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Modifier.matchParentSizeCompat(): Modifier = this.then(Modifier.fillMaxWidth().aspectRatio(1f))

/** Suche: Lidarr-Treffer, die noch nicht in der Bibliothek sind. */
fun LazyListScope.missingSearchResults(app: NaviApp, query: String, own: List<dev.navidroid.data.Album>, openRequests: () -> Unit) {
    if (app.lidarrStore.config == null) return
    item(key = "missing:$query") {
        val context = LocalContext.current
        val loader = rememberLoad("lidarr-search:$query") { app.lidarr.searchAlbums(query) }
        val ownKeys = own.map { norm(it.artist) to norm(it.name) }.toSet()
        Column {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text("Nicht in deiner Bibliothek", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("über Lidarr", style = MaterialTheme.typography.bodySmall, color = dev.navidroid.ui.theme.Navi.Muted)
            }
            when (val st = loader.state) {
                Load.Loading -> CenteredProgress(Modifier.fillMaxWidth().padding(16.dp))
                is Load.Err -> Text(st.message, color = dev.navidroid.ui.theme.Navi.Muted, modifier = Modifier.padding(horizontal = 20.dp))
                is Load.Ok -> {
                    val list = st.value.filter { !it.complete && (norm(it.artistName) to norm(it.title)) !in ownKeys }.take(8)
                    if (list.isEmpty()) Text("Keine weiteren Treffer", color = dev.navidroid.ui.theme.Navi.Muted, modifier = Modifier.padding(horizontal = 20.dp))
                    list.forEach { a ->
                        val key = "mb:${a.foreignId}"
                        val stt = SuggestionBus.state[key]
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(dev.navidroid.ui.theme.Navi.Surface2)) {
                                if (a.imageUrl != null) AsyncImage(a.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(a.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(a.artistName, a.type, a.year).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = dev.navidroid.ui.theme.Navi.Muted, maxLines = 1)
                            }
                            if (stt == true || (a.inLibrary && a.monitored)) {
                                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary) {
                                    Text("Angefordert", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                                }
                            } else {
                                OutlinedButton(onClick = {
                                    requestAlbum(app, key, a.artistName, a.title, a.foreignId, openRequests) {
                                        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                    }
                                }, enabled = stt == null) { Text(if (stt == false) "…" else "Holen") }
                            }
                        }
                    }
                }
            }
        }
    }
}
