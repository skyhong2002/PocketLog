package tw.skyhong.pocketlog

import org.json.JSONObject

/** Caller holds SyncEngine.lock. Permission boundaries share the encrypted session checkpoint. */
internal class UsageCheckpoint(private val files: JournalFiles, private val dropped: () -> Unit) {
    fun save(cursor: Long, app: String?, start: Long, granted: Boolean) {
        val bytes = JSONObject().put("cursor", cursor).put("app", app).put("start", start)
            .put("usage_granted", granted).toString().toByteArray()
        try { files.write("usage", bytes) } finally { bytes.fill(0) }
    }

    fun reconcile(now: Long, granted: Boolean): JSONObject? {
        val bytes = runCatching { files.read("usage") }.getOrNull()
        val saved = try { bytes?.let { runCatching { JSONObject(String(it)) }.getOrNull() } }
            finally { bytes?.fill(0) }
        if (bytes != null && saved == null) dropped()
        // Legacy checkpoints have no trustworthy permission boundary. Start fresh once.
        if (saved == null || !saved.has("usage_granted") || saved.optBoolean("usage_granted") != granted) {
            save(now, null, 0, granted)
            return null
        }
        return saved.takeIf { granted }
    }
}
