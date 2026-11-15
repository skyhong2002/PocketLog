package tw.skyhong.pocketlog

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageCheckpointTest {
    private class MemoryFiles : JournalFiles {
        var bytes: ByteArray? = null
        override fun names(prefix: String) = emptyList<String>()
        override fun size(name: String) = bytes?.size?.toLong() ?: 0
        override fun read(name: String) = bytes?.copyOf()
        override fun write(name: String, clear: ByteArray) { bytes = clear.copyOf() }
        override fun remove(name: String) { bytes = null }
    }

    @Test fun restoringPermissionDiscardsDeniedIntervalAndOpenSessionAcrossRestart() {
        val files = MemoryFiles()
        val checkpoint = UsageCheckpoint(files) { fail("unexpected corrupt checkpoint") }
        checkpoint.save(100, "synthetic.reader", 50, true)
        assertNull(checkpoint.reconcile(200, false))
        // A new collector instance must retain the denial, even after many denied checks.
        val restarted = UsageCheckpoint(files) { fail("unexpected corrupt checkpoint") }
        assertNull(restarted.reconcile(300, false))
        assertNull(restarted.reconcile(400, true))
        val restored = requireNotNull(restarted.reconcile(500, true))
        assertEquals(400L, restored.getLong("cursor"))
        assertEquals(0L, restored.getLong("start"))
        assertFalse(restored.has("app"))
        // A pause at 450 cannot complete a session that started before revocation.
        assertTrue(Sessions.scan(listOf(AppEvent(450, "synthetic.reader", false)), null, 0, 500).sessions.isEmpty())
    }

    @Test fun unchangedGrantPreservesCadenceAndOpenSession() {
        val checkpoint = UsageCheckpoint(MemoryFiles()) { fail("unexpected corrupt checkpoint") }
        checkpoint.save(100, "synthetic.reader", 80, true)
        val saved = requireNotNull(checkpoint.reconcile(200, true))
        assertEquals(100L, saved.getLong("cursor"))
        val scan = Sessions.scan(listOf(AppEvent(300, "synthetic.reader", false)), saved.getString("app"), saved.getLong("start"), 400)
        assertEquals(Session("synthetic.reader", 80, 300), scan.sessions.single())
    }

    @Test fun legacyCheckpointStartsAtNewBoundaryWithoutClaimingCorruption() {
        val files = MemoryFiles()
        files.bytes = JSONObject().put("cursor", 100).put("app", "synthetic.reader").put("start", 50).toString().toByteArray()
        val checkpoint = UsageCheckpoint(files) { fail("legacy is not corruption") }
        assertNull(checkpoint.reconcile(400, true))
        assertEquals(400L, requireNotNull(checkpoint.reconcile(500, true)).getLong("cursor"))
    }

    @Test fun corruptCheckpointFailsClosedAndReportsDrop() {
        val files = MemoryFiles().apply { bytes = "broken".toByteArray() }
        var drops = 0
        val checkpoint = UsageCheckpoint(files) { drops++ }
        assertNull(checkpoint.reconcile(400, true))
        assertEquals(1, drops)
        assertEquals(400L, requireNotNull(checkpoint.reconcile(500, true)).getLong("cursor"))
    }
}
