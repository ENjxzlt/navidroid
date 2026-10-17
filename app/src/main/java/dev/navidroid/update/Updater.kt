package dev.navidroid.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.navidroid.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val size: Long)

/** Prüft das neueste GitHub-Release von [BuildConfig.GITHUB_REPO] und installiert dessen APK. */
class Updater(private val context: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = BuildConfig.GITHUB_REPO.isNotBlank()

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!isConfigured) throw IOException("Kein GitHub-Repository für Updates konfiguriert")
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val release = http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null // noch kein Release
            if (!resp.isSuccessful) throw IOException("GitHub antwortet mit HTTP ${resp.code}")
            json.decodeFromString<GhRelease>(resp.body?.string().orEmpty())
        }
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return@withContext null
        val version = release.tagName.removePrefix("v")
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return@withContext null
        UpdateInfo(version, release.body.orEmpty().trim(), apk.downloadUrl, apk.size)
    }

    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "navidroid-${info.version}.apk")
        http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download fehlgeschlagen (HTTP ${resp.code})")
            val body = resp.body ?: throw IOException("Leerer Download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
        }
        target
    }

    /** Darf die App noch keine APKs installieren, wird die Systemeinstellung geöffnet. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        /** Vergleicht Versionen wie "1.2.10" numerisch; Suffixe wie "-beta" werden ignoriert. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
