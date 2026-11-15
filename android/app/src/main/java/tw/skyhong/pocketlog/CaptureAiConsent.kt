package tw.skyhong.pocketlog

import android.content.Context
import org.json.JSONObject

/** Readiness for future captures only. Existing queue payloads are never rebound or rewritten. */
object CaptureAiConsent {
    enum class State { OFF, CURRENT, UNVERIFIED, RENEWAL }

    fun evaluate(granted: String?, enabled: Boolean?, current: String?): State = when {
        granted == null -> State.OFF
        enabled == null -> State.UNVERIFIED
        !enabled -> State.RENEWAL
        current.isNullOrBlank() -> State.UNVERIFIED
        granted == current -> State.CURRENT
        else -> State.RENEWAL
    }

    fun refresh(context: Context, read: () -> JSONObject): State = synchronized(SyncEngine.lock) {
        val settings = RecordingSettings(context)
        if (!settings.hasExternalAiGrant) return@synchronized settings.captureAiState
        val state = try {
            val config = read()
            val enabled = config.opt("enabled") as? Boolean
            val id = (config.opt("provider_id") as? String)?.takeIf { it.isNotBlank() }
            evaluate(settings.aiProviderId, enabled, id).let {
                // A legacy/incomplete grant is never presented as current.
                if (it == State.OFF) State.RENEWAL else it
            }
        } catch (_: Exception) { State.UNVERIFIED }
        settings.recordCaptureAiState(state)
        state
    }
}
