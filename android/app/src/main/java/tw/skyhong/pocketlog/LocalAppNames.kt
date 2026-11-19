package tw.skyhong.pocketlog

import org.json.JSONObject

/** View-only labels for packages already in the journal. Never enumerate apps or rewrite cards. */
internal class LocalAppNames {
    private val names = linkedMapOf<String, String>()

    fun prepare(packages: Sequence<String>, lookup: (String) -> String?) {
        val resolved = packages.filter { it.isNotBlank() && it.length <= 200 }.distinct().take(MAX_PACKAGES)
            .associateWith { app ->
                // Android returns the package itself when an app has no label; treat that as unknown.
                runCatching { lookup(app) }.getOrNull()?.replace(Regex("[\\p{Cc}\\s]+"), " ")?.trim()
                    ?.let { RecordingRules.cap(it, 80) }?.takeIf { it.isNotBlank() && it != app }
            }
        synchronized(names) {
            resolved.forEach { (app, label) -> if (label != null) names[app] = label else names.remove(app) }
            while (names.size > MAX_PACKAGES) names.remove(names.keys.first())
        }
    }

    /** Never a package ID: the device label, then the shared known-name map, then a readable last segment. */
    fun name(app: String): String = synchronized(names) { names[app] } ?: fallback(app)
    /** A card whose stored title is missing or is just its package shows the app's name. */
    fun title(card: JSONObject): String {
        val original = card.optString("title")
        val app = card.optString("app")
        return if (original.isBlank() || original == app) name(app) else original
    }

    companion object {
        const val MAX_PACKAGES = 256
        /** Same map as the website (server/web/app.js APP_NAMES) so both clients agree. */
        val KNOWN = mapOf(
            "com.android.chrome" to "Chrome", "org.mozilla.firefox" to "Firefox", "com.google.android.youtube" to "YouTube",
            "com.google.android.gm" to "Gmail", "com.google.android.apps.maps" to "Google 地圖", "com.google.android.apps.docs" to "Google 雲端硬碟",
            "com.google.android.apps.photos" to "Google 相簿", "com.google.android.calendar" to "Google 日曆", "com.google.android.keep" to "Keep",
            "com.instagram.android" to "Instagram", "com.facebook.katana" to "Facebook", "com.facebook.orca" to "Messenger",
            "com.whatsapp" to "WhatsApp", "jp.naver.line.android" to "LINE", "org.telegram.messenger" to "Telegram",
            "com.discord" to "Discord", "com.Slack" to "Slack", "com.twitter.android" to "X", "com.reddit.frontpage" to "Reddit",
            "com.zhiliaoapp.musically" to "TikTok", "com.ss.android.ugc.trill" to "TikTok", "com.spotify.music" to "Spotify",
            "com.netflix.mediaclient" to "Netflix", "com.microsoft.teams" to "Teams", "com.notion.id" to "Notion", "md.obsidian" to "Obsidian",
        )
        private val NOISE = setOf("com", "org", "net", "io", "tw", "jp", "app", "apps", "android", "mobile", "client", "www")
        fun fallback(app: String): String {
            if (app.isBlank()) return "未知的 app"
            KNOWN[app]?.let { return it }
            val last = app.split('.').lastOrNull { it.isNotEmpty() && it.lowercase() !in NOISE } ?: app
            return last.replaceFirstChar { it.uppercaseChar() }
        }
    }
}
