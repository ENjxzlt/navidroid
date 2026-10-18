package dev.navidroid.desktop

import dev.navidroid.data.Credentials
import dev.navidroid.data.CredentialsSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Konfiguration unter ~/.config/navidroid (bzw. $XDG_CONFIG_HOME/navidroid). */
object Paths {
    val config: File = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config"), "navidroid").apply { mkdirs() }
    val cache: File = File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.cache"), "navidroid").apply { mkdirs() }
}

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

@Serializable
private data class SavedSession(val serverUrl: String, val username: String, val token: String, val salt: String)

/** Anmeldung: wie in der App nur Token + Salt, nie das Passwort. Datei nur für den eigenen Benutzer lesbar. */
class DesktopSession : CredentialsSource {
    private val file = File(Paths.config, "session.json")

    @Volatile
    override var current: Credentials? = runCatching {
        json.decodeFromString<SavedSession>(file.readText()).let { Credentials(it.serverUrl, it.username, it.token, it.salt) }
    }.getOrNull()
        private set

    fun save(c: Credentials) {
        file.writeText(json.encodeToString(SavedSession(c.serverUrl, c.username, c.token, c.salt)))
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        current = c
    }

    fun clear() {
        file.delete()
        current = null
    }
}

@Serializable
data class DesktopSettings(
    val onlineLyrics: Boolean = true,
    val volume: Int = 80,
    val autoplay: Boolean = true,
    /** Zeitstempel des letzten Abgleichs mit dem Handy (über den Helfer). */
    val syncedAt: Long = 0,
) {
    companion object {
        private val file = File(Paths.config, "settings.json")
        fun load(): DesktopSettings = runCatching { json.decodeFromString<DesktopSettings>(file.readText()) }.getOrDefault(DesktopSettings())
        fun save(s: DesktopSettings) = runCatching { file.writeText(json.encodeToString(s)) }
    }
}
