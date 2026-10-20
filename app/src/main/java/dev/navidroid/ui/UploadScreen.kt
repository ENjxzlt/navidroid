package dev.navidroid.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.navidroid.NaviApp
import dev.navidroid.upload.UploadAlbum
import dev.navidroid.upload.UploadState
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.launch

private val warn = Color(0xFFEDCF83)
private val okGreen = Color(0xFF7BD88F)

@Composable
fun UploadScreen(app: NaviApp, nav: NavController) {
    val context = LocalContext.current
    val up = app.uploads
    val start = rememberDownloadStarter() // fragt nach der Benachrichtigungs-Erlaubnis
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) up.add(context, uris)
    }
    val pick = { picker.launch(arrayOf("audio/*")) }
    val busy = up.running || up.finished

    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        Row(
            Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() })
            Column(Modifier.weight(1f)) {
                Text("Hochladen", style = MaterialTheme.typography.titleLarge)
                if (up.files.isNotEmpty()) {
                    Text(
                        "${up.files.size} Dateien · ${up.albums.size} ${if (up.albums.size == 1) "Album" else "Alben"} · ${formatBytes(up.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Navi.Muted,
                    )
                }
            }
            if (!busy && up.files.isNotEmpty()) OutlinedButton(onClick = pick) { Text("+ Dateien") }
            if (up.finished) TextButton(onClick = { up.clear() }) { Text("Neu") }
        }
        if (!busy) {
            SegmentedControl(
                options = listOf(false to "Musik", true to "Hörspiel"),
                selected = up.audiobook,
                onSelect = { up.useAudiobook(it) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        when {
            up.files.isEmpty() && !up.reading -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (up.audiobook) "Eigene Hörspiele hinzufügen" else "Eigene Musik hinzufügen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
                Text(
                    if (up.audiobook) {
                        "Wähle die Dateien deiner gekauften oder selbst gerippten Folgen. Je Folge gibst du Serie, Nummer und Titel an; " +
                            "sie landen in der Hörspiel-Bibliothek und erscheinen sortiert in der Übersicht."
                    } else {
                        "Wähle MP3-, FLAC-, M4A- oder andere Audiodateien. Die App liest die Tags und ordnet sie nach Album; du kannst alles vor dem Hochladen prüfen."
                    },
                    color = Navi.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                )
                Button(onClick = pick) { Text("Dateien auswählen") }
            }
            up.reading && up.files.isEmpty() -> CenteredProgress()
            busy -> ProgressView(app)
            else -> {
                LazyColumn(Modifier.weight(1f).imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(up.albums, key = { System.identityHashCode(it) }) { a -> AlbumCard(app, a) }
                    if (up.reading) item { CenteredProgress(Modifier.fillMaxWidth().padding(16.dp)) }
                }
                Button(
                    onClick = { start { up.start(context) } },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp),
                ) { Text("${up.files.size} Titel hochladen", fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}

@Composable
private fun AlbumCard(app: NaviApp, a: UploadAlbum) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { runCatching { readCoverImage(context, uri) }.onSuccess { a.cover = it } }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Navi.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Navi.Surface2)
                    .clickable { coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) {
                val cover = a.cover
                val bmp = remember(cover) { cover?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                if (bmp != null) Image(bmp.asImageBitmap(), "Cover", Modifier.size(64.dp), contentScale = ContentScale.Crop)
                else Text("+ BILD", style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    a.album, { a.album = it },
                    label = { Text(if (app.uploads.audiobook) "Titel der Folge" else "Album") },
                    singleLine = true,
                    isError = a.album.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val audiobook = app.uploads.audiobook
            OutlinedTextField(
                a.artist, { a.artist = it },
                label = { Text(if (audiobook) "Serie" else "Künstler") },
                singleLine = true,
                isError = a.artist.isBlank(),
                modifier = Modifier.weight(1f),
            )
            if (audiobook) {
                OutlinedTextField(
                    a.episode, { a.episode = it.filter(Char::isDigit).take(3) },
                    label = { Text("Folge") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(92.dp),
                )
            } else {
                OutlinedTextField(
                    a.year, { a.year = it.filter(Char::isDigit).take(4) },
                    label = { Text("Jahr") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(92.dp),
                )
            }
        }
        a.files.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${f.track}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = Navi.Faint, modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = if (f.hadTags) Navi.Text else warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (f.hadTags) "${f.fileName.substringAfterLast('.', "").uppercase()} · ${formatBytes(f.size)}" else "ohne vollständige Tags · ${f.fileName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Navi.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { app.uploads.remove(f) }) { Icon(Icons.Rounded.Close, "Entfernen", tint = Navi.Faint, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun ProgressView(app: NaviApp) {
    val up = app.uploads
    val accent = MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Navi.Surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row {
                    Text(
                        if (up.finished) "Fertig · ${up.files.count { it.state == UploadState.Done }} hochgeladen" else "${up.doneCount} von ${up.files.size} Titeln",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${formatBytes(up.sentBytes)} von ${formatBytes(up.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                }
                LinearProgressIndicator(
                    progress = { if (up.totalBytes > 0) up.sentBytes.toFloat() / up.totalBytes else 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = accent,
                    trackColor = Navi.Surface2,
                    drawStopIndicator = {},
                )
                Text(
                    if (up.finished) "Navidrome hat die neuen Titel eingelesen." else "Läuft im Hintergrund weiter. Danach liest Navidrome die Alben ein.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Navi.Muted,
                )
                if (up.running) TextButton(onClick = { up.cancel() }) { Text("Abbrechen") }
            }
        }
        up.albums.forEach { a ->
            items(a.files, key = { it.uri.toString() }) { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(f.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(f.error ?: a.album, style = MaterialTheme.typography.bodySmall, color = if (f.error != null) MaterialTheme.colorScheme.error else Navi.Muted, maxLines = 2)
                    }
                    val (label, color) = when (f.state) {
                        UploadState.Waiting -> "wartet" to Navi.Faint
                        UploadState.Running -> "${(f.progress * 100).toInt()} %" to accent
                        UploadState.Done -> "Fertig" to okGreen
                        UploadState.Exists -> "Schon vorhanden" to Navi.Muted
                        UploadState.Failed -> "Fehler" to MaterialTheme.colorScheme.error
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = color)
                }
            }
        }
    }
}
