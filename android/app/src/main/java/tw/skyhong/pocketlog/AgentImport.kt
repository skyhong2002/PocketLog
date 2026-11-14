package tw.skyhong.pocketlog

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Local parsing only, after a document-picker choice. Tools/attachments/metadata are excluded. */
object AgentImport {
    fun parse(bytes: ByteArray, source: String, now: Long = System.currentTimeMillis()): JSONArray {
        require(source in listOf("codex", "claude") && bytes.size <= 524288)
        val lines = bytes.toString(Charsets.UTF_8).split('\n'); require(lines.size <= 2000)
        val turns = mutableListOf<JSONObject>()
        lines.forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            val record = JSONObject(line)
            val message = when {
                source == "codex" && record.optString("type") == "response_item" && record.optJSONObject("payload")?.optString("type") == "message" -> record.getJSONObject("payload")
                source == "claude" && record.optString("type") in listOf("user", "assistant") -> record.optJSONObject("message")
                else -> null
            } ?: return@forEachIndexed
            val role = message.optString("role"); if (role !in listOf("user", "assistant")) return@forEachIndexed
            val at = runCatching { Instant.parse(record.getString("timestamp")).toEpochMilli() }.getOrNull() ?: return@forEachIndexed
            if (at <= now - 30 * 86_400_000L || at > now + 60_000L) return@forEachIndexed
            val content = message.opt("content")
            val value = when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { i -> content.optJSONObject(i)?.takeIf { it.optString("type") in listOf("text", "input_text", "output_text") }?.opt("text") as? String }.joinToString("\n")
                else -> ""
            }
            val clean = value.filter { !it.isISOControl() || it == '\n' || it == '\t' }.trim()
            if (clean.isNotBlank()) turns.add(JSONObject().put("id", "turn-$index").put("at_ms", at).put("role", role).put("text", clean.substring(0, clean.offsetByCodePoints(0, minOf(1000, clean.codePointCount(0, clean.length))))))
        }
        return JSONArray(turns.sortedBy { it.getLong("at_ms") }.takeLast(40))
    }
}
