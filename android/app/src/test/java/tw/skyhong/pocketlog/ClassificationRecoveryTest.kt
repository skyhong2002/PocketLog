package tw.skyhong.pocketlog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClassificationRecoveryTest {
    private fun workspace() = JSONObject().put("revision", 1).put("focus_minutes", 270).put("distraction_minutes", 60).put("onboarding_version", 1)
        .put("categories", JSONArray().put(JSONObject().put("id", "work").put("name", "Work").put("color", "#123456").put("role", "focus")))
        .put("rules", JSONArray().put(JSONObject().put("id", "rule").put("app", "org.example.app").put("title_contains", JSONObject.NULL).put("category", "work").put("enabled", true)))

    @Test fun unrelatedChangesKeepLatestFieldsAndLocalClassificationWithoutAliasing() {
        val base = workspace(); val draft = workspace(); draft.getJSONArray("categories").getJSONObject(0).put("name", "Draft")
        val latest = workspace().put("revision", 2).put("focus_minutes", 310).put("onboarding_version", 2)
        val before = listOf(base, draft, latest).map { it.toString() }
        assertEquals(ClassificationRecovery.Kind.OTHER, ClassificationRecovery.kind(base, latest, 1))
        val candidate = ClassificationRecovery.candidate(draft, latest)
        assertEquals(2, candidate.getLong("revision")); assertEquals(310, candidate.getInt("focus_minutes")); assertEquals(2, candidate.getInt("onboarding_version"))
        assertEquals("Draft", candidate.getJSONArray("categories").getJSONObject(0).getString("name"))
        candidate.getJSONArray("categories").getJSONObject(0).put("name", "Changed again")
        candidate.getJSONArray("rules").getJSONObject(0).put("enabled", false)
        assertEquals(before, listOf(base, draft, latest).map { it.toString() })
    }
    @Test fun categoryAndRuleChangesRequireExplicitReplacement() {
        val base = workspace()
        val latest = workspace().put("revision", 2)
        latest.getJSONArray("categories").getJSONObject(0).put("color", "#abcdef")
        assertEquals(ClassificationRecovery.Kind.CLASSIFICATION, ClassificationRecovery.kind(base, latest, 1))
        latest.put("categories", base.getJSONArray("categories"))
        latest.getJSONArray("rules").getJSONObject(0).put("enabled", false)
        assertEquals(ClassificationRecovery.Kind.CLASSIFICATION, ClassificationRecovery.kind(base, latest, 1))
    }
    @Test fun usedCategoryUsesAttemptedRevisionIncludingRebasedRetry() {
        val base = workspace(); val latest = workspace().put("revision", 9)
        assertEquals(ClassificationRecovery.Kind.USED, ClassificationRecovery.kind(base, latest, 9))
        assertEquals(ClassificationRecovery.Kind.OTHER, ClassificationRecovery.kind(base, latest, 8))
    }
    @Test fun objectKeyOrderAndAbsentNullableFieldsDoNotCreateConflicts() {
        val base = workspace(); val latest = workspace().put("revision", 2)
        latest.getJSONArray("rules").getJSONObject(0).remove("title_contains")
        latest.getJSONArray("categories").put(0, JSONObject("{\"role\":\"focus\",\"color\":\"#123456\",\"name\":\"Work\",\"id\":\"work\"}"))
        assertEquals(ClassificationRecovery.Kind.OTHER, ClassificationRecovery.kind(base, latest, 1))
    }
}
