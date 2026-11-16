package tw.skyhong.pocketlog

import org.junit.Assert.*
import org.junit.Test
import tw.skyhong.pocketlog.AiProviders.Preset

class AiProvidersTest {
    @Test fun presetsDeriveTheProviderEndpointAndProtocol() {
        assertEquals("https://api.openai.com/v1/chat/completions", Preset.OPENAI.endpoint("gpt-4.1-mini"))
        assertEquals("https://api.anthropic.com/v1/messages", Preset.ANTHROPIC.endpoint("claude-sonnet-4-5"))
        assertEquals("anthropic", Preset.ANTHROPIC.protocol)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", Preset.GEMINI.endpoint(" gemini-2.5-flash "))
        assertEquals("gemini", Preset.GEMINI.protocol)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", Preset.OPENROUTER.endpoint("openai/gpt-4.1-mini"))
        assertEquals("https://api.deepseek.com/chat/completions", Preset.DEEPSEEK.endpoint("deepseek-chat"))
        for (preset in listOf(Preset.OPENAI, Preset.OPENROUTER, Preset.DEEPSEEK, Preset.CUSTOM)) assertEquals("openai", preset.protocol)
        assertTrue(Preset.entries.filter { it != Preset.CUSTOM }.all { it.suggestedModel.isNotBlank() && !it.needsEndpoint })
    }
    @Test fun customUsesTheTypedOpenAiCompatibleEndpointWithTheSameSafetyChecks() {
        val custom = AiProviders.Preset.CUSTOM
        assertTrue(custom.needsEndpoint)
        assertEquals("https://ollama.example/v1/chat/completions", custom.endpoint("llama3", "https://ollama.example/v1"))
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", custom.endpoint("llama3", "http://127.0.0.1:11434/v1"))
        for (bad in listOf("http://example.com/v1", "https://user:pw@example.com/v1", "https://example.com/v1?key=secret")) {
            assertTrue(bad, runCatching { custom.endpoint("llama3", bad) }.isFailure)
        }
        assertTrue(runCatching { custom.endpoint("llama3", null) }.isFailure)
    }
    @Test fun modelsAreRequiredAndGeminiModelsCannotRewriteThePath() {
        assertTrue(runCatching { AiProviders.Preset.OPENAI.endpoint("  ") }.isFailure)
        assertTrue(runCatching { AiProviders.Preset.GEMINI.endpoint("../x?key=1") }.isFailure)
        assertTrue(runCatching { AiProviders.Preset.GEMINI.endpoint("gemini/2") }.isFailure)
    }
    @Test fun savedSettingsMapBackToTheirPresetAndHost() {
        assertEquals(AiProviders.Preset.ANTHROPIC, AiProviders.detect("anthropic", "https://api.anthropic.com/v1/messages"))
        assertEquals(AiProviders.Preset.GEMINI, AiProviders.detect("gemini", "https://generativelanguage.googleapis.com/v1beta/models/m:generateContent"))
        assertEquals(AiProviders.Preset.DEEPSEEK, AiProviders.detect("openai", "https://api.deepseek.com/chat/completions"))
        assertEquals(AiProviders.Preset.CUSTOM, AiProviders.detect("openai", "https://ollama.example/v1/chat/completions"))
        assertEquals(AiProviders.Preset.CUSTOM, AiProviders.detect("openai", "https://api.anthropic.com/v1/messages"))
        assertEquals(AiProviders.Preset.CUSTOM, AiProviders.detect(null, null))
        assertEquals("api.anthropic.com", AiProviders.host("https://api.anthropic.com/v1/messages"))
        assertEquals("", AiProviders.host("not a url"))
    }
    @Test fun everyHostedProviderLinksToAnHttpsKeyPage() {
        for (preset in Preset.entries.filter { it != Preset.CUSTOM }) {
            val url = requireNotNull(preset.keyUrl)
            assertTrue(url, url.startsWith("https://") && AiProviders.host(url).isNotEmpty())
        }
        assertNull(Preset.CUSTOM.keyUrl)
        assertEquals("自訂", Preset.CUSTOM.short)
        assertEquals("OpenAI", Preset.OPENAI.short)
    }
}
