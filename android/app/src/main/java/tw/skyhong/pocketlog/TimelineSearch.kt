package tw.skyhong.pocketlog

import java.net.URLEncoder

/** Pure bounds and URL rules for the native activity-card search. */
object TimelineSearch {
    const val MAX_QUERY_CHARS = 100
    const val MAX_RESULTS = 50
    const val MAX_REQUESTS = 5

    data class Cursor(val startMs: Long, val id: String, val epoch: Long)
    data class Request(val query: String, val category: String?, val cursor: Cursor? = null)

    fun normalizeQuery(value: String): String? = value.trim().takeIf { it.isNotEmpty() }
        ?.also { require(it.codePointCount(0, it.length) <= MAX_QUERY_CHARS) { "搜尋文字太長" } }

    fun path(fromMs: Long, toMs: Long, request: Request): String = buildString {
        append("/v1/activities?from_ms=").append(fromMs)
            .append("&to_ms=").append(toMs).append("&limit=").append(MAX_RESULTS)
        append("&q=").append(encode(request.query))
        request.category?.let { append("&category=").append(encode(it)) }
        request.cursor?.let {
            append("&after_start_ms=").append(it.startMs)
                .append("&after_id=").append(encode(it.id))
                .append("&epoch=").append(it.epoch)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
}
