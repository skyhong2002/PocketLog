package tw.skyhong.pocketlog

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.net.URL
import java.net.HttpURLConnection

class ApiFailure(val status: Int, val retryMs: Long) : Exception("HTTP request failed")

class PocketLogApi(private val credentials: Credentials) {
    private var observationGzip: Boolean? = null

    /** Authenticate and check the API contract before persisting a new connection. */
    fun verifyConnection(timelinePath: String) {
        ApiCompatibility.status(request("/v1/status"))
        ApiCompatibility.timeline(request(timelinePath))
    }

    fun request(path: String, body: JSONObject? = null, method: String = if (body == null) "GET" else "POST"): JSONObject {
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        require(bytes == null || bytes.size <= RecordingRules.MAX_UPLOAD_BODY)
        val compressed = bytes?.takeIf { path == "/v1/observations" && it.size >= 8 * 1024 && acceptsGzip() }
            ?.let { original ->
                val output = ByteArrayOutputStream()
                GZIPOutputStream(output).use { it.write(original) }
                output.toByteArray().takeIf { it.size < original.size }
            }
        if (compressed != null) {
            try {
                return send(path, compressed, method, gzip = true)
            } catch (failure: ApiFailure) {
                if (failure.status != 415) throw failure
                observationGzip = false
            }
        }
        return send(path, bytes, method, gzip = false)
    }

    private fun acceptsGzip(): Boolean {
        observationGzip?.let { return it }
        val available = runCatching {
            val features = request("/v1/status").optJSONObject("api")?.optJSONArray("capabilities")
            features != null && (0 until features.length()).any { features.optString(it) == "observation_gzip" }
        }.getOrDefault(false)
        observationGzip = available
        return available
    }

    private fun send(path: String, body: ByteArray?, method: String, gzip: Boolean): JSONObject {
        require(path.startsWith("/v1/"))
        // Refuses public plain HTTP before any connection exists, so the key never leaves over it.
        val endpoint = EndpointPolicy.normalize(credentials.endpoint)
        require(EndpointPolicy.validToken(credentials.token))
        val connection = URL(endpoint + path).openConnection() as HttpURLConnection
        try {
            // HTTPS keeps the platform TLS verifier; plain HTTP is private-network only (EndpointPolicy).
            // Redirects must never carry an owner credential elsewhere.
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer ${credentials.token}")
            connection.setRequestProperty("Accept", "application/json")
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                if (gzip) connection.setRequestProperty("Content-Encoding", "gzip")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val delay = connection.getHeaderField("Retry-After")?.let { RetryPolicy.parseDelay(it) } ?: 60_000L
                throw ApiFailure(status, delay)
            }
            if (status == 204) return JSONObject()
            val bytes = connection.inputStream.use { it.readNBytes(512 * 1024 + 1) }
            check(bytes.size <= 512 * 1024) { "回應超過大小限制" }
            return ApiCompatibility.decode(connection.contentType, String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
