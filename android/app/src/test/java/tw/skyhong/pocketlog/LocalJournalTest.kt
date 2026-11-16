package tw.skyhong.pocketlog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class LocalJournalTest {
    private class MemoryFiles : JournalFiles {
        val data = sortedMapOf<String, ByteArray>()
        override fun names(prefix: String) = data.keys.filter { it.startsWith(prefix) }.sorted()
        override fun size(name: String) = data[name]?.size?.toLong() ?: 0
        override fun write(name: String, clear: ByteArray) { require(clear.size <= 600 * 1024) { "file over PrivateFiles bound" }; data[name] = clear.copyOf() }
        override fun read(name: String) = data[name]?.copyOf()
        override fun remove(name: String) { data.remove(name) }
    }

    private val day = LocalJournal.DAY
    private val t0 = 20_000 * day + 3_600_000 // Fixed synthetic clock, 01:00 UTC.
    private var clock = t0 + 12 * 3_600_000
    private val files = MemoryFiles()
    private val journal = LocalJournal(files) { clock }

    private fun status(block: () -> Unit): Int = try { block(); 0 } catch (e: ApiFailure) { e.status }
    private fun timeline(from: Long = t0 - 2 * 3_600_000, to: Long = t0 + 10 * 3_600_000) = journal.request("/v1/timeline?from_ms=$from&to_ms=$to")
    private fun cards(data: JSONObject) = data.getJSONArray("cards").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun sessionsAreStoredOnceClippedAndOrderedLikeTheServer() {
        val a = Session("org.example.reader", t0, t0 + 600_000)
        val b = Session("org.example.editor", t0 - 300_000, t0 + 60_000) // Crosses the UTC day start.
        journal.addSessions(listOf(a, b, a, Session("org.example.zero", t0, t0)))
        journal.addSessions(listOf(a)) // Collector crash replay.
        val data = timeline(from = t0, to = t0 + 3_600_000)
        val list = cards(data)
        assertEquals(listOf(b.id, a.id), list.map { it.getString("id") })
        assertEquals(t0, list[0].getLong("start_ms")) // Clipped to the window.
        assertEquals("app_only", list[0].getString("source"))
        assertEquals("org.example.editor", list[0].getString("title"))
        assertEquals("unsorted", list[0].getString("category"))
        assertFalse(data.getBoolean("truncated"))
        assertTrue(data.getBoolean("local"))
        assertEquals(2, data.getJSONArray("activities").length())
        assertEquals(0, data.getJSONArray("checkins").length())
    }

    @Test fun rulesAndManualEditsClassifyCardsAndOverviewTotals() {
        val reader = Session("org.example.reader", t0, t0 + 1_200_000)
        val video = Session("org.example.video", t0 + 1_200_000, t0 + 1_800_000)
        journal.addSessions(listOf(reader, video))
        val w = journal.request("/v1/workspace")
        assertEquals(0, w.getInt("onboarding_version"))
        w.put("rules", JSONArray().put(JSONObject().put("id", "r1").put("category", "learning").put("app", "org.example.reader"))
            .put(JSONObject().put("id", "r2").put("category", "entertainment").put("app", "org.example.video")))
        val saved = journal.request("/v1/workspace", w, "PUT")
        assertEquals(1, saved.getInt("revision"))
        assertEquals(409, status { journal.request("/v1/workspace", w, "PUT") }) // Stale revision.
        assertEquals(listOf("learning", "entertainment"), cards(timeline()).map { it.getString("category") })
        // A manual edit wins over the matching rule.
        journal.request("/v1/cards", JSONObject().put("id", video.id).put("title", "Recorded lecture").put("summary", "Synthetic").put("category", "learning"))
        val edited = cards(timeline())[1]
        assertEquals(listOf("Recorded lecture", "learning", "user"), listOf(edited.getString("title"), edited.getString("category"), edited.getString("source")))
        val overview = journal.request("/v1/overview?from_ms=${t0 - day}&to_ms=${t0 + day / 2}")
        assertEquals("learning", overview.getJSONArray("categories").getJSONObject(0).getString("category"))
        assertEquals(1_800_000, overview.getJSONArray("categories").getJSONObject(0).getLong("duration_ms"))
        assertEquals("org.example.reader", overview.getJSONArray("apps").getJSONObject(0).getString("app"))
        val preview = journal.request("/v1/workspace/preview", JSONObject().put("workspace", saved)
            .put("sample", JSONObject().put("app", "org.example.reader").put("title", "x").put("category", "unsorted")))
        assertEquals("r1", preview.getString("matched_rule_id"))
        assertEquals(400, status { journal.request("/v1/cards", JSONObject().put("id", video.id).put("title", " ").put("summary", "").put("category", "work")) })
        assertEquals(400, status { journal.request("/v1/cards", JSONObject().put("id", video.id).put("title", "t").put("summary", "").put("category", "custom_missing")) })
        for (broken in listOf(
            JSONObject(saved.toString()).put("categories", JSONArray()),
            JSONObject(saved.toString()).put("rules", JSONArray().put(JSONObject().put("id", "empty").put("category", "work"))),
            JSONObject(saved.toString()).put("rules", JSONArray().put(JSONObject().put("id", "long").put("category", "work").put("title_contains", "x".repeat(81)))),
        )) assertEquals(400, status { journal.request("/v1/workspace", broken, "PUT") })
    }

    @Test fun reflectionsReplaceByIdDeleteWhenBlankAndStayInTheirDay() {
        journal.request("/v1/reflections", JSONObject().put("id", "reflection-a").put("at_ms", t0 + 1_000).put("text", "first"))
        journal.request("/v1/reflections", JSONObject().put("id", "reflection-a").put("at_ms", t0 + 2_000).put("text", "second"))
        journal.request("/v1/reflections", JSONObject().put("id", "reflection-b").put("at_ms", t0 - 3 * day).put("text", "later"))
        val shown = timeline().getJSONArray("reflections")
        assertEquals(1, shown.length())
        assertEquals("second", shown.getJSONObject(0).getString("text"))
        journal.request("/v1/reflections", JSONObject().put("id", "reflection-a").put("at_ms", t0 + 2_000).put("text", "  "))
        assertEquals(0, timeline().getJSONArray("reflections").length())
        assertEquals(400, status { journal.request("/v1/reflections", JSONObject().put("id", "x").put("at_ms", clock + day).put("text", "future")) })
    }

    @Test fun focusTimerFinishesOnceIntoAFlowRecordAndReviewsPersist() {
        val start = JSONObject().put("id", "focus-1").put("at_ms", clock).put("minutes", 25).put("text", "Write notes")
        journal.request("/v1/focus", start)
        assertEquals(409, status { journal.request("/v1/focus", JSONObject(start.toString()).put("id", "focus-2")) })
        clock += 10 * 60_000
        journal.request("/v1/focus/finish", JSONObject().put("id", "focus-1"))
        journal.request("/v1/focus/finish", JSONObject().put("id", "focus-1")) // Idempotent retry.
        assertTrue(journal.request("/v1/focus").isNull("session"))
        assertEquals(409, status { journal.request("/v1/focus/finish", JSONObject().put("id", "never-started")) })
        journal.request("/v1/reviews", JSONObject().put("id", "review-x").put("at_ms", t0).put("kind", "review").put("text", "")
            .put("end_ms", JSONObject.NULL).put("rating", "focused").put("source", JSONObject.NULL))
        val records = journal.request("/v1/reviews?from_ms=${t0 - 1}&to_ms=$clock").getJSONArray("records")
        assertEquals(listOf("review", "flow"), (0 until records.length()).map { records.getJSONObject(it).getString("kind") })
        val flow = records.getJSONObject(1)
        assertEquals(flow.getLong("at_ms") + 10 * 60_000, flow.getLong("end_ms"))
        assertEquals(400, status { journal.request("/v1/reviews", JSONObject().put("id", "r").put("at_ms", t0).put("kind", "review").put("text", "").put("rating", "great")) })
    }

    @Test fun serverOnlyRoutesAreRefusedAndErasureKeepsCategories() {
        for (path in listOf("/v1/ai", "/v1/ask", "/v1/conversations", "/v1/agent-sessions", "/v1/replay?from_ms=0&to_ms=1", "/v1/storage", "/v1/devices", "/v1/briefs/schedule")) {
            try { journal.request(path); fail(path) } catch (_: LocalOnlyUnsupported) {}
        }
        assertFalse(journal.request("/v1/collection").getBoolean("paused"))
        journal.addSessions(listOf(Session("org.example.reader", t0, t0 + 60_000)))
        val w = journal.request("/v1/workspace").put("focus_minutes", 120)
        journal.request("/v1/workspace", w, "PUT")
        journal.request("/v1/journal", method = "DELETE")
        assertEquals(0, cards(timeline()).size)
        assertEquals(120, journal.request("/v1/workspace").getInt("focus_minutes"))
        journal.eraseAll()
        assertEquals(270, journal.request("/v1/workspace").getInt("focus_minutes"))
        assertEquals(400, status { journal.request("/v1/timeline?from_ms=$t0&to_ms=${t0 + 27 * 3_600_000}") })
    }

    @Test fun storageStaysBoundedPerDayAndAcrossDays() {
        // A maximal day stays within one encrypted file and further sessions are dropped.
        val busy = (0 until LocalJournal.MAX_ACTIVITIES_PER_DAY + 50).map { Session("org.example.app${it % 7}.long.package.name", t0 + it * 1_000L, t0 + it * 1_000L + 900) }
        // Very long package names hit the byte bound before the count bound, without failing the write.
        journal.addSessions((0 until 3_000).map { Session("org.example." + "x".repeat(180) + it, t0 - day + it * 1_000L, t0 - day + it * 1_000L + 900) })
        assertTrue(files.size(files.names("lj_a_").single()) <= LocalJournal.MAX_DAY_BYTES)
        journal.addSessions(busy)
        val stored = JSONObject(String(files.read(files.names("lj_a_").last())!!)).getJSONArray("a").length()
        assertEquals(LocalJournal.MAX_ACTIVITIES_PER_DAY, stored)
        assertTrue(timeline(from = t0, to = t0 + 6 * 3_600_000).getBoolean("truncated"))
        // Oldest day files are pruned beyond the retention bound.
        journal.addSessions((1..LocalJournal.MAX_DAY_FILES + 5).map { Session("org.example.reader", t0 + it * day, t0 + it * day + 1_000) })
        assertEquals(LocalJournal.MAX_DAY_FILES, files.names("lj_a_").size)
        assertEquals(0, cards(timeline(from = t0, to = t0 + 3_600_000)).size)
    }

    @Test fun reminderDelayTargetsTheNextLocalOccurrenceAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val before = ZonedDateTime.of(2026, 3, 7, 20, 0, 0, 0, zone)
        assertEquals(Duration.ofMinutes(90), ReflectionReminder.delayUntil(before, 21 * 60 + 30))
        val after = ZonedDateTime.of(2026, 3, 7, 22, 0, 0, 0, zone)
        // 21:30 tomorrow is only 22.5 h away because clocks spring forward overnight.
        assertEquals(Duration.ofMinutes(22 * 60 + 30), ReflectionReminder.delayUntil(after, 21 * 60 + 30))
        assertEquals(Duration.ofDays(1), ReflectionReminder.delayUntil(ZonedDateTime.of(2026, 6, 1, 21, 30, 0, 0, zone), 21 * 60 + 30))
    }
}
