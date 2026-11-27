package tw.skyhong.pocketlog

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class RecordingTest {
    @Test fun exclusionsOverrideAllowlistAndAlwaysCoverSensitiveApps() {
        for (pkg in listOf("com.example.bankapp", "com.x8bit.bitwarden", "com.azure.authenticator", "tw.skyhong.pocketlog", "com.example.private")) {
            assertFalse(RecordingRules.contextAllowed(pkg, setOf(pkg), setOf("com.example.private"), "tw.skyhong.pocketlog"))
        }
        assertTrue(RecordingRules.contextAllowed("org.example.reader", setOf("org.example.reader"), emptySet(), "tw.skyhong.pocketlog"))
        assertFalse(RecordingRules.contextAllowed("org.example.other", setOf("org.example.reader"), emptySet(), "tw.skyhong.pocketlog"))
    }
    @Test fun sessionEventsSplitWithoutInventingGapsOrOngoingTime() {
        val scan = Sessions.scan(listOf(AppEvent(100, "a.app", true), AppEvent(200, "b.app", true), AppEvent(250, "a.app", false), AppEvent(300, "", false)), null, 0, 1000)
        assertEquals(listOf(Session("a.app", 100, 200), Session("b.app", 200, 300)), scan.sessions)
        assertNull(scan.openApp)
        val open = Sessions.scan(listOf(AppEvent(100, "a.app", true)), null, 0, 200)
        assertTrue(open.sessions.isEmpty())
        val closed = Sessions.scan(listOf(AppEvent(300, "a.app", false)), open.openApp, open.openStart, 400)
        assertEquals(Session("a.app", 100, 300), closed.sessions.single())
        assertEquals(closed.sessions.single().id, Session("a.app", 100, 300).id)
    }
    @Test fun longSessionsAreBoundedAndCheckpointsResumeAtBoundary() {
        val gap = RecordingRules.MAX_SESSION
        val scan = Sessions.scan(emptyList(), "a.app", 100, 100 + gap * 3 + 10)
        assertEquals(3, scan.sessions.size)
        assertTrue(scan.sessions.all { it.end - it.start == gap })
        assertEquals(100 + gap * 3, scan.openStart)
    }
    @Test fun unicodeCapDoesNotSplitSurrogatesAndTextIsDeduplicated() {
        assertEquals("a", RecordingRules.cap("a😀", 2))
        assertEquals("hello world", RecordingRules.normalize(listOf("hello  world", "hello world", "  ")))
    }
    @Test fun retryClassificationAndRetryAfterAreBounded() {
        assertTrue(RetryPolicy.permanent(401)); assertTrue(RetryPolicy.permanent(413))
        for (status in listOf(408, 425, 429, 500, 503)) assertFalse(RetryPolicy.permanent(status))
        assertEquals(86_400_000, RetryPolicy.parseDelay("999999999999"))
        assertEquals(1000, RetryPolicy.parseDelay("-1"))
        assertEquals(60_000, RetryPolicy.parseDelay("invalid"))
        assertEquals(60_000, RetryPolicy.parseDelay("Wed, 21 Oct 2015 07:28:00 GMT", java.time.Instant.parse("2015-10-21T07:27:00Z").toEpochMilli()))
    }
    private class MemoryFiles : JournalFiles {
        val data = mutableMapOf<String, ByteArray>()
        override fun names(prefix: String) = data.keys.filter { it.startsWith(prefix) }.sorted()
        override fun size(name: String) = data[name]?.size?.toLong() ?: 0
        override fun write(name: String, clear: ByteArray) { data[name] = clear.copyOf() }
        override fun read(name: String) = data[name]?.copyOf()
        override fun remove(name: String) { data.remove(name) }
    }
    @Test fun offlineTimelineRetainsTextAfterTheFirstServerAcknowledgesTheScreenshot() {
        val files = MemoryFiles()
        val queue = JournalQueue(files) {}
        val local = LocalTimeline(files)
        val credentials = Credentials("https://example.com", "a".repeat(64))
        val now = System.currentTimeMillis()
        val capture = JSONObject().put("id", "shot").put("app", "org.example.reader").put("at_ms", now)
            .put("text", "讀到這一頁").put("screenshot", "private image")
        queue.enqueue(null, "/v1/observations", capture, now)
        local.recordObservation(capture)
        val first = queue.entries(credentials).single()
        assertEquals("private image", first.data.getJSONObject("body").getString("screenshot"))
        queue.acknowledge(first)
        assertEquals(0, queue.pending(true))
        val saved = local.render(now - 60_000, now + 1).getJSONArray("cards").getJSONObject(0)
        assertEquals("讀到這一頁", saved.getString("summary"))
        assertTrue(saved.getBoolean("local_record"))
        assertFalse(saved.has("screenshot"))
        assertFalse(files.data.values.any { String(it).contains("private image") })
    }

    @Test fun offlineTimelineUsesCachedCardsForCoveredTimeAndShowsNewLocalSessions() {
        val local = LocalTimeline(MemoryFiles())
        val now = System.currentTimeMillis()
        fun activity(id: String, start: Long, end: Long) = JSONObject().put("id", id)
            .put("app", "org.example.reader").put("start_ms", start).put("end_ms", end)
        local.recordActivities(JSONArray().put(activity("old", now - 40, now - 20)).put(activity("new", now - 10, now)))
        val cached = JSONObject().put("cards", JSONArray().put(activity("server", now - 45, now - 15)))
        val cards = local.render(now - 60_000, now + 1, cached).getJSONArray("cards")
        assertEquals(listOf("server", "usage-new"), (0 until cards.length()).map { cards.getJSONObject(it).getString("id") })
    }

    @Test fun queuesAreDestinationBoundQuarantinedAndExpireOnFailure() {
        val files = MemoryFiles(); var dropped = 0
        val queue = JournalQueue(files) { dropped++ }
        val a = Credentials("https://example.com", "a".repeat(64))
        val b = Credentials("https://example.com", "b".repeat(64))
        val now = System.currentTimeMillis()
        queue.enqueue(a, "/v1/checkins", JSONObject().put("id", "stable"), now)
        assertTrue(queue.entries(b).isEmpty())
        val entry = queue.entries(a).single()
        queue.reject(entry)
        assertEquals(0 to 1, queue.counts())
        queue.retryRejected(b); assertEquals(0 to 1, queue.counts())
        queue.retryRejected(a); assertEquals(1 to 0, queue.counts())
        assertEquals("stable", queue.entries(a).single().data.getJSONObject("body").getString("id"))
        queue.reject(queue.entries(a).single())
        queue.prune(now + RecordingRules.RAW_TTL)
        assertEquals(0 to 0, queue.counts()); assertEquals(1, dropped)
    }
    @Test fun corruptEntriesCannotBlockQueueAndRevocationRemovesOnlyContexts() {
        val files = MemoryFiles(); var dropped = 0
        val queue = JournalQueue(files) { dropped++ }
        val a = Credentials("https://example.com", "a".repeat(64))
        val now = System.currentTimeMillis()
        files.data["q_${now}_broken"] = "malformed".toByteArray()
        queue.enqueue(a, "/v1/checkins", JSONObject().put("id", "mood"), now)
        queue.enqueue(a, "/v1/observations", JSONObject().put("id", "context"), now)
        assertEquals(2, queue.entries(a).size); assertEquals(1, dropped)
        queue.clearContexts()
        assertEquals("/v1/checkins", queue.entries(a).single().data.getString("path"))
    }
    @Test fun sharedPauseClearsCapturedQueuesIncludingQuarantineButKeepsExplicitJournal() {
        val files=MemoryFiles();val queue=JournalQueue(files){}
        val c=Credentials("https://example.com","a".repeat(64))
        queue.enqueue(c,"/v1/activities",JSONObject().put("id","capture"))
        queue.reject(queue.entries(c).single())
        queue.enqueue(c,"/v1/observations",JSONObject().put("id","context"))
        queue.enqueue(c,"/v1/checkins",JSONObject().put("id","explicit"))
        queue.clearCollected()
        assertEquals(1 to 0,queue.counts())
        assertEquals("/v1/checkins",queue.entries(c).single().data.getString("path"))
    }

    @Test fun captureCadenceIsTenSecondsWithFiveSecondSwitchGap() {
        assertEquals(0L, RecordingRules.nextCaptureDelay(1_000_000, 0, appSwitch = false))
        assertEquals(10_000L, RecordingRules.nextCaptureDelay(1_000_000, 1_000_000, appSwitch = false))
        assertEquals(4_000L, RecordingRules.nextCaptureDelay(1_006_000, 1_000_000, appSwitch = false))
        assertEquals(5_000L, RecordingRules.nextCaptureDelay(1_000_000, 1_000_000, appSwitch = true))
        assertEquals(0L, RecordingRules.nextCaptureDelay(1_010_000, 1_000_000, appSwitch = true))
        // A clock moved backwards never stalls capture.
        assertEquals(0L, RecordingRules.nextCaptureDelay(900_000, 1_000_000, appSwitch = true))
    }
    @Test fun imageBoundsFitTheServerContract() {
        assertEquals(4096, RecordingRules.MAX_IMAGE_EDGE)
        assertEquals(8_500_000, RecordingRules.MAX_IMAGE_PIXELS)
        assertTrue((RecordingRules.MAX_IMAGE_BYTES + 2) / 3 * 4 <= RecordingRules.MAX_IMAGE_BASE64)
        assertTrue(RecordingRules.MAX_IMAGE_BASE64 + 4 * RecordingRules.MAX_TEXT + 1024 <= RecordingRules.MAX_UPLOAD_BODY)
        assertTrue(ScreenChange.isMostlyBlack(IntArray(100) { 0xff000000.toInt() }))
        assertFalse(ScreenChange.isMostlyBlack(IntArray(100) { if (it < 10) 0xffffffff.toInt() else 0xff000000.toInt() }))
    }
    @Test fun largeContextUploadsFitAndJournalReadsSkipThem() {
        val files = MemoryFiles(); val queue = JournalQueue(files) {}
        val c = Credentials("https://example.com", "a".repeat(64))
        val now = System.currentTimeMillis()
        queue.enqueue(c, "/v1/observations", JSONObject().put("id", "shot").put("jpeg_base64", "A".repeat(RecordingRules.MAX_IMAGE_BASE64)), now)
        queue.enqueue(c, "/v1/reflections", JSONObject().put("id", "note"), now + 1)
        var reads = 0
        val counting = object : JournalFiles by files { override fun read(name: String): ByteArray? { reads++; return files.read(name) } }
        assertEquals("note", JournalQueue(counting) {}.pendingReflections(c).single().data.getJSONObject("body").getString("id"))
        assertEquals(1, reads)
        assertEquals(1, JournalQueue(counting) {}.entries(c, 1).size)
        queue.clearContexts()
        assertEquals(1 to 0, queue.counts())
    }
    @Test fun capturesWaitThreeDaysWhileOtherWritesKeepTheirDay() {
        val files = MemoryFiles(); var dropped = 0
        val queue = JournalQueue(files) { dropped++ }
        val c = Credentials("https://example.com", "a".repeat(64))
        val now = System.currentTimeMillis()
        queue.enqueue(c, "/v1/observations", JSONObject().put("id", "shot"), now)
        queue.enqueue(c, "/v1/activities", JSONObject().put("id", "session"), now)
        queue.prune(now + RecordingRules.RAW_TTL)
        assertEquals("/v1/observations", queue.entries(c).single().data.getString("path"))
        assertEquals(1, dropped)
        queue.prune(now + RecordingRules.CONTEXT_TTL - 1)
        assertEquals(1 to 0, queue.counts())
        queue.prune(now + RecordingRules.CONTEXT_TTL)
        assertEquals(0 to 0, queue.counts()); assertEquals(2, dropped)
        assertEquals(72 * 60 * 60 * 1000L, RecordingRules.CONTEXT_TTL)
    }
    @Test fun capturesWaitingForChargerAreSkippedWithoutBlockingOtherUploads() {
        val files = MemoryFiles(); val queue = JournalQueue(files) {}
        val c = Credentials("https://example.com", "a".repeat(64))
        val now = System.currentTimeMillis()
        queue.enqueue(c, "/v1/observations", JSONObject().put("id", "shot"), now)
        queue.enqueue(c, "/v1/cards", JSONObject().put("id", "edit"), now + 1)
        var reads = 0
        val counting = object : JournalFiles by files { override fun read(name: String): ByteArray? { reads++; return files.read(name) } }
        assertEquals("/v1/cards", JournalQueue(counting) {}.entries(c, 32, includeObservations = false).single().data.getString("path"))
        assertEquals(1, reads)
        assertEquals(2, queue.entries(c, 32, includeObservations = true).size)
        assertEquals(1, queue.pending(includeObservations = false))
        assertEquals(2, queue.pending(includeObservations = true))
        val (count, bytes) = queue.observationBacklog()
        assertEquals(1, count); assertTrue(bytes > 0)
    }
    @Test fun overTheByteCapOldCapturesGoBeforeAppSessions() {
        val files = MemoryFiles(); val queue = JournalQueue(files) {}
        val c = Credentials("https://example.com", "a".repeat(64))
        val now = System.currentTimeMillis()
        queue.enqueue(c, "/v1/activities", JSONObject().put("id", "oldest"), now)
        queue.enqueue(c, "/v1/observations", JSONObject().put("id", "shot"), now + 1)
        // Report the capture as huge so the queue is over its byte cap.
        val shot = files.data.keys.single { it.endsWith("_ctx") }
        val big = object : JournalFiles by files { override fun size(name: String) = if (name == shot) RecordingRules.MAX_QUEUE_BYTES + 1 else files.size(name) }
        JournalQueue(big) {}.prune(now + 2)
        assertEquals("/v1/activities", queue.entries(c).single().data.getString("path"))
    }
    @Test fun suggestedAppsAreInstalledAndNeverSensitiveOrExcluded() {
        val installed = listOf("com.android.chrome", "notion.id", "com.x8bit.bitwarden", "com.example.bankapp", "com.google.android.youtube", "tw.skyhong.pocketlog")
        assertEquals(setOf("com.android.chrome", "notion.id"), RecordingRules.suggested(installed, setOf("com.google.android.youtube"), "tw.skyhong.pocketlog"))
        assertTrue(RecordingRules.suggested(emptyList(), emptySet(), "tw.skyhong.pocketlog").isEmpty())
    }
}
