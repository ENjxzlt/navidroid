package dev.navidroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.navidroid.data.Album
import dev.navidroid.data.Daylist
import dev.navidroid.data.Mix
import dev.navidroid.data.Playlist
import dev.navidroid.data.Song
import dev.navidroid.lidarr.LidarrAlbum
import dev.navidroid.lidarr.Suggestion
import dev.navidroid.lidarr.YtRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/** Hintergrund des Inhaltsbereichs (wie in Main.kt). */
private val Panel = Color(0xFF121317)

/** Oben Platz für den schwebenden Zurück-Knopf. */
private val pagePadding = PaddingValues(start = 32.dp, end = 32.dp, top = 60.dp, bottom = 32.dp)

private fun greeting() = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Guten Tag"
    else -> "Guten Abend"
}

/** Für Vergleiche von Künstler/Titel zwischen Navidrome, Lidarr und Vorschlägen. */
private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

/** Stand je Anfrage/Import (Schlüssel: Vorschlag, Release oder Lidarr-Album): false = läuft, true = erledigt. */
private val requestState = mutableStateMapOf<String, Boolean>()

private fun runRequest(key: String, block: suspend () -> Unit) {
    if (requestState.containsKey(key)) return
    requestState[key] = false
    MainScope().launch {
        try {
            block()
            requestState[key] = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            requestState.remove(key)
        }
    }
}

// ---------------------------------------------------------------- Start

private enum class HomeFilter(val label: String) { All("Alle"), Music("Musik"), Mixes("Mixe") }

private data class HomeData(val recent: List<Album>, val newest: List<Album>)

@Composable
fun HomeScreen(app: DesktopApp) {
    var filter by remember { mutableStateOf(HomeFilter.All) }
    val data = rememberLoad("home:${app.version}") {
        coroutineScope {
            val user = app.session.current?.username
            val recent = async {
                val fromHelper = if (user != null && app.helper.configured) runCatching {
                    app.helper.historySummary(user).recentAlbums.map { Album(id = it.id, name = it.name.orEmpty(), artist = it.artist, coverArt = it.coverArt) }
                }.getOrNull() else null
                fromHelper?.takeIf { it.isNotEmpty() } ?: app.client.albumList("recent", 20, 0)
            }
            val newest = async { app.client.albumList("newest", 20, 0) }
            HomeData(recent.await(), newest.await())
        }
    }
    val mixes = rememberLoad("mixes") { app.mixes.mixes() }
    val mixList = (mixes.first as? Load.Ok)?.value.orEmpty()
    LoadBox(data) { d ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(32.dp)) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(greeting(), style = MaterialTheme.typography.displaySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeFilter.entries.forEach { f -> Chip(f.label, f == filter) { filter = f } }
                    }
                    if (filter != HomeFilter.Mixes && d.recent.isNotEmpty()) QuickTiles(app, d.recent.take(8))
                }
            }
            if (filter != HomeFilter.Music) {
                item(key = "daylist-mixes") { DaylistAndMixes(app, mixList) }
                if (filter == HomeFilter.Mixes && mixList.size > 3) {
                    item(key = "all-mixes") {
                        Column {
                            SectionTitle("Alle Mixe")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(mixList, key = { it.id }) { m -> MixTile(m, Modifier.width(200.dp).aspectRatio(1f)) { app.nav.go(Screen.MixPage(m.id)) } }
                            }
                        }
                    }
                }
            }
            if (filter != HomeFilter.Mixes) {
                albumRow(app, "Zuletzt gespielt", d.recent)
                albumRow(app, "Neu in deiner Bibliothek", d.newest)
                if (app.helper.configured) item(key = "youtube") { YoutubeSection(app) }
                if (app.helper.configured && app.lidarrStore.config != null) item(key = "suggestions") { SuggestionSection(app) }
            }
        }
    }
}

