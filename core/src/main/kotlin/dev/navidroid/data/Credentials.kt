package dev.navidroid.data

import java.security.MessageDigest
import java.security.SecureRandom

/** Subsonic-Token-Auth: Das Passwort selbst wird nie gespeichert, nur md5(passwort + salt) und das Salt. */
data class Credentials(
    val serverUrl: String,
    val username: String,
    val token: String,
    val salt: String,
)

/** Woher der Client die aktuelle Anmeldung bekommt (Android: SharedPreferences, Linux: Datei). */
interface CredentialsSource {
    val current: Credentials?
}

object Auth {
    fun createCredentials(serverUrl: String, username: String, password: String): Credentials {
        val saltBytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val salt = saltBytes.toHex()
        val token = MessageDigest.getInstance("MD5").digest((password + salt).toByteArray()).toHex()
        return Credentials(normalizeUrl(serverUrl), username.trim(), token, salt)
    }

    fun normalizeUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if ("://" in trimmed) trimmed else "https://$trimmed"
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
