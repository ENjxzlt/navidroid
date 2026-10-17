package dev.navidroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import dev.navidroid.NaviApp
import dev.navidroid.lidarr.AiMissing
import dev.navidroid.lidarr.AiTrack
import dev.navidroid.ui.theme.Navi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Suchbegriff, den der Anfragen-Tab beim nächsten Öffnen übernimmt (z. B. aus KI-Vorschlägen). */
object RequestSearchBus {
    var pendingQuery by mutableStateOf<String?>(null)
}

class AiPlaylistVm(private val app: NaviApp) : ViewModel() {
    var wish by mutableStateOf("")
    var count by mutableStateOf(40)
    var preferRare by mutableStateOf(false)
    var suggestMissing by mutableStateOf(false)
    var refine by mutableStateOf("")

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var name by mutableStateOf("")
        private set
    var description by mutableStateOf("")
        private set
    val tracks = mutableStateListOf<AiTrack>()
    var missing by mutableStateOf<List<AiMissing>>(emptyList())
        private set
    var saving by mutableStateOf(false)
        private set

    val hasResult: Boolean get() = tracks.isNotEmpty()

    fun generate(mode: Mode = Mode.New) {
        val creds = app.session.current ?: return
        if (wish.isBlank() || loading) return
        loading = true
        error = null
        val previous = if (mode == Mode.New) null else tracks.map { it.id }
        val refineText = if (mode == Mode.Refine) refine.trim().ifEmpty { null } else null
        viewModelScope.launch {
            try {
                val result = app.helper.aiPlaylist(creds, wish, count, preferRare, suggestMissing, previous, refineText)
                name = result.name
                description = result.description
                tracks.clear()
                tracks += result.tracks
                missing = result.missing
                refine = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Playlist konnte nicht erstellt werden"
            } finally {
                loading = false
            }
        }
    }

