package dev.navidroid.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.navidroid.NaviApp
import dev.navidroid.data.DaySlot
import dev.navidroid.data.Daylist
import dev.navidroid.data.DaylistRepository
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun DaySlot.brush(angle: Boolean = true) =
    if (angle) Brush.linearGradient(gradient.map { Color(it) }) else Brush.verticalGradient(gradient.map { Color(it) })

private val chipBg = Color.Black.copy(alpha = 0.22f)

/** Prüft regelmäßig, ob ein neues Zeitfenster begonnen hat. */
@Composable
private fun KeepDaylistFresh(app: NaviApp) {
    LaunchedEffect(Unit) {
        while (true) {
            app.daylist.ensure()
            delay(60_000)
        }
    }
}

@Composable
fun DaylistTile(app: NaviApp, nav: NavController, modifier: Modifier = Modifier) {
    KeepDaylistFresh(app)
    val player = LocalPlayer.current
    val d = app.daylist.current
    val slot = d?.moment?.slot ?: dev.navidroid.data.DayMoment.at(java.time.LocalDateTime.now()).slot
    Surface(
        onClick = { if (d != null) nav.navigate(Routes.DAYLIST) },
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.background(slot.brush()).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DAYLIST", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp), color = Color.White.copy(alpha = 0.88f))
                Text("· bis ${slot.endLabel}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.72f))
                if (app.daylist.loading) CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 2.dp)
            }
            Text(
                d?.name ?: if (app.daylist.loading) "Deine Daylist wird zusammengestellt …" else "Daylist",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, lineHeight = 28.sp),
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = d?.let { artistsLine(it) } ?: app.daylist.error
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.86f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    d?.tags?.take(2)?.forEach { TagChip(it, small = true) }
                }
                if (d != null) {
                    Surface(onClick = { player.play(d.songs.playableNow(app)) }, shape = CircleShape, color = Color.White, modifier = Modifier.size(46.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "Daylist abspielen", tint = Navi.Bg) }
                    }
                }
            }
        }
    }
}

private fun artistsLine(d: Daylist): String {
    val artists = d.songs.mapNotNull { it.artist }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    return if (artists.size <= 3) artists.joinToString(", ") else artists.take(3).joinToString(", ") + " und mehr"
}

@Composable
private fun TagChip(tag: String, small: Boolean = false, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Box(Modifier.padding(horizontal = if (small) 10.dp else 12.dp), contentAlignment = Alignment.Center) {
            Text("#$tag", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1)
        }
    }
    val m = Modifier.height(if (small) 26.dp else 30.dp)
    if (onClick != null) Surface(onClick = onClick, shape = RoundedCornerShape(15.dp), color = chipBg, modifier = m, content = content)
    else Surface(shape = RoundedCornerShape(15.dp), color = chipBg, modifier = m, content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DaylistScreen(app: NaviApp, nav: NavController) {
    KeepDaylistFresh(app)
    val player = LocalPlayer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val d = app.daylist.current
    if (d == null) {
        Column(Modifier.fillMaxSize().background(Navi.Bg)) {
            Box(Modifier.statusBarsPadding().padding(start = 20.dp, top = 8.dp)) { RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() }) }
            if (app.daylist.loading) CenteredProgress() else EmptyHint(app.daylist.error ?: "Noch keine Daylist")
        }
        return
    }
    val songs = d.songs
    val dl = app.offline.collection("mix:${DaylistRepository.OFFLINE_ID}")?.let { app.offline.progress(it) }
    LazyColumn(Modifier.fillMaxSize().background(Navi.Bg), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth().background(d.moment.slot.brush(angle = false)).statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() }, container = Color.Black.copy(alpha = 0.25f))
                Text(
                    listOfNotNull("DAYLIST", "${songs.size} TITEL", songs.sumOf { it.duration }.takeIf { it > 0 }?.let { formatLong(it).uppercase() }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
                    color = Color.White.copy(alpha = 0.88f),
                )
                Text(d.name, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp), color = Color.White)
                if (d.description.isNotBlank()) Text(d.description, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                downloadLine(dl)?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = Color.White) }
                if (d.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.tags.forEach { tag ->
                            TagChip(tag) {
                                scope.launch {
                                    try {
                                        val list = app.mixes.songsForTag(tag)
                                        if (list.isEmpty()) Toast.makeText(context, "Keine Titel zu #$tag gefunden", Toast.LENGTH_SHORT).show()
                                        else {
                                            player.play(list.playableNow(app))
                                            Toast.makeText(context, "Mix zu #$tag läuft", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Toast.makeText(context, e.message ?: "Fehler", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(onClick = { player.play(songs.playableNow(app)) }, shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.weight(1f).height(48.dp)) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = Navi.Bg)
                            Spacer(Modifier.width(6.dp))
                            Text("Abspielen", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = Navi.Bg)
                        }
                    }
                    DownloadToggle(
                        dl,
                        { app.offline.downloadMix(DaylistRepository.OFFLINE_ID, d.name, d.description, songs) },
                        { app.offline.remove("mix:${DaylistRepository.OFFLINE_ID}") },
                    )
                    Surface(
                        onClick = {
                            scope.launch {
                                try {
                                    app.client.createPlaylist(d.name, songs.map { it.id })
                                    PlaylistBus.changed()
                                    Toast.makeText(context, "Als Playlist „${d.name}“ gespeichert", Toast.LENGTH_SHORT).show()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "Fehler", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Als Playlist speichern", tint = Color.White) }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Schedule, null, tint = Navi.Muted, modifier = Modifier.size(14.dp))
                Text(
                    "Neu um ${d.moment.slot.startLabel} · ändert sich wieder um ${d.moment.slot.endLabel}" + if (d.byAi) "" else " · ohne KI erstellt",
                    style = MaterialTheme.typography.bodySmall,
                    color = Navi.Muted,
                )
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongRow(song, onClick = { playFrom(app, player, songs, i) }, showCover = true)
        }
    }
}
