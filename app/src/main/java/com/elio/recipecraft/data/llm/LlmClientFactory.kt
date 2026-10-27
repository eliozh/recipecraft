package com.elio.recipecraft.data.llm

import com.elio.recipecraft.data.settings.ConfigProblem
import com.elio.recipecraft.data.settings.Protocol
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.data.settings.SecretStore
import com.elio.recipecraft.data.settings.SettingsRepository
import com.elio.recipecraft.data.settings.problems
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** A ready client, or the reason the current settings cannot make one. */
sealed interface LlmClientResult {
    data class Ready(val client: LlmClient) : LlmClientResult
    data class Unavailable(val kind: LlmErrorKind) : LlmClientResult
}

/** Builds an [LlmClient] from saved provider settings and the key in [SecretStore]. */
interface LlmClientFactory {
    /** A client for the active provider. Listing models works without one, so [requireModel] can be off. */
    suspend fun forActiveProvider(requireModel: Boolean = true): LlmClientResult

    /** A client for [config], using the key saved for its provider. */
    suspend fun forConfig(config: ProviderConfig, requireModel: Boolean = true): LlmClientResult
}

@Singleton
class DefaultLlmClientFactory @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val httpClient: OkHttpClient,
    private val json: Json,
) : LlmClientFactory {

    override suspend fun forActiveProvider(requireModel: Boolean): LlmClientResult {
        val config = try {
            settingsRepository.providerConfig(settingsRepository.activeProvider.first()).first()
        } catch (_: IOException) {
            return LlmClientResult.Unavailable(LlmErrorKind.SettingsUnavailable)
        }
        return forConfig(config, requireModel)
    }

    override suspend fun forConfig(config: ProviderConfig, requireModel: Boolean): LlmClientResult {
        val problems = config.problems()
        unavailableReason(problems, requireModel)?.let { return LlmClientResult.Unavailable(it) }
        // T5 adds the Anthropic Messages client.
        if (config.protocol != Protocol.OPENAI_COMPATIBLE) {
            return LlmClientResult.Unavailable(LlmErrorKind.UnsupportedProtocol)
        }
        val key = try {
            secretStore.getKey(config.type)
        } catch (_: GeneralSecurityException) {
            return LlmClientResult.Unavailable(LlmErrorKind.KeyStoreUnavailable)
        } catch (_: IOException) {
            return LlmClientResult.Unavailable(LlmErrorKind.SettingsUnavailable)
        }
        if (key.isNullOrBlank()) return LlmClientResult.Unavailable(LlmErrorKind.MissingKey)
        val baseUrl = try {
            config.baseUrl.trim().toHttpUrl()
        } catch (_: IllegalArgumentException) {
            return LlmClientResult.Unavailable(LlmErrorKind.InvalidEndpoint)
        }
        return LlmClientResult.Ready(OpenAiCompatibleClient(baseUrl, config.model.trim(), key, httpClient, json))
    }

    private fun unavailableReason(problems: Set<ConfigProblem>, requireModel: Boolean): LlmErrorKind? = when {
        problems.any { it != ConfigProblem.MISSING_MODEL } -> LlmErrorKind.InvalidEndpoint
        requireModel && ConfigProblem.MISSING_MODEL in problems -> LlmErrorKind.MissingModel
        else -> null
    }
}
