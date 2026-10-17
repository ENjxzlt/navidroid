package dev.navidroid.ui

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Spielt das Musikvideo stumm und synchron zum laufenden Song ab: Ton, Warteschlange und
 * Benachrichtigung bleiben beim normalen Player, das Video folgt dessen Position.
 */
@OptIn(UnstableApi::class)
@Composable
fun SyncedVideo(url: String, headers: Map<String, String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = LocalPlayer.current
    val video = remember(url) {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
            .apply {
                volume = 0f
                setMediaItem(MediaItem.fromUri(url))
                prepare()
            }
    }
    DisposableEffect(video) { onDispose { video.release() } }
    LaunchedEffect(video) {
        while (true) {
            val playing = player.state.value.isPlaying
            val target = player.positionMs
            if (abs(video.currentPosition - target) > 400) video.seekTo(target)
            video.playWhenReady = playing
            delay(1_000)
        }
    }
    var fullscreen by remember { mutableStateOf(false) }

    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
        if (!fullscreen) VideoSurface(video, Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text("MUSIKVIDEO", style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
            RoundIconButton(Icons.Rounded.Fullscreen, "Vollbild", { fullscreen = true }, container = Color.Black.copy(alpha = 0.45f))
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                VideoSurface(video, Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.TopEnd).padding(24.dp)) {
                    RoundIconButton(Icons.Rounded.Close, "Vollbild beenden", { fullscreen = false }, container = Color.Black.copy(alpha = 0.45f))
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(video: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = video
            }
        },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}
