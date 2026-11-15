package tw.skyhong.pocketlog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GoalRecoveryTest {
    private fun workspace() = JSONObject().put("revision", 1).put("focus_minutes", 270).put("distraction_minutes", 60)
        .put("categories", JSONArray().put(JSONObject().put("name", "Work")))
        .put("rules", JSONArray().put(JSONObject().put("enabled", true)))
        .put("onboarding_version", 1)

    @Test fun unrelatedChangesPreserveLatestFieldsAndRevisionWithoutAliasing() {
        val base = workspace()
        val draft = workspace().put("focus_minutes", 300).put("distraction_minutes", 45)
        val latest = workspace().put("revision", 9).put("onboarding_version", 2).put("future_field", "latest")
        latest.getJSONArray("categories").getJSONObject(0).put("name", "Remote")
        latest.getJSONArray("rules").getJSONObject(0).put("enabled", false)
        val before = listOf(base, draft, latest).map { it.toString() }
        assertFalse(GoalRecovery.goalsChanged(base, latest))
        val candidate = GoalRecovery.candidate(draft, latest)
        assertEquals(300, candidate.getInt("focus_minutes"))
        assertEquals(45, candidate.getInt("distraction_minutes"))
        assertEquals(9L, candidate.getLong("revision"))
        assertEquals(2, candidate.getInt("onboarding_version"))
        assertEquals("latest", candidate.getString("future_field"))
        assertEquals("Remote", candidate.getJSONArray("categories").getJSONObject(0).getString("name"))
        assertFalse(candidate.getJSONArray("rules").getJSONObject(0).getBoolean("enabled"))
        candidate.getJSONArray("categories").getJSONObject(0).put("name", "Mutated")
        candidate.getJSONArray("rules").getJSONObject(0).put("enabled", true)
        assertEquals(before, listOf(base, draft, latest).map { it.toString() })
    }

    @Test fun eitherRemoteGoalChangeRequiresConfirmationIncludingAfterRebasedRetry() {
        val base = workspace()
        val draft = workspace().put("focus_minutes", 300)
        val latest = workspace().put("revision", 2)
        val retry = GoalRecovery.candidate(draft, latest)
        assertFalse(GoalRecovery.goalsChanged(base, latest))
        val raced = workspace().put("revision", 3).put("focus_minutes", 310)
        assertTrue(GoalRecovery.goalsChanged(base, raced))
        assertEquals(3L, GoalRecovery.candidate(retry, raced).getLong("revision"))
        assertEquals(300, GoalRecovery.candidate(retry, raced).getInt("focus_minutes"))
        assertTrue(GoalRecovery.goalsChanged(base, workspace().put("distraction_minutes", 90)))
        assertTrue(GoalRecovery.goalsChanged(base, draft))
    }

    @Test fun boundariesAreAccepted() {
        val candidate = GoalRecovery.candidate(workspace().put("focus_minutes", 0).put("distraction_minutes", 1440), workspace())
        assertEquals(0, candidate.getInt("focus_minutes"))
        assertEquals(1440, candidate.getInt("distraction_minutes"))
    }

    @Test fun invalidDraftCannotProduceRetryPayload() {
        for (key in listOf("focus_minutes", "distraction_minutes")) {
            for (value in listOf(-1, 1441)) {
                assertThrows(IllegalArgumentException::class.java) {
                    GoalRecovery.candidate(workspace().put(key, value), workspace())
                }
            }
        }
    }
}
