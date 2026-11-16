package tw.skyhong.pocketlog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/** The native UI talks to either the private server or the on-device journal through this. */
interface JournalBackend {
    fun request(path: String, body: JSONObject? = null, method: String = if (body == null) "GET" else "POST"): JSONObject
}

/** A server-only feature (models, Chat, Agents, replay, devices, storage) was requested in local-only mode. */
class LocalOnlyUnsupported : Exception("這項功能需要連接私人伺服器")

/**
 * Local-only mode: the `/v1` subset the native timeline, reviews, categories and Flow use,
 * answered from encrypted files on this phone (PrivateFiles: Keystore AES-GCM). Nothing is
 * uploaded and no model runs. Shapes match the server so pages render unchanged.
 *
 * Storage, all bounded by PrivateFiles' 600 KiB per file:
 *  - `lj_a_<epochDay>`: app sessions by UTC start day, ≤ [MAX_ACTIVITIES_PER_DAY] / [MAX_DAY_BYTES], newest [MAX_DAY_FILES] days kept
 *  - `lj_n_<xx>`: user card edits, sharded by activity ID prefix
 *  - `lj_r_<bucket>` / `lj_v_<bucket>`: reflections / review+flow records in [BUCKET_DAYS]-day buckets
 *  - `lj_workspace`, `lj_focus`: categories, rules and goals; the running focus timer
 */
class LocalJournal internal constructor(private val files: JournalFiles, private val now: () -> Long) : JournalBackend {
    constructor(context: Context) : this(PrivateFiles(context), System::currentTimeMillis)

