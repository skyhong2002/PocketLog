package tw.skyhong.pocketlog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdatesTest {
    private val hex = "ab".repeat(32)
    private fun asset(name: String, url: String = "https://github.com/skyhong2002/PocketLog/releases/download/v1/$name", digest: String? = null) =
        JSONObject().put("name", name).put("browser_download_url", url).put("size", 1234).also { if (digest != null) it.put("digest", digest) }
    private fun release(vararg assets: JSONObject, draft: Boolean = false, prerelease: Boolean = false, body: String = "修正") =
        JSONObject().put("tag_name", "v0.5.0").put("draft", draft).put("prerelease", prerelease).put("body", body).put("assets", JSONArray(assets.toList()))

    @Test fun parsesTheNewestApkAboveTheInstalledVersionCode() {
        val json = release(asset("PocketLog-0.5.0-40.apk", digest = "sha256:$hex"), asset("PocketLog-0.4.9-38.apk"), asset("PocketLog-0.5.0-40.apk.sha256"), asset("notes.txt"))
        val found = requireNotNull(Updates.parse(json, 36))
        assertEquals("0.5.0", found.version); assertEquals(40, found.versionCode)
        assertEquals("PocketLog-0.5.0-40.apk", found.apk.name); assertEquals(hex, found.sha256)
        assertEquals("PocketLog-0.5.0-40.apk.sha256", found.sha256Asset?.name)
        assertEquals("修正", found.notes)
    }
    @Test fun nothingIsOfferedWhenNotNewerOrNotAFinalRelease() {
        assertNull(Updates.parse(release(asset("PocketLog-0.4.31-36.apk")), 36))
        assertNull(Updates.parse(release(asset("PocketLog-0.5.0-40.apk")), 40))
        assertNull(Updates.parse(release(asset("PocketLog-0.5.0-40.apk"), draft = true), 1))
        assertNull(Updates.parse(release(asset("PocketLog-0.5.0-40.apk"), prerelease = true), 1))
        assertNull(Updates.parse(release(asset("pocketlog-0.5.0-40.apk"), asset("PocketLog-0.5.0.apk"), asset("PocketLog-0.5.0-x.apk")), 1))
        assertNull(Updates.parse(JSONObject(), 1))
        // An asset that would download from a non-GitHub host is ignored.
        assertNull(Updates.parse(release(asset("PocketLog-0.5.0-40.apk", url = "https://evil.example/PocketLog-0.5.0-40.apk")), 1))
    }
    @Test fun digestsComeFromTheDigestFieldOrTheSidecarFile() {
        assertEquals(hex, Updates.digest("sha256:${hex.uppercase()}"))
        for (bad in listOf(null, "", "md5:$hex", "sha256:abc", "sha256:${"z".repeat(64)}", hex)) assertNull(bad, Updates.digest(bad))
        assertEquals(hex, Updates.sidecar("$hex  PocketLog-0.5.0-40.apk\n", "PocketLog-0.5.0-40.apk"))
        assertEquals(hex, Updates.sidecar("$hex *PocketLog-0.5.0-40.apk", "PocketLog-0.5.0-40.apk"))
        assertEquals(hex, Updates.sidecar(hex, "PocketLog-0.5.0-40.apk"))
        assertNull(Updates.sidecar("$hex  Other.apk", "PocketLog-0.5.0-40.apk"))
        assertNull(Updates.sidecar("not a digest", "PocketLog-0.5.0-40.apk"))
        assertNull(Updates.sidecar("", "PocketLog-0.5.0-40.apk"))
    }
    @Test fun onlyHttpsGithubHostsAreAllowed() {
        for (ok in listOf("https://api.github.com/repos/a/b/releases/latest", "https://github.com/a/b/releases/download/v1/x.apk",
                "https://objects.githubusercontent.com/x", "https://release-assets.githubusercontent.com/x?sig=1", "https://GITHUB.COM:443/x"))
            assertTrue(ok, Updates.allowed(ok))
        for (bad in listOf("http://github.com/x", "https://github.com.evil.example/x", "https://evil.example/github.com", "https://user@github.com/x",
                "https://github.com:8443/x", "https://raw.githubusercontent.com/x", "ftp://github.com/x", "", null, "not a url"))
            assertFalse(bad.toString(), Updates.allowed(bad))
        assertEquals("https://api.github.com/repos/skyhong2002/PocketLog/releases/latest", Updates.latestUrl("skyhong2002/PocketLog"))
        assertTrue(runCatching { Updates.latestUrl("evil.example/x/../y?") }.isFailure)
    }
    @Test fun automaticChecksRunAtMostEveryTwelveHours() {
        val now = 100L * Updates.INTERVAL_MS
        assertTrue(Updates.due(true, now, 0))
        assertFalse(Updates.due(false, now, 0))
        assertFalse(Updates.due(true, now, now - Updates.INTERVAL_MS + 1))
        assertTrue(Updates.due(true, now, now - Updates.INTERVAL_MS))
        assertTrue(Updates.due(true, now, now + 60_000)) // clock moved backwards
        assertEquals(12 * 60 * 60 * 1000L, Updates.INTERVAL_MS)
    }
    @Test fun releaseNotesArePlainBoundedText() {
        val notes = Updates.notes("## 新功能\n* **更快** [連結](https://evil.example)\u0007\n\n\n\nend")
        assertFalse(notes.contains("https://")); assertFalse(notes.contains("#")); assertFalse(notes.contains("*")); assertFalse(notes.contains("\u0007"))
        assertTrue(notes.contains("連結"))
        val long = Updates.notes("字".repeat(2000))
        assertTrue(long.length <= Updates.NOTES_CHARS + 1); assertTrue(long.endsWith("…"))
        assertEquals("", Updates.notes(null))
    }
}
