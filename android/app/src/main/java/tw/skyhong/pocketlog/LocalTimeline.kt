package tw.skyhong.pocketlog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** An encrypted, bounded timeline made from app sessions and captured text. It never stores images. */
internal class LocalTimeline(private val files: JournalFiles) {
    constructor(context: Context) : this(PrivateFiles(context))

    fun recordObservation(body: JSONObject) {
        val at = body.getLong("at_ms")
        val text = RecordingRules.cap(body.optString("text"), 1_000)
        append(listOf(card("capture-" + body.getString("id"), body.getString("app"), at, at + 1, text)))
    }

    fun recordActivities(activities: JSONArray) {
        append((0 until activities.length()).map { index ->
            val activity = activities.getJSONObject(index)
            card("usage-" + activity.getString("id"), activity.getString("app"),
                activity.getLong("start_ms"), activity.getLong("end_ms"), "")
        })
    }

    private fun card(id: String, app: String, start: Long, end: Long, text: String) =
        JSONObject().put("id", id).put("app", app).put("start_ms", start).put("end_ms", end)
            .put("category", "unsorted").put("summary", text).put("local_record", true)

    private fun read(name: String): List<JSONObject> {
        val bytes = runCatching { files.read(name) }.getOrNull() ?: return emptyList()
        return try {
            val items = JSONArray(String(bytes, Charsets.UTF_8))
            (0 until items.length()).map(items::getJSONObject)
        } catch (_: Exception) { emptyList() } finally { bytes.fill(0) }
    }

    private fun append(cards: List<JSONObject>) {
        for ((bucket, incoming) in cards.groupBy { it.getLong("start_ms") / BUCKET_MS }) {
            val name = PREFIX + bucket
            val rows = (read(name) + incoming).distinctBy { it.getString("id") }.takeLast(72)
            val bytes = JSONArray(rows).toString().toByteArray()
            try { files.write(name, bytes) } finally { bytes.fill(0) }
        }
    }

    fun render(from: Long, to: Long, cached: JSONObject? = null, offline: Boolean = true): JSONObject {
        val existing = cached?.optJSONArray("cards") ?: JSONArray()
        val server = (0 until existing.length()).map(existing::getJSONObject)
        // Include sessions crossing the day boundary. UsageStats does not retain an unbounded open session.
        val local = ((from - RecordingRules.MAX_SESSION) / BUCKET_MS..to / BUCKET_MS).asSequence()
            .flatMap { read(PREFIX + it).asSequence() }
            .filter { it.getLong("end_ms") > from && it.getLong("start_ms") < to }
            .toList()
        val usage = local.filter { it.getString("id").startsWith("usage-") }
        val captures = local.filterNot { it.getString("id").startsWith("usage-") }
            .sortedBy { it.getLong("start_ms") }
        val capturesByApp = captures.groupBy { it.getString("app") }
        val used = mutableSetOf<String>()
        for (session in usage) {
            val matching = capturesByApp[session.getString("app")].orEmpty().filter {
                it.getLong("start_ms") in session.getLong("start_ms") until session.getLong("end_ms")
            }
            matching.forEach { used.add(it.getString("id")) }
            matching.lastOrNull { it.optString("summary").isNotBlank() }?.let { session.put("summary", it.getString("summary")) }
        }
        val serverByApp = server.groupBy { it.optString("app") }
        val grouped = mutableListOf<JSONObject>()
        for (capture in captures.filterNot { it.getString("id") in used }) {
            val previous = grouped.lastOrNull()
            if (previous != null && capture.getString("app") == previous.getString("app") &&
                capture.getLong("start_ms") - previous.getLong("end_ms") <= 2 * RecordingRules.CONTEXT_INTERVAL) {
                previous.put("end_ms", capture.getLong("end_ms"))
                capture.optString("summary").takeIf { it.isNotBlank() }?.let { previous.put("summary", it) }
            } else grouped += capture
        }
        val unmatched = (usage + grouped).filter { card ->
            serverByApp[card.getString("app")].orEmpty().none {
                it.getLong("start_ms") < card.getLong("end_ms") && card.getLong("start_ms") < it.getLong("end_ms")
            }
        }
        return (cached ?: JSONObject()).put("offline", offline)
            .put("cards", JSONArray((server + unmatched).sortedBy { it.getLong("start_ms") }))
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        val limit = now - 30 * RecordingRules.RAW_TTL
        var retained = 0L
        for (name in files.names(PREFIX).sortedByDescending { bucketTime(it) }) {
            retained += files.size(name)
            if (bucketTime(name) + BUCKET_MS <= limit || retained > MAX_BYTES) files.remove(name)
        }
    }

    fun clear() = files.names(PREFIX).forEach(files::remove)

    private fun bucketTime(name: String) = (name.removePrefix(PREFIX).toLongOrNull() ?: 0) * BUCKET_MS

    private companion object {
        const val PREFIX = "local_timeline_"
        const val BUCKET_MS = 5 * 60_000L
        const val MAX_BYTES = 48 * 1024 * 1024L
    }
}
