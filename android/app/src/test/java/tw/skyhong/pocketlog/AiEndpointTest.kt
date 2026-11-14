package tw.skyhong.pocketlog

import org.junit.Assert.*
import org.junit.Test

class AiEndpointTest {
    @Test fun normalizesBasesAndPreservesFullCompletionUrls() {
        assertEquals("https://api.openai.com/v1/chat/completions", AiEndpoint.normalize(" https://api.openai.com/v1/ "))
        assertEquals("https://openrouter.ai/api/v1/chat/completions", AiEndpoint.normalize("https://openrouter.ai/api/v1"))
        assertEquals("https://private.example/v1/chat/completions", AiEndpoint.normalize("https://private.example"))
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", AiEndpoint.normalize("http://127.0.0.1:11434/v1/chat/completions"))
    }
    @Test fun preservesNativeUrlsWithoutAppendingChatPath() {
        assertEquals("https://api.anthropic.com/v1/messages", AiEndpoint.normalize("https://api.anthropic.com/v1/messages", false))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent", AiEndpoint.normalize("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent", false))
        assertTrue(runCatching { AiEndpoint.normalize("https://example.com/v1?key=secret", false) }.isFailure)
    }
    @Test fun refusesInsecureRemoteHostsAndCredentialBearingUrls() {
        for (url in listOf("http://example.com/v1", "https://token@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#secret", "file:///private", "https:///nohost")) {
            assertTrue(url, runCatching { AiEndpoint.normalize(url) }.isFailure)
        }
    }
}
