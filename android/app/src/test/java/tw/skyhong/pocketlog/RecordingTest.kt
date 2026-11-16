package tw.skyhong.pocketlog

import org.junit.Assert.*
import org.junit.Test
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

    @Test fun captureCadenceMatchesInfovore() {
        assertEquals(0L, RecordingRules.nextCaptureDelay(1_000_000, 0, appSwitch = false))
        assertEquals(60_000L, RecordingRules.nextCaptureDelay(1_000_000, 1_000_000, appSwitch = false))
        assertEquals(20_000L, RecordingRules.nextCaptureDelay(1_040_000, 1_000_000, appSwitch = false))
        assertEquals(5_000L, RecordingRules.nextCaptureDelay(1_000_000, 1_000_000, appSwitch = true))
        assertEquals(0L, RecordingRules.nextCaptureDelay(1_010_000, 1_000_000, appSwitch = true))
        // A clock moved backwards never stalls capture.
        assertEquals(0L, RecordingRules.nextCaptureDelay(900_000, 1_000_000, appSwitch = true))
    }
    @Test fun imageBoundsFitTheServerContract() {
        assertEquals(720, RecordingRules.MAX_IMAGE_EDGE)
        assertTrue((RecordingRules.MAX_IMAGE_BYTES + 2) / 3 * 4 <= RecordingRules.MAX_IMAGE_BASE64)
        assertTrue(RecordingRules.MAX_IMAGE_BASE64 + 4 * RecordingRules.MAX_TEXT + 1024 <= RecordingRules.MAX_UPLOAD_BODY)
        assertTrue(ContextImage.isMostlyBlack(IntArray(100) { 0xff000000.toInt() }))
        assertFalse(ContextImage.isMostlyBlack(IntArray(100) { if (it < 10) 0xffffffff.toInt() else 0xff000000.toInt() }))
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
}
