package com.elio.recipecraft.data.settings

/** Built-in endpoints and default models; a user's saved values override the model only. */
object ProviderDefaults {

    const val ANTHROPIC_BASE_URL = "https://api.anthropic.com"
    const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"

    const val CLAUDE_MODEL = "claude-sonnet-5-5"
    const val DEEPSEEK_MODEL = "deepseek-chat"

    val activeProvider = ProviderType.CLAUDE

    fun config(type: ProviderType): ProviderConfig = when (type) {
        ProviderType.CLAUDE -> ProviderConfig(type, ANTHROPIC_BASE_URL, Protocol.ANTHROPIC, CLAUDE_MODEL)
        // OpenAI has no default: the user picks a model from the list their key can access.
        ProviderType.OPENAI -> ProviderConfig(type, OPENAI_BASE_URL, Protocol.OPENAI_COMPATIBLE, "")
        ProviderType.DEEPSEEK -> ProviderConfig(type, DEEPSEEK_BASE_URL, Protocol.OPENAI_COMPATIBLE, DEEPSEEK_MODEL)
        ProviderType.CUSTOM -> ProviderConfig(type, "", Protocol.OPENAI_COMPATIBLE, "")
    }

    /** Only a custom provider lets the user choose its base URL and protocol. */
    fun hasEditableEndpoint(type: ProviderType): Boolean = type == ProviderType.CUSTOM
}
