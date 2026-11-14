package tw.skyhong.pocketlog

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class UsageCollector(private val context: Context) {
    private val files = PrivateFiles(context)
    fun reset(now: Long = System.currentTimeMillis()) = checkpoint(now, null, 0)
    private fun checkpoint(cursor: Long, app: String?, start: Long) {
        val bytes = JSONObject().put("cursor", cursor).put("app", app).put("start", start).toString().toByteArray()
        try { files.write("usage", bytes) } finally { bytes.fill(0) }
    }
    fun collect(credentials: Credentials) {
        val settings = RecordingSettings(context)
        val now = System.currentTimeMillis()
        if (!settings.enabled) return
        if (!settings.hasUsagePermission()) { reset(now); return }
        val bytes = runCatching { files.read("usage") }.getOrNull() ?: run { reset(now); return }
        val saved = try { runCatching { JSONObject(String(bytes)) }.getOrNull() } finally { bytes.fill(0) }
        if (saved == null) { reset(now); settings.drop(); return }
        val cursor = saved.optLong("cursor", now)
        if (now >= cursor && now - cursor < 15 * 60 * 1000) return
        // Never backfill the time before consent, permission restoration, or beyond raw retention.
        if (cursor > now || now - cursor >= RecordingRules.RAW_TTL) { reset(now); settings.drop(); return }
        val stream = context.getSystemService(UsageStatsManager::class.java).queryEvents(cursor, now)
        val event = UsageEvents.Event()
        val events = mutableListOf<AppEvent>()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            if (events.size >= 20_000) { reset(now); settings.drop(); return }
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> events += AppEvent(event.timeStamp, event.packageName.orEmpty(), true)
                UsageEvents.Event.ACTIVITY_PAUSED -> events += AppEvent(event.timeStamp, event.packageName.orEmpty(), false)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> events += AppEvent(event.timeStamp, "", false)
            }
        }
        val scan = Sessions.scan(events, saved.optString("app").takeIf { it.isNotEmpty() && it != "null" }, saved.optLong("start"), now)
        val queue = JournalQueue(context)
        scan.sessions.filter { !RecordingRules.excluded(it.app, settings.exclusions, context.packageName) && it.end > it.start }
            .chunked(50).forEach { batch ->
                val activities = JSONArray()
                batch.forEach { a -> activities.put(JSONObject().put("id", a.id).put("start_ms", a.start).put("end_ms", a.end).put("app", a.app).put("category", "unsorted")) }
                queue.enqueue(credentials, "/v1/activities", JSONObject().put("activities", activities), now)
            }
        // Queue commits before cursor: crash replays stable IDs instead of losing sessions.
        checkpoint(now, scan.openApp, scan.openStart)
    }
}
