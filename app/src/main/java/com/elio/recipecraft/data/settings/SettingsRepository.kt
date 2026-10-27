package com.elio.recipecraft.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Non-secret settings: the active provider and each provider's config, stored separately per
 * provider so switching back and forth keeps what the user entered. API keys go to [SecretStore].
 */
@Singleton
class SettingsRepository @Inject constructor(
    @param:SettingsDataStore private val dataStore: DataStore<Preferences>,
) {

    val activeProvider: Flow<ProviderType> = dataStore.data
        .map { prefs -> prefs[ACTIVE_PROVIDER]?.let(::enumOrNull) ?: ProviderDefaults.activeProvider }
        .distinctUntilChanged()

    suspend fun setActiveProvider(type: ProviderType) {
        dataStore.edit { it[ACTIVE_PROVIDER] = type.name }
    }

    fun providerConfig(type: ProviderType): Flow<ProviderConfig> = dataStore.data
        .map { prefs -> prefs.configFor(type) }
        .distinctUntilChanged()

    /** Saves [config] with its text fields trimmed, matching what [problems] validates. */
    suspend fun updateProviderConfig(config: ProviderConfig) {
        dataStore.edit { prefs -> prefs.storeConfig(config) }
    }

    private fun Preferences.configFor(type: ProviderType): ProviderConfig {
        val defaults = ProviderDefaults.config(type)
        val model = this[modelKey(type)] ?: defaults.model
        if (!ProviderDefaults.hasEditableEndpoint(type)) return defaults.copy(model = model)
        return defaults.copy(
            baseUrl = this[baseUrlKey(type)] ?: defaults.baseUrl,
            protocol = this[protocolKey(type)]?.let { enumOrNull<Protocol>(it) } ?: defaults.protocol,
            model = model,
        )
    }

    private fun MutablePreferences.storeConfig(config: ProviderConfig) {
        val type = config.type
        this[modelKey(type)] = config.model.trim()
        // Built-in endpoints always come from ProviderDefaults, so only custom ones are stored.
        if (ProviderDefaults.hasEditableEndpoint(type)) {
            this[baseUrlKey(type)] = config.baseUrl.trim()
            this[protocolKey(type)] = config.protocol.name
        }
    }

    private companion object {
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")

        fun modelKey(type: ProviderType) = stringPreferencesKey("provider.${type.name}.model")
        fun baseUrlKey(type: ProviderType) = stringPreferencesKey("provider.${type.name}.base_url")
        fun protocolKey(type: ProviderType) = stringPreferencesKey("provider.${type.name}.protocol")

        inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
            enumValues<T>().firstOrNull { it.name == name }
    }
}
