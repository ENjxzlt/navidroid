package dev.navidroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.navidroid.NaviApp
import dev.navidroid.ui.theme.Navi

/** Eintrag „Mixe & Daylist“ in der Playlist-Liste der Bibliothek. */
@Composable
fun MixesFolderRow(app: NaviApp, onClick: () -> Unit) {
    val loader = rememberLoad("mixes") { app.mixes.mixes() }
    val count = (loader.state as? Load.Ok)?.value?.size
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(Navi.Surface2), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Mixe & Daylist", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            Text(
                listOfNotNull(count?.let { if (it == 1) "1 Mix" else "$it Mixe" }, "Daylist", "täglich neu").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Navi.Muted,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Navi.Faint)
    }
}

@Composable
fun MixesFolderScreen(app: NaviApp, nav: NavController) {
    val loader = rememberLoad("mixes") { app.mixes.mixes() }
    val daylist = app.daylist.current
    Column(Modifier.fillMaxSize().background(Navi.Bg)) {
        Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", { nav.popBackStack() })
            Text("Mixe & Daylist", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 12.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (daylist != null) item {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.navigate(Routes.DAYLIST) }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(daylist.moment.slot.gradient.map { Color(it) })))
                    Column(Modifier.weight(1f)) {
                        Text("Daylist", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(daylist.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("bis ${daylist.moment.slot.endLabel} · ${daylist.songs.size} Titel", style = MaterialTheme.typography.bodySmall, color = Navi.Muted)
                    }
                }
            }
            when (val st = loader.state) {
                Load.Loading -> item { CenteredProgress(Modifier.fillMaxWidth().padding(24.dp)) }
                is Load.Err -> item { Text(st.message, color = Navi.Muted, modifier = Modifier.padding(20.dp)) }
                is Load.Ok -> items(st.value, key = { it.id }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.navigate("mix/${m.id}") }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(mixColor(m.hue)), contentAlignment = Alignment.BottomStart) {
                            Text("MIX", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(6.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${m.songs.size} Titel · ${m.description}", style = MaterialTheme.typography.bodySmall, color = Navi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
