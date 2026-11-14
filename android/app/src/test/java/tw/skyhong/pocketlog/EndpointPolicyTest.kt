package tw.skyhong.pocketlog
import org.junit.Assert.*
import org.junit.Test

class EndpointPolicyTest {
    @Test fun normalizesHttpsOrigin() {
        assertEquals("https://example.com", EndpointPolicy.normalize(" HTTPS://EXAMPLE.COM:443/ "))
        assertEquals("https://server.example:8443", EndpointPolicy.normalize("https://server.example:8443"))
    }
    @Test fun refusesCredentialAndRoutingAmbiguity() {
        for (value in listOf("http://100.64.0.1", "https://user:pass@example.com", "https://example.com/path", "https://example.com?token=secret", "https://example.com#fragment", "file:///private", "https://example.com:0")) {
            assertTrue(value, runCatching { EndpointPolicy.normalize(value) }.isFailure)
        }
    }
    @Test fun tokenHasExpectedRandomSecretFormat() {
        assertTrue(EndpointPolicy.validToken("ab".repeat(32)))
        assertFalse(EndpointPolicy.validToken("secret"))
        assertFalse(EndpointPolicy.validToken("z".repeat(64)))
    }
}
