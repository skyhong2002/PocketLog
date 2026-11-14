package tw.skyhong.pocketlog

import java.time.Instant
import java.time.ZoneId

/** Only native day windows share snapshots; arbitrary queries keep their exact identity. */
internal data class TimelineCacheScope(val key: String, val from: Long? = null, val to: Long? = null) {
    fun accepts(cachedTo: Long?): Boolean = from == null || (cachedTo != null && cachedTo > from && cachedTo <= requireNotNull(to))

    companion object {
        fun forPath(path: String, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): TimelineCacheScope {
            val exact = TimelineCacheScope(path)
            val match = Regex("^/v1/timeline\\?from_ms=([0-9]+)&to_ms=([0-9]+)$").matchEntire(path) ?: return exact
            val from = match.groupValues[1].toLongOrNull() ?: return exact
            val to = match.groupValues[2].toLongOrNull() ?: return exact
            val day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
            if (from != TimelineMath.dayStart(day, zone)) return exact
            val end = TimelineMath.dayStart(day.plusDays(1), zone)
            if (to <= from || to > end || to > now) return exact
            val today = TimelineMath.journalDay(Instant.ofEpochMilli(now).atZone(zone))
            if (to != end && day != today) return exact
            return TimelineCacheScope("timeline-day:$from:$end", from, to)
        }
    }
}
