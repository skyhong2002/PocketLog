package tw.skyhong.pocketlog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TimelineUpdateFingerprintTest {
    private fun snapshot(title: String = "Synthetic") = JSONObject().put("cards", JSONArray().put(JSONObject().put("id", "synthetic").put("title", title).put("pending_context", true)))
    @Test fun movingRangeAndObjectOrderingDoNotInventUpdates() {
        val first = snapshot().put("from_ms", 1).put("to_ms", 2)
        val second = JSONObject("""{"to_ms":3,"cards":[{"pending_context":true,"title":"Synthetic","id":"synthetic"}]}""")
        assertEquals(TimelineUpdateWatch.fingerprint(first), TimelineUpdateWatch.fingerprint(second))
    }
    @Test fun titleCategoryAndPendingChangesAreUpdates() {
        val first = snapshot()
        assertNotEquals(TimelineUpdateWatch.fingerprint(first), TimelineUpdateWatch.fingerprint(snapshot("Changed")))
        val second = snapshot().apply { getJSONArray("cards").getJSONObject(0).put("category", "work").put("pending_context", false) }
        assertNotEquals(TimelineUpdateWatch.fingerprint(first), TimelineUpdateWatch.fingerprint(second))
    }
    @Test fun truncationChangesAreUpdatesButUnrelatedReflectionsAreNot() {
        val first = snapshot()
        assertNotEquals(TimelineUpdateWatch.fingerprint(first), TimelineUpdateWatch.fingerprint(snapshot().put("truncated", true)))
        assertEquals(TimelineUpdateWatch.fingerprint(first), TimelineUpdateWatch.fingerprint(snapshot().put("reflections", JSONArray().put(JSONObject().put("id", "synthetic-reflection").put("text", "Changed")))))
    }
}
