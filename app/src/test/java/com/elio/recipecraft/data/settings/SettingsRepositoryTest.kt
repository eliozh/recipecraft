package com.elio.recipecraft.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.elio.recipecraft.domain.Language
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.repository(file: File = tmp.newFile("settings.preferences_pb").also { it.delete() }) =
        SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })

    @Test
    fun `fresh install uses defaults`() = runTest {
        val repo = repository()
        assertEquals(ProviderType.CLAUDE, repo.activeProvider.first())
        assertEquals("claude-sonnet-5-5", repo.providerConfig(ProviderType.CLAUDE).first().model)
        assertEquals("deepseek-chat", repo.providerConfig(ProviderType.DEEPSEEK).first().model)
        assertEquals("", repo.providerConfig(ProviderType.OPENAI).first().model)
        assertEquals(ProviderDefaults.config(ProviderType.CUSTOM), repo.providerConfig(ProviderType.CUSTOM).first())
    }

    @Test
    fun `active provider is saved`() = runTest {
        val repo = repository()
        repo.setActiveProvider(ProviderType.DEEPSEEK)
        assertEquals(ProviderType.DEEPSEEK, repo.activeProvider.first())
    }

    @Test
    fun `each provider keeps its own settings`() = runTest {
        val repo = repository()
        repo.updateProviderConfig(ProviderDefaults.config(ProviderType.OPENAI).copy(model = "gpt-test"))
        repo.updateProviderConfig(ProviderDefaults.config(ProviderType.DEEPSEEK).copy(model = "deepseek-reasoner"))
        val custom = ProviderConfig(ProviderType.CUSTOM, "https://llm.example.com/v1", Protocol.ANTHROPIC, "local")
        repo.updateProviderConfig(custom)

        assertEquals("gpt-test", repo.providerConfig(ProviderType.OPENAI).first().model)
        assertEquals("deepseek-reasoner", repo.providerConfig(ProviderType.DEEPSEEK).first().model)
        assertEquals("claude-sonnet-5-5", repo.providerConfig(ProviderType.CLAUDE).first().model)
        assertEquals(custom, repo.providerConfig(ProviderType.CUSTOM).first())
    }

    @Test
    fun `text fields are trimmed before saving`() = runTest {
        val repo = repository()
        repo.updateProviderConfig(ProviderConfig(ProviderType.CUSTOM, "  https://llm.example.com/v1 ", Protocol.ANTHROPIC, " m\t"))
        repo.updateProviderConfig(ProviderDefaults.config(ProviderType.OPENAI).copy(model = " gpt-test "))

        assertEquals(
            ProviderConfig(ProviderType.CUSTOM, "https://llm.example.com/v1", Protocol.ANTHROPIC, "m"),
            repo.providerConfig(ProviderType.CUSTOM).first(),
        )
        assertEquals("gpt-test", repo.providerConfig(ProviderType.OPENAI).first().model)
    }

    @Test
    fun `a cleared model stays empty instead of reverting to the default`() = runTest {
        val repo = repository()
        repo.updateProviderConfig(ProviderDefaults.config(ProviderType.CLAUDE).copy(model = ""))
        assertEquals("", repo.providerConfig(ProviderType.CLAUDE).first().model)
    }

    @Test
    fun `built-in providers ignore endpoint overrides`() = runTest {
        val repo = repository()
        repo.updateProviderConfig(
            ProviderConfig(ProviderType.DEEPSEEK, "https://evil.example.com", Protocol.ANTHROPIC, "deepseek-chat"),
        )
        assertEquals(ProviderDefaults.config(ProviderType.DEEPSEEK), repo.providerConfig(ProviderType.DEEPSEEK).first())
    }

    @Test
    fun `settings survive reopening the file`() = runTest {
        val file = File(tmp.root, "reopen.preferences_pb")
        val custom = ProviderConfig(ProviderType.CUSTOM, "https://llm.example.com", Protocol.ANTHROPIC, "m")
        // Only one DataStore may own a file at a time, so the first one is closed before reopening.
        val firstJob = Job()
        val firstStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + firstJob),
        ) { file }
        SettingsRepository(firstStore).apply {
            setActiveProvider(ProviderType.CUSTOM)
            updateProviderConfig(custom)
        }
        firstJob.cancelAndJoin()

        val reopened = repository(file)
        assertEquals(ProviderType.CUSTOM, reopened.activeProvider.first())
        assertEquals(custom, reopened.providerConfig(ProviderType.CUSTOM).first())
    }

    @Test
    fun `unknown stored enum values fall back to defaults`() = runTest {
        val file = File(tmp.root, "corrupt.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        store.updateData {
            it.toMutablePreferences().apply {
                this[stringPreferencesKey("active_provider")] = "GEMINI"
                this[stringPreferencesKey("provider.CUSTOM.protocol")] = "GRPC"
            }
        }
        val repo = SettingsRepository(store)
        assertEquals(ProviderType.CLAUDE, repo.activeProvider.first())
        assertEquals(Protocol.OPENAI_COMPATIBLE, repo.providerConfig(ProviderType.CUSTOM).first().protocol)
    }

    @Test
    fun `the last language is stored`() = runTest {
        val repo = repository()
        assertEquals(null, repo.lastLanguage.first())
        repo.setLastLanguage(Language.ZH)
        assertEquals(Language.ZH, repo.lastLanguage.first())
    }
}
