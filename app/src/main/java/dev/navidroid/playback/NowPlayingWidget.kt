package dev.navidroid.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.RemoteViews
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil.imageLoader
import coil.request.ImageRequest
import dev.navidroid.MainActivity
import dev.navidroid.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Widget für den Startbildschirm: laufender Titel mit Cover, Abspielen/Pause, Weiter und Daylist. */
class NowPlayingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        NowPlayingWidget.push(context, NowPlayingWidget.last)
    }
}

object NowPlayingWidget {
    const val ACTION_TOGGLE = "dev.navidroid.widget.TOGGLE"
    const val ACTION_NEXT = "dev.navidroid.widget.NEXT"
    const val EXTRA_OPEN = "open"

    data class Snapshot(val title: String?, val artist: String?, val playing: Boolean, val cover: Bitmap?)

    @Volatile
    var last = Snapshot(null, null, false, null)
        private set

    fun attach(context: Context, player: Player, scope: CoroutineScope) {
        var coverFor: String? = null
        fun refresh() {
            val item = player.currentMediaItem
            val md = item?.mediaMetadata
            val base = Snapshot(md?.title?.toString(), md?.artist?.toString(), player.isPlaying, last.cover.takeIf { coverFor == item?.mediaId })
            last = base
            push(context, base)
            val art = md?.artworkUri
            if (item != null && coverFor != item.mediaId && art != null) {
                coverFor = item.mediaId
                scope.launch {
                    val bmp = runCatching {
                        (context.imageLoader.execute(ImageRequest.Builder(context).data(art).size(240).allowHardware(false).build()).drawable as? BitmapDrawable)?.bitmap
                    }.getOrNull()
                    if (coverFor == item.mediaId) {
                        last = last.copy(cover = bmp)
                        push(context, last)
                    }
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED)) refresh()
            }
        })
        refresh()
    }

    fun push(context: Context, s: Snapshot) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, NowPlayingWidgetProvider::class.java)
        if (manager.getAppWidgetIds(component).isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
        views.setTextViewText(R.id.widget_title, s.title ?: context.getString(R.string.widget_idle_title))
        views.setTextViewText(R.id.widget_artist, s.artist ?: context.getString(R.string.widget_idle_subtitle))
        if (s.cover != null) views.setImageViewBitmap(R.id.widget_cover, s.cover) else views.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
        views.setImageViewResource(R.id.widget_play, if (s.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun service(action: String, code: Int) = PendingIntent.getService(
            context, code, Intent(context, PlaybackService::class.java).setAction(action), flags,
        )
        views.setOnClickPendingIntent(R.id.widget_play, service(ACTION_TOGGLE, 11))
        views.setOnClickPendingIntent(R.id.widget_next, service(ACTION_NEXT, 12))
        val open = PendingIntent.getActivity(context, 13, Intent(context, MainActivity::class.java), flags)
        val daylist = PendingIntent.getActivity(
            context, 14,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, "daylist").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        views.setOnClickPendingIntent(R.id.widget_daylist, daylist)
        manager.updateAppWidget(component, views)
    }
}

@Suppress("unused")
private fun MediaItem?.idOrNull() = this?.mediaId
