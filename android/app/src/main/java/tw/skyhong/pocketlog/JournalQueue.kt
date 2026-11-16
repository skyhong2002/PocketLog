package tw.skyhong.pocketlog

import android.content.Context
import org.json.JSONObject
import java.util.UUID

class JournalQueue internal constructor(private val files: JournalFiles, private val dropped: () -> Unit) {
    constructor(context: Context) : this(PrivateFiles(context), { RecordingSettings(context).drop() })
    data class Entry(val name: String, val data: JSONObject)
    companion object {
        fun destination(c: Credentials) = RecordingRules.digest(c.endpoint + "\n" + c.token)
        private const val CONTEXT_PATH = "/v1/observations"
        private const val CONTEXT_SUFFIX = "_ctx"
    }
    fun enqueue(credentials: Credentials, path: String, body: JSONObject, now: Long = System.currentTimeMillis()) {
        require(path in setOf("/v1/activities", "/v1/checkins", "/v1/observations", "/v1/reflections", "/v1/cards"))
        require(body.toString().toByteArray().size <= RecordingRules.MAX_UPLOAD_BODY)
        val entry = JSONObject().put("destination", destination(credentials)).put("created", now).put("path", path).put("body", body)
        // The name marks screen context so journal-edit reads never decrypt large thumbnails.
        val name = "q_${now}_${UUID.randomUUID()}${if (path == CONTEXT_PATH) CONTEXT_SUFFIX else ""}"
        val clear = entry.toString().toByteArray()
        try { files.write(name, clear) } finally { clear.fill(0) }
        prune(now)
    }
    @JvmOverloads fun entries(credentials: Credentials, limit: Int = 32): List<Entry> = readEntries(credentials, limit)
    // Timeline reads need all retained writes, including edits behind the upload batch.
    internal fun pendingReflections(credentials: Credentials): List<Entry> =
        readEntries(credentials, RecordingRules.MAX_QUEUE_RECORDS, setOf("/v1/reflections"))
    internal fun pendingJournalEdits(credentials: Credentials): List<Entry> =
        readEntries(credentials, RecordingRules.MAX_QUEUE_RECORDS, setOf("/v1/reflections", "/v1/cards"))
    private fun readEntries(credentials: Credentials, limit: Int, paths: Set<String>? = null): List<Entry> {
        prune()
        val skipContext = paths != null && CONTEXT_PATH !in paths
        // Decrypt lazily and stop at the limit: a day of thumbnails can be hundreds of megabytes.
        return files.names("q_").asSequence().filterNot { skipContext && it.endsWith(CONTEXT_SUFFIX) }.mapNotNull { name ->
            val bytes = runCatching { files.read(name) }.getOrNull()
            if (bytes == null) { files.remove(name); dropped(); return@mapNotNull null }
            try {
                val data = JSONObject(String(bytes, Charsets.UTF_8))
                if (data.getString("destination") == destination(credentials) && (paths == null || data.optString("path") in paths)) Entry(name, data) else null
            } catch (_: Exception) { files.remove(name); dropped(); null } finally { bytes.fill(0) }
        }.take(limit).toList()
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
    fun clearContexts() = clearPaths(setOf(CONTEXT_PATH))
    fun clearCollected() = clearPaths(setOf(CONTEXT_PATH, "/v1/activities"))
    private fun clearPaths(paths: Set<String>) {
        (files.names("q_") + files.names("r_")).forEach { name ->
            if (CONTEXT_PATH in paths && name.endsWith(CONTEXT_SUFFIX)) { files.remove(name); return@forEach }
            val bytes = runCatching { files.read(name) }.getOrNull()
            if (bytes == null) { files.remove(name); dropped(); return@forEach }
            try { if (JSONObject(String(bytes)).getString("path") in paths) files.remove(name) }
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
            if (now - at >= RecordingRules.RAW_TTL || at > now + 60_000 || total > RecordingRules.MAX_QUEUE_BYTES || count > RecordingRules.MAX_QUEUE_RECORDS) {
                total -= files.size(name); count--; files.remove(name); dropped()
            }
        }
    }
}
