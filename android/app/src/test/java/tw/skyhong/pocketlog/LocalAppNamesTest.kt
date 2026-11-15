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
        for (source in listOf("user", "ai", "local_excerpt", "unknown", ""))
            assertEquals("org.example.reader", labels.title(card(source)))
        assertEquals("My chosen title", labels.title(card(title = "My chosen title")))
    }
    @Test fun missingInvisibleOrFailedPackagesKeepTheirOriginalIdentifier() {
        val labels = LocalAppNames()
        labels.prepare(sequenceOf("org.example.reader", "org.example.hidden")) { if (it.endsWith("reader")) throw SecurityException() else null }
        assertEquals("org.example.reader", labels.title(card()))
        assertEquals("org.example.hidden", labels.name("org.example.hidden"))
        assertEquals("org.example.other", labels.name("org.example.other"))
    }
    @Test fun workAndMemoryAreBoundedAndOnlyRequestedPackagesAreLookedUp() {
        val labels = LocalAppNames(); val lookedUp = mutableListOf<String>()
        labels.prepare((sequenceOf("same", "same") + (0..1000).asSequence().map { "org.example.app$it" })) { lookedUp.add(it); "Label $it" }
        assertEquals(LocalAppNames.MAX_PACKAGES, lookedUp.size); assertEquals(1, lookedUp.count { it == "same" })
        assertEquals("org.example.app1000", labels.name("org.example.app1000"))
        labels.prepare((0 until LocalAppNames.MAX_PACKAGES).asSequence().map { "new.$it" }) { "New" }
        assertEquals("same", labels.name("same")); assertEquals("New", labels.name("new.0"))
    }
    @Test fun labelsAreNormalizedBoundedAndRefreshedInsteadOfPersisted() {
        val labels = LocalAppNames()
        labels.prepare(sequenceOf("org.example.reader")) { "  Reader\n\t App  " }
        assertEquals("Reader App", labels.title(card()))
        labels.prepare(sequenceOf("org.example.reader")) { "🙂".repeat(100) }
        val long = labels.title(card()); assertTrue(long.length <= 80); assertFalse(Character.isHighSurrogate(long.last()))
        labels.prepare(sequenceOf("org.example.reader")) { "   " }
        assertEquals("org.example.reader", labels.title(card()))
    }
}
