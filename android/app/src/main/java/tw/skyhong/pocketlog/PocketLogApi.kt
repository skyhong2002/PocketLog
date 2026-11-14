package tw.skyhong.pocketlog

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class PocketLogApi(private val credentials: Credentials) {
    fun request(path: String, body: JSONObject? = null): JSONObject {
        require(path.startsWith("/v1/"))
        val endpoint = EndpointPolicy.normalize(credentials.endpoint)
        require(EndpointPolicy.validToken(credentials.token))
        val connection = URL(endpoint + path).openConnection() as HttpsURLConnection
        try {
            // Keep the platform TLS verifier. Redirects must never carry an owner credential elsewhere.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer ${credentials.token}")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            check(status in 200..299) { "伺服器回應 $status；請檢查連線與金鑰" }
            if (status == 204) return JSONObject()
            val bytes = connection.inputStream.use { it.readNBytes(512 * 1024 + 1) }
            check(bytes.size <= 512 * 1024) { "回應超過大小限制" }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
