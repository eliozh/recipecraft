package com.elio.recipecraft.data.settings

import java.net.URI
import java.net.URISyntaxException

/** The LLM services the user can configure. */
enum class ProviderType { CLAUDE, OPENAI, DEEPSEEK, CUSTOM }

/** The wire protocol a provider speaks. */
enum class Protocol { OPENAI_COMPATIBLE, ANTHROPIC }

/** Everything needed to reach a provider except the API key, which lives only in [SecretStore]. */
data class ProviderConfig(
    val type: ProviderType,
    val baseUrl: String,
    val protocol: Protocol,
    val model: String,
)

/** Why a [ProviderConfig] cannot be used to send a request yet. */
enum class ConfigProblem { MISSING_MODEL, MISSING_BASE_URL, INVALID_BASE_URL, INSECURE_BASE_URL }

/** The problems that block sending; empty when the config is usable. */
fun ProviderConfig.problems(): Set<ConfigProblem> = buildSet {
    if (model.isBlank()) add(ConfigProblem.MISSING_MODEL)
    if (baseUrl.isBlank()) {
        add(ConfigProblem.MISSING_BASE_URL)
    } else {
        baseUrlProblem(baseUrl.trim())?.let(::add)
    }
}

// API keys must never travel in cleartext, so only https endpoints are accepted.
private fun baseUrlProblem(url: String): ConfigProblem? = try {
    val uri = URI(url)
    when {
        uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty() -> ConfigProblem.INVALID_BASE_URL
        !uri.scheme.equals("https", ignoreCase = true) -> ConfigProblem.INSECURE_BASE_URL
        else -> null
    }
} catch (_: URISyntaxException) {
    ConfigProblem.INVALID_BASE_URL
}