    companion object {
        const val DAY = 86_400_000L
        const val MAX_ACTIVITIES_PER_DAY = 3_000
        /** Below PrivateFiles' 600 KiB per-file limit even with long package names. */
        const val MAX_DAY_BYTES = 560_000
        const val MAX_DAY_FILES = 400
        const val BUCKET_DAYS = 16L
        const val MAX_BUCKETS = 30
        private const val WORKSPACE = "lj_workspace"
        private const val FOCUS = "lj_focus"
        private val lock = Any()
        val BASE_CATEGORIES = listOf("unsorted", "work", "learning", "communication", "social_media", "entertainment", "news_reading", "life_admin", "rest", "idle")
        private val ROLES = setOf("focus", "neutral", "distraction")

        /** Same defaults as server/src/workspace.rs so a later server connection looks familiar. */
        fun defaultWorkspace(): JSONObject {
            val styles = listOf(
                arrayOf("work", "工作", "#b9a4ed", "focus"), arrayOf("learning", "學習", "#87bcb4", "focus"),
                arrayOf("communication", "溝通", "#e4be7c", "neutral"), arrayOf("social_media", "社群", "#e69c9c", "distraction"),
                arrayOf("entertainment", "娛樂", "#d4a9c8", "distraction"), arrayOf("news_reading", "閱讀", "#9bbce1", "neutral"),
                arrayOf("life_admin", "生活", "#b4c493", "neutral"), arrayOf("rest", "休息", "#80bda1", "neutral"),
                arrayOf("idle", "閒置", "#92949e", "neutral"), arrayOf("unsorted", "未分類", "#b1a797", "neutral"),
            )
            val categories = JSONArray()
            styles.forEach { (id, name, color, role) -> categories.put(JSONObject().put("id", id).put("name", name).put("color", color).put("role", role)) }
            return JSONObject().put("revision", 0).put("onboarding_version", 0).put("focus_minutes", 270)
                .put("distraction_minutes", 60).put("rules", JSONArray()).put("categories", categories)
        }

        /** First enabled rule whose exact app and case-insensitive title conditions match; edits win. */
        fun classify(workspace: JSONObject, category: String, app: String, title: String, edited: Boolean): Pair<String, String?> {
            if (edited) return category to null
            val lower = title.lowercase()
            val rules = workspace.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                if (!rule.optBoolean("enabled", true)) continue
                val needApp = rule.optString("app").takeIf { rule.has("app") && !rule.isNull("app") }
                val needTitle = rule.optString("title_contains").takeIf { rule.has("title_contains") && !rule.isNull("title_contains") }
                if ((needApp == null || needApp == app) && (needTitle == null || lower.contains(needTitle.lowercase())))
                    return rule.getString("category") to rule.getString("id")
            }
            return category to null
        }
    }

    override fun request(path: String, body: JSONObject?, method: String): JSONObject = synchronized(lock) {
        require(path.startsWith("/v1/"))
        val route = path.substringBefore('?')
        val q = query(path)
        when {
            method == "GET" && route == "/v1/timeline" -> timeline(q.long("from_ms"), q.long("to_ms"))
            method == "GET" && route == "/v1/overview" -> overview(q.long("from_ms"), q.long("to_ms"))
            method == "GET" && route == "/v1/workspace" -> workspace()
            method == "PUT" && route == "/v1/workspace" -> saveWorkspace(body ?: bad())
            method == "POST" && route == "/v1/workspace/preview" -> preview(body ?: bad())
            method == "POST" && route == "/v1/cards" -> edit(body ?: bad())
            method == "POST" && route == "/v1/reflections" -> reflect(body ?: bad())
            method == "GET" && route == "/v1/reviews" -> reviews(q.long("from_ms"), q.long("to_ms"))
            method == "POST" && route == "/v1/reviews" -> record(body ?: bad())
            method == "GET" && route == "/v1/focus" -> JSONObject().put("session", focus() ?: JSONObject.NULL)
            method == "POST" && route == "/v1/focus" -> startFocus(body ?: bad())
            method == "DELETE" && route == "/v1/focus" -> discardFocus(body ?: bad())
            method == "POST" && route == "/v1/focus/finish" -> finishFocus(body ?: bad())
            // Local collection follows this phone's own consent; there is no shared pause.
            method == "GET" && route == "/v1/collection" -> JSONObject().put("revision", 0).put("paused", false).put("changed_ms", JSONObject.NULL)
            method == "DELETE" && route == "/v1/journal" -> { erase(); JSONObject() }
            else -> throw LocalOnlyUnsupported()
        }
    }

    // ---------------------------------------------------------------- collection

    /** Stores finalized app sessions; replays of the same stable ID are ignored. */
    fun addSessions(sessions: List<Session>) = synchronized(lock) {
        sessions.filter { it.end > it.start && it.start >= 0 }.groupBy { Math.floorDiv(it.start, DAY) }.forEach { (day, batch) ->
            val name = dayName(day)
            val rows = readArray(name)
            val seen = (0 until rows.length()).mapTo(HashSet()) { rows.getJSONArray(it).getString(0) }
            var bytes = JSONObject().put("a", rows).toString().length
            for (s in batch.sortedBy { it.start }) {
                if (rows.length() >= MAX_ACTIVITIES_PER_DAY) break
                if (s.id in seen) continue
                val row = JSONArray().put(s.id).put(s.start).put(s.end).put(s.app)
                // ASCII-only content (hex IDs, digits, package names): length equals UTF-8 bytes.
                val cost = row.toString().length + 1
                if (bytes + cost > MAX_DAY_BYTES) break
                seen.add(s.id); rows.put(row); bytes += cost
            }
            write(name, JSONObject().put("a", rows))
        }
        files.names("lj_a_").let { days -> days.take((days.size - MAX_DAY_FILES).coerceAtLeast(0)).forEach(files::remove) }
    }

    private fun dayName(day: Long) = "lj_a_" + day.toString().padStart(7, '0')
    private fun readArray(name: String): JSONArray = read(name)?.optJSONArray("a") ?: JSONArray()

    private data class Stored(val id: String, val start: Long, val end: Long, val app: String)

    private fun stored(from: Long, to: Long): List<Stored> =
        (Math.floorDiv(from, DAY) - 1..Math.floorDiv(to - 1, DAY)).flatMap { day ->
            val rows = readArray(dayName(day))
            (0 until rows.length()).map { rows.getJSONArray(it) }.map { Stored(it.getString(0), it.getLong(1), it.getLong(2), it.getString(3)) }
        }.filter { it.end > from && it.start < to }.distinctBy { it.id }.sortedWith(compareBy({ it.start }, { it.id }))

    // ---------------------------------------------------------------- reads

    private fun window(from: Long, to: Long, maxSpan: Long) {
        if (from < 0 || to <= from || to - from > maxSpan || to > now() + 60_000) bad()
    }

    private fun card(a: Stored, from: Long, to: Long, workspace: JSONObject): JSONObject {
        val note = notes(a.id).optJSONObject(a.id)
        val edited = note != null
        val title = note?.optString("title") ?: a.app
        val (category, _) = classify(workspace, note?.optString("category") ?: "unsorted", a.app, title, edited)
        return JSONObject().put("id", a.id).put("start_ms", maxOf(a.start, from)).put("end_ms", minOf(a.end, to)).put("app", a.app)
            .put("title", title).put("summary", note?.optString("summary") ?: "").put("category", category)
            .put("source", if (edited) "user" else "app_only").put("pending_context", false)
    }

    private fun timeline(from: Long, to: Long): JSONObject {
        window(from, to, 26 * 3_600_000L)
        val workspace = workspace()
        val all = stored(from, to)
        val shown = all.take(1000)
        val activities = JSONArray(); val cards = JSONArray()
        shown.forEach { a ->
            activities.put(JSONObject().put("id", a.id).put("start_ms", maxOf(a.start, from)).put("end_ms", minOf(a.end, to)).put("app", a.app).put("category", "unsorted"))
            cards.put(card(a, from, to, workspace))
        }
        val reflections = JSONArray()
        bucketed("lj_r_", from, to).forEach { reflections.put(it) }
        return JSONObject().put("activities", activities).put("checkins", JSONArray()).put("truncated", all.size > shown.size)
            .put("cards", cards).put("reflections", reflections).put("local", true)
    }

    private fun overview(from: Long, to: Long): JSONObject {
        window(from, to, 30 * DAY)
        val workspace = workspace()
        val categories = linkedMapOf<String, Long>(); val apps = linkedMapOf<String, Long>()
        stored(from, to).forEach { a ->
            val c = card(a, from, to, workspace)
            val duration = c.getLong("end_ms") - c.getLong("start_ms")
            categories.merge(c.getString("category"), duration, Long::plus)
            apps.merge(a.app, duration, Long::plus)
        }
        val categoryRows = JSONArray()
        categories.entries.sortedByDescending { it.value }.forEach { categoryRows.put(JSONObject().put("category", it.key).put("duration_ms", it.value)) }
        val appRows = JSONArray()
        apps.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(20)
            .forEach { appRows.put(JSONObject().put("app", it.key).put("duration_ms", it.value)) }
        // Topic keywords come from server-side models or many card titles; local mode has neither.
        return JSONObject().put("from_ms", from).put("to_ms", to).put("categories", categoryRows).put("apps", appRows)
            .put("checkin_count", 0).put("mood_average", JSONObject.NULL).put("energy_average", JSONObject.NULL)
            .put("title_keywords", JSONArray()).put("keyword_mode", "local").put("keywords_sample_limit", 0).put("local", true)
    }

    // ---------------------------------------------------------------- workspace and classification

    fun workspace(): JSONObject = read(WORKSPACE) ?: defaultWorkspace()

    private fun categoryIds(workspace: JSONObject): Set<String> =
        workspace.getJSONArray("categories").let { a -> (0 until a.length()).mapTo(LinkedHashSet()) { a.getJSONObject(it).getString("id") } }

    private fun validWorkspace(w: JSONObject): Boolean = runCatching {
        val categories = w.getJSONArray("categories")
        val ids = categoryIds(w)
        val rules = w.optJSONArray("rules") ?: JSONArray()
        categories.length() in BASE_CATEGORIES.size..BASE_CATEGORIES.size + 30 && ids.size == categories.length() &&
            ids.containsAll(BASE_CATEGORIES) && ids.all { it in BASE_CATEGORIES || Regex("custom_[a-z0-9_]{1,57}").matches(it) } &&
            (0 until categories.length()).map { categories.getJSONObject(it) }.all { c ->
                c.getString("name").let { it.isNotBlank() && it.codePointCount(0, it.length) <= 40 } &&
                    Regex("#[0-9a-fA-F]{6}").matches(c.getString("color")) && c.getString("role") in ROLES
            } &&
            w.getInt("focus_minutes") in 0..1440 && w.getInt("distraction_minutes") in 0..1440 && w.getInt("onboarding_version") in 0..1 &&
            rules.length() <= 32 && (0 until rules.length()).map { rules.getJSONObject(it) }.all { r ->
                val app = r.optString("app").takeIf { r.has("app") && !r.isNull("app") }
                val title = r.optString("title_contains").takeIf { r.has("title_contains") && !r.isNull("title_contains") }
                // Same bounds as server/src/workspace.rs validate().
                validId(r.getString("id")) && r.getString("category") in ids && (app != null || title != null) &&
                    (app == null || app.isNotEmpty() && app.length <= 200) &&
                    (title == null || title.isNotBlank() && title.codePointCount(0, title.length) <= 80 && title.none(Char::isISOControl))
            }
    }.getOrDefault(false)

    private fun saveWorkspace(next: JSONObject): JSONObject {
        val current = workspace()
        if (!validWorkspace(next)) bad()
        if (next.optLong("revision", -1) != current.getLong("revision")) throw ApiFailure(409, 0)
        val saved = JSONObject(next.toString()).put("revision", current.getLong("revision") + 1)
        write(WORKSPACE, saved)
        return saved
    }

    private fun preview(body: JSONObject): JSONObject {
        val w = body.optJSONObject("workspace") ?: bad()
        val sample = body.optJSONObject("sample") ?: bad()
        if (!validWorkspace(w)) bad()
        val category = sample.optString("category")
        if (category !in categoryIds(w)) bad()
        val edited = sample.optBoolean("edited", false)
        val (result, rule) = classify(w, category, sample.optString("app"), sample.optString("title"), edited)
        return JSONObject().put("category", result).put("matched_rule_id", rule ?: JSONObject.NULL).put("manual_override", edited)
    }

    // ---------------------------------------------------------------- writes

    private fun validId(id: String) = id.isNotEmpty() && id.length <= 80 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
    private fun noteShard(id: String) = "lj_n_" + id.take(2).lowercase().filter { it in '0'..'9' || it in 'a'..'z' }.padEnd(2, '0')
    private fun notes(id: String): JSONObject = read(noteShard(id)) ?: JSONObject()

    private fun edit(body: JSONObject): JSONObject {
        val id = body.optString("id"); val title = body.optString("title"); val summary = body.optString("summary"); val category = body.optString("category")
        if (!validId(id) || title.isBlank() || title.codePointCount(0, title.length) > 200 || summary.codePointCount(0, summary.length) > 1000 ||
            '\u0000' in title || '\u0000' in summary || category !in categoryIds(workspace())) bad()
        val shard = noteShard(id)
        write(shard, notes(id).put(id, JSONObject().put("title", title).put("summary", summary).put("category", category)))
        return JSONObject()
    }

    private fun bucket(at: Long) = Math.floorDiv(Math.floorDiv(at, DAY), BUCKET_DAYS)
    private fun bucketName(prefix: String, bucket: Long) = prefix + bucket.toString().padStart(5, '0')

    /** Upserts by ID; an ID moves bucket only by one either side, so neighbours are cleared too. */
    private fun upsert(prefix: String, id: String, at: Long, value: JSONObject?) {
        val home = bucket(at)
        for (b in home - 1..home + 1) {
            val name = bucketName(prefix, b)
            val existing = read(name) ?: if (b == home && value != null) JSONObject() else continue
            existing.remove(id)
            if (b == home && value != null) existing.put(id, value)
            if (existing.length() == 0) files.remove(name) else write(name, existing)
        }
        files.names(prefix).let { all -> all.take((all.size - MAX_BUCKETS).coerceAtLeast(0)).forEach(files::remove) }
    }

    private fun bucketed(prefix: String, from: Long, to: Long): List<JSONObject> =
        (bucket(from)..bucket(to - 1)).flatMap { b ->
            val map = read(bucketName(prefix, b)) ?: return@flatMap emptyList()
            map.keys().asSequence().map { map.getJSONObject(it) }.toList()
        }.filter { it.getLong("at_ms") in from until to }.sortedWith(compareBy({ it.getLong("at_ms") }, { it.getString("id") }))

    private fun reflect(body: JSONObject): JSONObject {
        val id = body.optString("id"); val at = body.optLong("at_ms", -1); val text = body.optString("text")
        if (!validId(id) || at <= 0 || at > now() + 60_000 || text.codePointCount(0, text.length) > 2000 || '\u0000' in text) bad()
        upsert("lj_r_", id, at, if (text.isBlank()) null else JSONObject().put("id", id).put("at_ms", at).put("text", text))
        return JSONObject()
    }

    private fun reviews(from: Long, to: Long): JSONObject {
        window(from, to, 30 * DAY)
        val rows = JSONArray()
        bucketed("lj_v_", from, to).take(100).forEach { rows.put(it) }
        return JSONObject().put("records", rows).put("truncated", false)
    }

    private fun record(body: JSONObject): JSONObject {
        val id = body.optString("id"); val at = body.optLong("at_ms", -1); val kind = body.optString("kind"); val text = body.optString("text")
        val valid = validId(id) && at > 0 && at <= now() + 60_000 && kind in setOf("review", "flow") && text.codePointCount(0, text.length) <= 4000 &&
            (kind != "review" || body.optString("rating") in setOf("focused", "neutral", "distracted")) &&
            (kind != "flow" || body.optLong("end_ms", -1).let { it in at..at + DAY && it <= now() + 60_000 })
        if (!valid) bad()
        upsert("lj_v_", id, at, JSONObject(body.toString()))
        return JSONObject()
    }

    private fun focus(): JSONObject? = read(FOCUS)?.optJSONObject("session")

    private fun startFocus(body: JSONObject): JSONObject {
        val id = body.optString("id"); val at = body.optLong("at_ms", -1); val minutes = body.optInt("minutes", -1); val text = body.optString("text").trim()
        if (!validId(id) || kotlin.math.abs(at - now()) > 60_000 || minutes !in 1..180 || text.isEmpty() || text.codePointCount(0, text.length) > 300 || text.any(Char::isISOControl)) bad()
        focus()?.let { if (it.getString("id") != id) throw ApiFailure(409, 0) }
        val session = JSONObject().put("id", id).put("at_ms", at).put("minutes", minutes).put("text", text)
        write(FOCUS, JSONObject().put("session", session))
        return JSONObject().put("session", session)
    }

    private fun discardFocus(body: JSONObject): JSONObject {
        if (focus()?.getString("id") == body.optString("id")) files.remove(FOCUS)
        return JSONObject()
    }

    /** Same as the server: idempotent, and an old ID can never finish a newer timer. */
    private fun finishFocus(body: JSONObject): JSONObject {
        val id = body.optString("id")
        val session = focus()
        if (session == null || session.getString("id") != id) {
            if (bucketed("lj_v_", maxOf(0, now() - 31 * DAY), now() + 60_000).any { it.getString("id") == id && it.getString("kind") == "flow" }) return JSONObject()
            throw ApiFailure(409, 0)
        }
        val at = session.getLong("at_ms")
        val end = minOf(now(), at + session.getLong("minutes") * 60_000).coerceAtLeast(at)
        upsert("lj_v_", id, at, JSONObject().put("id", id).put("at_ms", at).put("kind", "flow").put("text", session.getString("text"))
            .put("end_ms", end).put("rating", JSONObject.NULL).put("source", JSONObject.NULL))
        files.remove(FOCUS)
        return JSONObject()
    }

    /** Journal content only; categories, rules and goals survive like on the server. */
    fun erase() = synchronized(lock) {
        listOf("lj_a_", "lj_n_", "lj_r_", "lj_v_").forEach { prefix -> files.names(prefix).forEach(files::remove) }
        files.remove(FOCUS)
    }

    /** Leaving local-only mode for good: also forget local categories and goals. */
    fun eraseAll() = synchronized(lock) { erase(); files.remove(WORKSPACE) }

    // ---------------------------------------------------------------- helpers

    private fun read(name: String): JSONObject? {
        val bytes = files.read(name) ?: return null
        return try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
    }

    private fun write(name: String, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        try { files.write(name, bytes) } finally { bytes.fill(0) }
    }

    private fun bad(): Nothing = throw ApiFailure(400, 0)

    private fun query(path: String): Map<String, String> = path.substringAfter('?', "").split('&').filter { '=' in it }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun Map<String, String>.long(key: String): Long = this[key]?.toLongOrNull() ?: bad()
}
