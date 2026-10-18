package dev.navidroid.ui

import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import dev.navidroid.NaviApp
import dev.navidroid.data.Album
import dev.navidroid.data.Song
import dev.navidroid.playback.PlayerConnection
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalPlayer = staticCompositionLocalOf<PlayerConnection> { error("Kein PlayerConnection bereitgestellt") }

// ---- Navigation ----

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val ALBUM = "album/{id}"
    const val ARTIST = "artist/{id}"
    const val PLAYLIST = "playlist/{id}"
    const val PLAYLIST_EDIT = "playlist/{id}/edit"
    const val PLAYER = "player"
    const val LYRICS = "lyrics"
    const val AI_PLAYLIST = "ai-playlist"
    const val SETTINGS = "settings"
    const val DOWNLOADS = "downloads"
    const val DAYLIST = "daylist"
    const val ALBUM_EDIT = "album/{id}/edit"
    const val MIXES = "mixes"
    const val TRASH = "trash"
    const val UPLOAD = "upload"
    const val OFFLINE_COLLECTION = "offline/{id}"
}

fun NavController.openAlbum(id: String) = navigate("album/$id")
fun NavController.openArtist(id: String) = navigate("artist/$id")
fun NavController.openPlaylist(id: String) = navigate("playlist/$id")
fun NavController.editPlaylist(id: String) = navigate("playlist/$id/edit")

// ---- Laden mit Zustand, der Navigation überlebt ----

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val reload: () -> Unit)

class LoadStore : ViewModel() {
    val states = mutableStateMapOf<String, Load<Any?>>()
}

/** Lädt Daten einmalig pro [key] im ViewModel des aktuellen Navigationsziels. */
@Composable
fun <T> rememberLoad(key: String, block: suspend () -> T): Loader<T> {
    val store: LoadStore = viewModel()
    val currentBlock by rememberUpdatedState(block)
    val start = {
        store.states[key] = Load.Loading
        store.viewModelScope.launch {
            store.states[key] = try {
                Load.Ok(currentBlock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Err(e.message ?: e.javaClass.simpleName)
            }
        }
        Unit
    }
    LaunchedEffect(key) { if (key !in store.states) start() }
    @Suppress("UNCHECKED_CAST")
    val state = (store.states[key] ?: Load.Loading) as Load<T>
    return Loader(state, start)
}

@Composable
fun <T> LoadContent(loader: Loader<T>, content: @Composable (T) -> Unit) {
    when (val s = loader.state) {
        Load.Loading -> CenteredProgress()
        is Load.Err -> ErrorView(s.message, loader.reload)
        is Load.Ok -> content(s.value)
    }
}

@Composable
fun CenteredProgress(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.size(12.dp))
        OutlinedButton(onClick = onRetry) { Text("Erneut versuchen") }
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Navi.Muted, textAlign = TextAlign.Center)
    }
}

// ---- Farben aus dem Cover ----

/** Aus einem Cover gewonnene Farben: dunkle Hintergrundtönung und ein heller Akzent. */
data class CoverColors(val tint: Color, val accent: Color?)

private val coverColorCache = LruCache<String, CoverColors>(96)

private fun hslColor(rgb: Int, saturation: (Float) -> Float, lightness: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(rgb, hsl)
    hsl[1] = saturation(hsl[1])
    hsl[2] = lightness
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun coverColors(palette: Palette): CoverColors? {
    val base = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch ?: return null
    val tint = hslColor(base.rgb, { it.coerceAtMost(0.55f) }, 0.13f)
    // Akzent: kräftigste Farbe des Covers, aufgehellt, damit sie auf Dunkel gut lesbar ist.
    // Fast graue Cover behalten den Standard-Akzent.
    val accentSwatch = palette.lightVibrantSwatch ?: palette.vibrantSwatch ?: palette.lightMutedSwatch ?: palette.dominantSwatch
    val accent = accentSwatch?.let { sw ->
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(sw.rgb, it) }
        if (hsl[1] < 0.12f) null else hslColor(sw.rgb, { it.coerceIn(0.45f, 0.85f) }, 0.72f)
    }
    return CoverColors(tint, accent)
}

