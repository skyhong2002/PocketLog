package tw.skyhong.pocketlog

import android.content.Context
import org.json.JSONObject
import java.util.UUID

class JournalQueue internal constructor(private val files: JournalFiles, private val dropped: () -> Unit) {
    constructor(context: Context) : this(PrivateFiles(context), { RecordingSettings(context).drop() })
    data class Entry(val name: String, val data: JSONObject)
    companion object {
        fun destination(c: Credentials) = RecordingRules.digest(c.endpoint + "\n" + c.token)
    }
    fun enqueue(credentials: Credentials, path: String, body: JSONObject, now: Long = System.currentTimeMillis()) {
        require(path in setOf("/v1/activities", "/v1/checkins", "/v1/observations", "/v1/reflections", "/v1/cards"))
        require(body.toString().toByteArray().size <= 60 * 1024)
        val entry = JSONObject().put("destination", destination(credentials)).put("created", now).put("path", path).put("body", body)
        val name = "q_${now}_${UUID.randomUUID()}"
        val clear = entry.toString().toByteArray()
        try { files.write(name, clear) } finally { clear.fill(0) }
        prune(now)
    }
    fun entries(credentials: Credentials): List<Entry> {
        prune()
        return files.names("q_").take(256).mapNotNull { name ->
            val bytes = runCatching { files.read(name) }.getOrNull()
            if (bytes == null) { files.remove(name); dropped(); return@mapNotNull null }
            try {
                val data = JSONObject(String(bytes, Charsets.UTF_8))
                if (data.getString("destination") == destination(credentials)) Entry(name, data) else null
            } catch (_: Exception) { files.remove(name); dropped(); null } finally { bytes.fill(0) }
        }
    }
    fun acknowledge(entry: Entry) = files.remove(entry.name)
    fun reject(entry: Entry) {
        val bytes = entry.data.toString().toByteArray()
        try { files.write(entry.name.replaceFirst("q_", "r_"), bytes) } finally { bytes.fill(0) }
        files.remove(entry.name)
    }
    fun counts(): Pair<Int, Int> { prune(); return files.names("q_").size to files.names("r_").size }
    fun clearRejected() { files.names("r_").forEach(files::remove) }
    fun retryRejected(c: Credentials) {
        prune()
        files.names("r_").forEach { name ->
            val bytes = runCatching { files.read(name) }.getOrNull()
            if (bytes == null) { files.remove(name); dropped(); return@forEach }
            try {
                val data = JSONObject(String(bytes, Charsets.UTF_8))
                if (data.getString("destination") == destination(c)) {
                    files.write(name.replaceFirst("r_", "q_"), bytes); files.remove(name)
                }
            } catch (_: Exception) { files.remove(name); dropped() } finally { bytes.fill(0) }
        }
    }
    fun clearContexts() {
        (files.names("q_") + files.names("r_")).forEach { name ->
            val bytes = runCatching { files.read(name) }.getOrNull()
            if (bytes == null) { files.remove(name); dropped(); return@forEach }
            try { if (JSONObject(String(bytes)).getString("path") == "/v1/observations") files.remove(name) }
            catch (_: Exception) { files.remove(name); dropped() }
            finally { bytes.fill(0) }
        }
    }
    fun prune(now: Long = System.currentTimeMillis()) {
        val all = (files.names("q_") + files.names("r_")).sortedBy { it.substringAfter('_') }
        var total = all.sumOf { files.size(it) }
        var count = all.size
        all.forEach { name ->
            val at = name.split('_').getOrNull(1)?.toLongOrNull() ?: 0
            if (now - at >= RecordingRules.RAW_TTL || at > now + 60_000 || total > RecordingRules.MAX_QUEUE_BYTES || count > 4096) {
                total -= files.size(name); count--; files.remove(name); dropped()
            }
        }
    }
}
