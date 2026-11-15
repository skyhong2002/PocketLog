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
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false) && !remotePaused
        set(value) = update(prefs.edit().putBoolean("enabled", value))
    // Remote resume only releases this gate; it never changes local consent bits.
    var remotePaused: Boolean
        get() = prefs.getBoolean("remote_paused", false)
        set(value) { if (value != remotePaused) update(prefs.edit().putBoolean("remote_paused", value)) }
    var contextEnabled: Boolean
        get() = enabled && prefs.getBoolean("context", false)
        set(value) = update(prefs.edit().putBoolean("context", value))
    var analysisConsent: Boolean
        get() = contextEnabled && prefs.getBoolean("analysis", false)
        set(value) = update(prefs.edit().putBoolean("analysis", value))
    var imagesEnabled: Boolean
        get() = contextEnabled && prefs.getBoolean("images", false)
        set(value) = update(prefs.edit().putBoolean("images", value))
    var privacyShield: Boolean
        get() = prefs.getBoolean("privacy_shield", true)
        set(value) = update(prefs.edit().putBoolean("privacy_shield", value))
    var replayConsent: Boolean
        get() = imagesEnabled && prefs.getBoolean("replay", false)
        set(value) = update(prefs.edit().putBoolean("replay", value))
    var externalAiConsent: Boolean
        get() = analysisConsent && prefs.getBoolean("external_ai", false)
        set(value) = update(prefs.edit().putBoolean("external_ai", value).putBoolean("ai_renewal", false).putBoolean("ai_verified", false))
    val hasExternalAiGrant: Boolean get() = prefs.getBoolean("external_ai", false)
    val captureAiState: CaptureAiConsent.State get() = when {
        hasExternalAiGrant -> if (prefs.getBoolean("ai_verified", false)) CaptureAiConsent.State.CURRENT else CaptureAiConsent.State.UNVERIFIED
        prefs.getBoolean("ai_renewal", false) -> CaptureAiConsent.State.RENEWAL
        else -> CaptureAiConsent.State.OFF
    }
    // Caller holds SyncEngine.lock. A confirmed change invalidates only future AI consent.
    fun recordCaptureAiState(state: CaptureAiConsent.State) {
        if (state == captureAiState) return
        val editor = prefs.edit().putBoolean("ai_verified", state == CaptureAiConsent.State.CURRENT)
        if (state == CaptureAiConsent.State.RENEWAL) editor.putBoolean("external_ai", false).remove("ai_provider_id").putBoolean("ai_renewal", true)
        update(editor)
    }
    var aiProviderId: String?
        get() = prefs.getString("ai_provider_id", null)
        set(value) = update(prefs.edit().putString("ai_provider_id", value).putBoolean("ai_verified", false))
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
    var nextAttempt: Long
        get() = prefs.getLong("next", 0)
        set(value) { check(prefs.edit().putLong("next", value).commit()) }
}
