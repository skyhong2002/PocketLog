package tw.skyhong.pocketlog

import org.json.JSONObject

/** Retain the original goal baseline; each explicit retry preserves every latest unrelated field. */
internal object GoalRecovery {
    fun goalsChanged(base: JSONObject, latest: JSONObject): Boolean =
        base.getInt("focus_minutes") != latest.getInt("focus_minutes") ||
            base.getInt("distraction_minutes") != latest.getInt("distraction_minutes")

    fun candidate(draft: JSONObject, latest: JSONObject): JSONObject {
        val focus = draft.getInt("focus_minutes")
        val distraction = draft.getInt("distraction_minutes")
        require(focus in 0..1440 && distraction in 0..1440)
        return JSONObject(latest.toString()).put("focus_minutes", focus).put("distraction_minutes", distraction)
    }
}
