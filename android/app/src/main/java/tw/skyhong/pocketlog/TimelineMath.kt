package tw.skyhong.pocketlog

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Pure timeline arithmetic shared by native views; no Android types so it stays unit-testable. */
object TimelineMath {
    const val DAY_START_HOUR = 4
    const val HISTORY_DAYS = 28L

    data class Interval(val start: Long, val end: Long)
    /** Lane placement for one interval: [lane] within a cluster of [lanes] mutually overlapping columns. */
    data class Placement(val lane: Int, val lanes: Int)

    fun journalDay(now: ZonedDateTime = ZonedDateTime.now()): LocalDate =
        if (now.hour < DAY_START_HOUR) now.toLocalDate().minusDays(1) else now.toLocalDate()

    fun dayStart(day: LocalDate, zone: ZoneId): Long = day.atTime(DAY_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

    fun dayLength(day: LocalDate, zone: ZoneId): Long = dayStart(day.plusDays(1), zone) - dayStart(day, zone)

    /** Position within this actual local journal day, including 23/25-hour DST boundaries. */
    fun dayFraction(day: LocalDate, zone: ZoneId, at: Long): Float =
        ((at - dayStart(day, zone)).toDouble() / dayLength(day, zone)).coerceIn(0.0, 1.0).toFloat()

    /** Journal window [04:00, next 04:00) clipped to now, so future time is never requested. */
    fun window(first: LocalDate, days: Long, zone: ZoneId, now: Long): Pair<Long, Long> =
        dayStart(first, zone) to minOf(dayStart(first.plusDays(days), zone), now)

    fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    fun inHistory(day: LocalDate, today: LocalDate): Boolean = day <= today && day >= today.minusDays(HISTORY_DAYS)

    /**
     * Assigns overlapping intervals to side-by-side lanes. Clusters are maximal runs of transitively
     * overlapping intervals; every member of a cluster shares its lane count so widths stay aligned.
     * Results keep the caller's order.
     */
    fun lanes(intervals: List<Interval>): List<Placement> {
        val order = intervals.indices.sortedWith(compareBy({ intervals[it].start }, { intervals[it].end }))
        val result = arrayOfNulls<Placement>(intervals.size)
        var cluster = mutableListOf<Pair<Int, Int>>()
        var laneEnds = mutableListOf<Long>()
        var clusterEnd = Long.MIN_VALUE
        fun flush() {
            cluster.forEach { (index, lane) -> result[index] = Placement(lane, laneEnds.size) }
            cluster = mutableListOf(); laneEnds = mutableListOf()
        }
        for (index in order) {
            val item = intervals[index]
            if (cluster.isNotEmpty() && item.start >= clusterEnd) flush()
            var lane = laneEnds.indexOfFirst { it <= item.start }
            if (lane < 0) { lane = laneEnds.size; laneEnds += item.end } else laneEnds[lane] = item.end
            cluster += index to lane
            clusterEnd = if (cluster.size == 1) item.end else maxOf(clusterEnd, item.end)
        }
        flush()
        return result.map { requireNotNull(it) }
    }

    /** Longest continuous span after merging touching or overlapping intervals. */
    fun longestMerged(intervals: List<Interval>): Long {
        var longest = 0L; var start = 0L; var end = Long.MIN_VALUE
        for (item in intervals.sortedBy { it.start }) {
            if (item.start > end) { start = item.start; end = item.end } else end = maxOf(end, item.end)
            longest = maxOf(longest, end - start)
        }
        return longest
    }

    /** Union length, so overlapping cards are not double counted in a "tracked" total. */
    fun covered(intervals: List<Interval>): Long {
        var total = 0L; var start = 0L; var end = Long.MIN_VALUE
        for (item in intervals.sortedBy { it.start }) {
            if (item.start > end) { if (end > start) total += end - start; start = item.start; end = item.end } else end = maxOf(end, item.end)
        }
        if (end > start) total += end - start
        return total
    }

    fun duration(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0) + 30_000) / 60_000
        return when {
            ms in 1 until 30_000 -> "不到 1 分"
            minutes < 60 -> "$minutes 分"
            minutes % 60 == 0L -> "${minutes / 60} 小時"
            else -> "${minutes / 60} 小時 ${minutes % 60} 分"
        }
    }

    /** Fraction for progress bars; zero goals render as "no goal" rather than dividing by zero. */
    fun progress(value: Long, goal: Long): Float = if (goal <= 0) 0f else (value.toFloat() / goal).coerceIn(0f, 1f)
}
