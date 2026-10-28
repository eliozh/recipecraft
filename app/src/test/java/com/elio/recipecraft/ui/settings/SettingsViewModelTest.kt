package com.elio.recipecraft.ui.settings

import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.ModelListResult
import com.elio.recipecraft.data.settings.Protocol
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.data.settings.ProviderDefaults
import com.elio.recipecraft.data.settings.ProviderType
import com.elio.recipecraft.data.settings.SettingsRepository
import com.elio.recipecraft.domain.AppLanguage
import com.elio.recipecraft.testing.FakeLanguageSettings
import com.elio.recipecraft.testing.FakeLlmClient
import com.elio.recipecraft.testing.FakeLlmClientFactory
import com.elio.recipecraft.testing.FakeSecretStore
import com.elio.recipecraft.testing.InMemoryDataStore
import com.elio.recipecraft.testing.PLACEHOLDER_KEY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val secrets = FakeSecretStore()
    private val language = FakeLanguageSettings()
    private val client = FakeLlmClient()
    private val factory = FakeLlmClientFactory(LlmClientResult.Ready(client))

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // In memory, so every read and write runs on the test scheduler and advanceUntilIdle sees it.
    private fun repository() = SettingsRepository(InMemoryDataStore())

    private fun viewModel(repository: SettingsRepository) = SettingsViewModel(language, repository, secrets, factory)

    @Test
    fun `loads the saved active provider with its config and key`() = runTest {
        val repo = repository()
        repo.setActiveProvider(ProviderType.DEEPSEEK)
        secrets.keys[ProviderType.DEEPSEEK] = PLACEHOLDER

        val vm = viewModel(repo)
        assertTrue(vm.uiState.value.loading)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertFalse(state.loadFailed)
        assertEquals(ProviderType.DEEPSEEK, state.activeProvider)
        assertEquals(ProviderDefaults.config(ProviderType.DEEPSEEK), state.config)
        assertEquals(PLACEHOLDER, state.apiKey.plaintext)
    }

    @Test
    fun `switching provider saves the choice and loads that provider's settings`() = runTest {
        val repo = repository()
        repo.updateProviderConfig(ProviderDefaults.config(ProviderType.OPENAI).copy(model = "gpt-test"))
        secrets.keys[ProviderType.OPENAI] = PLACEHOLDER
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.onToggleKeyVisibility()
        vm.onProviderSelected(ProviderType.OPENAI)
        assertTrue(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.keyVisible)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals("gpt-test", state.config.model)
        assertEquals(PLACEHOLDER, state.apiKey.plaintext)
        assertEquals(ProviderType.OPENAI, repo.activeProvider.first())
    }

    @Test
    fun `edits are saved to the active provider in order`() = runTest {
        val repo = repository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onProviderSelected(ProviderType.CUSTOM)
        advanceUntilIdle()

        vm.onBaseUrlChanged("https://llm.example.com")
        vm.onProtocolSelected(Protocol.ANTHROPIC)
        vm.onModelChanged("m")
        vm.onApiKeyChanged("sk-")
        vm.onApiKeyChanged(PLACEHOLDER)
        advanceUntilIdle()

        assertEquals(
            ProviderConfig(ProviderType.CUSTOM, "https://llm.example.com", Protocol.ANTHROPIC, "m"),
            repo.providerConfig(ProviderType.CUSTOM).first(),
        )
        assertEquals(PLACEHOLDER, secrets.keys[ProviderType.CUSTOM])

        vm.onClearApiKey()
        advanceUntilIdle()
        assertEquals(null, secrets.keys[ProviderType.CUSTOM])
        assertEquals("", vm.uiState.value.apiKey.plaintext)
    }

    @Test
    fun `a failed save is reported and later operations still run`() = runTest {
        val repo = repository()
        val vm = viewModel(repo)
        advanceUntilIdle()

        secrets.failWrites = true
        vm.onApiKeyChanged(PLACEHOLDER)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.saveFailed)
        assertFalse(vm.uiState.value.loadFailed)

        secrets.failWrites = false
        vm.onModelChanged("claude-test")
        vm.onApiKeyChanged(PLACEHOLDER)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.saveFailed)
        assertEquals("claude-test", repo.providerConfig(ProviderType.CLAUDE).first().model)
        assertEquals(PLACEHOLDER, secrets.keys[ProviderType.CLAUDE])
    }

    @Test
    fun `a failed load clears loading, reports the error and retries on resume`() = runTest {
        val repo = repository()
        secrets.keys[ProviderType.CLAUDE] = PLACEHOLDER
        secrets.failReads = true
        val vm = viewModel(repo)
        advanceUntilIdle()

        var state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.loadFailed)
        assertFalse(state.saveFailed)
        assertEquals(ProviderDefaults.config(ProviderType.CLAUDE), state.config)
        assertEquals("", state.apiKey.plaintext)

        secrets.failReads = false
        vm.onResume()
        advanceUntilIdle()
        state = vm.uiState.value
        assertFalse(state.loadFailed)
        assertEquals(PLACEHOLDER, state.apiKey.plaintext)
    }

    @Test
    fun `reselecting the provider after a failed load retries it`() = runTest {
        val repo = repository()
        val vm = viewModel(repo)
        advanceUntilIdle()

        secrets.failReads = true
        vm.onProviderSelected(ProviderType.DEEPSEEK)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loadFailed)
        assertFalse(vm.uiState.value.loading)

        secrets.failReads = false
        secrets.keys[ProviderType.DEEPSEEK] = PLACEHOLDER
        vm.onProviderSelected(ProviderType.DEEPSEEK)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loadFailed)
        assertEquals(PLACEHOLDER, vm.uiState.value.apiKey.plaintext)
    }

    @Test
    fun `after a failed load, edits are ignored so the shown defaults never overwrite what is stored`() = runTest {
        val repo = repository()
        val stored = ProviderConfig(ProviderType.CUSTOM, "https://llm.example.com/v1", Protocol.OPENAI_COMPATIBLE, "m")
        repo.updateProviderConfig(stored)
        repo.setActiveProvider(ProviderType.CUSTOM)
        secrets.keys[ProviderType.CUSTOM] = PLACEHOLDER
        secrets.failReads = true
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loadFailed)
        assertFalse(vm.uiState.value.editable)

        vm.onModelChanged("other")
        vm.onBaseUrlChanged("")
        vm.onProtocolSelected(Protocol.ANTHROPIC)
        vm.onApiKeyChanged("")
        advanceUntilIdle()
        assertEquals(stored, repo.providerConfig(ProviderType.CUSTOM).first())
        assertEquals(PLACEHOLDER, secrets.keys[ProviderType.CUSTOM])

        secrets.failReads = false
        vm.onRetryLoad()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state.editable)
        assertEquals(stored, state.config)
        assertEquals(PLACEHOLDER, state.apiKey.plaintext)
    }

    @Test
    fun `a failed key save stays reported until the key saves, whatever else saves meanwhile`() = runTest {
        val repo = repository()
        val vm = viewModel(repo)
        advanceUntilIdle()

        secrets.failWrites = true
        vm.onApiKeyChanged(PLACEHOLDER)
        advanceUntilIdle()
        assertEquals(setOf(SettingsField.API_KEY), vm.uiState.value.failedSaves)

        vm.onModelChanged("claude-test")
        advanceUntilIdle()
        assertEquals("claude-test", repo.providerConfig(ProviderType.CLAUDE).first().model)
        assertEquals(setOf(SettingsField.API_KEY), vm.uiState.value.failedSaves)
        assertTrue(vm.uiState.value.saveFailed)

        secrets.failWrites = false
        vm.onApiKeyChanged(PLACEHOLDER)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.failedSaves.isEmpty())
    }

    @Test
    fun `a failed key save is dropped once another provider loads`() = runTest {
        val vm = viewModel(repository())
        advanceUntilIdle()
        secrets.failWrites = true
        vm.onApiKeyChanged(PLACEHOLDER)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.saveFailed)

        vm.onProviderSelected(ProviderType.OPENAI)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.saveFailed)
    }

    @Test
    fun `retry does nothing unless a load failed`() = runTest {
        val vm = viewModel(repository())
        advanceUntilIdle()
        vm.onRetryLoad()
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `test connection reports a rate limit at once instead of waiting to retry`() = runTest {
        val vm = viewModel(repository())
        advanceUntilIdle()
        vm.onTestConnection()
        client.events.send(LlmEvent.Retrying(1, 2_000))
        advanceUntilIdle()
        assertEquals(ConnectionTestState.Failed(LlmErrorKind.RateLimited), vm.uiState.value.connectionTest)
    }

    @Test
    fun `language follows changes made outside the app`() = runTest {
        val vm = viewModel(repository())
        advanceUntilIdle()
        language.current = AppLanguage.ZH
        vm.onResume()
        assertEquals(AppLanguage.ZH, vm.uiState.value.language)

        vm.onLanguageSelected(AppLanguage.EN)
        assertEquals(AppLanguage.EN, language.current)
        assertEquals(AppLanguage.EN, vm.uiState.value.language)
    }

    @Test
    fun `secret toString never reveals the key`() {
        assertFalse(Secret(PLACEHOLDER).toString().contains("PLACEHOLDER"))
        assertFalse(SettingsUiState(apiKey = Secret(PLACEHOLDER)).toString().contains("PLACEHOLDER"))
        assertEquals(Secret(PLACEHOLDER), Secret(PLACEHOLDER))
    }

    @Test
    fun `test connection uses the saved config and succeeds on the first token`() = runTest {
        val repo = repository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onProviderSelected(ProviderType.DEEPSEEK)
        vm.onApiKeyChanged(PLACEHOLDER)

        vm.onTestConnection()
        assertEquals(ConnectionTestState.Running, vm.uiState.value.connectionTest)
        client.events.send(LlmEvent.Delta("pong"))
        advanceUntilIdle()

        assertEquals(ConnectionTestState.Succeeded, vm.uiState.value.connectionTest)
        assertEquals(PLACEHOLDER, secrets.keys[ProviderType.DEEPSEEK])
        assertEquals(ProviderDefaults.config(ProviderType.DEEPSEEK) to true, factory.configs.single())
        assertTrue("the rest of the reply is cancelled", client.cancelled)
    }

    @Test
    fun `test connection reports a failed request`() = runTest {
        val vm = viewModel(repository())
        advanceUntilIdle()
        vm.onTestConnection()
        client.events.send(LlmEvent.Error(LlmErrorKind.InvalidKey))
        advanceUntilIdle()
        assertEquals(ConnectionTestState.Failed(LlmErrorKind.InvalidKey), vm.uiState.value.connectionTest)
    }

    @Test
    fun `test connection reports settings that cannot make a client`() = runTest {
        factory.result = LlmClientResult.Unavailable(LlmErrorKind.KeyStoreUnavailable)
        val vm = viewModel(repository())
        advanceUntilIdle()
        vm.onTestConnection()
        advanceUntilIdle()
        assertEquals(
            ConnectionTestState.Failed(LlmErrorKind.KeyStoreUnavailable),
            vm.uiState.value.connectionTest,
        )
    }

    @Test
    fun `editing settings cancels a running check`() = runTest {
        val vm = viewModel(repository())
        advanceUntilIdle()
        vm.onTestConnection()
        advanceUntilIdle()
        vm.onModelChanged("other")
        client.events.send(LlmEvent.Done(null))
        advanceUntilIdle()
        assertEquals(ConnectionTestState.Idle, vm.uiState.value.connectionTest)
        assertTrue(client.cancelled)
    }

    @Test
    fun `fetching models works without a model and picking one saves it`() = runTest {
        val repo = repository()
        client.models = ModelListResult.Success(listOf("gpt-a", "gpt-b"))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onProviderSelected(ProviderType.OPENAI)
        advanceUntilIdle()

        vm.onFetchModels()
        assertEquals(ModelFetchState.Loading, vm.uiState.value.modelFetch)
        advanceUntilIdle()
        assertEquals(ModelFetchState.Loaded(listOf("gpt-a", "gpt-b")), vm.uiState.value.modelFetch)
        assertEquals(false, factory.configs.single().second)

        vm.onModelPicked("gpt-b")
        advanceUntilIdle()
        assertEquals(ModelFetchState.Idle, vm.uiState.value.modelFetch)
        assertEquals("gpt-b", repo.providerConfig(ProviderType.OPENAI).first().model)
    }

    @Test
    fun `fetching models reports failures and the picker can be dismissed`() = runTest {
        client.models = ModelListResult.Failure(LlmErrorKind.RateLimited)
        val vm = viewModel(repository())
        advanceUntilIdle()
        vm.onFetchModels()
        advanceUntilIdle()
        assertEquals(ModelFetchState.Failed(LlmErrorKind.RateLimited), vm.uiState.value.modelFetch)

        factory.result = LlmClientResult.Unavailable(LlmErrorKind.MissingKey)
        vm.onFetchModels()
        advanceUntilIdle()
        assertEquals(ModelFetchState.Failed(LlmErrorKind.MissingKey), vm.uiState.value.modelFetch)

        vm.onDismissModelPicker()
        assertEquals(ModelFetchState.Idle, vm.uiState.value.modelFetch)
    }

    private companion object {
        const val PLACEHOLDER = PLACEHOLDER_KEY
    }
}
