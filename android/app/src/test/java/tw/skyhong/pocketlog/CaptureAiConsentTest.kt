package tw.skyhong.pocketlog

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureAiConsentTest {
    @Test fun matchingIdentityIsTheOnlyCurrentGrant() {
        assertEquals(CaptureAiConsent.State.CURRENT, CaptureAiConsent.evaluate("a", true, "a"))
        assertEquals(CaptureAiConsent.State.RENEWAL, CaptureAiConsent.evaluate("a", true, "b"))
        assertEquals(CaptureAiConsent.State.RENEWAL, CaptureAiConsent.evaluate("a", false, "a"))
    }
    @Test fun failedAndIncompleteReadsCannotClaimCurrentReadiness() {
        assertEquals(CaptureAiConsent.State.UNVERIFIED, CaptureAiConsent.evaluate("a", null, "a"))
        assertEquals(CaptureAiConsent.State.UNVERIFIED, CaptureAiConsent.evaluate("a", true, null))
        assertEquals(CaptureAiConsent.State.UNVERIFIED, CaptureAiConsent.evaluate("a", true, " "))
    }
    @Test fun configurationCannotCreateConsent() {
        for (enabled in listOf(true, false, null))
            assertEquals(CaptureAiConsent.State.OFF, CaptureAiConsent.evaluate(null, enabled, "b"))
    }
}