/** Schnellzugriff: zuletzt gespielte Alben als flache Kacheln (4 × 2). */
@Composable
private fun QuickTiles(app: DesktopApp, albums: List<Album>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        albums.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a -> QuickTile(app, a, Modifier.weight(1f)) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Albumkachel mit Rechtsklick-Menü. */
@Composable
private fun AlbumCard(app: DesktopApp, album: Album, modifier: Modifier = Modifier) {
    val ctx = rememberContextMenu()
    Box(modifier.contextMenu(ctx)) {
        AlbumTile(app, album, Modifier.fillMaxWidth()) { app.nav.go(Screen.AlbumPage(album.id)) }
        ContextMenuAnchor(ctx) { AlbumContextMenu(app, album, ctx.expanded, { ctx.expanded = false }) }
    }
}

@Composable
private fun QuickTile(app: DesktopApp, album: Album, modifier: Modifier) {
    val ctx = rememberContextMenu()
    Box(modifier.contextMenu(ctx)) {
        QuickTileContent(app, album, Modifier.fillMaxWidth())
        ContextMenuAnchor(ctx) { AlbumContextMenu(app, album, ctx.expanded, { ctx.expanded = false }) }
    }
}

@Composable
private fun QuickTileContent(app: DesktopApp, album: Album, modifier: Modifier) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        modifier.height(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = if (hovered) 0.12f else 0.07f))
            .hoverable(hover).clickable { app.nav.go(Screen.AlbumPage(album.id)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(app.client.coverArtUrl(album.coverArt, 150), Modifier.size(64.dp), 0)
        Text(album.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
        if (hovered) {
            Box(
                Modifier.padding(end = 10.dp).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable {
                    MainScope().launch { runCatching { app.client.album(album.id).song }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { app.player.play(it) } }
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, "Abspielen", tint = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

private fun gradientOf(d: Daylist) = Brush.linearGradient(d.moment.slot.gradient.map { Color(it) })

@Composable
private fun DaylistAndMixes(app: DesktopApp, mixes: List<Mix>) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f)) {
            SectionTitle("Deine Daylist")
            DaylistTile(app, Modifier.fillMaxWidth().height(230.dp))
        }
        Column(Modifier.weight(1.2f)) {
            SectionTitle("Deine Mixe", Modifier.clickable { app.nav.go(Screen.Mixes) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                mixes.take(3).forEach { m -> MixTile(m, Modifier.weight(1f).height(230.dp)) { app.nav.go(Screen.MixPage(m.id)) } }
                repeat(3 - mixes.take(3).size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DaylistTile(app: DesktopApp, modifier: Modifier) {
    val d = app.daylist.current
    if (d == null) {
        Box(modifier.clip(RoundedCornerShape(18.dp)).background(Navi.Surface), contentAlignment = Alignment.Center) {
            Text(if (app.daylist.loading) "Deine Daylist wird zusammengestellt …" else "Noch keine Daylist", color = Navi.Muted)
        }
        return
    }
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(gradientOf(d)).clickable { app.nav.go(Screen.Daylist) }.padding(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("DAYLIST · BIS ${d.moment.slot.endLabel}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
            Text(d.name, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 32.sp), color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.tags.take(2).forEach { TagChip(it) }
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color.White).clickable { if (d.songs.isNotEmpty()) app.player.play(d.songs) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, "Daylist abspielen", tint = Color.Black) }
        }
    }
}

@Composable
private fun TagChip(tag: String) {
    Text(
        "#$tag", style = MaterialTheme.typography.labelMedium, color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun LazyListScope.albumRow(app: DesktopApp, title: String, albums: List<Album>) {
    if (albums.isEmpty()) return
    item(key = title) {
        Column {
            SectionTitle(title)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(albums, key = { it.id }) { a -> AlbumCard(app, a, Modifier.width(190.dp)) }
            }
        }
    }
}

fun mixColor(hue: Float, light: Float = 0.26f): Color {
    val c = java.awt.Color.getHSBColor(hue, 0.45f, light * 2.2f)
    return Color(c.red, c.green, c.blue)
}

@Composable
private fun MixTile(m: Mix, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(mixColor(m.hue, 0.34f), mixColor(m.hue, 0.18f))))
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Text("MIX", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
        Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(m.description, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Kachel mit rundem Download-Knopf (YouTube-Releases, Vorschläge). */
@Composable
private fun FetchTile(cover: String?, title: String, subtitle: String, note: String?, noteAccent: Boolean, state: Boolean?, doneLabel: String, onFetch: () -> Unit) {
    Column(Modifier.width(180.dp).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Cover(cover, Modifier.fillMaxSize(), 10)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(10.dp).size(40.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White)
                    .clickable(enabled = state == null, onClick = onFetch),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    null -> Icon(Icons.Rounded.Download, "Holen", tint = Color.Black, modifier = Modifier.size(22.dp))
                    false -> CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                    true -> Icon(Icons.Rounded.Check, doneLabel, tint = Color.Black, modifier = Modifier.size(22.dp))
                }
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            state == true -> Text(doneLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            note != null -> Text(note, style = MaterialTheme.typography.labelMedium, color = if (noteAccent) MaterialTheme.colorScheme.primary else Navi.Faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun YoutubeSection(app: DesktopApp) {
    val load = rememberLoad("yt:${app.version}") { app.helper.youtubeReleases().items }
    val items = (load.first as? Load.Ok)?.value?.takeIf { it.isNotEmpty() } ?: return
    Column {
        SectionTitle("Neu auf YouTube")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items, key = { it.key }) { r: YtRelease ->
                val key = "yt:${r.key}"
                FetchTile(r.cover, r.title, r.artistLine ?: r.artist, "Nur auf YouTube", true, requestState[key], "Wird geladen") {
                    runRequest(key) { app.helper.importYoutubeRelease(r.key) }
                }
            }
        }
    }
}

@Composable
private fun SuggestionSection(app: DesktopApp) {
    val load = rememberLoad("suggestions:${app.version}") { app.helper.suggestions().forYou }
    val items = (load.first as? Load.Ok)?.value?.takeIf { it.isNotEmpty() } ?: return
    Column {
        SectionTitle("Vorschläge für dich")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items, key = { it.key }) { s: Suggestion ->
                val key = "sug:${s.key}"
                FetchTile(s.cover, s.title, listOfNotNull(s.artist, s.year?.toString()).joinToString(" · "), s.reason.takeIf { it.isNotBlank() }, false, requestState[key], "Angefordert") {
                    runRequest(key) {
                        val hits = app.lidarr.searchAlbums("${s.artist} ${s.title}")
                        val album = hits.firstOrNull { s.foreignAlbumId != null && it.foreignId == s.foreignAlbumId }
                            ?: hits.firstOrNull { norm(it.title) == norm(s.title) && norm(it.artistName) == norm(s.artist) }
                            ?: hits.firstOrNull { norm(it.artistName) == norm(s.artist) && norm(it.title).startsWith(norm(s.title)) }
                            ?: error("Nicht bei Lidarr gefunden")
                        app.lidarr.requestAlbum(album)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Mixe & Daylist

@Composable
fun MixesFolderScreen(app: DesktopApp) {
    val mixes = rememberLoad("mixes") { app.mixes.mixes() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Mixe & Daylist", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 20.dp)) }
        item {
            val d = app.daylist.current
            FolderRow(
                tile = { Box(Modifier.fillMaxSize().background(d?.let(::gradientOf) ?: Brush.linearGradient(listOf(Navi.Surface2, Navi.Surface2)))) },
                title = d?.name ?: "Daylist",
                subtitle = if (d != null) "Daylist · bis ${d.moment.slot.endLabel} · ${d.songs.size} Titel"
                else if (app.daylist.loading) "Wird zusammengestellt …" else "Noch nicht verfügbar",
            ) { app.nav.go(Screen.Daylist) }
        }
        when (val s = mixes.first) {
            is Load.Ok -> items(s.value, key = { it.id }) { m ->
                FolderRow(
                    tile = { Box(Modifier.fillMaxSize().background(mixColor(m.hue)), contentAlignment = Alignment.Center) { Text("MIX", style = MaterialTheme.typography.labelSmall, color = Color.White) } },
                    title = m.name,
                    subtitle = "Mix · ${m.songs.size} Titel · ${m.description}",
                ) { app.nav.go(Screen.MixPage(m.id)) }
            }
            Load.Loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 3.dp) } }
            is Load.Err -> item { Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun FolderRow(tile: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (hovered) Navi.Surface else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))) { tile() }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun DaylistScreen(app: DesktopApp) {
    val d = app.daylist.current
    if (d == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (app.daylist.loading) CircularProgressIndicator(strokeWidth = 3.dp)
            Text(if (app.daylist.loading) "Deine Daylist wird zusammengestellt …" else "Noch keine Daylist", color = Navi.Muted, modifier = Modifier.padding(16.dp))
        }
        return
    }
    val slot = d.moment.slot
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth().background(gradientOf(d))) {
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Panel.copy(alpha = 0.85f)))))
                Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 72.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DAYLIST · ${d.songs.size} TITEL", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                    Text(d.name, style = MaterialTheme.typography.displaySmall.copy(fontSize = 56.sp, lineHeight = 60.sp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(d.description, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { d.tags.forEach { TagChip(it) } }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { app.player.play(d.songs) }, enabled = d.songs.isNotEmpty(), shape = RoundedCornerShape(24.dp), modifier = Modifier.height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Text("Abspielen", modifier = Modifier.padding(start = 6.dp), fontWeight = FontWeight.ExtraBold)
                        }
                        OutlinedButton(onClick = { app.player.play(d.songs, shuffle = true) }, enabled = d.songs.isNotEmpty(), shape = RoundedCornerShape(24.dp), modifier = Modifier.height(44.dp)) {
                            Icon(Icons.Rounded.Shuffle, null, tint = Color.White)
                            Text("Zufällig", color = Color.White, modifier = Modifier.padding(start = 6.dp))
                        }
                        DownloadToggle(app, "mix", "daylist", d.name, null, d.songs)
                    }
                    Text("Neu um ${slot.startLabel} · ändert sich wieder um ${slot.endLabel}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
        item { TableHeader(showCover = true) }
        itemsIndexed(d.songs) { i, s -> Box(Modifier.padding(horizontal = 20.dp)) { SongListRow(app, s, i, showCover = true) { app.player.play(d.songs, i) } } }
    }
}

// ---------------------------------------------------------------- Suche

@Composable
fun SearchScreen(app: DesktopApp) {
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    LaunchedEffect(query) { delay(300); debounced = query.trim() }
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 56.dp)) {
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text("Was möchtest du hören?") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.width(560.dp),
        )
        if (debounced.isEmpty()) return@Column
        val res = rememberLoad(debounced) { app.client.search(debounced) }
        val lidarr = if (app.lidarrStore.config != null) rememberLoad("lidarr-search:$debounced") { app.lidarr.searchAlbums(debounced) } else null
        LoadBox(res) { r ->
            val own = r.album.map { norm(it.artist) + "|" + norm(it.name) }.toSet()
            val missing = ((lidarr?.first as? Load.Ok)?.value.orEmpty())
                .filter { !it.complete && (norm(it.artistName) + "|" + norm(it.title)) !in own }
                .take(8)
            if (r.artist.isEmpty() && r.album.isEmpty() && r.song.isEmpty() && missing.isEmpty()) {
                Text("Keine Treffer für „$debounced“", color = Navi.Muted, modifier = Modifier.padding(top = 24.dp))
                return@LoadBox
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                if (r.artist.isNotEmpty() || r.song.isNotEmpty()) item(key = "top") {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        r.artist.firstOrNull()?.let { a ->
                            Column(Modifier.width(380.dp)) {
                                SectionTitle("Top-Treffer")
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Navi.Surface)
                                        .clickable { app.nav.go(Screen.ArtistPage(a.id)) }.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Cover(app.client.coverArtUrl(a.coverArt, 300), Modifier.size(100.dp), 999)
                                    Text(a.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "KÜNSTLER", style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Navi.Bg).padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                        if (r.song.isNotEmpty()) Column(Modifier.weight(1f)) {
                            SectionTitle("Titel")
                            r.song.take(5).forEachIndexed { i, s -> SongListRow(app, s, i, showCover = true) { app.player.play(r.song, i) } }
                        }
                    }
                }
                if (r.artist.isNotEmpty()) item(key = "artists") {
                    Column {
                        SectionTitle("Künstler")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(r.artist, key = { it.id }) { a ->
                                Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).clickable { app.nav.go(Screen.ArtistPage(a.id)) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Cover(app.client.coverArtUrl(a.coverArt, 300), Modifier.size(134.dp), 999)
                                    Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                    Text("Künstler", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                                }
                            }
                        }
                    }
                }
                if (r.album.isNotEmpty()) item(key = "albums") {
                    Column {
                        SectionTitle("Alben")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(r.album, key = { it.id }) { a -> AlbumCard(app, a, Modifier.width(180.dp)) }
                        }
                    }
                }
                if (missing.isNotEmpty()) item(key = "missing") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionTitle("Nicht in deiner Bibliothek")
                        missing.forEach { LidarrRow(app, it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LidarrRow(app: DesktopApp, album: LidarrAlbum) {
    val key = "lidarr:${album.foreignId.ifEmpty { album.artistName + album.title }}"
    val state = if (album.inLibrary && album.monitored) true else requestState[key]
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(album.imageUrl, Modifier.size(48.dp), 6)
        Column(Modifier.weight(1f)) {
            Text(album.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(album.artistName, album.year, album.type).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (state) {
            true -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text("Angefordert", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            false -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            null -> OutlinedButton(onClick = { runRequest(key) { app.lidarr.requestAlbum(album) } }, shape = RoundedCornerShape(20.dp), modifier = Modifier.height(36.dp)) {
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                Text("Holen", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Alben & Künstler

@Composable
fun AlbumsScreen(app: DesktopApp) {
    val types = listOf("newest" to "Neu", "alphabeticalByName" to "A–Z", "frequent" to "Häufig", "random" to "Zufällig", "starred" to "Favoriten")
    var type by remember { mutableStateOf("newest") }
    val albums = rememberLoad("albums:$type") { app.client.albumList(type, 500, 0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 32.dp, end = 32.dp, top = 56.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Alben", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(end = 16.dp))
            types.forEach { (k, label) -> Chip(label, k == type) { type = k } }
        }
        LoadBox(albums) { list ->
            LazyVerticalGrid(GridCells.Adaptive(190.dp), contentPadding = PaddingValues(horizontal = 26.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { a -> AlbumCard(app, a) }
            }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else Navi.Text,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) MaterialTheme.colorScheme.primary else Navi.Surface2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
fun ArtistsScreen(app: DesktopApp) {
    val artists = rememberLoad("artists") { app.client.artists().flatMap { it.artist } }
    LoadBox(artists) { list ->
        LazyVerticalGrid(GridCells.Adaptive(170.dp), contentPadding = PaddingValues(start = 26.dp, end = 26.dp, top = 50.dp, bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Künstler", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(6.dp)) }
            items(list, key = { it.id }) { a ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { app.nav.go(Screen.ArtistPage(a.id)) }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(app.client.coverArtUrl(a.coverArt, 300), Modifier.fillMaxWidth().aspectRatio(1f), 999)
                    Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(if (a.albumCount == 1) "1 Album" else "${a.albumCount} Alben", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Album, Playlist, Mix

/** Verlauf aus abgedunkelter Akzentfarbe nach Panel-Farbe. */
@Composable
private fun accentBrush() = Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f).compositeOver(Panel), Panel))

/** Kopf für Album, Playlist und Mix. */
@Composable
private fun Header(
    app: DesktopApp, cover: String?, overline: String, title: String, subtitle: String?, meta: String, songs: List<Song>,
    onSubtitle: (() -> Unit)? = null, tile: (@Composable () -> Unit)? = null, actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().background(accentBrush()).padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.size(220.dp).shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))) {
            if (tile != null) tile() else Cover(cover, Modifier.fillMaxSize(), 14)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(overline, style = MaterialTheme.typography.labelSmall, color = Navi.Text.copy(alpha = 0.8f))
            Text(title, style = MaterialTheme.typography.displaySmall.copy(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = if (onSubtitle != null) Modifier.clickable(onClick = onSubtitle) else Modifier)
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { app.player.play(songs) }, enabled = songs.isNotEmpty(), shape = RoundedCornerShape(24.dp), modifier = Modifier.height(44.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Text("Abspielen", modifier = Modifier.padding(start = 6.dp), fontWeight = FontWeight.ExtraBold)
                }
                OutlinedButton(onClick = { app.player.play(songs, shuffle = true) }, enabled = songs.isNotEmpty(), shape = RoundedCornerShape(24.dp), modifier = Modifier.height(44.dp)) {
                    Icon(Icons.Rounded.Shuffle, null)
                    Text("Zufällig", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = { songs.firstOrNull()?.let(app::startRadio) }, enabled = songs.isNotEmpty(), shape = RoundedCornerShape(24.dp), modifier = Modifier.height(44.dp)) {
                    Icon(Icons.Rounded.Radio, null)
                    Text("Song-Radio", modifier = Modifier.padding(start = 6.dp))
                }
                actions()
            }
        }
    }
}

/** Spaltenköpfe passend zu [SongRow]. */
@Composable
private fun TableHeader(showCover: Boolean) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#", style = MaterialTheme.typography.labelSmall, color = Navi.Faint, modifier = Modifier.width(28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("TITEL", style = MaterialTheme.typography.labelSmall, color = Navi.Faint, modifier = Modifier.weight(1f))
            if (showCover) Text("ALBUM", style = MaterialTheme.typography.labelSmall, color = Navi.Faint, modifier = Modifier.weight(0.7f))
            Text("ZEIT", style = MaterialTheme.typography.labelSmall, color = Navi.Faint)
            Spacer(Modifier.width(48.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Navi.Outline))
    }
}

private fun totalTime(songs: List<Song>): String? = songs.sumOf { it.duration }.takeIf { it > 0 }?.let {
    val h = it / 3600
    val m = (it % 3600) / 60
    if (h > 0) "$h Std $m Min" else "$m Min"
}

/** Titelliste mit Seitenabstand unter einem vollbreiten Kopf. */
private fun LazyListScope.songList(app: DesktopApp, songs: List<Song>, showCover: Boolean) {
    item { TableHeader(showCover) }
    itemsIndexed(songs) { i, s -> Box(Modifier.padding(horizontal = 20.dp)) { SongListRow(app, s, i, showCover = showCover) { app.player.play(songs, i) } } }
}

/** Server nicht erreichbar: heruntergeladene Sammlung anzeigen, sonst den Fehler weiterreichen. */
private suspend fun <T> offlineFallback(app: DesktopApp, id: String, online: suspend () -> T, offline: (OfflineCollection) -> T): T = try {
    online()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    app.offline.collection(id)?.takeIf { it.songs.isNotEmpty() }?.let(offline) ?: throw e
}

/** Knopf „Herunterladen“ für Album, Playlist oder Mix; zeigt Fortschritt und entfernt beim zweiten Klick. */
@Composable
private fun DownloadToggle(app: DesktopApp, kind: String, id: String, name: String, coverArt: String?, songs: List<Song>) {
    val state = app.offline.state(id)
    val accent = MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = { if (state == DownloadState.None) app.offline.download(kind, id, name, coverArt, songs) else app.offline.remove(id) },
        enabled = songs.isNotEmpty() || state != DownloadState.None,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.height(44.dp),
    ) {
        when (state) {
            DownloadState.None -> Icon(Icons.Rounded.Download, null)
            DownloadState.Done -> Icon(Icons.Rounded.DownloadDone, null, tint = accent)
            else -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Text(
            when (state) {
                DownloadState.None -> "Herunterladen"
                DownloadState.Done -> "Heruntergeladen"
                else -> "Wird geladen …"
            },
            color = if (state == DownloadState.Done) accent else Navi.Text,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
fun AlbumScreen(app: DesktopApp, id: String) {
    val album = rememberLoad("album:$id:${app.version}") {
        offlineFallback(app, id, { app.client.album(id) }) { c -> Album(id = id, name = c.name, artist = c.songs.mapNotNull { it.artist }.distinct().singleOrNull(), coverArt = c.coverArt, song = c.songs) }
    }
    LoadBox(album) { a ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Header(
                    app, app.client.coverArtUrl(a.coverArt, 600),
                    listOfNotNull("ALBUM", a.year?.takeIf { it > 0 }?.toString()).joinToString(" · "),
                    a.name, a.artist,
                    listOfNotNull("${a.song.size} Titel", totalTime(a.song)).joinToString(" · "),
                    a.song,
                    onSubtitle = a.artistId?.let { aid -> { app.nav.go(Screen.ArtistPage(aid)) } },
                    actions = {
                        DownloadToggle(app, "album", a.id, a.name, a.coverArt, a.song)
                        AlbumMoreButton(app, a) { if (app.nav.current == Screen.AlbumPage(a.id)) app.nav.back() }
                    },
                )
            }
            songList(app, a.song, showCover = false)
        }
    }
}

@Composable
fun PlaylistScreen(app: DesktopApp, id: String) {
    val pl = rememberLoad("playlist:$id:${app.version}") {
        offlineFallback(app, id, { app.client.playlist(id) }) { c -> Playlist(id = id, name = c.name, coverArt = c.coverArt, entry = c.songs) }
    }
    LoadBox(pl) { p ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Header(
                    app, app.client.coverArtUrl(p.coverArt, 600), "PLAYLIST", p.name, p.comment?.takeIf { it.isNotBlank() } ?: p.owner,
                    listOfNotNull("${p.entry.size} Titel", totalTime(p.entry)).joinToString(" · "), p.entry,
                    actions = {
                        DownloadToggle(app, "playlist", p.id, p.name, p.coverArt, p.entry)
                        OutlinedButton(onClick = { app.nav.go(Screen.PlaylistEdit(p.id)) }, shape = RoundedCornerShape(24.dp), modifier = Modifier.height(44.dp)) {
                            Icon(Icons.Rounded.Edit, null)
                            Text("Bearbeiten", modifier = Modifier.padding(start = 6.dp))
                        }
                    },
                )
            }
            songList(app, p.entry, showCover = true)
        }
    }
}

@Composable
fun MixScreen(app: DesktopApp, id: String) {
    val mix = rememberLoad("mix:$id") {
        offlineFallback(app, id, { app.mixes.mix(id) ?: app.mixes.mixes().first { it.id == id } }) { c -> Mix(id, c.name, "Offline gespeichert", c.songs, 0.6f) }
    }
    LoadBox(mix) { m ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Header(
                    app, null, "MIX · TÄGLICH NEU", m.name, m.description, listOfNotNull("${m.songs.size} Titel", totalTime(m.songs)).joinToString(" · "), m.songs,
                    tile = {
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(mixColor(m.hue, 0.34f), mixColor(m.hue, 0.18f))))) {
                            Text("MIX", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(14.dp))
                            Text(m.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 3, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
                        }
                    },
                    actions = { DownloadToggle(app, "mix", m.id, m.name, null, m.songs) },
                )
            }
            songList(app, m.songs, showCover = true)
        }
    }
}

@Composable
fun ArtistScreen(app: DesktopApp, id: String) {
    val artist = rememberLoad("artist:$id:${app.version}") { app.client.artist(id) }
    LoadBox(artist) { a ->
        LazyVerticalGrid(GridCells.Adaptive(190.dp), contentPadding = PaddingValues(start = 26.dp, end = 26.dp, bottom = 16.dp), modifier = Modifier.fillMaxSize().background(accentBrush())) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 64.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(app.client.coverArtUrl(a.coverArt, 400), Modifier.size(180.dp).shadow(24.dp, CircleShape).clip(CircleShape), 999)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("KÜNSTLER", style = MaterialTheme.typography.labelSmall, color = Navi.Text.copy(alpha = 0.8f))
                        Text(a.name, style = MaterialTheme.typography.displaySmall.copy(fontSize = 56.sp, lineHeight = 60.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (a.albumCount == 1) "1 Album" else "${a.albumCount} Alben", color = Navi.Muted)
                        Button(
                            onClick = {
                                MainScope().launch {
                                    val songs = runCatching { app.client.similarSongsByArtist(a.id, 50) }.getOrDefault(emptyList())
                                    if (songs.isNotEmpty()) app.player.play(songs)
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.padding(top = 12.dp).height(44.dp),
                        ) {
                            Icon(Icons.Rounded.Radio, null)
                            Text("Künstler-Radio", modifier = Modifier.padding(start = 6.dp), fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
            if (app.helper.configured) item(span = { GridItemSpan(maxLineSpan) }) { ArtistYoutubeSection(app, a.name) }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Alben", Modifier.padding(start = 10.dp, top = 8.dp)) }
            items(a.album.sortedByDescending { it.year ?: 0 }, key = { it.id }) { al -> AlbumCard(app, al) }
        }
    }
}

/** Ausgeblendete YouTube-Veröffentlichungen (bis zum nächsten Laden). */
private val hiddenYoutube = mutableStateMapOf<String, Boolean>()

/** „Neu auf YouTube“: Veröffentlichungen des offiziellen Kanals, die noch nicht in der Bibliothek sind. */
@Composable
private fun ArtistYoutubeSection(app: DesktopApp, artist: String) {
    val load = rememberLoad("yt-artist:$artist:${app.version}") { app.helper.youtubeReleases(artist).items }
    val items = (load.first as? Load.Ok)?.value.orEmpty().filter { hiddenYoutube[it.key] != true }
    if (items.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "YouTube", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE53935)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text("Neu auf YouTube", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
            Text("noch nicht in deiner Bibliothek", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(items, key = { it.key }) { r: YtRelease ->
                val key = "yt:${r.key}"
                val state = requestState[key]
                Column(
                    Modifier.width(200.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.05f)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Cover(r.cover, Modifier.fillMaxWidth().aspectRatio(1f), 10)
                    Text(r.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.artistLine ?: r.artist, style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { runRequest(key) { app.helper.importYoutubeRelease(r.key) } },
                            enabled = state == null, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.weight(1f).height(32.dp),
                        ) {
                            when (state) {
                                false -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                true -> Text("Wird geladen", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold))
                                null -> Text("Importieren", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold))
                            }
                        }
                        if (state == null) OutlinedButton(
                            onClick = {
                                hiddenYoutube[r.key] = true
                                MainScope().launch { runCatching { app.helper.dismissYoutubeRelease(r.key) } }
                            },
                            shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(32.dp),
                        ) { Text("Ausblenden", style = MaterialTheme.typography.labelMedium, color = Navi.Text) }
                    }
                }
            }
        }
        if (requestState.any { (k, v) -> v && items.any { "yt:${it.key}" == k } }) {
            Text("Import läuft – der Fortschritt steht unter „Anfragen“.", style = MaterialTheme.typography.bodySmall, color = accent)
        }
    }
}
