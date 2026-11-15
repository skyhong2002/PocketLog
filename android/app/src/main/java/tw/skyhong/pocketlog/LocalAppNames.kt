package tw.skyhong.pocketlog

import org.json.JSONObject

/** View-only labels for packages already in the journal. Never enumerate apps or rewrite cards. */
internal class LocalAppNames {
    private val names = linkedMapOf<String, String>()

    fun prepare(packages: Sequence<String>, lookup: (String) -> String?) {
        val resolved = packages.filter { it.isNotBlank() && it.length <= 200 }.distinct().take(MAX_PACKAGES)
            .associateWith { app ->
                runCatching { lookup(app) }.getOrNull()?.replace(Regex("[\\p{Cc}\\s]+"), " ")?.trim()
                    ?.let { RecordingRules.cap(it, 80) }?.takeIf { it.isNotBlank() } ?: app
            }
        synchronized(names) {
            resolved.forEach { (app, label) -> names[app] = label }
            while (names.size > MAX_PACKAGES) names.remove(names.keys.first())
        }
    }

    fun name(app: String): String = synchronized(names) { names[app] ?: app }
    fun title(card: JSONObject): String {
        val original = card.optString("title")
        val app = card.optString("app")
        return if (card.optString("source") == "app_only" && original == app) name(app) else original
    }

    companion object { const val MAX_PACKAGES = 256 }
}
