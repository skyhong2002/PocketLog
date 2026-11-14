package tw.skyhong.pocketlog

/** Conservative pre-capture text gate, not OCR. A match rejects the entire window. */
object PrivacyShield {
    private val secrets = Regex("(?i)(?:\\bsk-[a-z0-9_-]{12,}|\\bgh[pousr]_[a-z0-9]{20,}|\\bAKIA[A-Z0-9]{16}\\b|\\bAIza[a-z0-9_-]{20,}|\\bbearer\\s+[a-z0-9._-]{8,}|(?:password|passwd|api[_ -]?key|access[_ -]?token|authorization)\\s*[:=]\\s*[^\\s]{6,})")
    private val longNumber = Regex("(?<![0-9])(?:[0-9][ -]?){13,19}(?![0-9])")
    fun rejects(text: CharSequence?): Boolean {
        if (text == null) return false
        // Do not truncate away a potential secret later in an oversized node.
        if (text.length > 2048) return true
        return secrets.containsMatchIn(text) || longNumber.containsMatchIn(text)
    }
}
