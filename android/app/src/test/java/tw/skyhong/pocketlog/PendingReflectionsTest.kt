package tw.skyhong.pocketlog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PendingReflectionsTest {
    private class MemoryFiles : JournalFiles {
        val data = mutableMapOf<String, ByteArray>()
        override fun names(prefix: String) = data.keys.filter { it.startsWith(prefix) }.sorted()
        override fun size(name: String) = data[name]?.size?.toLong() ?: 0
        override fun write(name: String, clear: ByteArray) { data[name] = clear.copyOf() }
        override fun read(name: String) = data[name]?.copyOf()
        override fun remove(name: String) { data.remove(name) }
    }
    private val c = Credentials("https://example.com", "a".repeat(64))
    private val now = System.currentTimeMillis()
    private val path = "/v1/timeline?from_ms=${now - 1000}&to_ms=${now + 1000}"
    private fun body(text: String, at: Long = now, id: String = "reflection-day") = JSONObject().put("id", id).put("at_ms", at).put("text", text)
    private fun base() = JSONObject().put("reflections", JSONArray().put(body("A")))
    private fun entry(name: String, body: JSONObject) = JournalQueue.Entry(name, JSONObject().put("path", "/v1/reflections").put("body", body))

    @Test fun updatesAndDeletionOverrideSnapshotWithoutMutatingIt() {
        val base = base().put("offline", true)
        val writes = listOf(entry("q_1", body("B")), entry("q_2", body("C")))
        val result = PendingReflections.overlay(base, path, writes, now)
        assertEquals("C", result.getJSONArray("reflections").getJSONObject(0).getString("text"))
        assertTrue(result.getBoolean("pending_reflections")); assertTrue(result.getBoolean("offline"))
        assertEquals("A", base.getJSONArray("reflections").getJSONObject(0).getString("text"))
        val deleted = PendingReflections.overlay(base, path, writes + entry("q_3", body(" \n")), now)
        assertEquals(0, deleted.getJSONArray("reflections").length())
        assertTrue(deleted.getBoolean("pending_reflections"))
    }

    @Test fun fullQueueScanExcludesOtherDestinationRejectedExpiredAndInvalid() {
        val files = MemoryFiles(); val queue = JournalQueue(files) {}
        repeat(260) { queue.enqueue(c, "/v1/checkins", JSONObject().put("id", "m$it"), now - 500) }
        queue.enqueue(c, "/v1/reflections", body("B"), now)
        queue.enqueue(Credentials(c.endpoint, "b".repeat(64)), "/v1/reflections", body("other"), now + 1)
        val expired = entry("q_${now - RecordingRules.RAW_TTL}_expired", body("expired"))
        files.write(expired.name, expired.data.put("destination", JournalQueue.destination(c)).toString().toByteArray())
        val rejected = entry("r_${now}_rejected", body("rejected"))
        files.write(rejected.name, rejected.data.put("destination", JournalQueue.destination(c)).toString().toByteArray())
        queue.enqueue(c, "/v1/reflections", body("invalid").put("unexpected", true), now + 2)
        assertEquals(256, queue.entries(c).size)
        val result = PendingReflections.overlay(base(), path, queue.pendingReflections(c), now)
        assertEquals("B", result.getJSONArray("reflections").getJSONObject(0).getString("text"))
    }

    @Test fun windowBoundariesStableIdsAndInvalidBodiesAreRespected() {
        val writes = listOf(entry("q_1", body("other day", now + 1000, "other")),
            entry("q_2", body("moved", now + 1000)), entry("q_3", body("bad").put("at_ms", "$now")),
            entry("q_4", body("bad\u0000")), entry("q_5", body("x".repeat(2001))))
        val result = PendingReflections.overlay(base(), path, writes, now)
        assertEquals(0, result.getJSONArray("reflections").length())
        assertTrue(result.getBoolean("pending_reflections"))
        assertFalse(PendingReflections.overlay(base(), path, listOf(writes.first()), now).getBoolean("pending_reflections"))
    }

    @Test fun filenameTieBreakMatchesUploadAndAcknowledgedWritesDisappear() {
        val files = MemoryFiles(); val queue = JournalQueue(files) {}
        queue.enqueue(c, "/v1/reflections", body("first"), now)
        queue.enqueue(c, "/v1/reflections", body("second"), now)
        val upload = queue.entries(c)
        val result = PendingReflections.overlay(base(), path, queue.pendingReflections(c).reversed(), now)
        assertEquals(upload.last().data.getJSONObject("body").getString("text"), result.getJSONArray("reflections").getJSONObject(0).getString("text"))
        upload.forEach(queue::acknowledge)
        assertFalse(PendingReflections.overlay(base(), path, queue.pendingReflections(c), now).getBoolean("pending_reflections"))
    }

    @Test fun deletionThenNewSaveAndLowerBoundaryAreVisibleButInvalidValuesAreIgnored() {
        val writes = listOf(entry("q_1", body("")), entry("q_2", body("restored", now - 1000)),
            entry("q_3", body("bad", id = "invalid id")), entry("q_4", body("old", now - 30L * RecordingRules.RAW_TTL)),
            entry("q_5", body("future", now + 60_001)))
        val result = PendingReflections.overlay(base(), path, writes, now)
        assertEquals("restored", result.getJSONArray("reflections").getJSONObject(0).getString("text"))
        assertEquals(now - 1000, result.getJSONArray("reflections").getJSONObject(0).getLong("at_ms"))
    }
}
