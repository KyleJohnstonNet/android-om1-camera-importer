package dev.om1.importer

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

data class DiagnosticEvent(val time: String, val kind: String, val detail: String)

/** Bounded, app-private, atomic log. No photo bytes, credentials, MACs or USB serials. */
object DiagnosticLog {
    private lateinit var file: AtomicFile
    private val mutableEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events = mutableEvents.asStateFlow()
    val running = MutableStateFlow(false)

    @Synchronized fun initialize(context: Context) {
        if (::file.isInitialized) return
        file = AtomicFile(File(context.filesDir, "diagnostics.json"))
        if (file.baseFile.exists()) {
            try {
                val entries = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                    .getJSONArray("events")
                mutableEvents.value = (maxOf(0, entries.length() - 4096) until entries.length()).map { i ->
                    entries.getJSONObject(i).let {
                        DiagnosticEvent(it.getString("time"), it.getString("kind"), it.getString("detail"))
                    }
                }
            } catch (_: Exception) {
                mutableEvents.value = listOf(DiagnosticEvent(Instant.now().toString(), "log_error",
                    "Previous diagnostic log could not be read."))
            }
        }
    }

    @Synchronized fun record(kind: String, detail: String) {
        val next = mutableEvents.value + DiagnosticEvent(Instant.now().toString(), kind, detail.take(24000))
        mutableEvents.value = if(next.size > 4096) next.drop(next.size - 4096) else next
        val stream = file.startWrite()
        try {
            stream.write(report().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized fun report(): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("appVersion", BuildConfig.VERSION_NAME)
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("androidSdk", Build.VERSION.SDK_INT)
        put("androidRelease", Build.VERSION.RELEASE)
        put("securityPatch", Build.VERSION.SECURITY_PATCH)
        put("cameraProtocolVerified", false)
        put("note", "Connection diagnostics only. TCP reachability does not establish pairing or photo transfer.")
        put("events", JSONArray().apply {
            mutableEvents.value.forEach { event -> put(JSONObject().apply {
                put("time", event.time); put("kind", event.kind); put("detail", event.detail)
            }) }
        })
    }.toString(2)
}
