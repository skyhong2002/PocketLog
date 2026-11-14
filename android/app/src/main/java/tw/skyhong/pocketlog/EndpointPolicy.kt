package tw.skyhong.pocketlog

import java.net.URI

object EndpointPolicy {
    fun normalize(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", ignoreCase = true)) { "伺服器必須使用 HTTPS" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "請輸入伺服器來源網址" }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "網址不可含路徑" }
        require(uri.port == -1 || uri.port in 1..65535) { "連接埠無效" }
        return URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port, null, null, null).toASCIIString()
    }
    fun validToken(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
