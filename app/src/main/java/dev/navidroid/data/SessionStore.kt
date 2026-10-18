package dev.navidroid.data

import android.content.Context

class SessionStore(context: Context) : CredentialsSource {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Volatile
    override var current: Credentials? = load()
        private set

    fun save(credentials: Credentials) {
        prefs.edit()
            .putString(KEY_URL, credentials.serverUrl)
            .putString(KEY_USER, credentials.username)
            .putString(KEY_TOKEN, credentials.token)
            .putString(KEY_SALT, credentials.salt)
            .apply()
        current = credentials
    }

    fun clear() {
        prefs.edit().clear().apply()
        current = null
    }

    private fun load(): Credentials? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val salt = prefs.getString(KEY_SALT, null) ?: return null
        return Credentials(url, user, token, salt)
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_USER = "user"
        private const val KEY_TOKEN = "token"
        private const val KEY_SALT = "salt"

        fun createCredentials(serverUrl: String, username: String, password: String): Credentials =
            Auth.createCredentials(serverUrl, username, password)

        fun normalizeUrl(input: String): String = Auth.normalizeUrl(input)
    }
}
