package tw.skyhong.pocketlog

import org.junit.Assert.*
import org.junit.Test

class ScreenChangeTest {
    private fun shot(app: String = "org.example.reader", text: String = "chapter 1", hash: Long? = 0x0F0F0F0FL, protected: Boolean = false, revision: Long = 1, at: Long = 1_000_000) =
        ScreenChange.Fingerprint(app, RecordingRules.digest(text), hash, protected, revision, at)

    @Test fun sameAppTextAndPictureIsSkipped() {
        assertTrue(ScreenChange.unchanged(shot(), shot(at = 1_010_000)))
        // A few flipped bits (cursor, clock) still count as the same picture.
        assertTrue(ScreenChange.unchanged(shot(), shot(hash = 0x0F0F0F0FL xor 0b111, at = 1_010_000)))
    }
    @Test fun continuousConsentRetainsEvidenceEvenWhenPixelsAndTextAreIdentical() {
        val previous = shot()
        val next = shot(at = 1_010_000)
        assertTrue(ScreenChange.skipCapture(previous, next, retainUnchanged = false))
        assertFalse(ScreenChange.skipCapture(previous, next, retainUnchanged = true))
        assertFalse(ScreenChange.skipCapture(shot(hash = null), shot(hash = null, at = 1_010_000), retainUnchanged = true))
    }
    @Test fun anyRealChangeIsRecorded() {
        assertFalse(ScreenChange.unchanged(null, shot()))
        assertFalse(ScreenChange.unchanged(shot(), shot(app = "org.example.other", at = 1_010_000)))
        assertFalse(ScreenChange.unchanged(shot(), shot(text = "chapter 2", at = 1_010_000)))
        assertFalse(ScreenChange.unchanged(shot(), shot(hash = 0x0F0F0F0FL xor 0xFF, at = 1_010_000)))
        assertFalse(ScreenChange.unchanged(shot(), shot(protected = true, hash = null, at = 1_010_000)))
        assertFalse(ScreenChange.unchanged(shot(), shot(revision = 2, at = 1_010_000)))
        // A screenshot appearing where there was none (or vice versa) is a change.
        assertFalse(ScreenChange.unchanged(shot(hash = null), shot(at = 1_010_000)))
        assertFalse(ScreenChange.unchanged(shot(), shot(hash = null, at = 1_010_000)))
    }
    @Test fun textOnlyCapturesSkipOnSameText() {
        assertTrue(ScreenChange.unchanged(shot(hash = null), shot(hash = null, at = 1_010_000)))
        assertTrue(ScreenChange.unchanged(shot(hash = null, protected = true), shot(hash = null, protected = true, at = 1_010_000)))
    }
    @Test fun longStaticScreensAreRefreshedAndClockJumpsNeverSkip() {
        assertFalse(ScreenChange.unchanged(shot(), shot(at = 1_000_000 + ScreenChange.REFRESH_MS)))
        assertTrue(ScreenChange.unchanged(shot(), shot(at = 1_000_000 + ScreenChange.REFRESH_MS - 1)))
        assertFalse(ScreenChange.unchanged(shot(), shot(at = 999_999)))
    }
    @Test fun differenceHashFollowsBrightnessGradients() {
        assertEquals(0L, ScreenChange.dHash(IntArray(72) { 128 }))
        assertEquals(-1L, ScreenChange.dHash(IntArray(72) { 255 - (it % 9) * 20 }))
        assertEquals(64, ScreenChange.distance(0L, -1L))
        assertEquals(255, ScreenChange.luma(0xffffffff.toInt()))
        assertEquals(0, ScreenChange.luma(0xff000000.toInt()))
        assertTrue(runCatching { ScreenChange.dHash(IntArray(64)) }.isFailure)
    }
    @Test fun capturesUploadWhileChargingOrWhenTheBacklogIsLargeOnUnmeteredNetworks() {
        val limit = UploadPolicy.BACKLOG_BYTES
        assertEquals(256L * 1024 * 1024, limit)
        assertTrue(UploadPolicy.observations(charging = true, unmetered = false, queuedObservationBytes = 0))
        assertFalse(UploadPolicy.observations(charging = false, unmetered = true, queuedObservationBytes = limit))
        assertTrue(UploadPolicy.observations(charging = false, unmetered = true, queuedObservationBytes = limit + 1))
        assertFalse(UploadPolicy.observations(charging = false, unmetered = false, queuedObservationBytes = limit * 3))
        assertTrue(UploadPolicy.backlogNeedsUpload(limit + 1)); assertFalse(UploadPolicy.backlogNeedsUpload(limit))
    }
}
