package tw.skyhong.pocketlog

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/** A user-started upload continues through screen lock and Activity changes. */
class UploadService : Service() {
    companion object {
        const val ALLOW_METERED = "allow_metered"
        private const val CHANNEL = "manual_upload"
        private const val NOTIFICATION_ID = 1847
    }

    private val main = Handler(Looper.getMainLooper())
    private val notifications get() = getSystemService(NotificationManager::class.java)
    @Volatile private var stopped = false
    private var started = false
    private var lastPostedAt = 0L
    private var lastMessage = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        started = true
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "PocketLog 上傳進度", NotificationManager.IMPORTANCE_LOW))
        val initial = SyncEngine.UploadProgress()
        startForeground(NOTIFICATION_ID, notification(initial), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val accepted = SyncEngine.uploadNow(this, intent?.getBooleanExtra(ALLOW_METERED, false) == true, { !stopped }) { progress ->
            main.post {
                if (stopped) return@post
                if (!progress.running) {
                    finish(progress)
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPostedAt >= 1_000 || progress.message != lastMessage) {
                        post(progress)
                        lastMessage = progress.message
                        lastPostedAt = now
                    }
                }
            }
        }
        if (!accepted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val progress = SyncEngine.manualProgress ?: SyncEngine.UploadProgress()
        finish(progress.copy(running = false, message = "Android 已結束背景上傳，待傳資料會保留"))
    }

    override fun onDestroy() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun finish(progress: SyncEngine.UploadProgress) {
        stopped = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        post(progress)
        stopSelf()
    }

    private fun post(progress: SyncEngine.UploadProgress) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notifications.notify(NOTIFICATION_ID, notification(progress))
        }
    }

    private fun notification(progress: SyncEngine.UploadProgress): Notification {
        val title = when {
            progress.running -> "PocketLog 上傳中"
            progress.sent == progress.total -> "PocketLog 上傳完成"
            else -> "PocketLog 上傳已停止"
        }
        val counts = "已送達 ${progress.sent} / ${progress.total} 筆"
        val text = when {
            progress.running && progress.total == 0 -> "正在準備上傳"
            progress.message.isNotBlank() && (progress.running || progress.sent < progress.total) -> "$counts · ${progress.message}"
            progress.total > 0 -> counts
            else -> progress.message
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(progress.running)
            .setAutoCancel(!progress.running)
            .apply { if (progress.running) setProgress(progress.total, progress.sent, progress.total == 0) }
            .build()
    }
}
