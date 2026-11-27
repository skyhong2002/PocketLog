package tw.skyhong.pocketlog

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationUploadTest {
    @Test fun negotiatedGzipPreservesPayloadAndOlderServersReceivePlainJson() {
        for (advertises in listOf(false, true)) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val received = LinkedBlockingQueue<Pair<String?, String>>()
            server.createContext("/v1/status") { exchange ->
                val capabilities = if (advertises) """["observation_gzip"]""" else "[]"
                val response = """{"api":{"capabilities":$capabilities}}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.createContext("/v1/observations") { exchange ->
                val encoding = exchange.requestHeaders.getFirst("Content-Encoding")
                val stream = if (encoding == "gzip") GZIPInputStream(exchange.requestBody) else exchange.requestBody
                val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                received.offer(encoding to json)
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            server.start()
            try {
                val body = JSONObject().put("screenshot", "Aa0/".repeat(3000))
                val api = PocketLogApi(Credentials("http://127.0.0.1:${server.address.port}", "a".repeat(64)))
                api.request("/v1/observations", body)
                assertEquals((if (advertises) "gzip" else null) to body.toString(), received.poll())
            } finally {
                server.stop(0)
            }
        }
    }
}
