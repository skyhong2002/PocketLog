package tw.skyhong.pocketlog

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SyncEngine {
    val lock = Any()
    fun schedule(context: Context) {
        val settings = RecordingSettings(context)
        val constraints = Constraints.Builder().setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
        // Metadata reads are independent of connectivity; uploads enforce network policy below.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("pocketlog-record", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<JournalWorker>(15, TimeUnit.MINUTES).build())
        WorkManager.getInstance(context).enqueueUniqueWork("pocketlog-upload", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<JournalWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }
    fun enqueue(context: Context, path: String, body: JSONObject) = synchronized(lock) {
        val credentials = requireNotNull(CredentialStore(context).load())
        JournalQueue(context).enqueue(credentials, path, body)
        schedule(context)
    }
    fun sync(context: Context): Boolean = synchronized(lock) {
        val settings = RecordingSettings(context)
        val credentials = CredentialStore(context).load() ?: return@synchronized true
        JournalQueue(context).prune()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.getNetworkCapabilities(manager.activeNetwork)
        if (network == null || !network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            (settings.wifiOnly && !network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))) {
            UsageCollector(context).collect(credentials)
            settings.status("已加密保留，等待符合網路設定的連線"); return@synchronized true
        }
        if (System.currentTimeMillis() < settings.nextAttempt) return@synchronized false
        // No extra periodic worker: refresh the stop switch on the existing sync cadence.
        val control = try { PocketLogApi(credentials).request("/v1/collection") } catch (failure: ApiFailure) {
            if (failure.status != 401) throw failure
            settings.enabled = false; settings.contextEnabled = false; settings.imagesEnabled = false; settings.replayConsent = false; settings.externalAiConsent = false; settings.aiProviderId = null
            JournalQueue(context).clearCollected(); UsageCollector(context).reset()
            settings.status("金鑰已過期、撤銷或失效，已停止擷取；請重新設定連線")
            return@synchronized true
        }
        val paused = control.getBoolean("paused")
        if (paused != settings.remotePaused) {
            settings.remotePaused = paused
            UsageCollector(context).reset()
            if (paused) JournalQueue(context).clearCollected()
        }
        if (!paused) UsageCollector(context).collect(credentials)
        val queue = JournalQueue(context)
        var count = 0
        // Bound a run to at most eight requests; the next worker continues the FIFO.
        for (entry in queue.entries(credentials).take(8)) {
            try {
                PocketLogApi(credentials).request(entry.data.getString("path"), entry.data.getJSONObject("body"))
                queue.acknowledge(entry); count++
            } catch (failure: ApiFailure) {
                if (failure.status == 423 && entry.data.getString("path") in setOf("/v1/activities", "/v1/observations")) {
                    settings.remotePaused = true
                    queue.clearCollected()
                    UsageCollector(context).reset()
                    settings.status("伺服器已暫停活動收集；待傳活動與情境已清除")
                    return@synchronized true
                }
                if (RetryPolicy.permanent(failure.status)) {
                    queue.reject(entry)
                    settings.status("有資料被拒絕（HTTP ${failure.status}），請檢查連線／金鑰後手動重試")
                    return@synchronized true
                }
                settings.nextAttempt = System.currentTimeMillis() + failure.retryMs
                settings.status("伺服器暫時忙碌，稍後重試")
                return@synchronized false
            } catch (_: Exception) {
                settings.nextAttempt = System.currentTimeMillis() + 60_000
                settings.status("連線失敗，資料仍加密保留")
                return@synchronized false
            }
        }
        settings.nextAttempt = 0
        settings.status("已同步 $count 筆；離線佇列最多保留 24 小時")
        queue.counts().first == 0
    }
    fun clearLocal(context: Context) = synchronized(lock) {
        CaptureDiagnostics.clear()
        RecordingSettings(context).apply { enabled = false; contextEnabled = false; imagesEnabled = false; replayConsent = false; analysisConsent = false; externalAiConsent = false; aiProviderId = null; nextAttempt = 0 }
        WorkManager.getInstance(context).cancelAllWork()
        PrivateFiles(context).clear()
    }
    fun readTimeline(context: Context, path: String): JSONObject = synchronized(lock) {
        val credentials = requireNotNull(CredentialStore(context).load())
        val files = PrivateFiles(context)
        val scope = TimelineCacheScope.forPath(path)
        val name = "cache_" + RecordingRules.digest(JournalQueue.destination(credentials) + scope.key)
        files.names("cache_").forEach { old ->
            val bytes = runCatching { files.read(old) }.getOrNull()
            try { if (bytes == null || System.currentTimeMillis() - JSONObject(String(bytes)).getLong("at") >= 30L * RecordingRules.RAW_TTL) files.remove(old) }
            catch (_: Exception) { files.remove(old) } finally { bytes?.fill(0) }
        }
        while (files.names("cache_").size > 29 || files.names("cache_").sumOf { files.size(it) } > 2 * 1024 * 1024) files.remove(files.names("cache_").first())
        val base = try {
            val data = PocketLogApi(credentials).request(path)
            val bytes = JSONObject().put("at", System.currentTimeMillis()).put("data", data)
                .apply { scope.to?.let { put("requested_to_ms", it) } }.toString().toByteArray()
            try { files.write(name, bytes) } finally { bytes.fill(0) }
            data
        } catch (failure: Exception) {
            // Authentication errors must never silently fall back to a cached journal.
            if (failure is ApiFailure) throw failure
            val bytes = files.read(name) ?: throw failure
            try {
                val cached = JSONObject(String(bytes))
                val cachedTo = if (cached.has("requested_to_ms")) cached.getLong("requested_to_ms") else null
                if (!scope.accepts(cachedTo)) throw failure
                cached.getJSONObject("data").put("offline", true)
            } finally { bytes.fill(0) }
        }
        val pending = JournalQueue(context).pendingJournalEdits(credentials)
        PendingCardEdits.overlay(PendingReflections.overlay(base, path, pending), path, pending)
    }
}
class JournalWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        if (SyncEngine.sync(applicationContext)) Result.success() else Result.retry()
    } catch (_: Exception) { Result.retry() }
}