/** Ersatzquelle für ein Cover, die erst bei Bedarf ermittelt wird (z. B. Cover Art Archive). */
typealias CoverAlternative = suspend () -> Any?

/** Lädt das Cover klein und berechnet seine Farben; Ergebnisse werden zwischengespeichert. */
@Composable
fun rememberCoverColors(model: Any?, alternative: CoverAlternative? = null): CoverColors? {
    val context = LocalContext.current
    val key = model?.toString()
    var colors by remember(key) { mutableStateOf(key?.let { coverColorCache.get(it) }) }
    LaunchedEffect(key) {
        if (colors != null) return@LaunchedEffect
        suspend fun bitmapOf(data: Any?) = data?.let {
            val request = ImageRequest.Builder(context).data(it).size(96).allowHardware(false).build()
            (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
        }
        val bitmap = bitmapOf(model) ?: alternative?.let { bitmapOf(it()) } ?: return@LaunchedEffect
        val computed = withContext(Dispatchers.Default) { coverColors(Palette.from(bitmap).generate()) } ?: return@LaunchedEffect
        if (key != null) coverColorCache.put(key, computed)
        colors = computed
    }
    return colors
}

/** Dunkle, aus dem Cover gewonnene Hintergrundfarbe, auf der weißer Text gut lesbar bleibt. */
@Composable
fun rememberCoverTint(model: Any?, fallback: Color = Navi.Surface, alternative: CoverAlternative? = null): Color =
    animateColorAsState(rememberCoverColors(model, alternative)?.tint ?: fallback, tween(500), label = "coverTint").value

/** Setzt die Akzentfarbe des Themes auf die Farbe des Covers (mit sanftem Übergang). */
@Composable
fun CoverAccentTheme(model: Any?, alternative: CoverAlternative? = null, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as NaviApp
    val coverAccent = if (app.settings.coverColors) rememberCoverColors(model, alternative)?.accent else null
    val accent by animateColorAsState(coverAccent ?: Navi.Accent, tween(600), label = "coverAccent")
    val scheme = MaterialTheme.colorScheme.copy(
        primary = accent,
        secondary = accent,
        onPrimary = Navi.Bg,
        onSecondary = Navi.Bg,
    )
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

/** Gedämpfte Textfarbe passend zur Tönung (hell, leicht eingefärbt). */
fun mutedOn(tint: Color): Color = Color(ColorUtils.blendARGB(Color.White.toArgb(), tint.toArgb(), 0.28f))

// ---- Bausteine ----

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    container: Color = Navi.Surface2,
    tint: Color = Navi.Text,
    size: Int = 44,
) {
    Surface(onClick = onClick, shape = CircleShape, color = container, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, tint = tint) }
    }
}

@Composable
fun PillChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, Navi.Outline),
        modifier = Modifier.height(36.dp),
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold),
                color = if (selected) MaterialTheme.colorScheme.onPrimary else Color(0xFFD5D8DE),
            )
        }
    }
}

@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options) { (key, label) -> PillChip(label, key == selected) { onSelect(key) } }
    }
}

