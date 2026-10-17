package dev.navidroid.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.navidroid.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val apk: File, val needsPermission: Boolean) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateVm(private val updater: Updater) : ViewModel() {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    var dialogVisible by mutableStateOf(false)
        private set
    private var checkedOnStart = false

    /** Stiller Check beim Start: Dialog erscheint nur, wenn es ein Update gibt. */
    fun checkOnStart() {
        if (checkedOnStart || !updater.isConfigured) return
        checkedOnStart = true
        check(manual = false)
    }

    fun checkManually() = check(manual = true)

    private fun check(manual: Boolean) {
        if (state is UpdateState.Checking || state is UpdateState.Downloading) {
            dialogVisible = true
            return
        }
        state = UpdateState.Checking
        dialogVisible = manual
        viewModelScope.launch {
            state = try {
                updater.check()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: "Update-Prüfung fehlgeschlagen")
            }
            if (state is UpdateState.Available) dialogVisible = true
        }
    }

    fun download(info: UpdateInfo) {
        state = UpdateState.Downloading(info, 0f)
        viewModelScope.launch {
            try {
                val apk = updater.download(info) { p -> state = UpdateState.Downloading(info, p) }
                state = UpdateState.ReadyToInstall(info, apk, needsPermission = false)
                install()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = UpdateState.Failed(e.message ?: "Download fehlgeschlagen")
            }
        }
    }

    fun install() {
        val s = state as? UpdateState.ReadyToInstall ?: return
        if (!updater.canInstall()) {
            state = s.copy(needsPermission = true)
            updater.openInstallPermissionSettings()
            return
        }
        state = s.copy(needsPermission = false)
        updater.install(s.apk)
    }

    fun dismiss() {
        dialogVisible = false
    }
}

@Composable
fun UpdateDialog(vm: UpdateVm) {
    if (!vm.dialogVisible) return
    when (val s = vm.state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Suche nach Updates …") },
            text = { CircularProgressIndicator() },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("Ausblenden") } },
        )
        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Kein Update verfügbar") },
            text = { Text("Du verwendest die neueste Version (${BuildConfig.VERSION_NAME}).") },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("OK") } },
        )
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Update auf ${s.info.version}") },
            text = {
                Column {
                    Text(
                        "Installiert: ${BuildConfig.VERSION_NAME} · Größe: ${"%.1f".format(s.info.size / 1_048_576f)} MB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (s.info.notes.isNotEmpty()) {
                        Text(
                            s.info.notes,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.download(s.info) }) { Text("Herunterladen & installieren") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Später") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Lade ${s.info.version} herunter …") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(s.progress * 100).toInt()} %", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("Im Hintergrund") } },
        )
        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Bereit zur Installation") },
            text = {
                Text(
                    if (s.needsPermission) "Erlaube NaviDroid in den Einstellungen, Apps zu installieren, und tippe dann auf „Installieren“."
                    else "Version ${s.info.version} wurde heruntergeladen."
                )
            },
            confirmButton = { TextButton(onClick = vm::install) { Text("Installieren") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Später") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Update fehlgeschlagen") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = vm::checkManually) { Text("Erneut versuchen") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Schließen") } },
        )
    }
}
