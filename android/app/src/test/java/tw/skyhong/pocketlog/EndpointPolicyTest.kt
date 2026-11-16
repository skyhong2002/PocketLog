package tw.skyhong.pocketlog
import org.junit.Assert.*
import org.junit.Test

class EndpointPolicyTest {
    @Test fun normalizesHttpsOrigin() {
        assertEquals("https://example.com", EndpointPolicy.normalize(" HTTPS://EXAMPLE.COM:443/ "))
        assertEquals("https://server.example:8443", EndpointPolicy.normalize("https://server.example:8443"))
    }
    @Test fun plainHttpIsAllowedOnlyForPrivateNetworkHosts() {
        assertEquals("http://100.64.0.1:12446", EndpointPolicy.normalize("http://100.64.0.1:12446"))
        assertEquals("http://nas.lan", EndpointPolicy.normalize("HTTP://NAS.LAN:80/"))
        assertTrue(EndpointPolicy.isPlainHttp("http://nas.lan"))
        assertFalse(EndpointPolicy.isPlainHttp("https://nas.lan"))
        for (public in listOf("http://example.com", "http://8.8.8.8", "http://100.128.0.1", "http://172.32.0.1", "http://[2001:db8::1]")) {
            assertTrue(public, runCatching { EndpointPolicy.normalize(public) }.isFailure)
        }
    }
    @Test fun privateHostsMatchTheServerRule() {
        // Same cases as server/src/analysis.rs network_tests.
        for (ok in listOf("127.0.0.1", "localhost", "cliproxyapi", "192.168.1.20", "10.0.0.5", "172.20.0.3", "100.101.102.103",
                "[fd12:3456::1]", "[::1]", "mybox.local", "nas.lan", "host.docker.internal", "169.254.1.1", "router.home.arpa", "app.localhost", "fe80::1")) {
            assertTrue(ok, EndpointPolicy.privateNetworkHost(ok))
        }
        for (public in listOf("api.openai.com", "8.8.8.8", "100.128.0.1", "172.32.0.1", "[2001:db8::1]", "", "999.1.1.1.example.com")) {
            assertFalse(public, EndpointPolicy.privateNetworkHost(public))
        }
    }
    @Test fun refusesCredentialAndRoutingAmbiguity() {
        for (value in listOf("https://user:pass@example.com", "https://example.com/path", "https://example.com?token=secret", "https://example.com#fragment", "file:///private", "https://example.com:0", "ftp://nas.lan")) {
            assertTrue(value, runCatching { EndpointPolicy.normalize(value) }.isFailure)
        }
    }
    @Test fun theDeviceKeyIsNeverSentOverPublicHttp() {
        // PocketLogApi validates the origin before opening any connection or writing the header.
        val failure = runCatching { PocketLogApi(Credentials("http://8.8.8.8", "ab".repeat(32))).request("/v1/collection") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        val named = runCatching { PocketLogApi(Credentials("http://journal.example.com", "ab".repeat(32))).request("/v1/collection") }.exceptionOrNull()
        assertTrue(named is IllegalArgumentException)
    }
    @Test fun tokenHasExpectedRandomSecretFormat() {
        assertTrue(EndpointPolicy.validToken("ab".repeat(32)))
        assertFalse(EndpointPolicy.validToken("secret"))
        assertFalse(EndpointPolicy.validToken("z".repeat(64)))
    }
}
