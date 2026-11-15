package tw.skyhong.pocketlog

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ConnectionRejectionTest {
    @Test fun onlyExplicit401EstablishesKnownCredentialRejection() {
        assertTrue(ConnectionRejection.isKnownFailure(ApiFailure(401, 60_000)))
        for (status in listOf(400, 403, 404, 409, 423, 429, 500, 503))
            assertFalse(ConnectionRejection.isKnownFailure(ApiFailure(status, 60_000)))
        assertFalse(ConnectionRejection.isKnownFailure(IOException("offline")))
        assertFalse(ConnectionRejection.isKnownFailure(IllegalStateException("incomplete response")))
    }
    @Test fun rejectionBelongsToTheExactConnectionAndNeverToANewKeyOrHost() {
        val current = Credentials("https://example.invalid", "a".repeat(64))
        val rejected = JournalQueue.destination(current)
        assertTrue(ConnectionRejection.matches(rejected, current))
        assertFalse(ConnectionRejection.matches(rejected, current.copy(token = "b".repeat(64))))
        assertFalse(ConnectionRejection.matches(rejected, current.copy(endpoint = "https://other.invalid")))
        assertFalse(ConnectionRejection.matches(rejected, null))
        assertFalse(ConnectionRejection.matches(null, current))
    }
}
