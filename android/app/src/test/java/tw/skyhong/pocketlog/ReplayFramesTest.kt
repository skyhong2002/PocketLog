package tw.skyhong.pocketlog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayFramesTest {
    private val card = JSONObject().put("start_ms", 1000).put("end_ms", 2000).put("app", "com.discord")

    @Test fun includesOnlyMatchingFramesInsideTheActivity() {
        fun frame(id: String, at: Long, app: String = "com.discord") = JSONObject()
            .put("id", id).put("at_ms", at).put("app", app)
        val images = JSONArray(listOf(
            frame("second", 1400), frame("first", 1000), frame("outside", 2000),
            frame("wrong-app", 1200, "com.android.chrome"), frame("../bad", 1600), frame("second", 1400),
        ))
        val items = ReplayFrames.matching(JSONObject().put("images", images), card)
        assertEquals(listOf("first", "second"), items.map { it.getString("id") })
    }

    @Test fun avoidsExpiredRequestsWithAGracePeriodForClockSkew() {
        assertNull(ReplayFrames.window(card, 2000 + 86_460_001L))
        assertEquals("/v1/replay?from_ms=1000&to_ms=2000",
            ReplayFrames.window(card, 2000 + 86_460_000L))
    }
}
