package dev.navidroid.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Playlist bearbeiten: umbenennen, sortieren (ziehen), Titel entfernen, löschen. */
@Composable
fun PlaylistEditScreen(app: DesktopApp, id: String) { Text("Playlist bearbeiten") }

/** Dialog „Neue Playlist“ aus der Seitenleiste. */
@Composable
fun NewPlaylistDialog(app: DesktopApp, onDismiss: () -> Unit) { onDismiss() }
