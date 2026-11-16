package tw.skyhong.pocketlog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PendingCardEditsTest {
    private val path = "/v1/timeline?from_ms=100&to_ms=200"
    private fun card(id: String = "activity") = JSONObject().put("id", id).put("start_ms", 100).put("end_ms", 200)
        .put("app", "org.example.reader").put("title", "Server title").put("summary", "Server summary")
        .put("category", "work").put("source", "ai").put("pending_context", true)
    private fun base() = JSONObject().put("cards", JSONArray().put(card())).put("offline", true).put("truncated", true)
    private fun body(title: String = "My correction", category: String = "rest", id: String = "activity") =
        JSONObject().put("id", id).put("title", title).put("summary", "My summary").put("category", category)
    private fun entry(name: String, body: JSONObject, route: String = "/v1/cards") = JournalQueue.Entry(name, JSONObject().put("path", route).put("body", body))
    private fun first(result: JSONObject) = result.getJSONArray("cards").getJSONObject(0)

    @Test fun decoratesOnlyExistingCardWithoutMutatingSnapshotOrIntervals() {
        val base = base(); val before = base.toString()
        val result = PendingCardEdits.overlay(base, path, listOf(entry("q_1", body()), entry("q_2", body(id = "absent"))))
        val card = first(result)
        assertEquals(before, base.toString()); assertEquals(1, result.getJSONArray("cards").length())
        assertEquals(100, card.getInt("start_ms")); assertEquals(200, card.getInt("end_ms")); assertEquals("org.example.reader", card.getString("app"))
        assertEquals("My correction", card.getString("title")); assertEquals("rest", card.getString("category"))
        assertEquals("user", card.getString("source")); assertTrue(card.getBoolean("pending_edit")); assertTrue(card.getBoolean("pending_context"))
        assertTrue(result.getBoolean("offline")); assertTrue(result.getBoolean("truncated")); assertTrue(result.getBoolean("pending_card_edits"))
    }
    @Test fun doesNotCreateCardsOrAffectOtherRoutesOrInvalidWindows() {
        val writes = listOf(entry("q_1", body()))
        for (query in listOf("/v1/overview?from_ms=100&to_ms=200", "/v1/timeline?from_ms=200&to_ms=100", "/v1/timeline?from_ms=999999999999999999999&to_ms=200")) {
            val result = PendingCardEdits.overlay(base(), query, writes)
            assertEquals("Server title", first(result).getString("title")); assertFalse(result.getBoolean("pending_card_edits"))
        }
        val empty = JSONObject().put("cards", JSONArray())
        assertEquals(0, PendingCardEdits.overlay(empty, path, writes).getJSONArray("cards").length())
        assertFalse(PendingCardEdits.overlay(base(), path, listOf(entry("q_1", body(), "/v1/reflections"))).getBoolean("pending_card_edits"))
    }
    @Test fun latestValidFilenameWinsAndServerBoundsUseUnicodeScalars() {
        val valid = body("🙂".repeat(200), "custom_removed").put("summary", "🙂".repeat(1000))
        val invalid = listOf(body(" "), body("x".repeat(201)), body().put("summary", "x".repeat(1001)), body("nul\u0000"),
            body().put("summary", "nul\u0000"), body(category = "unknown"), body(id = "bad id"), body().put("extra", true), body().put("title", 123))
        val writes = listOf(entry("q_1_a", body("earlier")), entry("q_1_z", valid)) + invalid.mapIndexed { i, b -> entry("q_2_$i", b) }
        val result = PendingCardEdits.overlay(base(), path, writes.reversed())
        assertEquals(valid.getString("title"), first(result).getString("title")); assertEquals(valid.getString("summary"), first(result).getString("summary"))
        assertEquals("custom_removed", first(result).getString("category")); assertTrue(first(result).getBoolean("pending_edit"))
    }
    private class MemoryFiles : JournalFiles {
        val data = mutableMapOf<String, ByteArray>()
        override fun names(prefix: String) = data.keys.filter { it.startsWith(prefix) }.sorted()
        override fun size(name: String) = data[name]?.size?.toLong() ?: 0
        override fun write(name: String, clear: ByteArray) { data[name] = clear.copyOf() }
        override fun read(name: String) = data[name]?.copyOf()
        override fun remove(name: String) { data.remove(name) }
    }
    @Test fun fullRetainedQueueHonorsDestinationRejectionExpiryAndAcknowledgement() {
        val now = System.currentTimeMillis(); val files = MemoryFiles(); val queue = JournalQueue(files) {}
        val c = Credentials("https://example.com", "a".repeat(64))
        repeat(260) { queue.enqueue(c, "/v1/activities", JSONObject(), now - 1000) }
        queue.enqueue(c, "/v1/cards", body("accepted"), now)
        queue.enqueue(Credentials(c.endpoint, "b".repeat(64)), "/v1/cards", body("other token"), now + 1)
        queue.enqueue(Credentials("https://other.example", c.token), "/v1/cards", body("other server"), now + 2)
        val rejected = entry("r_${now}_reject", body("rejected")); files.write(rejected.name, rejected.data.put("destination", JournalQueue.destination(c)).toString().toByteArray())
        val expired = entry("q_${now - RecordingRules.RAW_TTL}_expired", body("expired")); files.write(expired.name, expired.data.put("destination", JournalQueue.destination(c)).toString().toByteArray())
        assertEquals(32, queue.entries(c).size)
        val pending = queue.pendingJournalEdits(c)
        assertEquals(1, pending.size); assertEquals("accepted", first(PendingCardEdits.overlay(base(), path, pending)).getString("title"))
        pending.forEach(queue::acknowledge)
        assertFalse(PendingCardEdits.overlay(base(), path, queue.pendingJournalEdits(c)).getBoolean("pending_card_edits"))
    }
}
