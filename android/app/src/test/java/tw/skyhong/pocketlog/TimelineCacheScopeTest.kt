package tw.skyhong.pocketlog

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TimelineCacheScopeTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val day = LocalDate.of(2026, 11, 15)
    private val from = TimelineMath.dayStart(day, zone)
    private fun path(start: Long = from, end: Long) = "/v1/timeline?from_ms=$start&to_ms=$end"

    @Test fun currentDayReopensReuseSnapshotAndRefuseLaterRanges() {
        val earlier = TimelineCacheScope.forPath(path(end = from + 1000), zone, from + 1000)
        val later = TimelineCacheScope.forPath(path(end = from + 2000), zone, from + 2000)
        assertEquals(earlier.key, later.key)
        assertTrue(later.accepts(earlier.to))
        assertFalse(earlier.accepts(later.to))
        assertFalse(earlier.accepts(null))
        assertFalse(earlier.accepts(from))
        assertTrue(earlier.accepts(earlier.to))
    }

    @Test fun dstUsesActualNextFourOClockAcrossShortAndLongDays() {
        val dstZone = ZoneId.of("America/New_York")
        for ((date, hours) in listOf(LocalDate.of(2026, 3, 7) to 23, LocalDate.of(2026, 10, 31) to 25)) {
            val start = TimelineMath.dayStart(date, dstZone)
            val end = TimelineMath.dayStart(date.plusDays(1), dstZone)
            assertEquals(hours * 3_600_000L, end - start)
            val partial = TimelineCacheScope.forPath(path(start, start + 1000), dstZone, start + 1000)
            val complete = TimelineCacheScope.forPath(path(start, end), dstZone, end)
            assertEquals(partial.key, complete.key)
            assertTrue(complete.accepts(partial.to))
        }
    }

    @Test fun arbitraryOverviewAndOldPartialQueriesKeepExactKeys() {
        val now = TimelineMath.dayStart(day.plusDays(1), zone) + 1000
        val queries = listOf("/v1/overview?from_ms=$from&to_ms=$now", path(from + 1, from + 2000),
            path(end = from + 2000), path(end = now), "/v1/timeline?to_ms=$now&from_ms=$from",
            path(end = Long.MAX_VALUE))
        for (query in queries) {
            val scope = TimelineCacheScope.forPath(query, zone, now)
            assertEquals(query, scope.key)
            assertNull(scope.to)
            assertTrue(scope.accepts(null))
        }
    }

    @Test fun nextDayCannotReusePreviousDayKeyAndCredentialDestinationsStayDistinct() {
        val first = TimelineCacheScope.forPath(path(end = from + 1000), zone, from + 1000)
        val next = TimelineMath.dayStart(day.plusDays(1), zone)
        val second = TimelineCacheScope.forPath(path(next, next + 1000), zone, next + 1000)
        assertNotEquals(first.key, second.key)
        val a = Credentials("https://example.com", "a".repeat(64))
        val b = Credentials("https://example.com", "b".repeat(64))
        assertNotEquals(RecordingRules.digest(JournalQueue.destination(a) + first.key), RecordingRules.digest(JournalQueue.destination(b) + first.key))
    }
}
