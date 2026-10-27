package com.elio.recipecraft.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.elio.recipecraft.di.IoDispatcher
import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The only place API keys are kept. Implementations must never log or expose the plaintext. */
interface SecretStore {
    suspend fun getKey(type: ProviderType): String?
    suspend fun setKey(type: ProviderType, key: String)
    suspend fun clear(type: ProviderType)
}

/** Supplies the [Aead] that seals API keys; on a device it is bound to an Android Keystore key. */
fun interface AeadProvider {
    fun aead(): Aead
}

/**
 * Stores each provider's API key as Base64 AEAD ciphertext in its own DataStore. The provider
 * name is the associated data, so a ciphertext copied to another provider's slot fails to open.
 */
@Singleton
class TinkSecretStore @Inject constructor(
    @param:SecretsDataStore private val dataStore: DataStore<Preferences>,
    private val aeadProvider: AeadProvider,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SecretStore {

    // Creating the Aead touches the Keystore, so it happens once, lazily, off the main thread.
    private val aead by lazy { aeadProvider.aead() }

    override suspend fun getKey(type: ProviderType): String? = withContext(ioDispatcher) {
        val sealed = dataStore.data.first()[keyFor(type)] ?: return@withContext null
        try {
            aead.decrypt(Base64.getDecoder().decode(sealed), associatedData(type)).decodeToString()
        } catch (_: GeneralSecurityException) {
            // A keyset or Keystore key that no longer matches (e.g. after a restore) reads as no key.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override suspend fun setKey(type: ProviderType, key: String) = withContext(ioDispatcher) {
        if (key.isEmpty()) return@withContext clear(type)
        val sealed = aead.encrypt(key.encodeToByteArray(), associatedData(type))
        dataStore.edit { it[keyFor(type)] = Base64.getEncoder().encodeToString(sealed) }
        Unit
    }

    override suspend fun clear(type: ProviderType) = withContext(ioDispatcher) {
        dataStore.edit { it.remove(keyFor(type)) }
        Unit
    }

    private fun keyFor(type: ProviderType) = stringPreferencesKey("api_key.${type.name}")

    private fun associatedData(type: ProviderType) = "recipecraft.api_key.${type.name}".encodeToByteArray()
}
