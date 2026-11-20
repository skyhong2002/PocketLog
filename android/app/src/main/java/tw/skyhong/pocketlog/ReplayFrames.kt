package tw.skyhong.pocketlog

import org.json.JSONObject

/** The private archive contains only thumbnails explicitly saved for replay by their source device. */
internal object ReplayFrames {
    private const val DAY_MS = 86_400_000L
    private val validId = Regex("[a-zA-Z0-9_-]{1,80}")

    fun window(card: JSONObject, now: Long): String? {
        val start = card.getLong("start_ms").coerceAtLeast(0)
        val end = card.getLong("end_ms")
        // Let the server decide around the expiry boundary when the phone clock differs.
        if (end < now - DAY_MS - 60_000 || end <= start) return null
        return "/v1/replay?from_ms=$start&to_ms=$end"
    }

    fun matching(data: JSONObject, card: JSONObject): List<JSONObject> {
        val rows = data.getJSONArray("images")
        val start = card.getLong("start_ms")
        val end = card.getLong("end_ms")
        val app = card.optString("app")
        return (0 until minOf(256, rows.length())).mapNotNull(rows::optJSONObject).filter {
            val at = it.optLong("at_ms", -1)
            validId.matches(it.optString("id")) && at in start until end &&
                (app.isBlank() || it.optString("app") == app)
        }.distinctBy { it.getString("id") }.sortedBy { it.getLong("at_ms") }
    }
}
