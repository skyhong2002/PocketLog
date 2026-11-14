package tw.skyhong.pocketlog

/** Bounded, Activity-memory-only editor drafts. Empty text is an intentional edit, not absence. */
class EditorDrafts(private val maxEntries: Int, private val maxCodePoints: Int) {
    init { require(maxEntries > 0 && maxCodePoints > 0) }
    private val values = linkedMapOf<String, String>()
    operator fun get(key: String): String? = values[key]
    fun put(key: String, text: String) {
        values.remove(key)
        values[key] = text.substring(0, text.offsetByCodePoints(0, minOf(text.codePointCount(0, text.length), maxCodePoints)))
        while (values.size > maxEntries) values.remove(values.keys.first())
    }
    fun removeIfUnchanged(key: String, submitted: String) {
        if (values[key] == submitted) values.remove(key)
    }
    fun clear() = values.clear()
}
