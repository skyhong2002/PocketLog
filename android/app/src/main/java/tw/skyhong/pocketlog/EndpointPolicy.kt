package tw.skyhong.pocketlog

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Which server origins the phone may send its device key to: HTTPS anywhere, plain HTTP only to a
 * private-network host (VPN, LAN, loopback). This is the real gate for cleartext; the network
 * security config only allows cleartext so that this check can decide.
 */
object EndpointPolicy {
    fun normalize(value: String): String {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "網址要以 https:// 或 http:// 開頭" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "請輸入伺服器來源網址" }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "網址不可含路徑" }
        require(uri.port == -1 || uri.port in 1..65535) { "連接埠無效" }
        val host = uri.host.lowercase()
        require(scheme == "https" || privateNetworkHost(host)) { "公開網路上的伺服器必須使用 HTTPS；http 只能用在 VPN／私人網路" }
        val defaultPort = if (scheme == "https") 443 else 80
        return URI(scheme, null, host, if (uri.port == defaultPort) -1 else uri.port, null, null, null).toASCIIString()
    }

    /** True when the saved origin has no TLS; its protection depends on the VPN／private network. */
    fun isPlainHttp(endpoint: String?): Boolean = endpoint?.startsWith("http://", ignoreCase = true) == true

    /**
     * Same rule as the server (`server/src/analysis.rs::private_network_host`): loopback, RFC 1918,
     * IPv4 link-local, 100.64/10, IPv6 loopback/ULA/link-local, `localhost`, `*.localhost`, `*.local`,
     * `*.lan`, `*.internal`, `*.home.arpa` and single-label names. Never resolves DNS.
     */
    fun privateNetworkHost(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return false
        ipv4(host)?.let { (a, b) ->
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (':' in host) {
            // An IPv6 literal (contains ':'), so this never performs a DNS lookup.
            val ip = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet6Address ?: return false
            val first = ((ip.address[0].toInt() and 0xff) shl 8) or (ip.address[1].toInt() and 0xff)
            return ip.isLoopbackAddress || (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
        }
        val name = host.trimEnd('.').lowercase()
        return name == "localhost" || '.' !in name ||
            listOf(".localhost", ".local", ".lan", ".internal", ".home.arpa").any { name.endsWith(it) }
    }

    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return null
        val octets = parts.map { it.toInt() }
        if (octets.any { it > 255 }) return null
        return octets[0] to octets[1]
    }

    fun validToken(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
