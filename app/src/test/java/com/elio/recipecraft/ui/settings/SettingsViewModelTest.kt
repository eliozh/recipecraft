package com.elio.recipecraft.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.data.settings.Protocol
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.data.settings.ProviderDefaults
import com.elio.recipecraft.data.settings.ProviderType
import com.elio.recipecraft.data.settings.SecretStore
import com.elio.recipecraft.data.settings.SettingsRepository
import com.elio.recipecraft.domain.AppLanguage
import com.elio.recipecraft.domain.Language
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    private fun viewModel(repository: SettingsRepository) = SettingsViewModel(language, repository, secrets)

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

    private class FakeSecretStore : SecretStore {
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

    private class InMemoryDataStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class FakeLanguageSettings : AppLanguageSettings {
        var current = AppLanguage.FOLLOW_SYSTEM
        override val choice get() = current
        override val resolvedLanguage = Language.EN
        override fun setChoice(choice: AppLanguage) {
            current = choice
        }
    }

    private companion object {
        const val PLACEHOLDER = "sk-test-PLACEHOLDER-1234"
    }
}
