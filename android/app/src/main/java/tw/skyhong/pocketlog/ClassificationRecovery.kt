package tw.skyhong.pocketlog

import org.json.JSONArray
import org.json.JSONObject

/** Compare only the fields this editor owns; every retry still uses the server revision guard. */
internal object ClassificationRecovery {
    enum class Kind { USED, OTHER, CLASSIFICATION }
    fun kind(base: JSONObject, latest: JSONObject, attemptedRevision: Long): Kind = when {
        latest.getLong("revision") == attemptedRevision -> Kind.USED
        key(base) == key(latest) -> Kind.OTHER
        else -> Kind.CLASSIFICATION
    }
    fun candidate(draft: JSONObject, latest: JSONObject): JSONObject = JSONObject(latest.toString())
        .put("categories", JSONArray(draft.getJSONArray("categories").toString()))
        .put("rules", JSONArray((draft.optJSONArray("rules") ?: JSONArray()).toString()))

    private fun key(workspace: JSONObject): String {
        val categories = workspace.getJSONArray("categories")
        val rules = workspace.optJSONArray("rules") ?: JSONArray()
        return JSONArray().put(JSONArray().also { result ->
            for (i in 0 until categories.length()) {
                val c = categories.getJSONObject(i)
                result.put(JSONArray().put(c.getString("id")).put(c.getString("name"))
                    .put(c.getString("color")).put(c.getString("role")))
            }
        }).put(JSONArray().also { result ->
            for (i in 0 until rules.length()) {
                val r = rules.getJSONObject(i)
                result.put(JSONArray().put(r.getString("id")).put(r.opt("app") ?: JSONObject.NULL)
                    .put(r.opt("title_contains") ?: JSONObject.NULL).put(r.getString("category")).put(r.getBoolean("enabled")))
            }
        }).toString()
    }
}
