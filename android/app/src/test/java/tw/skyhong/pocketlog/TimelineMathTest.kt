package tw.skyhong.pocketlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.skyhong.pocketlog.TimelineMath.Interval
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TimelineMathTest {
    private val taipei = ZoneId.of("Asia/Taipei")

    @Test fun categoryIntervalsPreferScreenEvidenceAndManualCorrections() {
        val intervals = listOf(
            TimelineMath.CategoryInterval(0, 50, "unsorted", 0, "usage"),
            TimelineMath.CategoryInterval(10, 40, "work", 2, "screen"),
            TimelineMath.CategoryInterval(20, 30, "learning", 3, "manual"),
            TimelineMath.CategoryInterval(60, 70, "work", 2, "screen-only"),
        )
        assertEquals(
            listOf(
                Triple(0L, 10L, "unsorted"), Triple(10L, 20L, "work"),
                Triple(20L, 30L, "learning"), Triple(30L, 40L, "work"),
                Triple(40L, 50L, "unsorted"), Triple(60L, 70L, "work"),
            ),
            TimelineMath.categoryIntervals(intervals).map { Triple(it.start, it.end, it.category) },
        )
    }

    @Test fun dayBeginsAtFourAndWindowIsClippedToNow() {
        assertEquals(LocalDate.of(2026, 11, 14), TimelineMath.journalDay(ZonedDateTime.of(2026, 11, 15, 3, 59, 0, 0, taipei)))
        assertEquals(LocalDate.of(2026, 11, 15), TimelineMath.journalDay(ZonedDateTime.of(2026, 11, 15, 4, 0, 0, 0, taipei)))
        val start = ZonedDateTime.of(2026, 11, 15, 4, 0, 0, 0, taipei).toInstant().toEpochMilli()
        val now = start + 3_600_000
        assertEquals(start to now, TimelineMath.window(LocalDate.of(2026, 11, 15), 1, taipei, now))
        val full = TimelineMath.window(LocalDate.of(2026, 11, 14), 1, taipei, now)
        assertEquals(start, full.second)
        assertEquals(86_400_000L, full.second - full.first)
    }

    @Test fun weekStartsMondayAndHistoryCoversThePermanentJournal() {
        assertEquals(LocalDate.of(2026, 11, 9), TimelineMath.weekStart(LocalDate.of(2026, 11, 15)))
        assertEquals(LocalDate.of(2026, 11, 9), TimelineMath.weekStart(LocalDate.of(2026, 11, 9)))
        val today = LocalDate.of(2026, 11, 15)
        assertTrue(TimelineMath.inHistory(today.minusDays(29), today))
        assertTrue(TimelineMath.inHistory(today.minusDays(TimelineMath.HISTORY_DAYS), today))
        assertFalse(TimelineMath.inHistory(today.minusDays(TimelineMath.HISTORY_DAYS + 1), today))
        assertFalse(TimelineMath.inHistory(today.plusDays(1), today))
    }

    @Test fun denseHoursExpandAndEveryCardKeepsSpace() {
        val hour = 3_600_000L
        val positions = TimelineMath.readableAxis(
            listOf(0, hour, hour * 2, hour * 3),
            listOf(Interval(100, 220) to 60, Interval(200, 250) to 68, Interval(hour * 2 + 400, hour * 2 + 500) to 60),
            96, 24, 6,
        )
        assertEquals(listOf(24, 170, 266, 362), positions.ticks)
        assertEquals(listOf(30, 96, 272), positions.blocks)
    }

    @Test fun onlyShortNearbyAppRecordsAreGroupedInsideOneWindow() {
        fun record(minute: Int, group: Boolean = true) =
            TimelineMath.TimelineEntry(Interval(minute * 60_000L, minute * 60_000L + 20_000), group)
        val entries = listOf(record(1), record(2), record(6), record(7, false), record(8), record(9), record(10))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3), listOf(4, 5), listOf(6)),
            TimelineMath.displayGroups(entries, 0))
    }

    @Test fun weeklyDurationClipsDateBoundariesAndMergesOverlap() {
        val day = LocalDate.of(2026, 11, 16)
        val start = TimelineMath.dayStart(day, taipei)
        val end = TimelineMath.dayStart(day.plusDays(1), taipei)
        val items = listOf(
            Interval(start - 60_000, start + 30_000),
            Interval(start + 10_000, start + 60_000),
            Interval(end - 30_000, end + 60_000),
            Interval(end + 80_000, end + 90_000),
        )
        assertEquals(90_000L, TimelineMath.coveredInDay(day, taipei, items))
    }

    @Test fun totalsMergeOverlapsAndFormatDurations() {
        val items = listOf(Interval(0, 60_000), Interval(30_000, 120_000), Interval(600_000, 660_000))
        assertEquals(180_000L, TimelineMath.covered(items))
        assertEquals(120_000L, TimelineMath.longestMerged(items))
        assertEquals("不到 1 分", TimelineMath.duration(0))
        assertEquals("不到 1 分", TimelineMath.duration(10_000))
        assertEquals("54 分", TimelineMath.duration(54 * 60_000L))
        assertEquals("2 小時", TimelineMath.duration(120 * 60_000L))
        assertEquals("1 小時 44 分", TimelineMath.duration(104 * 60_000L))
        assertEquals(0f, TimelineMath.progress(10, 0))
        assertEquals(1f, TimelineMath.progress(10, 5))
    }
    @Test fun dstDaysRetainTheirFullLocalDayAndLastHour() {
        val ny = ZoneId.of("America/New_York")
        for ((day, hours) in listOf(LocalDate.of(2026, 3, 7) to 23, LocalDate.of(2026, 10, 31) to 25)) {
            assertEquals(hours * 3_600_000L, TimelineMath.dayLength(day, ny))
            val end = TimelineMath.dayStart(day.plusDays(1), ny)
            assertEquals(1f, TimelineMath.dayFraction(day, ny, end))
            assertTrue(TimelineMath.dayFraction(day, ny, end - 3_600_000) < 1f)
            assertTrue(TimelineMath.dayFraction(day, ny, end - 3_600_000) > .9f)
            assertEquals(0f, TimelineMath.dayFraction(day, ny, TimelineMath.dayStart(day, ny) - 1))
        }
        assertEquals(86_400_000L, TimelineMath.dayLength(LocalDate.of(2026, 10, 31), taipei))
    }

    @Test fun newYorkSpringWeekUsesProgressForItsTwentyThreeHourJournalDay() {
        assertTransitionWeekProgress(LocalDate.of(2026, 3, 7), ZoneId.of("America/New_York"), 23 * 3_600_000L)
    }

    @Test fun newYorkFallWeekUsesProgressForItsTwentyFiveHourJournalDay() {
        assertTransitionWeekProgress(LocalDate.of(2026, 10, 31), ZoneId.of("America/New_York"), 25 * 3_600_000L)
    }

    @Test fun lordHoweSpringWeekUsesProgressForItsHalfHourTransition() {
        assertTransitionWeekProgress(LocalDate.of(2026, 10, 3), ZoneId.of("Australia/Lord_Howe"), 23 * 3_600_000L + 30 * 60_000L)
    }

    @Test fun taipeiKeepsClockAxisEvenDuringOtherZonesTransitionWeeks() {
        for (day in listOf(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 31))) {
            val first = TimelineMath.weekStart(day)
            assertFalse(TimelineMath.weekUsesProgressAxis(first, taipei))
            for (offset in 0L..6L) assertEquals(86_400_000L, TimelineMath.dayLength(first.plusDays(offset), taipei))
        }
    }

    private fun assertTransitionWeekProgress(day: LocalDate, zone: ZoneId, length: Long) {
        val first = TimelineMath.weekStart(day)
        assertEquals(length, TimelineMath.dayLength(day, zone))
        assertTrue(TimelineMath.weekUsesProgressAxis(first, zone))
        assertFalse(TimelineMath.weekUsesProgressAxis(first.minusWeeks(1), zone))
        assertFalse(TimelineMath.weekUsesProgressAxis(first.plusWeeks(1), zone))
        // Progress ticks apply to every column, with geometry based on each day's actual duration.
        for (offset in 0L..6L) {
            val date = first.plusDays(offset)
            val start = TimelineMath.dayStart(date, zone)
            val actualLength = TimelineMath.dayLength(date, zone)
            for (quarter in 0..4) {
                assertEquals(quarter / 4f, TimelineMath.dayFraction(date, zone, start + actualLength * quarter / 4), .00001f)
            }
        }
    }

    @Test fun zhTwDateLabels() {
        assertEquals("11月16日（週一）", TimelineMath.dayLabel(java.time.LocalDate.of(2026, 11, 16)))
        assertEquals("11月16日 – 22日", TimelineMath.weekLabel(java.time.LocalDate.of(2026, 11, 16)))
        assertEquals("11月30日 – 12月6日", TimelineMath.weekLabel(java.time.LocalDate.of(2026, 11, 30)))
        assertEquals("週一", TimelineMath.weekdayShort(java.time.LocalDate.of(2026, 11, 16)))
        assertEquals("4.1 時", TimelineMath.hours(4 * 3_600_000L + 6 * 60_000L))
        assertEquals("0.0 時", TimelineMath.hours(0))
    }
    @Test fun shortRangesNeverShowAFakeMinute() {
        assertEquals("不到 1 分", TimelineMath.duration(59_999))
        assertEquals("1 分", TimelineMath.duration(60_000))
        assertEquals("01:26 · 不到 1 分", TimelineMath.rangeLabel("01:26", "01:26", 0))
        // 40 s that crosses a minute boundary is still under a minute and shows only the start.
        assertEquals("01:26 · 不到 1 分", TimelineMath.rangeLabel("01:26", "01:27", 40_000))
        assertEquals("16:00–16:14 · 14 分", TimelineMath.rangeLabel("16:00", "16:14", 14 * 60_000L))
    }
    @Test fun scaledTimelineKeepsGapsAndProportionalCardHeights() {
        val day = java.time.LocalDate.of(2026, 11, 19)
        val zone = java.time.ZoneId.of("Asia/Taipei")
        val start = TimelineMath.dayStart(day, zone)
        fun position(hours: Int, minutes: Int = 0) =
            TimelineMath.timeOffset(start, start + hours * 3_600_000L + minutes * 60_000L, 96)
        assertEquals(2304, position(24))
        assertEquals(1920, position(20)) // Midnight stays twenty hours after 04:00.
        assertEquals(48, position(5, 30) - position(5))
        assertEquals(24, position(7, 15) - position(7))
        assertEquals(144, position(7) - position(5, 30))
    }
}
