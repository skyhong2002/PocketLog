package tw.skyhong.pocketlog

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class UsageCollector(private val context: Context) {
    private val state = UsageCheckpoint(PrivateFiles(context)) { RecordingSettings(context).drop() }
    fun reset(now: Long = System.currentTimeMillis()) = state.save(now, null, 0, RecordingSettings(context).hasUsagePermission())
    fun reconcilePermission() {
        state.reconcile(System.currentTimeMillis(), RecordingSettings(context).hasUsagePermission())
    }
    private fun checkpoint(cursor: Long, app: String?, start: Long) {
        state.save(cursor, app, start, true)
    }
    fun collect(credentials: Credentials?) {
        val settings = RecordingSettings(context)
        val now = System.currentTimeMillis()
        if (!settings.enabled) return
        val saved = state.reconcile(now, settings.hasUsagePermission()) ?: return
        val cursor = saved.optLong("cursor", now)
        if (now >= cursor && now - cursor < 15 * 60 * 1000) return
        // Respect consent/observed permission boundaries and raw retention.
        if (cursor > now || now - cursor >= RecordingRules.RAW_TTL) { reset(now); settings.drop(); return }
        val scan = scan(saved, cursor, now) ?: run { reset(now); settings.drop(); return }
        val queue = JournalQueue(context)
        scan.sessions.filter { !RecordingRules.excluded(it.app, settings.exclusions, context.packageName) && it.end > it.start }
            .chunked(50).forEach { batch ->
                val activities = JSONArray()
                batch.forEach { a -> activities.put(JSONObject().put("id", a.id).put("start_ms", a.start).put("end_ms", a.end).put("app", a.app).put("category", "unsorted")) }
                queue.enqueue(credentials, "/v1/activities", JSONObject().put("activities", activities), now)
                LocalTimeline(context).recordActivities(activities)
            }
        // Queue commits before cursor: crash replays stable IDs instead of losing sessions.
        checkpoint(now, scan.openApp, scan.openStart)
    }

    /** Preview since the last collector checkpoint without advancing it or queuing uploads. */
    fun preview(now: Long = System.currentTimeMillis()): List<Session> {
        val settings = RecordingSettings(context)
        val revision = settings.revision
        if (!settings.enabled || !settings.hasUsagePermission()) return emptyList()
        val saved = state.readGranted() ?: return emptyList()
        val cursor = saved.optLong("cursor", now)
        if (cursor > now || now - cursor >= RecordingRules.RAW_TTL) return emptyList()
        val scan = scan(saved, cursor, now) ?: return emptyList()
        val sessions = scan.withOngoing(now).filter {
            !RecordingRules.excluded(it.app, settings.exclusions, context.packageName)
        }
        return if (settings.revision == revision && settings.hasUsagePermission()) sessions else emptyList()
    }

    private fun scan(saved: JSONObject, cursor: Long, now: Long): SessionScan? {
        val stream = context.getSystemService(UsageStatsManager::class.java).queryEvents(cursor, now) ?: return null
        val event = UsageEvents.Event()
        val events = mutableListOf<AppEvent>()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            if (events.size >= 20_000) return null
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> events += AppEvent(event.timeStamp, event.packageName.orEmpty(), true)
                UsageEvents.Event.ACTIVITY_PAUSED -> events += AppEvent(event.timeStamp, event.packageName.orEmpty(), false)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> events += AppEvent(event.timeStamp, "", false)
            }
        }
        return Sessions.scan(events, saved.optString("app").takeIf { it.isNotEmpty() && it != "null" }, saved.optLong("start"), now)
    }
}
