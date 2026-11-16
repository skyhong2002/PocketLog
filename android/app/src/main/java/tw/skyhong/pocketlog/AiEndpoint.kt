package tw.skyhong.pocketlog

import java.net.URI

/** Provider URLs describe a server-to-model connection; API credentials stay in request headers. */
object AiEndpoint {
    fun normalize(value: String, openAi: Boolean = true): String {
        val raw = value.trim().trimEnd('/')
        val uri = URI(raw)
        // Same rule as the server: plain HTTP only to a private-network host (local proxy, VPN, LAN).
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host != null && EndpointPolicy.privateNetworkHost(uri.host)))
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(raw.length <= 2048)
        if (!openAi) return raw
        val path = uri.path.orEmpty()
        return when {
            path.endsWith("/chat/completions") -> raw
            path.endsWith("/v1") -> "$raw/chat/completions"
            path.isEmpty() -> "$raw/v1/chat/completions"
            else -> "$raw/chat/completions"
        }
    }
}
