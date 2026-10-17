package dev.navidroid.offline

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dev.navidroid.MainActivity
import dev.navidroid.NaviApp
import dev.navidroid.R

/** Lädt im Hintergrund weiter und zeigt den Fortschritt als Benachrichtigung. */
@androidx.annotation.OptIn(UnstableApi::class)
class OfflineDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads_channel,
    R.string.downloads_channel_description,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = (application as NaviApp).offline.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val waiting = downloads.count { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING }
        val message = when {
            notMetRequirements != 0 -> "Wartet auf WLAN"
            else -> if (waiting == 1) "1 Titel" else "$waiting Titel"
        }
        return helper.buildProgressNotification(this, android.R.drawable.stat_sys_download, open, message, downloads, notMetRequirements)
    }

    companion object {
        private const val NOTIFICATION_ID = 7301
        private const val CHANNEL_ID = "downloads"
    }
}
