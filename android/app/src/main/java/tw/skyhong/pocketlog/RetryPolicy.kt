package tw.skyhong.pocketlog

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object RetryPolicy {
    fun permanent(status: Int) = status in 400..499 && status !in setOf(408, 425, 429)
    fun parseDelay(value: String, now: Long = System.currentTimeMillis()): Long {
        val seconds = value.trim().toLongOrNull()
        val delay = if (seconds != null) seconds.coerceIn(1, 86400) * 1000 else
            runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now }.getOrDefault(60_000)
        return delay.coerceIn(1000, 86_400_000)
    }
}
