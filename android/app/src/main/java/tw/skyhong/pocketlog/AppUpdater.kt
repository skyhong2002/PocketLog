package tw.skyhong.pocketlog

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Thrown with a short user-facing message; never carries response bodies or URLs. */
class UpdateFailure(message: String) : Exception(message)

/**
 * GitHub release updater for release builds. Contacts only GitHub over HTTPS, sends no journal data
 * and no credentials, verifies the APK's SHA-256 before handing it to PackageInstaller.
 */
class AppUpdater(private val context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    var autoCheck: Boolean
        get() = prefs.getBoolean("auto", true)
        set(value) { prefs.edit().putBoolean("auto", value).apply() }
    var lastCheck: Long
        get() = prefs.getLong("last_check", 0)
        set(value) { prefs.edit().putLong("last_check", value).apply() }
    var skippedCode: Int
        get() = prefs.getInt("skipped", 0)
        set(value) { prefs.edit().putInt("skipped", value).apply() }
    var lastResult: String
        get() = prefs.getString("result", "").orEmpty()
        set(value) { prefs.edit().putString("result", value).apply() }

    /** The newest release offered to this build, or null when up to date. */
    fun check(): Updates.Release? {
        val json = try { String(get(Updates.latestUrl(BuildConfig.UPDATE_REPOSITORY), Updates.MAX_RELEASE_JSON.toLong(), json = true), Charsets.UTF_8) }
        catch (e: UpdateFailure) { if (e.message == NOT_FOUND) { lastCheck = System.currentTimeMillis(); lastResult = "目前沒有可用的版本"; return null }; throw e }
        lastCheck = System.currentTimeMillis()
        val release = Updates.parse(JSONObject(json), BuildConfig.VERSION_CODE)
        lastResult = if (release == null) "已是最新版" else "有新版本 ${release.version}"
        return release
    }

    /** Downloads into the app cache with progress, verifies SHA-256 and returns the file. */
    fun download(release: Updates.Release, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val expected = release.sha256 ?: release.sha256Asset?.let { Updates.sidecar(String(get(it.url, 4096), Charsets.UTF_8), release.apk.name) }
            ?: throw UpdateFailure("這個版本沒有提供檢查碼，為了安全不安裝")
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val file = File(folder, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            open(release.apk.url) { input, total ->
                if (total > Updates.MAX_APK_BYTES) throw UpdateFailure("檔案太大，已停止下載")
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        if (cancelled()) throw UpdateFailure(CANCELLED)
                        val n = input.read(buffer); if (n < 0) break
                        done += n
                        if (done > Updates.MAX_APK_BYTES) throw UpdateFailure("檔案太大，已停止下載")
                        digest.update(buffer, 0, n); out.write(buffer, 0, n); progress(done, total)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) throw UpdateFailure("檢查碼不符，檔案可能損壞或被修改，已刪除")
            return file
        } catch (e: Exception) { file.delete(); throw e }
    }

    /** Hands the verified APK to PackageInstaller; the result arrives at [UpdateStatusReceiver]. */
    fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input -> session.openWrite("PocketLog.apk", 0, file.length()).use { out -> input.copyTo(out); session.fsync(out) } }
                val intent = Intent(context, UpdateStatusReceiver::class.java).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) { runCatching { installer.abandonSession(id) }; throw e }
        finally { file.delete() }
    }

    fun cleanup() { File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() } }

    private fun get(url: String, limit: Long, json: Boolean = false): ByteArray = open(url, json) { input, total ->
        if (total > limit) throw UpdateFailure("回應太大")
        val bytes = input.readNBytes((limit + 1).toInt())
        if (bytes.size > limit) throw UpdateFailure("回應太大")
        bytes
    }

    /** HTTPS GET that follows at most five redirects, each to a GitHub host only. No credentials are sent. */
    private fun <T> open(start: String, json: Boolean = false, read: (InputStream, Long) -> T): T {
        var url = start
        repeat(6) {
            if (!Updates.allowed(url)) throw UpdateFailure("下載位址不是 GitHub，已停止")
            val connection = URL(url).openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000; connection.useCaches = false
                connection.setRequestProperty("User-Agent", "PocketLog-Android")
                connection.setRequestProperty("Accept", if (json) "application/vnd.github+json" else "application/octet-stream")
                val status = try { connection.responseCode } catch (e: IOException) { throw UpdateFailure("沒有網路，請稍後再試") }
                when {
                    status in 300..399 -> { url = URI(url).resolve(connection.getHeaderField("Location") ?: throw UpdateFailure("下載失敗")).toString(); return@repeat }
                    status == 403 || status == 429 -> throw UpdateFailure("GitHub 暫時限制查詢次數，請稍後再試")
                    status == 404 -> throw UpdateFailure(NOT_FOUND)
                    status !in 200..299 -> throw UpdateFailure("GitHub 暫時無法使用（HTTP $status）")
                }
                return try { connection.inputStream.use { read(it, connection.contentLengthLong) } } catch (e: IOException) { throw UpdateFailure("下載中斷，請檢查網路後再試") }
            } finally { connection.disconnect() }
        }
        throw UpdateFailure("轉址太多次，已停止")
    }

    companion object {
        const val CANCELLED = "已取消更新"
        private const val NOT_FOUND = "找不到版本"
        /** PackageInstaller status → short message; null when nothing needs saying. */
        fun statusMessage(status: Int): String? = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "已更新"
            PackageInstaller.STATUS_FAILURE_ABORTED -> CANCELLED
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "簽章不同：請先移除舊版再安裝"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "空間不足，無法安裝"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "系統不允許安裝這個更新"
            else -> "安裝沒有完成"
        }
    }
}

/** Receives PackageInstaller results: shows Android's confirmation, or records a short result. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        AppUpdater(context).apply { lastResult = AppUpdater.statusMessage(status) ?: lastResult; cleanup() }
    }
}