    fun save(onSaved: (String) -> Unit) {
        if (saving || tracks.isEmpty()) return
        saving = true
        viewModelScope.launch {
            try {
                val pl = app.client.createPlaylist(name.ifBlank { "KI-Playlist" }, tracks.map { it.id })
                PlaylistBus.changed()
                pl?.id?.let(onSaved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Speichern fehlgeschlagen"
            } finally {
                saving = false
            }
        }
    }

    fun reset() {
        tracks.clear()
        missing = emptyList()
        error = null
    }

    enum class Mode { New, Variant, Refine }
}

private val ideas = listOf("Workout", "Fokus", "Sonntagmorgen", "Roadtrip", "Abends ruhig", "Party")

@Composable
fun AiPlaylistScreen(app: NaviApp, nav: NavController, onSearchMissing: (String) -> Unit) {
    val vm: AiPlaylistVm = viewModel { AiPlaylistVm(app) }
    when {
        vm.loading -> AiLoading(vm.hasResult)
        vm.hasResult -> AiResult(vm, nav, onSearchMissing)
        else -> AiForm(vm, onClose = { nav.popBackStack() })
    }
}

@Composable
private fun AiLoading(refining: Boolean) {
    Column(
        Modifier.fillMaxSize().background(Navi.Bg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(
            if (refining) "Claude passt die Playlist an …" else "Claude stellt deine Playlist zusammen …",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text("Das dauert meist eine knappe Minute.", style = MaterialTheme.typography.bodyMedium, color = Navi.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiForm(vm: AiPlaylistVm, onClose: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().background(Navi.Bg).statusBarsPadding().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Navi.Surface2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = accent)
                }
                Column(Modifier.weight(1f)) {
                    Text("Playlist mit KI", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
                    Text("Aus deiner Bibliothek · über deinen Claude-Worker", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                }
                RoundIconButton(Icons.Rounded.Close, "Schließen", onClose)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("WAS MÖCHTEST DU HÖREN?", style = MaterialTheme.typography.labelSmall, color = Navi.Muted)
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Navi.Surface)
                        .border(1.dp, accent, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    if (vm.wish.isEmpty()) {
                        Text("z. B. „Ruhige Musik zum Kochen, eher Indie und ein bisschen Soul“", color = Navi.Muted, style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = vm.wish,
                        onValueChange = { vm.wish = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Navi.Text),
                        cursorBrush = SolidColor(accent),
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ideas.forEach { idea -> PillChip(idea, selected = false) { vm.wish = if (vm.wish.isBlank()) idea else "${vm.wish.trimEnd()}, $idea" } }
                }
            }

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Navi.Surface2).padding(horizontal = 14.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Länge", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4), modifier = Modifier.weight(1f))
                    SegmentedControl(
                        options = listOf(20 to "20", 40 to "40", 60 to "60"),
                        selected = vm.count,
                        onSelect = { vm.count = it },
                        modifier = Modifier.width(186.dp),
                    )
                }
                ToggleLine("Selten gehörte bevorzugen", "Mehr Neues aus deiner Sammlung", vm.preferRare) { vm.preferRare = it }
                ToggleLine("Fehlende Alben vorschlagen", "Passende Musik, die du noch nicht hast", vm.suggestMissing) { vm.suggestMissing = it }
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
        Surface(
            onClick = { vm.generate() },
            enabled = vm.wish.isNotBlank(),
            shape = RoundedCornerShape(26.dp),
            color = if (vm.wish.isNotBlank()) accent else Navi.Outline,
            modifier = Modifier.navigationBarsPadding().padding(20.dp).fillMaxWidth().height(52.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("Playlist erstellen", style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun ToggleLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFC9CDD4))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Navi.Faint)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AiResult(vm: AiPlaylistVm, nav: NavController, onSearchMissing: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    var showMissing by remember { mutableStateOf(false) }
    val first = vm.tracks.firstOrNull()
    val tint = rememberCoverTint(
        (androidx.compose.ui.platform.LocalContext.current.applicationContext as NaviApp).client.coverArtUrl(first?.coverArt, 300),
        fallback = Navi.Surface,
        alternative = albumCoverAlternative(first?.albumId),
    )

    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        Column(
            Modifier.fillMaxWidth().background(tint).statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Rounded.Close, "Verwerfen", { vm.reset() }, container = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("VON CLAUDE", style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(14.dp))) {
                    // Mosaik aus den ersten vier Covern
                    Column {
                        listOf(0, 2).forEach { row ->
                            Row {
                                listOf(0, 1).forEach { col ->
                                    val t = vm.tracks.getOrNull(row + col)
                                    CoverArt(t?.coverArt, 300, Modifier.size(42.dp), albumCoverAlternative(t?.albumId))
                                }
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(vm.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (vm.description.isNotBlank()) Text(vm.description, style = MaterialTheme.typography.bodyMedium, color = mutedOn(tint), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("${vm.tracks.size} Titel · ${formatLong(vm.tracks.sumOf { it.duration })}", style = MaterialTheme.typography.bodySmall, color = mutedOn(tint))
                }
            }
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(Color.White.copy(alpha = 0.06f)).padding(start = 14.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (vm.refine.isEmpty()) Text("Anpassen, z. B. „mehr Frauenstimmen“", color = mutedOn(tint), style = MaterialTheme.typography.bodyMedium)
                    BasicTextField(
                        value = vm.refine,
                        onValueChange = { vm.refine = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Navi.Text),
                        cursorBrush = SolidColor(accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { vm.generate(AiPlaylistVm.Mode.Refine) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Surface(
                    onClick = { vm.generate(AiPlaylistVm.Mode.Refine) },
                    enabled = vm.refine.isNotBlank(),
                    shape = CircleShape,
                    color = if (vm.refine.isNotBlank()) accent else Navi.Outline,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Anpassen", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp)) }
                }
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(vm.tracks, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().height(58.dp).padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CoverArt(t.coverArt, 300, Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)), albumCoverAlternative(t.albumId))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(t.artist, t.album).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { vm.tracks.remove(t) }) { Icon(Icons.Rounded.Close, "Aus Vorschlag entfernen", tint = Navi.Faint) }
                }
            }
            if (vm.missing.isNotEmpty()) {
                item {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0xFF3A3F48), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${vm.missing.size} passende Alben fehlen dir", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold))
                            Text("z. B. ${vm.missing.first().artist} – ${vm.missing.first().album}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Surface(onClick = { showMissing = true }, shape = RoundedCornerShape(17.dp), color = Color.Transparent, border = BorderStroke(1.dp, Navi.Outline), modifier = Modifier.height(34.dp)) {
                            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text("Ansehen", style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Navi.Bg).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(onClick = { vm.generate(AiPlaylistVm.Mode.Variant) }, shape = RoundedCornerShape(26.dp), color = Color.Transparent, border = BorderStroke(1.dp, Navi.Outline), modifier = Modifier.height(52.dp)) {
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Neu", style = MaterialTheme.typography.labelLarge)
                }
            }
            Surface(
                onClick = { vm.save { id -> nav.popBackStack(); nav.openPlaylist(id) } },
                enabled = !vm.saving && vm.tracks.isNotEmpty(),
                shape = RoundedCornerShape(26.dp),
                color = accent,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (vm.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Playlist speichern", style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }

    if (showMissing) {
        AlertDialog(
            onDismissRequest = { showMissing = false },
            title = { Text("Passende Alben, die dir fehlen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    vm.missing.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${m.artist} – ${m.album}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                if (m.reason.isNotBlank()) Text(m.reason, style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                            }
                            TextButton(onClick = { showMissing = false; onSearchMissing("${m.artist} ${m.album}") }) { Text("Anfordern") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMissing = false }) { Text("Schließen") } },
        )
    }
}
