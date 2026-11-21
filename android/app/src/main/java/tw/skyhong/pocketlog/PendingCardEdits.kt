package tw.skyhong.pocketlog

import org.json.JSONObject

/** Local intentions decorate existing returned cards only; never rewrite the encrypted server snapshot. */
internal object PendingCardEdits {
    private val baseCategories = setOf("unsorted", "work", "learning", "communication", "social_media", "entertainment", "news_reading", "life_admin", "rest", "idle", "distraction")
    fun overlay(base: JSONObject, path: String, entries: List<JournalQueue.Entry>): JSONObject {
        val result = JSONObject(base.toString()).put("pending_card_edits", false)
        val window = Regex("^/v1/timeline\\?from_ms=([0-9]+)&to_ms=([0-9]+)$").matchEntire(path) ?: return result
        val from = window.groupValues[1].toLongOrNull() ?: return result
        val to = window.groupValues[2].toLongOrNull() ?: return result
        if (from >= to) return result
        val cards = result.optJSONArray("cards") ?: return result
        val byId = (0 until cards.length()).map { cards.getJSONObject(it) }.associateBy { it.optString("id") }
        // Match the existing uploader's stable order, including equal-timestamp filenames.
        for (entry in entries.sortedBy { it.name }) {
            if (entry.data.optString("path") != "/v1/cards") continue
            val body = entry.data.optJSONObject("body") ?: continue
            if (body.keys().asSequence().toSet() != setOf("id", "title", "summary", "category")) continue
            val id = body.opt("id") as? String ?: continue
            val title = body.opt("title") as? String ?: continue
            val summary = body.opt("summary") as? String ?: continue
            val category = body.opt("category") as? String ?: continue
            if (!Regex("[A-Za-z0-9_-]{1,80}").matches(id) || title.isBlank() || '\u0000' in title || '\u0000' in summary ||
                title.codePointCount(0, title.length) > 200 || summary.codePointCount(0, summary.length) > 1000 ||
                (category !in baseCategories && !Regex("custom_[a-z0-9_]{1,57}").matches(category))) continue
            val card = byId[id] ?: continue
            // Custom-category membership may have changed remotely: this remains explicitly pending.
            card.put("title", title).put("summary", summary).put("category", category)
                .put("source", "user").put("pending_edit", true)
            result.put("pending_card_edits", true)
        }
        return result
    }
}
