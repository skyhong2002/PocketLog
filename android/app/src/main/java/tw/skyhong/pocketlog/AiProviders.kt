package tw.skyhong.pocketlog

import java.net.URI

/**
 * Provider presets for the simple AI form. The user picks a provider, pastes a key and keeps or edits
 * the suggested model; the endpoint is derived here and only typed by hand for [Preset.CUSTOM].
 */
object AiProviders {
    // Suggested models match the website's presets (server/web/app.js AI_PROVIDERS).
    enum class Preset(val label: String, val protocol: String, val suggestedModel: String, val vision: Boolean, private val template: String?, val keyUrl: String?) {
        OPENAI("OpenAI", "openai", "gpt-5-mini", true, "https://api.openai.com/v1/chat/completions", "https://platform.openai.com/api-keys"),
        ANTHROPIC("Anthropic", "anthropic", "claude-haiku-4-5", true, "https://api.anthropic.com/v1/messages", "https://console.anthropic.com/settings/keys"),
        GEMINI("Google Gemini", "gemini", "gemini-2.5-flash", true, "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent", "https://aistudio.google.com/apikey"),
        OPENROUTER("OpenRouter", "openai", "openai/gpt-5-mini", true, "https://openrouter.ai/api/v1/chat/completions", "https://openrouter.ai/keys"),
        DEEPSEEK("DeepSeek", "openai", "deepseek-chat", false, "https://api.deepseek.com/chat/completions", "https://platform.deepseek.com/api_keys"),
        CUSTOM("自訂（OpenAI 相容：CLIProxyAPI、LiteLLM、Ollama…）", "openai", "", true, null, null);

        /** Short name for the provider grid. */
        val short: String get() = if (this == CUSTOM) "自訂" else label

        val needsEndpoint: Boolean get() = template == null

        /** Full API URL for [model]. Custom endpoints go through the same HTTPS/no-credential checks. */
        fun endpoint(model: String, custom: String? = null): String {
            val id = model.trim()
            require(id.isNotEmpty() && id.length <= 200) { "model" }
            val base = template ?: return AiEndpoint.normalize(requireNotNull(custom) { "endpoint" }, true)
            if (this == GEMINI) require(Regex("[A-Za-z0-9._-]{1,200}").matches(id)) { "model" }
            return AiEndpoint.normalize(base.replace("{model}", id), protocol == "openai")
        }
    }

    private val hosts = mapOf(
        "api.openai.com" to Preset.OPENAI, "api.anthropic.com" to Preset.ANTHROPIC,
        "generativelanguage.googleapis.com" to Preset.GEMINI, "openrouter.ai" to Preset.OPENROUTER,
        "api.deepseek.com" to Preset.DEEPSEEK,
    )

    /** Which preset a saved configuration came from; anything unknown is shown as custom. */
    fun detect(protocol: String?, endpoint: String?): Preset {
        val preset = hosts[host(endpoint)] ?: return Preset.CUSTOM
        return if (preset.protocol == (protocol ?: "openai")) preset else Preset.CUSTOM
    }

    /** Host only, for the one-line "will be sent to …" disclosures. */
    fun host(endpoint: String?): String = runCatching { URI(endpoint.orEmpty().trim()).host }.getOrNull()?.lowercase().orEmpty()
}
