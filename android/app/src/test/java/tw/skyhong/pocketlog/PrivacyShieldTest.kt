package tw.skyhong.pocketlog

import org.junit.Assert.*
import org.junit.Test
class PrivacyShieldTest {
    @Test fun rejectsSuspectedSecretsAndOversizedNodesBeforePixels() {
        for (text in listOf("sk-"+"x".repeat(24), "Bearer synthetic-token-value", "api_key=synthetic", "4111 1111 1111 1111", "x".repeat(2049))) assertTrue(text.take(30),PrivacyShield.rejects(text))
        for (text in listOf("閱讀文件", "Today 14:35", "Project 2026", "There are 12 steps")) assertFalse(text,PrivacyShield.rejects(text))
        assertFalse(PrivacyShield.rejects(null))
    }
}
