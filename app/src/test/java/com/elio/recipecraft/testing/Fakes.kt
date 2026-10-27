package com.elio.recipecraft.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.elio.recipecraft.data.llm.LlmClient
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmRequest
import com.elio.recipecraft.data.llm.ModelListResult
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.data.settings.ProviderType
import com.elio.recipecraft.data.settings.SecretStore
import com.elio.recipecraft.domain.AppLanguage
import com.elio.recipecraft.domain.Language
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow

const val PLACEHOLDER_KEY = "sk-test-PLACEHOLDER-1234"

class FakeSecretStore : SecretStore {
    val keys = mutableMapOf<ProviderType, String>()
    var failReads = false
    var failWrites = false

    override suspend fun getKey(type: ProviderType): String? {
        if (failReads) throw GeneralSecurityException("test")
        return keys[type]
    }

    override suspend fun setKey(type: ProviderType, key: String) {
        if (failWrites) throw GeneralSecurityException("test")
        if (key.isEmpty()) keys.remove(type) else keys[type] = key
    }

    override suspend fun clear(type: ProviderType) {
        keys.remove(type)
    }
}

/** Every read and write runs on the caller, so advanceUntilIdle sees it. */
class InMemoryDataStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}

class FakeLanguageSettings : AppLanguageSettings {
    var current = AppLanguage.FOLLOW_SYSTEM
    override val choice get() = current
    override val resolvedLanguage = Language.EN
    override fun setChoice(choice: AppLanguage) {
        current = choice
    }
}

/** A client whose reply the test feeds event by event through [events]. */
class FakeLlmClient : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    val events = Channel<LlmEvent>(Channel.UNLIMITED)
    var cancelled = false
    var models: ModelListResult = ModelListResult.Success(emptyList())

    override fun stream(request: LlmRequest): Flow<LlmEvent> {
        requests += request
        return events.receiveAsFlow().onCompletion { if (it is CancellationException) cancelled = true }
    }

    override suspend fun listModels(): ModelListResult = models
}

class FakeLlmClientFactory(var result: LlmClientResult) : LlmClientFactory {
    val configs = mutableListOf<Pair<ProviderConfig, Boolean>>()

    override suspend fun forActiveProvider(requireModel: Boolean): LlmClientResult = result

    override suspend fun forConfig(config: ProviderConfig, requireModel: Boolean): LlmClientResult {
        configs += config to requireModel
        return result
    }
}