@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    badge: (T) -> Int = { 0 },
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Navi.Surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (key, label) ->
            val active = key == selected
            Surface(
                onClick = { onSelect(key) },
                shape = RoundedCornerShape(10.dp),
                color = if (active) Navi.Outline else Color.Transparent,
                modifier = Modifier.weight(1f).height(40.dp),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                        color = if (active) Navi.Text else Navi.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val count = badge(key)
                    if (count > 0) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                if (count > 99) "99+" else "$count",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing),
                                color = MaterialTheme.colorScheme.onPrimary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(Navi.Surface).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Navi.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = Navi.Muted, style = MaterialTheme.typography.bodyLarge)
            if (enabled) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Navi.Text),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (value.isNotEmpty() && enabled) {
            IconButton(onClick = { onValueChange("") }) { Icon(Icons.Rounded.Close, "Leeren", tint = Navi.Muted) }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun CoverArt(id: String?, size: Int, modifier: Modifier = Modifier, alternative: CoverAlternative? = null) {
    val app = LocalContext.current.applicationContext as NaviApp
    val url = remember(id, size) { app.client.coverArtUrl(id, size) }
    CoverImage(url, modifier, alternative)
}

/** Cover mit Platzhalter; schlägt das Laden fehl, wird einmal die [alternative] Quelle versucht. */
@Composable
fun CoverImage(model: Any?, modifier: Modifier = Modifier, alternative: CoverAlternative? = null) {
    val scope = rememberCoroutineScope()
    var current by remember(model) { mutableStateOf(model) }
    var triedAlternative by remember(model) { mutableStateOf(false) }
    val useAlternative = {
        if (!triedAlternative && alternative != null) {
            triedAlternative = true
            scope.launch { alternative()?.let { current = it } }
        }
    }
    LaunchedEffect(model) { if (model == null) useAlternative() }
    Box(modifier.background(Navi.Surface2), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Navi.Muted.copy(alpha = 0.4f))
        if (current != null) {
            AsyncImage(
                model = current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { useAlternative() },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** Ersatz-Cover für Titel: über das Album des Titels (nur bei Bedarf nachgeschlagen). */
@Composable
fun albumCoverAlternative(albumId: String?): CoverAlternative? {
    val app = LocalContext.current.applicationContext as NaviApp
    return remember(albumId) { albumId?.let { id -> { app.covers.forAlbum(id) } } }
}

@Composable
fun AlbumCard(album: Album, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val app = LocalContext.current.applicationContext as NaviApp
        val alternative: CoverAlternative = remember(album.id) {
            { app.covers.forAlbumInfo(album.musicBrainzId, album.name, album.artist, 250) }
        }
        CoverArt(album.coverArt, 300, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)), alternative)
        Column(Modifier.padding(bottom = 4.dp)) {
            Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                album.artist.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = Navi.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Titelzeile im Stil des Redesigns: Nummer oder Cover, Titel/Künstler, Dauer, Menü.
 * Der gerade laufende Titel wird in der Akzentfarbe hervorgehoben.
 */
@Composable
fun SongRow(song: Song, onClick: () -> Unit, number: Int? = null, showCover: Boolean = false) {
    val player = LocalPlayer.current
    val playerState by player.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val current = playerState.mediaId == song.id
    val accent = MaterialTheme.colorScheme.primary
    var menu by remember { mutableStateOf(false) }
    val offline = rememberOffline()
    val startDownload = rememberDownloadStarter()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (current) Navi.Surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            showCover -> CoverArt(song.coverArt, 300, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), albumCoverAlternative(song.albumId))
            current -> Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, "Läuft gerade", tint = accent, modifier = Modifier.size(18.dp))
            }
            number != null -> Text(
                "$number",
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Navi.Faint,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (current) accent else Navi.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SongDownloadMark(song.id)
                Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Länge 0 heißt: Navidrome konnte die Datei nicht auslesen – dann lieber nichts anzeigen.
        if (song.duration > 0) {
            Text(formatDuration(song.duration), style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Optionen", tint = Navi.Muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Als Nächstes abspielen") },
                    onClick = {
                        player.playNext(listOf(song))
                        menu = false
                        Toast.makeText(context, "Wird als Nächstes gespielt", Toast.LENGTH_SHORT).show()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Song-Radio starten") },
                    onClick = {
                        menu = false
                        startRadio(context.applicationContext as NaviApp, player, song, context)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Zur Playlist hinzufügen …") },
                    onClick = {
                        menu = false
                        addToPlaylist(listOf(song), song.title, song.artist.orEmpty(), song.coverArt)
                    },
                )
                if (offline.songs.containsKey(song.id)) {
                    DropdownMenuItem(text = { Text("Download entfernen") }, onClick = { menu = false; offline.removeSong(song.id) })
                } else {
                    DropdownMenuItem(text = { Text("Herunterladen") }, onClick = {
                        menu = false
                        startDownload { offline.downloadSongs(listOf(song)) }
                    })
                }
                DropdownMenuItem(
                    text = { Text("Zur Warteschlange hinzufügen") },
                    onClick = {
                        player.enqueue(listOf(song))
                        menu = false
                        Toast.makeText(context, "Zur Warteschlange hinzugefügt", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatMs(ms: Long): String = formatDuration((ms / 1000).toInt())

/** "1 Std. 0 Min." bzw. "42 Min." für Album-Gesamtdauern. */
fun formatLong(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "$h Std. $m Min." else "$m Min."
}
