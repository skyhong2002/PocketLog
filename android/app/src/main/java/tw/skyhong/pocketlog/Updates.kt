package tw.skyhong.pocketlog

import org.json.JSONObject
import java.net.URI

/**
 * Pure rules for the GitHub release updater: which release and asset to offer, which hosts may serve
 * it, how its SHA-256 is read, and how often to check. No Android types, so every rule is unit-tested.
 */
object Updates {
    const val INTERVAL_MS = 12 * 60 * 60 * 1000L
    const val MAX_APK_BYTES = 150L * 1024 * 1024
    const val MAX_RELEASE_JSON = 1024 * 1024
    const val NOTES_CHARS = 600
    /** HTTPS hosts allowed to serve release metadata, assets and their redirects. */
    val HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private val APK = Regex("^PocketLog-(.+)-(\\d+)\\.apk$")
    private val HEX = Regex("^[0-9a-fA-F]{64}$")

    data class Asset(val name: String, val url: String, val size: Long)
    data class Release(val version: String, val versionCode: Int, val notes: String, val apk: Asset, val sha256: String?, val sha256Asset: Asset?)

    fun latestUrl(repository: String): String {
        require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repository))
        return "https://api.github.com/repos/$repository/releases/latest"
    }

    /** The newest offered APK in a release, or null for drafts, prereleases or nothing newer than [currentCode]. */
    fun parse(release: JSONObject, currentCode: Int): Release? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val assets = release.optJSONArray("assets") ?: return null
        val all = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        val best = all.mapNotNull { a ->
            val match = APK.matchEntire(a.optString("name")) ?: return@mapNotNull null
            val code = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (code <= currentCode) return@mapNotNull null
            val url = a.optString("browser_download_url")
            if (!allowed(url)) return@mapNotNull null
            Triple(code, match.groupValues[1], a)
        }.maxByOrNull { it.first } ?: return null
        val (code, version, json) = best
        val name = json.getString("name")
        val apk = Asset(name, json.getString("browser_download_url"), json.optLong("size", -1))
        val sidecar = all.firstOrNull { it.optString("name") == "$name.sha256" }?.let { Asset(it.getString("name"), it.optString("browser_download_url"), it.optLong("size", -1)) }
            ?.takeIf { allowed(it.url) }
        return Release(version, code, notes(release.optString("body")), apk, digest(json.optString("digest")), sidecar)
    }

    /** `sha256:<hex>` from GitHub's asset `digest` field, lower-cased; anything else is null. */
    fun digest(value: String?): String? {
        val hex = value?.trim()?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7) ?: return null
        return hex.takeIf { HEX.matches(it) }?.lowercase()
    }

    /** First token of a `sha256sum` line (`<hex>  name`), checked against [apkName] when a name is present. */
    fun sidecar(text: String, apkName: String): String? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val parts = line.split(Regex("\\s+"), limit = 2)
        val hex = parts[0].takeIf { HEX.matches(it) }?.lowercase() ?: return null
        val named = parts.getOrNull(1)?.trimStart('*')
        return if (named == null || named == apkName) hex else null
    }

    /** HTTPS on a GitHub host only; used for the release lookup, downloads and every redirect. */
    fun allowed(url: String?): Boolean {
        val uri = runCatching { URI(url ?: return false) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.host?.lowercase() in HOSTS && (uri.port == -1 || uri.port == 443)
    }

    /** Whether an automatic check is due: enabled, never checked, or 12 h since the last one (clock jumps reset it). */
    fun due(enabled: Boolean, now: Long, lastCheck: Long): Boolean =
        enabled && (lastCheck <= 0 || now < lastCheck || now - lastCheck >= INTERVAL_MS)

    /** Release notes as plain text: markdown markers and control characters stripped, about 600 characters. */
    fun notes(body: String?): String {
        val plain = body.orEmpty().replace(Regex("[\\p{Cc}&&[^\\n]]"), "")
            .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1").replace(Regex("[#*_`>]+"), "").replace(Regex("\\n{3,}"), "\n\n").trim()
        return if (plain.length <= NOTES_CHARS) plain else RecordingRules.cap(plain, NOTES_CHARS).trimEnd() + "…"
    }
}
