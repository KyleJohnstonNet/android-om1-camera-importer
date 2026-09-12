package dev.om1.importer

import dev.om1.importer.core.SessionWindow
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

data class SavedSession(
    val id: String,
    val starts: Long,
    val ends: Long,
    val zone: String,
    val account: String,
    val album: String?,
    val albumTitle: String,
    val camera: String? = null
) {
    init { require(id.isNotBlank()); SessionWindow(starts, ends); ZoneId.of(zone) }
    fun json(): JSONObject = JSONObject().put("id", id).put("starts", starts).put("ends", ends)
        .put("zone", zone).put("account", account).put("album", album).put("albumTitle", albumTitle).put("camera", camera)

    companion object {
        fun read(json: JSONObject, fallbackZone: String = ZoneId.systemDefault().id): SavedSession {
            fun optional(key: String) = if(json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }
            return SavedSession(optional("id") ?: UUID.randomUUID().toString(), json.getLong("starts"), json.getLong("ends"),
                optional("zone") ?: fallbackZone, json.getString("account"), optional("album"),
                optional("albumTitle") ?: if(optional("album") == null) "General library" else "Saved album", optional("camera"))
        }
    }
}
