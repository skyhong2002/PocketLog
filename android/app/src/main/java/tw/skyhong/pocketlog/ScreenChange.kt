package tw.skyhong.pocketlog

/**
 * Pure rules for skipping a capture that would repeat the previous one. No Android types, so the
 * battery-saving decision is unit-tested. A skipped capture is never encoded, stored or uploaded.
 */
object ScreenChange {
    /** Bits of the 64-bit difference hash that may differ before a frame counts as changed. */
    const val MAX_HASH_DISTANCE = 3
    /** Even an unchanged screen is recorded again after this long, so long sessions keep evidence. */
    const val REFRESH_MS = 5 * 60 * 1000L
    const val HASH_WIDTH = 9
    const val HASH_HEIGHT = 8

    /**
     * What one stored capture looked like. [textDigest] is a digest of the normalised screen text;
     * [imageHash] is null when no usable screenshot existed (screenshots off, protected or blank).
     */
    data class Fingerprint(val app: String, val textDigest: String, val imageHash: Long?, val protectedScreen: Boolean, val revision: Long, val atMs: Long)

    fun skipCapture(previous: Fingerprint?, current: Fingerprint, retainUnchanged: Boolean): Boolean =
        !retainUnchanged && unchanged(previous, current)

    fun unchanged(previous: Fingerprint?, current: Fingerprint): Boolean {
        if (previous == null) return false
        if (current.atMs < previous.atMs || current.atMs - previous.atMs >= REFRESH_MS) return false
        if (previous.app != current.app || previous.textDigest != current.textDigest ||
            previous.protectedScreen != current.protectedScreen || previous.revision != current.revision) return false
        val before = previous.imageHash
        val now = current.imageHash
        return when {
            before == null && now == null -> true
            before == null || now == null -> false
            else -> distance(before, now) <= MAX_HASH_DISTANCE
        }
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Difference hash over a 9×8 luminance grid in row-major order: one bit per horizontal neighbour pair. */
    fun dHash(luma: IntArray): Long {
        require(luma.size == HASH_WIDTH * HASH_HEIGHT)
        var hash = 0L
        var bit = 0
        for (y in 0 until HASH_HEIGHT) for (x in 0 until HASH_WIDTH - 1) {
            if (luma[y * HASH_WIDTH + x] > luma[y * HASH_WIDTH + x + 1]) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    fun luma(argb: Int): Int = (((argb shr 16) and 0xff) * 299 + ((argb shr 8) and 0xff) * 587 + (argb and 0xff) * 114) / 1000

    /** Secure or blank compositor output is mostly black; such a frame is never stored or uploaded. */
    fun isMostlyBlack(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return true
        val dark = pixels.count { p -> ((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff) < 30 }
        return dark >= pixels.size * 98 / 100
    }
}

/** When queued screen captures may upload. App sessions and edits are not affected. */
object UploadPolicy {
    /** Above this many queued capture bytes, an unmetered network is enough so storage cannot fill up. */
    const val BACKLOG_BYTES = 256L * 1024 * 1024

    fun observations(charging: Boolean, unmetered: Boolean, queuedObservationBytes: Long): Boolean =
        charging || (unmetered && queuedObservationBytes > BACKLOG_BYTES)

    fun backlogNeedsUpload(queuedObservationBytes: Long): Boolean = queuedObservationBytes > BACKLOG_BYTES
}
