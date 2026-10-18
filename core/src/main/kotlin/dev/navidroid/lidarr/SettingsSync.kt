package dev.navidroid.lidarr

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Lidarr-Zugang als JSON für den Abgleich zwischen Handy und Desktop über den Helfer. */
object SyncFormat {
    fun lidarr(c: LidarrConfig) = buildJsonObject {
        put("url", c.url); put("apiKey", c.apiKey)
        put("quality", c.qualityProfileId); put("metadata", c.metadataProfileId); put("root", c.rootFolderPath)
    }

    fun lidarr(o: JsonObject?): LidarrConfig? {
        o ?: return null
        val url = o.str("url") ?: return null
        val key = o.str("apiKey") ?: return null
        val root = o.str("root") ?: return null
        return LidarrConfig(url, key, (o["quality"] as? JsonPrimitive)?.intOrNull ?: 1, (o["metadata"] as? JsonPrimitive)?.intOrNull ?: 1, root)
    }

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
