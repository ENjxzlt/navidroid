package dev.navidroid.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

/**
 * MPRIS: NaviDroid erscheint in der Mediensteuerung von KDE Plasma/GNOME, Medientasten funktionieren,
 * Titel und Cover stehen im Sperrbildschirm.
 */
@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

class Mpris(
    private val player: DesktopPlayer,
    private val cover: (String?) -> String?,
    private val onRaise: () -> Unit,
    private val onQuit: () -> Unit,
) : MediaPlayer2, MediaPlayer2Player, Properties {
    private var conn: DBusConnection? = null
    private val path = "/org/mpris/MediaPlayer2"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    fun start() {
        try {
            val c = DBusConnectionBuilder.forSessionBus().build()
            c.requestBusName("org.mpris.MediaPlayer2.navidroid")
            c.exportObject(path, this)
            conn = c
            scope.launch {
                player.state.map { Triple(it.current?.id, it.playing, it.volume) }.distinctUntilChanged().collect {
                    changed(PLAYER_IFACE, mapOf("PlaybackStatus" to Variant(status()), "Metadata" to metadataVariant(), "Volume" to Variant(it.third / 100.0)))
                }
            }
        } catch (e: Throwable) {
            System.err.println("MPRIS nicht verfügbar: ${e.message}")
        }
    }

    fun stop() {
        runCatching { conn?.releaseBusName("org.mpris.MediaPlayer2.navidroid") }
        runCatching { conn?.close() }
    }

    private fun changed(iface: String, props: Map<String, Variant<*>>) {
        val c = conn ?: return
        runCatching { c.sendMessage(Properties.PropertiesChanged(path, iface, props, emptyList())) }
    }

    private fun status() = when {
        player.state.value.current == null -> "Stopped"
        player.state.value.playing -> "Playing"
        else -> "Paused"
    }

    private fun trackPath(id: String?) = DBusPath("/dev/navidroid/track/" + (id ?: "none").replace(Regex("[^A-Za-z0-9_]"), "_"))

    private fun metadataVariant(): Variant<*> {
        val s = player.state.value
        val song = s.current
        val m = LinkedHashMap<String, Variant<*>>()
        m["mpris:trackid"] = Variant(trackPath(song?.id))
        if (song != null) {
            m["xesam:title"] = Variant(song.title)
            song.artist?.let { m["xesam:artist"] = Variant(arrayOf(it), "as") }
            song.album?.let { m["xesam:album"] = Variant(it) }
            m["mpris:length"] = Variant(s.durationMs.coerceAtLeast(song.duration * 1000L) * 1000L)
            cover(song.coverArt)?.let { m["mpris:artUrl"] = Variant(it) }
        }
        return Variant(m, "a{sv}")
    }

    // ---- org.mpris.MediaPlayer2 ----
    override fun Raise() { scope.launch { onRaise() } }
    override fun Quit() { scope.launch { onQuit() } }

    // ---- org.mpris.MediaPlayer2.Player ----
    override fun Next() { scope.launch { player.next() } }
    override fun Previous() { scope.launch { player.previous() } }
    override fun Pause() { scope.launch { player.pause() } }
    override fun PlayPause() { scope.launch { player.toggle() } }
    override fun Stop() { scope.launch { player.pause() } }
    override fun Play() { scope.launch { player.resume() } }
    override fun Seek(offset: Long) { scope.launch { player.seek(player.state.value.positionMs + offset / 1000) } }
    override fun SetPosition(trackId: DBusPath, position: Long) { scope.launch { player.seek(position / 1000) } }
    override fun OpenUri(uri: String) {}

    // ---- org.freedesktop.DBus.Properties ----
    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = GetAll(interfaceName)[propertyName]?.value as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        if (interfaceName == PLAYER_IFACE && propertyName == "Volume") {
            val v = (value as? Number)?.toDouble() ?: return
            scope.launch { player.setVolume((v * 100).toInt()) }
        }
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        ROOT_IFACE -> mapOf(
            "CanQuit" to Variant(true),
            "CanRaise" to Variant(true),
            "HasTrackList" to Variant(false),
            "Identity" to Variant("NaviDroid"),
            "DesktopEntry" to Variant("navidroid"),
            "SupportedUriSchemes" to Variant(arrayOf<String>(), "as"),
            "SupportedMimeTypes" to Variant(arrayOf<String>(), "as"),
        )
        PLAYER_IFACE -> {
            val s = player.state.value
            mapOf(
                "PlaybackStatus" to Variant(status()),
                "LoopStatus" to Variant(when (s.repeat) { Repeat.Off -> "None"; Repeat.All -> "Playlist"; Repeat.One -> "Track" }),
                "Rate" to Variant(1.0),
                "Shuffle" to Variant(s.shuffle),
                "Metadata" to metadataVariant(),
                "Volume" to Variant(s.volume / 100.0),
                "Position" to Variant(s.positionMs * 1000L),
                "MinimumRate" to Variant(1.0),
                "MaximumRate" to Variant(1.0),
                "CanGoNext" to Variant(true),
                "CanGoPrevious" to Variant(true),
                "CanPlay" to Variant(s.current != null),
                "CanPause" to Variant(s.current != null),
                "CanSeek" to Variant(true),
                "CanControl" to Variant(true),
            )
        }
        else -> emptyMap()
    }

    override fun getObjectPath(): String = path

    companion object {
        const val ROOT_IFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
    }
}
