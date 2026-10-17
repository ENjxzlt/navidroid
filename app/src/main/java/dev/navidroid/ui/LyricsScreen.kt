package dev.navidroid.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.navidroid.NaviApp
import dev.navidroid.data.LyricLine
import dev.navidroid.data.Lyrics
import dev.navidroid.data.SongRef
import dev.navidroid.ui.theme.Navi

private val LyricStyle @Composable get() = MaterialTheme.typography.headlineLarge.copy(
    fontSize = 30.sp,
    lineHeight = 38.sp,
    fontWeight = FontWeight.ExtraBold,
)

@Composable
fun LyricsScreen(app: NaviApp, onClose: () -> Unit) {
    val player = LocalPlayer.current
    val s by player.state.collectAsStateWithLifecycle()
    val tint = rememberCoverTint(s.artworkUri, fallback = Navi.Surface)
    val soft = mutedOn(tint)

    var lyrics by remember { mutableStateOf<Load<Lyrics?>>(Load.Loading) }
    LaunchedEffect(s.mediaId) {
        val id = s.mediaId ?: return@LaunchedEffect
        lyrics = Load.Loading
        lyrics = Load.Ok(app.lyrics.load(SongRef(id, s.title, s.artist, s.album, s.durationMs)))
    }

    Column(Modifier.fillMaxSize().background(tint).systemBarsPadding()) {
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoverImage(s.artworkUri, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundIconButton(Icons.Rounded.KeyboardArrowDown, "Songtext schließen", onClose, container = Color.White.copy(alpha = 0.08f))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val l = lyrics) {
                Load.Loading -> CenteredProgress()
                is Load.Err -> EmptyHint(l.message)
                is Load.Ok -> when {
                    l.value == null -> NoLyrics(app, soft)
                    l.value.synced -> SyncedLyrics(l.value, tint)
                    else -> PlainLyrics(l.value, soft)
                }
            }
        }

        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)) {
            PlaybackProgress(s, soft)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = player::previous, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Vorheriger Titel", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                PlayPauseButton(s, tint, 64)
                IconButton(onClick = player::next, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Nächster Titel", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@Composable
private fun NoLyrics(app: NaviApp, soft: Color) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Kein Songtext gefunden", style = MaterialTheme.typography.titleLarge)
        Text(
            if (app.lyrics.onlineLyrics) "Weder Navidrome noch LRCLIB haben einen Text zu diesem Titel."
            else "Navidrome hat keinen Text zu diesem Titel. Online-Songtexte (LRCLIB) sind im Menü auf der Startseite ausgeschaltet.",
            color = soft,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, soft: Color) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        item {
            Text("Nicht synchronisiert", style = MaterialTheme.typography.labelSmall, color = soft, modifier = Modifier.padding(bottom = 12.dp))
        }
        itemsIndexed(lyrics.lines) { _, line ->
            Text(
                line.text.ifEmpty { " " },
                style = LyricStyle.copy(fontSize = 24.sp, lineHeight = 32.sp),
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        item { SourceNote(lyrics, soft) }
    }
}

@Composable
private fun SourceNote(lyrics: Lyrics, soft: Color) {
    Text(
        "Songtext: ${lyrics.source}",
        style = MaterialTheme.typography.bodySmall,
        color = soft,
        modifier = Modifier.padding(top = 32.dp, bottom = 16.dp),
    )
}

/**
 * Synchronisierter Songtext: Die aktive Zeile wandert nach oben, gesungene Wörter werden hell,
 * das gerade gesungene Wort leuchtet in der Akzentfarbe. Tippen auf eine Zeile springt dorthin.
 */
@Composable
private fun SyncedLyrics(lyrics: Lyrics, tint: Color) {
    val player = LocalPlayer.current
    val accent = MaterialTheme.colorScheme.primary
    val listState = rememberLazyListState()
    var position by remember { mutableLongStateOf(player.positionMs) }
    var lastUserScroll by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            position = player.positionMs
        }
    }
    // Scrollt der Nutzer selbst, pausiert das automatische Mitlaufen kurz.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start || it is DragInteraction.Stop) lastUserScroll = System.currentTimeMillis()
        }
    }

    val current = lyrics.lineAt(position)
    LaunchedEffect(current) {
        if (current >= 0 && System.currentTimeMillis() - lastUserScroll > 3_000) {
            listState.animateScrollToItem(current)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 120.dp, bottom = 320.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            LyricLineView(
                line = line,
                state = when {
                    i < current -> LineState.Past
                    i == current -> LineState.Active
                    else -> LineState.Upcoming
                },
                positionMs = if (i == current) position else 0L,
                accent = accent,
                onClick = { player.seekTo(line.startMs) },
            )
        }
        item { Box(Modifier.padding(horizontal = 24.dp)) { SourceNote(lyrics, mutedOn(tint)) } }
    }
}

private enum class LineState { Past, Active, Upcoming }

@Composable
private fun LyricLineView(line: LyricLine, state: LineState, positionMs: Long, accent: Color, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (state == LineState.Active) 1f else 0.94f, label = "lineScale")
    val text: AnnotatedString = if (state == LineState.Active && line.words.isNotEmpty()) {
        buildAnnotatedString {
            line.words.forEachIndexed { i, w ->
                val color = when {
                    positionMs >= w.endMs -> Color.White
                    positionMs >= w.startMs -> accent
                    else -> Color.White.copy(alpha = 0.38f)
                }
                withStyle(SpanStyle(color = color)) { append(w.text) }
                if (i < line.words.lastIndex) append(" ")
            }
        }
    } else {
        AnnotatedString(line.text.ifEmpty { "• • •" })
    }
    val baseColor = when (state) {
        LineState.Past -> Color.White.copy(alpha = 0.45f)
        LineState.Active -> Color.White
        LineState.Upcoming -> Color.White.copy(alpha = 0.32f)
    }
    Text(
        text = text,
        style = LyricStyle,
        color = baseColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 11.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
    )
}

