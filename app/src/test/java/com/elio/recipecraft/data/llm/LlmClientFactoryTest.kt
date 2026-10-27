package com.elio.recipecraft.data.llm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.elio.recipecraft.data.settings.Protocol
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.data.settings.ProviderDefaults
import com.elio.recipecraft.data.settings.ProviderType
import com.elio.recipecraft.data.settings.SettingsRepository
import com.elio.recipecraft.testing.FakeSecretStore
import com.elio.recipecraft.testing.InMemoryDataStore
import com.elio.recipecraft.testing.PLACEHOLDER_KEY
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmClientFactoryTest {

    private val secrets = FakeSecretStore()
    private val repository = SettingsRepository(InMemoryDataStore())
    private val factory = DefaultLlmClientFactory(repository, secrets, OkHttpClient(), Json)

    @Test
    fun `builds an openai-compatible client for the active built-in provider`() = runTest {
        repository.setActiveProvider(ProviderType.DEEPSEEK)
        secrets.keys[ProviderType.DEEPSEEK] = PLACEHOLDER_KEY

        val result = factory.forActiveProvider()

        assertTrue(result is LlmClientResult.Ready)
        assertTrue((result as LlmClientResult.Ready).client is OpenAiCompatibleClient)
    }

    @Test
    fun `openai and an openai-compatible custom endpoint get a client`() = runTest {
        secrets.keys[ProviderType.OPENAI] = PLACEHOLDER_KEY
        secrets.keys[ProviderType.CUSTOM] = PLACEHOLDER_KEY
        val openAi = ProviderDefaults.config(ProviderType.OPENAI).copy(model = "gpt-test")
        val custom = ProviderConfig(ProviderType.CUSTOM, " https://llm.example.com/v1 ", Protocol.OPENAI_COMPATIBLE, "m")

        assertTrue(factory.forConfig(openAi) is LlmClientResult.Ready)
        assertTrue(factory.forConfig(custom) is LlmClientResult.Ready)
    }

    @Test
    fun `a missing model blocks chat but not listing models`() = runTest {
        secrets.keys[ProviderType.OPENAI] = PLACEHOLDER_KEY
        repository.setActiveProvider(ProviderType.OPENAI)

        assertEquals(LlmClientResult.Unavailable(LlmErrorKind.MissingModel), factory.forActiveProvider())
        assertTrue(factory.forActiveProvider(requireModel = false) is LlmClientResult.Ready)
    }

    @Test
    fun `a missing or blank key is reported`() = runTest {
        val config = ProviderDefaults.config(ProviderType.DEEPSEEK)
        assertEquals(LlmClientResult.Unavailable(LlmErrorKind.MissingKey), factory.forConfig(config))
        secrets.keys[ProviderType.DEEPSEEK] = " "
        assertEquals(LlmClientResult.Unavailable(LlmErrorKind.MissingKey), factory.forConfig(config))
    }

    @Test
    fun `anthropic protocols are not supported yet`() = runTest {
        secrets.keys[ProviderType.CLAUDE] = PLACEHOLDER_KEY
        secrets.keys[ProviderType.CUSTOM] = PLACEHOLDER_KEY
        val custom = ProviderConfig(ProviderType.CUSTOM, "https://llm.example.com", Protocol.ANTHROPIC, "m")

        assertEquals(LlmClientResult.Unavailable(LlmErrorKind.UnsupportedProtocol), factory.forActiveProvider())
        assertEquals(LlmClientResult.Unavailable(LlmErrorKind.UnsupportedProtocol), factory.forConfig(custom))
    }

    @Test
    fun `an invalid or insecure endpoint is reported before the model`() = runTest {
        secrets.keys[ProviderType.CUSTOM] = PLACEHOLDER_KEY
        val base = ProviderConfig(ProviderType.CUSTOM, "", Protocol.OPENAI_COMPATIBLE, "")
        listOf("", "not a url", "http://llm.example.com").forEach { url ->
            assertEquals(
                url,
                LlmClientResult.Unavailable(LlmErrorKind.InvalidEndpoint),
                factory.forConfig(base.copy(baseUrl = url)),
            )
        }
    }

    @Test
    fun `a url that passes validation but okhttp rejects is an invalid endpoint`() = runTest {
        secrets.keys[ProviderType.CUSTOM] = PLACEHOLDER_KEY
        val config = ProviderConfig(ProviderType.CUSTOM, "https://host:99999", Protocol.OPENAI_COMPATIBLE, "m")
        assertEquals(LlmClientResult.Unavailable(LlmErrorKind.InvalidEndpoint), factory.forConfig(config))
    }

    @Test
    fun `an unavailable keystore is not the same as no key`() = runTest {
        secrets.failReads = true
        assertEquals(
            LlmClientResult.Unavailable(LlmErrorKind.KeyStoreUnavailable),
            factory.forConfig(ProviderDefaults.config(ProviderType.DEEPSEEK)),
        )
    }

    @Test
    fun `unreadable settings are reported`() = runTest {
        val broken = DefaultLlmClientFactory(SettingsRepository(FailingDataStore()), secrets, OkHttpClient(), Json)
        assertEquals(LlmClientResult.Unavailable(LlmErrorKind.SettingsUnavailable), broken.forActiveProvider())
    }

    private class FailingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk") }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("disk")
    }
}
