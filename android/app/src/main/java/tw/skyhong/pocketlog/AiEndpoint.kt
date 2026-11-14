package tw.skyhong.pocketlog

import java.net.URI

/** Provider URLs describe a server-to-model connection; API credentials stay in request headers. */
object AiEndpoint {
    fun normalize(value: String): String {
        val raw = value.trim().trimEnd('/')
        val uri = URI(raw)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "[::1]", "::1")))
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(raw.length <= 2048)
        val path = uri.path.orEmpty()
        return when {
            path.endsWith("/chat/completions") -> raw
            path.endsWith("/v1") -> "$raw/chat/completions"
            path.isEmpty() -> "$raw/v1/chat/completions"
            else -> "$raw/chat/completions"
        }
    }
}
