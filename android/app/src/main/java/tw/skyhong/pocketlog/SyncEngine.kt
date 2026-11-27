package tw.skyhong.pocketlog

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.net.NetworkCapabilities
import androidx.work.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SyncEngine {
    val lock = Any()
    private val timelineLock = Any()
    private var timelineEpoch = 0L
    @Volatile private var lastCaptureSchedule = 0L
    data class UploadProgress(val sent: Int = 0, val total: Int = 0, val running: Boolean = true, val message: String = "")
    private val manualExecutor = Executors.newSingleThreadExecutor()
    @Volatile var manualProgress: UploadProgress? = null
        private set

    @Synchronized fun uploadNow(context: Context, allowMetered: Boolean) {
        if (manualProgress?.running == true) return
        val app = context.applicationContext
        manualProgress = UploadProgress()
        manualExecutor.execute {
            var sent = 0
            var total = 0
            val result = runCatching {
                val store = CredentialStore(app)
                val queue = JournalQueue(app)
                val (credentials, remaining) = synchronized(lock) {
                    val saved = requireNotNull(store.load())
                    queue.prune()
                    saved to queue.pendingNames().toMutableSet()
                }
                total = remaining.size
                manualProgress = UploadProgress(sent, total)
                var message = ""
                do {
                    val keepGoing = synchronized(lock) {
                        if (store.load() != credentials) {
                            message = "連線已變更，已停止上傳"
                            return@synchronized false
                        }
                        sync(app, remaining, allowMetered) { entry ->
                            remaining.remove(entry.name)
                            manualProgress = UploadProgress(++sent, total)
                        }
                        message = RecordingSettings(app).status()
                        // Newly recorded items belong to the next upload. Expired/rejected entries
                        // disappear from the queue without increasing the acknowledged count.
                        remaining.retainAll(queue.pendingNames().toSet())
                        remaining.isNotEmpty() && message == PARTIAL_UPLOAD
                    }
                } while (keepGoing)
                if (sent == total && sent > 0) "已上傳 $sent 筆" else message
            }.getOrElse { "無法完成上傳，請再試一次" }
            manualProgress = UploadProgress(sent, total, running = false, message = result)
            schedule(app)
        }
    }

    private const val PARTIAL_UPLOAD = "這批資料尚未傳完"

    fun activeNetwork(context: Context): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)
    }

    fun networkStatus(network: NetworkCapabilities?, wifiOnly: Boolean): String? = when {
        network == null || !network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "等待網路連線"
        wifiOnly && !network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> "等待 Wi‑Fi"
        else -> null
    }

    fun reschedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        for (name in listOf("pocketlog-upload", "pocketlog-captures", "pocketlog-captures-backlog")) manager.cancelUniqueWork(name)
        schedule(context)
    }

    fun schedule(context: Context) {
        val settings = RecordingSettings(context)
        val constraints = Constraints.Builder().setRequiredNetworkType(network(settings)).build()
        // Metadata reads are independent of connectivity; uploads enforce network policy below.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("pocketlog-record", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<JournalWorker>(15, TimeUnit.MINUTES).build())
        if (CredentialStore(context).load() == null) return
        WorkManager.getInstance(context).enqueueUniqueWork("pocketlog-upload", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<JournalWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
        scheduleCaptureUpload(context, settings)
    }
    private fun network(settings: RecordingSettings) = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
    /**
     * By default, avoid waking the device for capture uploads on battery. A large backlog
     * may also use an unmetered network. No periodic wakeup is added.
     */
    private fun scheduleCaptureUpload(context: Context, settings: RecordingSettings) {
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork("pocketlog-captures", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<JournalWorker>().setConstraints(Constraints.Builder().setRequiresCharging(settings.chargeOnly).setRequiredNetworkType(network(settings)).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
        if (settings.chargeOnly && UploadPolicy.backlogNeedsUpload(JournalQueue(context).observationBacklog().second))
            manager.enqueueUniqueWork("pocketlog-captures-backlog", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<JournalWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }
    /** Called after each queued capture (every ten seconds at most); re-arms the charger work every few minutes. */
    fun afterCapture(context: Context) {
        val now = System.currentTimeMillis()
        if (now >= lastCaptureSchedule && now - lastCaptureSchedule < 5 * 60_000L) return
        lastCaptureSchedule = now
        if (CredentialStore(context).load() != null) scheduleCaptureUpload(context, RecordingSettings(context))
    }
    fun charging(context: Context): Boolean = context.getSystemService(BatteryManager::class.java)?.isCharging == true
    fun enqueue(context: Context, path: String, body: JSONObject) = synchronized(lock) {
        val credentials = requireNotNull(CredentialStore(context).load())
        JournalQueue(context).enqueue(credentials, path, body)
        schedule(context)
    }
    internal fun stopForRejectedCredentials(context: Context, credentials: Credentials, failure: Throwable): Boolean = synchronized(lock) {
        if (!ConnectionRejection.isKnownFailure(failure)) return@synchronized false
        RecordingSettings(context).rejectCredentials(credentials)
        JournalQueue(context).clearCollected(); UsageCollector(context).reset()
        true
    }
    fun sync(context: Context): Boolean = synchronized(lock) {
        if (manualProgress?.running == true) return@synchronized false
        sync(context, null, false) {}
    }

    private fun sync(context: Context, manual: Set<String>?, allowMetered: Boolean, acknowledged: (JournalQueue.Entry) -> Unit): Boolean = synchronized(lock) {
        val settings = RecordingSettings(context)
        val credentials = CredentialStore(context).load()
        JournalQueue(context).prune()
        if (manual == null) LocalTimeline(context).prune()
        if (credentials == null) {
            UsageCollector(context).collect(null)
            return@synchronized true
        }
        val network = activeNetwork(context)
        val waitingNetwork = networkStatus(network, settings.wifiOnly && !allowMetered)
        if (waitingNetwork != null) {
            UsageCollector(context).collect(credentials)
            settings.status(waitingNetwork)
            return@synchronized true
        }
        if (System.currentTimeMillis() < settings.nextAttempt) {
            // Server backoff must not pause the existing local UsageStats cadence.
            UsageCollector(context).collect(credentials)
            settings.status("伺服器稍後再試；記錄照常進行")
            return@synchronized false
        }
        // No extra periodic worker: refresh the stop switch on the existing sync cadence.
        val control = try { PocketLogApi(credentials).request("/v1/collection") } catch (failure: Exception) {
            if (stopForRejectedCredentials(context, credentials, failure)) return@synchronized true
            // A transient outage is equivalent to being offline: preserve the last
            // known pause and local consent, and never upload without reading control.
            val transient = failure !is ApiFailure || !RetryPolicy.permanent(failure.status)
            if (transient) UsageCollector(context).collect(credentials)
            settings.nextAttempt = if (transient) System.currentTimeMillis() + (if (failure is ApiFailure) failure.retryMs else 60_000L) else 0L
            settings.status(if (transient) "伺服器暫時連不上；記錄已加密保存，照常進行" else "伺服器拒絕連線；請檢查網址與金鑰，尚未上傳")
            return@synchronized false
        }
        val paused = control.getBoolean("paused")
        settings.confirmCollectionCredentials(credentials)
        if (paused != settings.remotePaused) {
            settings.remotePaused = paused
            UsageCollector(context).reset()
            if (paused) JournalQueue(context).clearCollected()
        }
        // Model processing is now a single server-side switch; drop any legacy per-capture grant.
        settings.forgetLegacyAiGrant()
        settings.applyScreenshotDefault()
        if (!paused) UsageCollector(context).collect(credentials)
        val queue = JournalQueue(context)
        val unmetered = requireNotNull(network).hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val captures = manual != null || !settings.chargeOnly || UploadPolicy.observations(charging(context), unmetered, queue.observationBacklog().second)
        var count = 0
        // Ten-second context needs more than a few requests per run: drain in batches of 32 for at
        // most 45 seconds. The lock is held throughout and capture enqueues wait on it, so keep
        // the run short; each capture schedules another upload and the next worker continues the FIFO.
        val deadline = android.os.SystemClock.elapsedRealtime() + if (manual == null) 45_000L else 3_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val batch = queue.entries(credentials, 32, includeObservations = captures, names = manual)
            if (batch.isEmpty()) break
            for (entry in batch) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) break
                if (manual != null && networkStatus(activeNetwork(context), settings.wifiOnly && !allowMetered) != null) {
                    settings.status("網路已變更，請重新上傳")
                    return@synchronized false
                }
                try {
                    PocketLogApi(credentials).request(entry.data.getString("path"), entry.data.getJSONObject("body"))
                    queue.acknowledge(entry); count++; acknowledged(entry)
                } catch (failure: ApiFailure) {
                    if (failure.status == 423 && entry.data.getString("path") in setOf("/v1/activities", "/v1/observations")) {
                        settings.remotePaused = true
                        queue.clearCollected()
                        UsageCollector(context).reset()
                        settings.status("伺服器已暫停記錄；尚未上傳的記錄已清除")
                        return@synchronized true
                    }
                    if (RetryPolicy.permanent(failure.status)) {
                        queue.reject(entry)
                        settings.status("有資料被伺服器拒絕（HTTP ${failure.status}），請到設定重試")
                        return@synchronized true
                    }
                    settings.nextAttempt = System.currentTimeMillis() + failure.retryMs
                    settings.status("伺服器暫時忙碌，稍後再試")
                    return@synchronized false
                } catch (_: Exception) {
                    settings.nextAttempt = System.currentTimeMillis() + 60_000
                    settings.status("連線失敗，資料仍加密保存在手機")
                    return@synchronized false
                }
            }
        }
        if (manual != null) {
            val more = queue.pendingNames().any { it in manual }
            settings.nextAttempt = 0
            if (count > 0) settings.lastSync = System.currentTimeMillis()
            settings.status(when {
                more && count > 0 -> PARTIAL_UPLOAD
                more -> "還有資料無法上傳，請稍後重試"
                paused -> "伺服器已暫停記錄"
                count > 0 -> "已送出可上傳的資料"
                else -> "沒有需要上傳的資料"
            })
            return@synchronized !more
        }
        settings.nextAttempt = 0
        settings.lastSync = System.currentTimeMillis()
        val waiting = if (captures) 0 else queue.observationBacklog().first
        val sent = if (count > 0) "已上傳 $count 筆" else "沒有需要上傳的資料"
        settings.status(if (waiting > 0) "$sent；$waiting 張截圖等充電時上傳" else sent)
        // Captures waiting for a charger are not a failure: retrying on battery would only wake the phone.
        queue.pending(includeObservations = captures) == 0
    }
    fun clearTimelineCopies(context: Context) = synchronized(lock) {
        synchronized(timelineLock) {
            timelineEpoch++
            LocalTimeline(context).clear()
            val files = PrivateFiles(context)
            files.names("cache_").forEach(files::remove)
        }
    }

    @JvmOverloads
    fun clearLocal(context: Context, afterClear: () -> Unit = {}) = synchronized(lock) {
        synchronized(timelineLock) {
            timelineEpoch++
            CaptureDiagnostics.clear()
            RecordingSettings(context).apply { enabled = false; contextEnabled = false; imagesEnabled = false; replayConsent = false; analysisConsent = false; forgetLegacyAiGrant(); nextAttempt = 0 }
            WorkManager.getInstance(context).cancelAllWork()
            PrivateFiles(context).clear()
            afterClear()
        }
    }

    fun readTimeline(context: Context, path: String): JSONObject {
        val store = CredentialStore(context)
        val files = PrivateFiles(context)
        val scope = TimelineCacheScope.forPath(path)
        val (credentials, epoch) = synchronized(timelineLock) {
            val saved = requireNotNull(store.load())
            saved to timelineEpoch
        }
        val name = "cache_" + RecordingRules.digest(JournalQueue.destination(credentials) + scope.key)
        val timeline = path.startsWith("/v1/timeline?")
        val queue = JournalQueue(context)
        val pendingBefore = if (timeline) queue.pendingJournalEdits(credentials) else emptyList()
        val result = runCatching {
            if (networkStatus(activeNetwork(context), wifiOnly = false) != null) throw IOException("Offline")
            PocketLogApi(credentials).request(path)
        }
        return synchronized(timelineLock) {
            check(epoch == timelineEpoch && store.load() == credentials)
            val now = System.currentTimeMillis()
            val cachedNames = files.names("cache_")
            cachedNames.filter { now - files.modifiedAt(it) >= 30L * RecordingRules.RAW_TTL }.forEach(files::remove)
            val base = result.fold({ fresh ->
                val bytes = JSONObject().put("at", now).put("data", fresh)
                    .apply { scope.to?.let { put("requested_to_ms", it) } }.toString().toByteArray()
                try { files.write(name, bytes) } finally { bytes.fill(0) }
                val names = files.names("cache_").sortedBy(files::modifiedAt)
                var size = names.sumOf(files::size)
                var count = names.size
                for (old in names) {
                    if (count <= 30 && size <= 2 * 1024 * 1024) break
                    if (old == name) continue
                    size -= files.size(old)
                    count--
                    files.remove(old)
                }
                fresh
            }, { failure ->
                // Authentication errors must never silently fall back to a cached journal.
                if (failure is ApiFailure) throw failure
                val cached = try {
                    val bytes = files.read(name) ?: throw failure
                    try { JSONObject(String(bytes)) } finally { bytes.fill(0) }
                } catch (_: Exception) {
                    files.remove(name)
                    throw failure
                }
                val cachedTo = cached.optLong("requested_to_ms").takeIf { cached.has("requested_to_ms") }
                val data = cached.optJSONObject("data")
                if (now - cached.optLong("at") >= 30L * RecordingRules.RAW_TTL || !scope.accepts(cachedTo) || data == null) throw failure
                data.put("offline", true)
            })
            // Preserve local edits spanning the GET, including uploads acknowledged during the read.
            if (!timeline) return@synchronized base
            val pendingAfter = queue.pendingJournalEdits(credentials)
            val edits = (pendingBefore + pendingAfter).distinctBy { it.name }
            if (edits.isEmpty()) base else PendingCardEdits.overlay(PendingReflections.overlay(base, path, edits), path, edits)
        }
    }

}
class JournalWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        if (SyncEngine.sync(applicationContext)) Result.success() else Result.retry()
    } catch (_: Exception) { Result.retry() }
}
