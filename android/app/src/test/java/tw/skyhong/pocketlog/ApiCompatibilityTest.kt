package tw.skyhong.pocketlog

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiCompatibilityTest {
    private fun status() = JSONObject().put("schema_version", 2)
        .put("analysis", "local_excerpt_or_owner_enabled_model").put("pending_context", 0)

    private fun failure(reason: ApiProtocolFailure.Reason, work: () -> Unit) {
        try { work(); fail("Expected protocol failure") } catch (error: ApiProtocolFailure) {
            assertEquals(reason, error.reason)
            assertNull(error.cause)
            assertFalse(error.toString().contains("private-response"))
        }
    }

    @Test fun oldStatusAndAdditiveFieldsRemainCompatible() {
        ApiCompatibility.status(status())
        ApiCompatibility.status(status().put("schema_version", 99).put("new_field", true)
            .put("api", JSONObject().put("version", 1).put("capabilities", listOf("future_feature"))))
        ApiCompatibility.timeline(JSONObject("""{"activities":[],"cards":[],"new_field":true}"""))
        assertEquals(1, ApiCompatibility.decode(null, """{"legacy":1}""").getInt("legacy"))
    }

    @Test fun unsupportedApiVersionIsDifferentFromMalformedMetadata() {
        failure(ApiProtocolFailure.Reason.UNSUPPORTED_VERSION) {
            ApiCompatibility.status(status().put("api", JSONObject().put("version", 2)))
        }
        for (api in listOf(JSONObject(), JSONObject().put("version", "1"), JSONObject().put("version", 1.5), JSONObject.NULL)) {
            failure(ApiProtocolFailure.Reason.INVALID_RESPONSE) { ApiCompatibility.status(status().put("api", api)) }
        }
        failure(ApiProtocolFailure.Reason.INVALID_RESPONSE) { ApiCompatibility.status(JSONObject("""{"ok":true}""")) }
        failure(ApiProtocolFailure.Reason.INVALID_RESPONSE) { ApiCompatibility.timeline(JSONObject("""{"activities":[]}""")) }
    }

    @Test fun wrongServiceAndMalformedResponsesNeverExposeBodies() {
        failure(ApiProtocolFailure.Reason.WRONG_SERVICE) { ApiCompatibility.decode("text/html; charset=utf-8", "<html>private-response</html>") }
        failure(ApiProtocolFailure.Reason.WRONG_SERVICE) { ApiCompatibility.decode("application/json", " <html>private-response</html>") }
        failure(ApiProtocolFailure.Reason.INVALID_RESPONSE) { ApiCompatibility.decode("application/json", "private-response") }
        failure(ApiProtocolFailure.Reason.INVALID_RESPONSE) { ApiCompatibility.decode("application/json", "[]") }
    }

    private fun server(statusCode: Int = 200, statusBody: String, contentType: String = "application/json", work: (PocketLogApi, MutableList<String>) -> Unit) {
        val calls = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            calls.add(exchange.requestURI.path)
            val status = exchange.requestURI.path == "/v1/status"
            val bytes = (if (status) statusBody else """{"activities":[],"cards":[]}""").toByteArray()
            exchange.responseHeaders.set("Content-Type", contentType)
            exchange.sendResponseHeaders(if (status) statusCode else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { work(PocketLogApi(Credentials("http://127.0.0.1:${server.address.port}", "a".repeat(64))), calls) }
        finally { server.stop(0) }
    }

    @Test fun handshakeReadsStatusBeforeTimelineAndStopsOnWrongServiceOrVersion() {
        server(statusBody = status().toString()) { api, calls ->
            api.verifyConnection("/v1/timeline?from_ms=0&to_ms=1")
            assertEquals(listOf("/v1/status", "/v1/timeline"), calls)
        }
        server(statusBody = "<html>private-response</html>", contentType = "text/html") { api, calls ->
            failure(ApiProtocolFailure.Reason.WRONG_SERVICE) { api.verifyConnection("/v1/timeline") }
            assertEquals(listOf("/v1/status"), calls)
        }
        server(statusBody = status().put("api", JSONObject().put("version", 2)).toString()) { api, calls ->
            failure(ApiProtocolFailure.Reason.UNSUPPORTED_VERSION) { api.verifyConnection("/v1/timeline") }
            assertEquals(listOf("/v1/status"), calls)
        }
    }

    @Test fun rejectedKeyKeepsItsAuthenticationError() {
        server(statusCode = 401, statusBody = """{"error":"unauthorized"}""") { api, calls ->
            try { api.verifyConnection("/v1/timeline"); fail("Expected rejected key") }
            catch (error: ApiFailure) { assertEquals(401, error.status) }
            assertEquals(listOf("/v1/status"), calls)
        }
    }
}
