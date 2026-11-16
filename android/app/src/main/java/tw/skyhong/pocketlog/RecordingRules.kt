package tw.skyhong.pocketlog

import java.security.MessageDigest

/** Rules adapted from infovore's ScreenLogic, with explicit allowlisting and tighter bounds. */
object RecordingRules {
    const val MAX_TEXT = 2000
    const val RAW_TTL = 24 * 60 * 60 * 1000L
    // Several hours of 10-second captures with screenshots while offline.
    const val MAX_QUEUE_BYTES = 1024 * 1024 * 1024L
    const val MAX_QUEUE_RECORDS = 32_768
    const val MAX_SESSION = 20 * 60 * 1000L
    // Owner decision: one capture (text + screenshot) every ten seconds while an allowed
    // app stays in front, plus app switches no closer than five seconds apart.
    const val CONTEXT_INTERVAL = 10 * 1000L
    const val CONTEXT_MIN_GAP = 5 * 1000L
    // Android rejects takeScreenshot calls closer than about one second apart.
    const val SCREENSHOT_GAP = 1_100L
    const val MAX_IMAGE_EDGE = 720
    // 300,000 bytes encode to exactly the server's 400,000 base64 characters.
    const val MAX_IMAGE_BYTES = 300_000
    const val MAX_IMAGE_BASE64 = 400_000
    const val MAX_UPLOAD_BODY = 480 * 1024
    val sensitive = setOf("com.x8bit.bitwarden", "com.onepassword.android", "com.agilebits.onepassword",
        "com.lastpass.lpandroid", "com.dashlane", "keepass2android.keepass2android", "com.kunzisoft.keepass.free",
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy", "com.android.systemui")
    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    fun packages(value: String): Set<String> = value.split(Regex("[,;\\s]+"))
        .filter { it.isNotBlank() }.onEach { require(packageName.matches(it)) { "請輸入完整 app 套件名稱" } }.toSet()
    fun excluded(pkg: String, extra: Set<String>, own: String): Boolean = pkg == own || pkg in sensitive || pkg in extra ||
        Regex("(?i)(bank|wallet|payment|authenticator|password|keyguard)").containsMatchIn(pkg)
    fun contextAllowed(pkg: String, allowed: Set<String>, excluded: Set<String>, own: String): Boolean =
        pkg in allowed && !excluded(pkg, excluded, own)
    /** Delay before the next capture attempt: app switches wait [CONTEXT_MIN_GAP], otherwise [CONTEXT_INTERVAL]. */
    fun nextCaptureDelay(now: Long, last: Long, appSwitch: Boolean): Long {
        if (last <= 0 || now < last) return 0
        return maxOf(0, last + (if (appSwitch) CONTEXT_MIN_GAP else CONTEXT_INTERVAL) - now)
    }
    fun cap(text: String, max: Int = MAX_TEXT): String {
        val end = minOf(text.length, max)
        return text.substring(0, if (end > 0 && Character.isHighSurrogate(text[end - 1])) end - 1 else end)
    }
    fun normalize(lines: List<String>): String = cap(lines.map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotBlank() }.distinct().joinToString("\n"))
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

data class AppEvent(val at: Long, val pkg: String, val foreground: Boolean)
data class Session(val app: String, val start: Long, val end: Long) {
    val id: String get() = RecordingRules.digest("$app:$start:$end")
}
data class SessionScan(val sessions: List<Session>, val openApp: String?, val openStart: Long)
object Sessions {
    /** Only finalized sessions leave the collector; an ongoing session survives the next scan. */
    fun scan(events: List<AppEvent>, app: String?, start: Long, now: Long): SessionScan {
        val out = mutableListOf<Session>()
        var current = app
        var began = start
        fun close(at: Long) {
            val pkg = current ?: return
            var from = began
            while (at > from) {
                val to = minOf(at, from + RecordingRules.MAX_SESSION)
                out += Session(pkg, from, to)
                from = to
            }
        }
        events.sortedBy { it.at }.filter { it.at <= now && it.at >= 0 && (!it.foreground || it.pkg.isNotBlank()) }.forEach { event ->
            if (event.foreground && event.pkg != current) {
                close(event.at)
                current = event.pkg
                began = event.at
            } else if (!event.foreground && (event.pkg == current || event.pkg.isEmpty())) {
                close(event.at)
                current = null
                began = 0
            }
        }
        // Long sessions must not grow beyond the server's 24-hour interval bound.
        while (current != null && now - began >= RecordingRules.MAX_SESSION) {
            val boundary = began + RecordingRules.MAX_SESSION
            close(boundary)
            began = boundary
        }
        return SessionScan(out, current, began)
    }
}
