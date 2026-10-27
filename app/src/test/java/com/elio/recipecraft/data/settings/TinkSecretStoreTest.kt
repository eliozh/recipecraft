package com.elio.recipecraft.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.File
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TinkSecretStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val aead = newAead()

    private val file by lazy { File(tmp.root, "secrets.preferences_pb") }

    private fun TestScope.dataStore() = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }

    private fun TestScope.store(aeadProvider: AeadProvider = AeadProvider { aead }) =
        TinkSecretStore(dataStore(), aeadProvider, StandardTestDispatcher(testScheduler))

    @Test
    fun `set get clear round trip`() = runTest {
        val store = store()
        assertNull(store.getKey(ProviderType.OPENAI))

        store.setKey(ProviderType.OPENAI, PLACEHOLDER)
        assertEquals(PLACEHOLDER, store.getKey(ProviderType.OPENAI))

        store.clear(ProviderType.OPENAI)
        assertNull(store.getKey(ProviderType.OPENAI))
    }

    @Test
    fun `keys are kept per provider`() = runTest {
        val store = store()
        store.setKey(ProviderType.OPENAI, PLACEHOLDER)
        store.setKey(ProviderType.DEEPSEEK, OTHER_PLACEHOLDER)
        store.clear(ProviderType.OPENAI)

        assertNull(store.getKey(ProviderType.OPENAI))
        assertEquals(OTHER_PLACEHOLDER, store.getKey(ProviderType.DEEPSEEK))
    }

    @Test
    fun `setting an empty key clears it`() = runTest {
        val store = store()
        store.setKey(ProviderType.CLAUDE, PLACEHOLDER)
        store.setKey(ProviderType.CLAUDE, "")
        assertNull(store.getKey(ProviderType.CLAUDE))
    }

    @Test
    fun `plaintext never reaches the file`() = runTest {
        val store = store()
        store.setKey(ProviderType.OPENAI, PLACEHOLDER)
        store.getKey(ProviderType.OPENAI) // the write has completed once a read sees it

        val bytes = file.readBytes()
        assertFalse(bytes.decodeToString().contains("PLACEHOLDER"))
        assertFalse(bytes.decodeToString().contains(PLACEHOLDER.takeLast(8)))
    }

    @Test
    fun `ciphertext moved to another provider does not decrypt`() = runTest {
        val dataStore = dataStore()
        val store = TinkSecretStore(dataStore, { aead }, StandardTestDispatcher(testScheduler))
        store.setKey(ProviderType.OPENAI, PLACEHOLDER)
        val sealed = dataStore.data.first()[stringPreferencesKey("api_key.OPENAI")]!!
        dataStore.updateData {
            it.toMutablePreferences().apply { this[stringPreferencesKey("api_key.DEEPSEEK")] = sealed }
        }
        assertNull(store.getKey(ProviderType.DEEPSEEK))
    }

    @Test
    fun `undecryptable or corrupt values read as no key`() = runTest {
        val dataStore = dataStore()
        TinkSecretStore(dataStore, { aead }, StandardTestDispatcher(testScheduler))
            .setKey(ProviderType.OPENAI, PLACEHOLDER)
        dataStore.updateData {
            it.toMutablePreferences().apply { this[stringPreferencesKey("api_key.CLAUDE")] = "not base64 !" }
        }
        val store = TinkSecretStore(dataStore, { newAead() }, StandardTestDispatcher(testScheduler))

        assertNull(store.getKey(ProviderType.OPENAI))
        assertNull(store.getKey(ProviderType.CLAUDE))
    }

    @Test
    fun `aead is created once`() = runTest {
        var created = 0
        val store = store(AeadProvider { created++; aead })
        store.setKey(ProviderType.OPENAI, PLACEHOLDER)
        store.getKey(ProviderType.OPENAI)
        assertEquals(1, created)
    }

    @Test
    fun `an unavailable keystore is an error, not a missing key`() = runTest {
        val dataStore = dataStore()
        TinkSecretStore(dataStore, { aead }, StandardTestDispatcher(testScheduler))
            .setKey(ProviderType.OPENAI, PLACEHOLDER)
        val store = TinkSecretStore(
            dataStore,
            { throw GeneralSecurityException("Keystore unavailable") },
            StandardTestDispatcher(testScheduler),
        )

        val error = runCatching { store.getKey(ProviderType.OPENAI) }.exceptionOrNull()
        assertTrue(error is GeneralSecurityException)
        // With nothing stored there is nothing to decrypt, so no Keystore is needed.
        assertNull(store.getKey(ProviderType.CLAUDE))
    }

    private fun newAead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, RegistryConfiguration.get())
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private companion object {
        const val PLACEHOLDER = "sk-test-PLACEHOLDER-1234"
        const val OTHER_PLACEHOLDER = "sk-test-PLACEHOLDER-5678"
    }
}
