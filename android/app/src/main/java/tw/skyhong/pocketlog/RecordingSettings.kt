package tw.skyhong.pocketlog

import android.content.Context
import android.content.SharedPreferences
import android.app.AppOpsManager
import android.os.Process

class RecordingSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("recording", Context.MODE_PRIVATE)
    val revision: Long get() = prefs.getLong("revision", 0)
    // Fail closed if a consent change cannot be persisted. Caller holds SyncEngine.lock.
    private fun update(editor: SharedPreferences.Editor) { check(editor.putLong("revision", revision + 1).commit()) }
    fun isAuthRejected(credentials: Credentials?): Boolean = ConnectionRejection.matches(prefs.getString("rejected_destination", null), credentials)
    // Caller holds SyncEngine.lock. Store no endpoint/key; never restore any consent on recovery.
    fun rejectCredentials(credentials: Credentials) = update(prefs.edit()
        .putString("rejected_destination", JournalQueue.destination(credentials))
        .putBoolean("enabled", false).putBoolean("context", false).putBoolean("analysis", false)
        .putBoolean("images", false).putBoolean("replay", false).also(::forgetLegacyAi)
        .putString("status", "金鑰已過期、撤銷或失效，已停止擷取；請重新設定連線"))
    // Per-capture model grants were replaced by the server's automatic summary switch.
    private fun forgetLegacyAi(editor: SharedPreferences.Editor) {
        editor.remove("external_ai").remove("ai_renewal").remove("ai_verified").remove("ai_provider_id")
    }
    fun forgetLegacyAiGrant() { if (prefs.contains("external_ai") || prefs.contains("ai_provider_id")) update(prefs.edit().also(::forgetLegacyAi)) }
    // Screenshots became part of screen context by default (owner decision 2026-11-16). Once per
    // install, an existing context grant also turns screenshots on; later explicit choices stick.
    // Caller holds SyncEngine.lock.
    fun applyScreenshotDefault() {
        if (prefs.getBoolean("images_default_v1", false)) return
        val editor = prefs.edit().putBoolean("images_default_v1", true)
        if (hasContextGrant) editor.putBoolean("images", true)
        update(editor)
    }
    fun confirmCollectionCredentials(credentials: Credentials) { if (isAuthRejected(credentials)) clearRejectedCredentials() }
    fun clearRejectedCredentials() { if (prefs.contains("rejected_destination")) update(prefs.edit().remove("rejected_destination")) }
    // Saved choices remain reviewable/revocable while upstream recording is paused.
    // Capture code must continue using the gated properties below.
    val hasUsageGrant: Boolean get() = prefs.getBoolean("enabled", false)
    val hasContextGrant: Boolean get() = prefs.getBoolean("context", false)
    val hasImagesGrant: Boolean get() = prefs.getBoolean("images", false)
    val hasReplayGrant: Boolean get() = prefs.getBoolean("replay", false)
    var enabled: Boolean
        get() = hasUsageGrant && !remotePaused
        set(value) = update(prefs.edit().putBoolean("enabled", value))
    // Remote resume only releases this gate; it never changes local consent bits.
    var remotePaused: Boolean
        get() = prefs.getBoolean("remote_paused", false)
        set(value) { if (value != remotePaused) update(prefs.edit().putBoolean("remote_paused", value)) }
    var contextEnabled: Boolean
        get() = enabled && hasContextGrant
        set(value) = update(prefs.edit().putBoolean("context", value))
    var analysisConsent: Boolean
        get() = contextEnabled && prefs.getBoolean("analysis", false)
        set(value) = update(prefs.edit().putBoolean("analysis", value))
    var imagesEnabled: Boolean
        get() = contextEnabled && hasImagesGrant
        set(value) = update(prefs.edit().putBoolean("images", value))
    var privacyShield: Boolean
        get() = prefs.getBoolean("privacy_shield", true)
        set(value) = update(prefs.edit().putBoolean("privacy_shield", value))
    var replayConsent: Boolean
        get() = imagesEnabled && hasReplayGrant
        set(value) = update(prefs.edit().putBoolean("replay", value))
    // PocketLog's own FLAG_SECURE: an explicit display preference, off by default (owner, 2026-11-17).
    // Not a capture consent, so it does not bump [revision].
    var protectAppScreen: Boolean
        get() = prefs.getBoolean("protect_app_screen", false)
        set(value) { check(prefs.edit().putBoolean("protect_app_screen", value).commit()) }
    var chargeOnly: Boolean
        get() = prefs.getBoolean("charge_only", true)
        set(value) { check(prefs.edit().putBoolean("charge_only", value).commit()) }

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi", true)
        set(value) { check(prefs.edit().putBoolean("wifi", value).commit()) }
    var exclusions: Set<String>
        get() = prefs.getStringSet("excluded", emptySet())!!.toSet()
        set(value) = update(prefs.edit().putStringSet("excluded", value))
    var allowed: Set<String>
        get() = prefs.getStringSet("allowed", emptySet())!!.toSet()
        set(value) = update(prefs.edit().putStringSet("allowed", value))
    @Suppress("DEPRECATION")
    fun hasUsagePermission(): Boolean = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    fun dropped(): Int = prefs.getInt("dropped", 0)
    fun drop() { check(prefs.edit().putInt("dropped", (dropped().toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).commit()) }
    fun status(value: String) { check(prefs.edit().putString("status", value).commit()) }
    fun status(): String = prefs.getString("status", "尚未同步")!!
    /** Last completed upload pass, for 「上次同步 03:15」. */
    var lastSync: Long
        get() = prefs.getLong("last_sync", 0)
        set(value) { check(prefs.edit().putLong("last_sync", value).commit()) }
    var nextAttempt: Long
        get() = prefs.getLong("next", 0)
        set(value) { check(prefs.edit().putLong("next", value).commit()) }
}
