package tw.skyhong.pocketlog

import org.json.JSONArray
import org.json.JSONObject

/** Applies local intentions to a copy; the encrypted cache remains the server snapshot. */
internal object PendingReflections {
    fun overlay(base: JSONObject, path: String, entries: List<JournalQueue.Entry>, now: Long = System.currentTimeMillis()): JSONObject {
        val result = JSONObject(base.toString())
        result.put("pending_reflections", false)
        val window = Regex("^/v1/timeline\\?from_ms=([0-9]+)&to_ms=([0-9]+)$").matchEntire(path) ?: return result
        val from = window.groupValues[1].toLongOrNull() ?: return result
        val to = window.groupValues[2].toLongOrNull() ?: return result
        if (from >= to) return result
        val reflections = linkedMapOf<String, JSONObject>()
        val original = result.optJSONArray("reflections") ?: JSONArray()
        for (i in 0 until original.length()) {
            val reflection = original.getJSONObject(i)
            reflections[reflection.getString("id")] = reflection
        }
        // Match upload order, including its filename tie-break for writes in the same millisecond.
        for (entry in entries.sortedBy { it.name }) {
            if (entry.data.optString("path") != "/v1/reflections") continue
            val body = entry.data.optJSONObject("body") ?: continue
            if (body.keys().asSequence().toSet() != setOf("id", "at_ms", "text")) continue
            val id = body.opt("id") as? String ?: continue
            val text = body.opt("text") as? String ?: continue
            val at = body.opt("at_ms") as? Long ?: (body.opt("at_ms") as? Int)?.toLong() ?: continue
            if (!Regex("[A-Za-z0-9_-]{1,80}").matches(id) || '\u0000' in text ||
                text.codePointCount(0, text.length) > 2000 || at <= now - 30L * RecordingRules.RAW_TTL || at > now + 60_000) continue
            // Removing by ID also handles edits that move an existing reflection out of this window.
            val affected = reflections.containsKey(id) || at in from until to
            reflections.remove(id)
            if (at in from until to && text.isNotBlank()) reflections[id] = JSONObject(body.toString())
            if (affected) result.put("pending_reflections", true)
        }
        result.put("reflections", JSONArray(reflections.values.sortedWith(compareBy<JSONObject> { it.getLong("at_ms") }.thenBy { it.getString("id") })))
        return result
    }
}
