package tw.skyhong.pocketlog

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalAppNamesTest {
    private fun card(source: String = "app_only", title: String = "org.example.reader") = JSONObject()
        .put("app", "org.example.reader").put("title", title).put("source", source).put("category", "learning")

    @Test fun onlyUsageFallbackTitlesUseLocalNamesWithoutChangingTheCard() {
        val labels = LocalAppNames(); labels.prepare(sequenceOf("org.example.reader")) { "Reader" }
        val raw = card(); val before = raw.toString()
        assertEquals("Reader", labels.title(raw)); assertEquals(before, raw.toString())
        // A title that is just the package never shows the package, whatever the source.
        for (source in listOf("user", "ai", "local_excerpt", "unknown", ""))
            assertEquals("Reader", labels.title(card(source)))
        assertEquals("My chosen title", labels.title(card(title = "My chosen title")))
    }
    @Test fun missingInvisibleOrFailedPackagesFallBackToKnownOrReadableNames() {
        val labels = LocalAppNames()
        labels.prepare(sequenceOf("org.example.reader", "org.example.hidden", "com.netflix.mediaclient")) { if (it.endsWith("reader")) throw SecurityException() else if (it.contains("netflix")) it else null }
        assertEquals("Reader", labels.title(card()))
        assertEquals("Hidden", labels.name("org.example.hidden"))
        assertEquals("Netflix", labels.name("com.netflix.mediaclient"))
        assertEquals("Chrome", labels.name("com.android.chrome"))
        assertEquals("Google 雲端硬碟", labels.name("com.google.android.apps.docs"))
        assertEquals("Pocketlogfixture", labels.name("tw.skyhong.pocketlogfixture"))
        assertEquals("未知的 app", labels.name(""))
        assertEquals("Reader", labels.title(card(title = "")))
    }
    @Test fun workAndMemoryAreBoundedAndOnlyRequestedPackagesAreLookedUp() {
        val labels = LocalAppNames(); val lookedUp = mutableListOf<String>()
        labels.prepare((sequenceOf("same", "same") + (0..1000).asSequence().map { "org.example.app$it" })) { lookedUp.add(it); "Label $it" }
        assertEquals(LocalAppNames.MAX_PACKAGES, lookedUp.size); assertEquals(1, lookedUp.count { it == "same" })
        assertEquals("App1000", labels.name("org.example.app1000"))
        labels.prepare((0 until LocalAppNames.MAX_PACKAGES).asSequence().map { "new.$it" }) { "New" }
        assertEquals("Same", labels.name("same")); assertEquals("New", labels.name("new.0"))
    }
    @Test fun labelsAreNormalizedBoundedAndRefreshedInsteadOfPersisted() {
        val labels = LocalAppNames()
        labels.prepare(sequenceOf("org.example.reader")) { "  Reader\n\t App  " }
        assertEquals("Reader App", labels.title(card()))
        labels.prepare(sequenceOf("org.example.reader")) { "🙂".repeat(100) }
        val long = labels.title(card()); assertTrue(long.length <= 80); assertFalse(Character.isHighSurrogate(long.last()))
        labels.prepare(sequenceOf("org.example.reader")) { "   " }
        assertEquals("Reader", labels.title(card()))
    }
}
