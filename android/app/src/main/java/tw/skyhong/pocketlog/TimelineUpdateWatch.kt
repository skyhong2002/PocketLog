package tw.skyhong.pocketlog

import android.os.Handler
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.Executor

/** Foreground read notification only. Never syncs, caches, captures or runs a model. */
internal class TimelineUpdateWatch(
    private val handler: Handler,
    private val executor: Executor,
    private val selected: LocalDate,
    baseline: JSONObject,
    private val eligible: () -> Boolean,
    private val read: () -> JSONObject,
    private val changed: (State) -> Unit,
    private val today: () -> LocalDate = { TimelineMath.journalDay() },
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
) {
    enum class State { CURRENT, UPDATED, FAILED, ROLLOVER }
    companion object {
        const val INTERVAL_MS = 120_000L
        /** Compare activity evidence only; range endpoints, reviews and key order do not invent card updates. */
        fun fingerprint(data: JSONObject): String {
            fun stable(value: Any?): String = when (value) {
                is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + stable(value.opt(it)) }
                is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { stable(value.opt(it)) }
                null, JSONObject.NULL -> "null"
                is String -> JSONObject.quote(value)
                else -> value.toString()
            }
            val shown = JSONObject().put("cards", data.optJSONArray("cards") ?: JSONArray())
                .put("truncated", data.optBoolean("truncated"))
            return MessageDigest.getInstance("SHA-256").digest(stable(shown).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
    private val original = fingerprint(baseline)
    @Volatile private var running = false
    @Volatile private var closed = false
    private var inFlight = false
    @Volatile private var epoch = 0
    private var lastAttempt = elapsed()
    private var retryAfter = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!running || closed) return
            check()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }
    fun resume() {
        if (closed || running) return
        running = true; epoch++
        handler.postDelayed(tick, INTERVAL_MS)
    }
    fun pause() {
        running = false; epoch++; handler.removeCallbacks(tick)
    }
    fun close() { closed = true; pause() }
    /** Same production path, callable by controlled synthetic instrumentation. */
    fun check() {
        if (!running || closed || inFlight) return
        if (!eligible()) return
        if (selected != today()) { changed(State.ROLLOVER); return }
        if (elapsed() < retryAfter || elapsed() - lastAttempt < INTERVAL_MS) return
        lastAttempt = elapsed(); inFlight = true
        val owner = epoch
        executor.execute {
            // A queued executor operation must not start a read after navigation/Stop.
            val result = if (!closed && running && owner == epoch) runCatching { fingerprint(read()) } else null
            handler.post {
                inFlight = false
                if (result == null || closed || !running || owner != epoch || !eligible()) return@post
                val failure = result.exceptionOrNull()
                if (failure is ApiFailure) retryAfter = elapsed() + failure.retryMs
                if (selected != today()) changed(State.ROLLOVER)
                else changed(result.fold({ if (it == original) State.CURRENT else State.UPDATED }, { State.FAILED }))
            }
        }
    }
}
