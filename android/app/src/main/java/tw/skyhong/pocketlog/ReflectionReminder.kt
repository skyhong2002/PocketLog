package tw.skyhong.pocketlog

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Optional daily reflection reminder at a time the owner picks. Off by default. The notification
 * carries no journal content and works in both server and local-only modes. Scheduling is
 * inexact WorkManager work (no exact-alarm permission), so Android may delay it under Doze.
 */
class ReflectionReminder(private val context: Context) {
    private val prefs = context.getSharedPreferences("reminder", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        private set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    /** Minutes after local midnight, 0..1439; default 21:30. */
    var minuteOfDay: Int
        get() = prefs.getInt("minute", 21 * 60 + 30).coerceIn(0, 1439)
        private set(value) { check(prefs.edit().putInt("minute", value.coerceIn(0, 1439)).commit()) }

    fun notificationsAllowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun enable(minute: Int) { minuteOfDay = minute; enabled = true; schedule() }
    fun disable() { enabled = false; WorkManager.getInstance(context).cancelUniqueWork(WORK) }

    /** Replaces any pending reminder with one at the next occurrence of the chosen time. */
    fun schedule(now: ZonedDateTime = ZonedDateTime.now()) {
        if (!enabled) return
        // Skip an occurrence less than a minute away so a reminder that just fired re-arms for tomorrow.
        val delay = delayUntil(now.plusMinutes(1), minuteOfDay).plusMinutes(1)
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build())
    }

    internal fun show() {
        if (!enabled || !notificationsAllowed()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "每日反思提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "在你選的時間提醒寫下每日反思；通知不含日誌內容。"
        })
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DAILY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(NOTIFICATION, android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_daily).setContentTitle("寫下今天的反思")
            .setContentText("花一分鐘記下今天值得留下的事。").setContentIntent(open).setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_REMINDER).build())
    }

    companion object {
        private const val WORK = "pocketlog-reflection-reminder"
        private const val CHANNEL = "reflection-reminder"
        private const val NOTIFICATION = 4001

        /** Time until the next local occurrence of [minute]; always in the future, DST-aware. */
        fun delayUntil(now: ZonedDateTime, minute: Int): Duration {
            val at = LocalTime.of(minute / 60, minute % 60)
            var next = now.with(at)
            if (!next.isAfter(now)) next = now.plusDays(1).with(at)
            return Duration.between(now, next)
        }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val reminder = ReflectionReminder(applicationContext)
        runCatching { reminder.show() }
        // Re-arm for tomorrow even if posting failed; a disabled reminder schedules nothing.
        reminder.schedule()
        return Result.success()
    }
}
