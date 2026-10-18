package dev.navidroid.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

private val PanelBg = Color(0xFF10161F)

private sealed interface VideoStatus {
    data object Searching : VideoStatus
    data object Off : VideoStatus
    data object NotFound : VideoStatus
    data object Downloading : VideoStatus
    data class Failed(val message: String) : VideoStatus
    data class Ready(val file: File) : VideoStatus
}

/** Rechte Leiste: Musikvideo stumm und synchron zum Song (Design „Video, Geräte, Einschlaf-Timer“). */
@Composable
fun VideoPanel(app: DesktopApp, modifier: Modifier) {
    val s by app.player.state.collectAsStateCompat()
    val song = s.current
    var status by remember { mutableStateOf<VideoStatus>(VideoStatus.Searching) }
    LaunchedEffect(song?.id) {
        status = VideoStatus.Searching
        if (song == null) return@LaunchedEffect run { status = VideoStatus.NotFound }
        if (!app.helper.configured || !app.helper.videosEnabled) return@LaunchedEffect run { status = VideoStatus.Off }
        val url = app.helper.findVideo(song.artist.orEmpty(), song.title) ?: return@LaunchedEffect run { status = VideoStatus.NotFound }
        status = VideoStatus.Downloading
        status = try {
            VideoStatus.Ready(cachedVideo(app, url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VideoStatus.Failed(e.message ?: "Video konnte nicht geladen werden")
        }
    }

    // Eigener VLC-Player mit Bildausgabe in den Speicher (kein SwingPanel); wird beim Verlassen freigegeben
    val renderer = remember { runCatching { VideoRenderer() }.getOrNull() }
    DisposableEffect(renderer) { onDispose { renderer?.release() } }
    val file = (status as? VideoStatus.Ready)?.file
    LaunchedEffect(renderer, file) {
        val r = renderer ?: return@LaunchedEffect
        r.clear()
        if (file == null) {
            r.player.controls().stop()
            return@LaunchedEffect
        }
        r.player.media().play(file.absolutePath, ":start-time=${app.player.positionNow() / 1000.0}")
        var cooldownUntil = 0L
        while (true) {
            delay(500)
            syncVideo(r.player, app.player, cooldownUntil)?.let { cooldownUntil = it }
        }
    }
    var fullscreen by remember { mutableStateOf(false) }
    val showVideo = file != null && renderer != null

    Column(modifier.clip(RoundedCornerShape(16.dp)).background(PanelBg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelTabs(app)
            Spacer(Modifier.weight(1f))
            if (showVideo) RoundButton({ fullscreen = true }) { Icon(Icons.Rounded.Fullscreen, "Vollbild", tint = Navi.Text, modifier = Modifier.size(18.dp)) }
            RoundButton({ app.rightPanel = RightPanel.None }) { Icon(Icons.Rounded.Close, "Schließen", tint = Navi.Muted, modifier = Modifier.size(18.dp)) }
        }
        if (song == null) {
            Text("Nichts wird abgespielt.", color = Navi.Muted)
            return@Column
        }
        if (showVideo) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                VideoCanvas(renderer!!, Modifier.fillMaxSize())
                val h = renderer.sourceSize?.height
                Box(
                    Modifier.align(Alignment.BottomStart).padding(12.dp).height(24.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Synchron zum Song" + (h?.let { " · ${it}p" } ?: ""), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Cover(app.client.coverArtUrl(song.coverArt, 800), Modifier.fillMaxSize(), 12)
                if (status == VideoStatus.Searching || status == VideoStatus.Downloading) {
                    Row(
                        Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White)
                        Text(if (status == VideoStatus.Downloading) "Video wird geladen …" else "Suche Video …", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(song.title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = when (val st = status) {
                is VideoStatus.Ready -> "Offizielles Video, vom Helfer geladen"
                VideoStatus.Searching -> "Suche Musikvideo …"
                VideoStatus.Downloading -> "Video wird vom Helfer geladen …"
                VideoStatus.NotFound -> "Kein Musikvideo vorhanden"
                VideoStatus.Off -> "Musikvideos sind im Helfer ausgeschaltet"
                is VideoStatus.Failed -> "Video nicht verfügbar: ${st.message}"
            }
            Text(listOfNotNull(song.artist, sub).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Navi.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (renderer == null) "Für Videos wird VLC benötigt." else "Der Ton kommt weiter aus deiner Datei, das Video läuft stumm mit. Ohne geladenes Video siehst du hier das Cover.",
            style = MaterialTheme.typography.bodySmall, color = Navi.Muted, lineHeight = 18.sp,
        )
    }

    if (fullscreen && showVideo) {
        val windowState = rememberWindowState(placement = WindowPlacement.Fullscreen)
        Window(
            onCloseRequest = { fullscreen = false },
            state = windowState,
            title = "Musikvideo – ${song?.title.orEmpty()}",
            undecorated = true,
            onKeyEvent = { e -> if (e.key == Key.Escape) { fullscreen = false; true } else false },
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                VideoCanvas(renderer!!, Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.TopEnd).padding(24.dp)) {
                    RoundButton({ fullscreen = false }, Color.Black.copy(alpha = 0.45f)) {
                        Icon(Icons.Rounded.FullscreenExit, "Vollbild beenden", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** Umschalter Songtext | Video | Warteschlange (wie im Design). */
@Composable
private fun PanelTabs(app: DesktopApp) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Navi.Bg).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(RightPanel.Lyrics to "Songtext", RightPanel.Video to "Video", RightPanel.Queue to "Warteschlange").forEach { (panel, label) ->
            val selected = app.rightPanel == panel
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(if (selected) Navi.Surface2 else Color.Transparent)
                    .clickable { app.rightPanel = panel }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selected) Navi.Text else Navi.Muted) }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, background: Color = Navi.Surface2, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp).clip(CircleShape).background(background)) { content() }
}

/** Zeichnet das jeweils letzte Bild des Videos, eingepasst (Letterbox). */
@Composable
private fun VideoCanvas(renderer: VideoRenderer, modifier: Modifier) {
    Canvas(modifier) {
        renderer.frameId // Neuzeichnen bei jedem neuen Bild
        val img = renderer.image() ?: return@Canvas
        val scale = minOf(size.width / img.width, size.height / img.height)
        val w = img.width * scale
        val h = img.height * scale
        val x = (size.width - w) / 2f
        val y = (size.height - h) / 2f
        drawIntoCanvas {
            it.nativeCanvas.drawImageRect(
                img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeXYWH(x, y, w, h),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), null, true,
            )
        }
    }
}

/** Video an die Audio-Position angleichen; gibt eine neue Sperrzeit nach einem Sprung zurück. */
private fun syncVideo(video: MediaPlayer, audio: DesktopPlayer, cooldownUntil: Long): Long? {
    val playing = audio.state.value.playing
    val target = audio.positionNow()
    val st = video.status().state()
    val length = video.status().length()
    if (st == State.ENDED || st == State.STOPPED) {
        // Audio ist zurückgesprungen, das Video war schon zu Ende
        if (length <= 0 || target < length - 1_000) {
            video.controls().play()
            video.controls().setTime(target)
            return System.currentTimeMillis() + 1_500
        }
        return null
    }
    if (playing && !video.status().isPlaying) video.controls().play()
    if (!playing && video.status().isPlaying) video.controls().setPause(true)
    val now = System.currentTimeMillis()
    val t = video.status().time()
    if (now >= cooldownUntil && t >= 0 && abs(t - target) > 300 && (length <= 0 || target < length)) {
        video.controls().setTime(target)
        return now + 1_500
    }
    return null
}

/** Videos vom Helfer brauchen den Token-Header, den libVLC nicht senden kann – daher erst in den Cache laden. */
private suspend fun cachedVideo(app: DesktopApp, url: String): File = withContext(Dispatchers.IO) {
    val dir = File(Paths.cache, "videos").apply { mkdirs() }
    val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(24) + ".mp4"
    val file = File(dir, name)
    if (file.length() > 0) {
        file.setLastModified(System.currentTimeMillis())
        return@withContext file
    }
    val tmp = File(dir, "$name.part")
    val request = Request.Builder().url(url).apply { app.helper.authHeaders().forEach { (k, v) -> header(k, v) } }.build()
    try {
        app.http.newBuilder().readTimeout(java.time.Duration.ofMinutes(2)).build().newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Leere Antwort")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        }
        if (!tmp.renameTo(file)) throw IOException("Cache nicht beschreibbar")
    } finally {
        tmp.delete()
    }
    // Höchstens 12 Videos aufheben
    dir.listFiles { f -> f.name.endsWith(".mp4") }?.sortedByDescending { it.lastModified() }?.drop(12)?.forEach { it.delete() }
    file
}

/**
 * Stummer VLC-Player, der Bilder als RV32 (BGRA) in den Speicher schreibt; Compose zeichnet sie
 * als Skia-Image. Eigene Factory, weil der Audio-Player mit „--no-video“ läuft.
 */
private class VideoRenderer {
    private val factory = MediaPlayerFactory("--quiet", "--no-audio", "--no-osd", "--no-spu", "--no-video-title-show")
    val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
    private val lock = Any()
    private var pixels = ByteArray(0)
    private var frameW = 0
    private var frameH = 0
    private var rowBytes = 0
    private var image: Image? = null
    private var imageFrame = -1L
    var frameId by mutableLongStateOf(0L)
        private set
    var sourceSize by mutableStateOf<IntSize?>(null)
        private set

    init {
        val surface = factory.videoSurfaces().newVideoSurface(
            object : BufferFormatCallbackAdapter() {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    sourceSize = IntSize(sourceWidth, sourceHeight)
                    // Für die Seitenleiste reicht 720p; spart Speicher und Kopierarbeit
                    val scale = minOf(1f, 1280f / sourceWidth.coerceAtLeast(1), 720f / sourceHeight.coerceAtLeast(1))
                    return RV32BufferFormat((sourceWidth * scale).roundToInt().coerceAtLeast(2), (sourceHeight * scale).roundToInt().coerceAtLeast(2))
                }
            },
            object : RenderCallback {
                override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat) {
                    val buf = nativeBuffers.firstOrNull() ?: return
                    val pitch = bufferFormat.pitches[0]
                    val size = minOf(pitch * bufferFormat.lines[0], buf.capacity())
                    synchronized(lock) {
                        if (pixels.size != size) pixels = ByteArray(size)
                        buf.duplicate().apply { position(0) }.get(pixels, 0, size)
                        frameW = bufferFormat.width
                        frameH = minOf(bufferFormat.height, size / pitch)
                        rowBytes = pitch
                    }
                    frameId++
                }
            },
            true,
        )
        player.videoSurface().set(surface)
    }

    /** Nur im UI-Thread aufrufen. */
    fun image(): Image? {
        if (imageFrame != frameId) synchronized(lock) {
            if (frameW > 0 && frameH > 0) {
                image?.close()
                image = Image.makeRaster(ImageInfo(frameW, frameH, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), pixels, rowBytes)
            }
            imageFrame = frameId
        }
        return image
    }

    fun clear() {
        synchronized(lock) { frameW = 0; frameH = 0 }
        image?.close()
        image = null
        sourceSize = null
        frameId++
    }

    fun release() {
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        runCatching { factory.release() }
        image?.close()
        image = null
    }
}
