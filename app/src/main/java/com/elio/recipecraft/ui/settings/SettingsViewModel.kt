package com.elio.recipecraft.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.data.settings.ConfigProblem
import com.elio.recipecraft.data.settings.Protocol
import com.elio.recipecraft.data.settings.ProviderConfig
import com.elio.recipecraft.data.settings.ProviderDefaults
import com.elio.recipecraft.data.settings.ProviderType
import com.elio.recipecraft.data.settings.SecretStore
import com.elio.recipecraft.data.settings.SettingsRepository
import com.elio.recipecraft.data.settings.problems
import com.elio.recipecraft.domain.AppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Holds the API key being edited; [toString] never reveals it, so state dumps cannot leak it. */
class Secret(val plaintext: String) {
    override fun equals(other: Any?) = other is Secret && other.plaintext == plaintext
    override fun hashCode() = plaintext.hashCode()
    override fun toString() = "Secret(***)"

    companion object {
        val EMPTY = Secret("")
    }
}

data class SettingsUiState(
    val language: AppLanguage = AppLanguage.FOLLOW_SYSTEM,
    val activeProvider: ProviderType = ProviderDefaults.activeProvider,
    val config: ProviderConfig = ProviderDefaults.config(ProviderDefaults.activeProvider),
    val apiKey: Secret = Secret.EMPTY,
    val keyVisible: Boolean = false,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
) {
    val problems: Set<ConfigProblem> get() = config.problems()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val languageSettings: AppLanguageSettings,
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(language = languageSettings.choice))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Loads and saves run one at a time in order, so a slow write can never land after a newer one.
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (operation in operations) operation()
        }
        enqueue {
            val type = try {
                settingsRepository.activeProvider.first()
            } catch (_: IOException) {
                _uiState.value.activeProvider
            }
            loadProvider(type)
        }
    }

    /** The language can also be changed in system Settings while the app runs, so re-read it. */
    fun onResume() {
        _uiState.update { it.copy(language = languageSettings.choice) }
        retryFailedLoad()
    }

    fun onLanguageSelected(language: AppLanguage) {
        // The platform recreates the activity with the new locale; this ViewModel survives it.
        languageSettings.setChoice(language)
        _uiState.update { it.copy(language = language) }
    }

    fun onProviderSelected(type: ProviderType) {
        if (type == _uiState.value.activeProvider) return retryFailedLoad()
        _uiState.update { it.copy(activeProvider = type, loading = true, keyVisible = false) }
        enqueue {
            save { settingsRepository.setActiveProvider(type) }
            loadProvider(type)
        }
    }

    fun onModelChanged(model: String) = updateConfig { it.copy(model = model) }

    fun onBaseUrlChanged(baseUrl: String) = updateConfig { it.copy(baseUrl = baseUrl) }

    fun onProtocolSelected(protocol: Protocol) = updateConfig { it.copy(protocol = protocol) }

    fun onApiKeyChanged(key: String) {
        val type = _uiState.value.activeProvider
        _uiState.update { it.copy(apiKey = Secret(key), saveFailed = false) }
        enqueue { save { secretStore.setKey(type, key) } }
    }

    fun onClearApiKey() = onApiKeyChanged("")

    fun onToggleKeyVisibility() {
        _uiState.update { it.copy(keyVisible = !it.keyVisible) }
    }

    private fun updateConfig(change: (ProviderConfig) -> ProviderConfig) {
        val config = change(_uiState.value.config)
        _uiState.update { it.copy(config = config, saveFailed = false) }
        enqueue { save { settingsRepository.updateProviderConfig(config) } }
    }

    private fun retryFailedLoad() {
        val state = _uiState.value
        if (!state.loadFailed || state.loading) return
        _uiState.update { it.copy(loading = true) }
        enqueue { loadProvider(state.activeProvider) }
    }

    /** Always clears [SettingsUiState.loading], so a failed read never leaves the fields disabled. */
    private suspend fun loadProvider(type: ProviderType) {
        val loaded = try {
            settingsRepository.providerConfig(type).first() to secretStore.getKey(type).orEmpty()
        } catch (_: IOException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        }
        _uiState.update {
            // On failure, show defaults for this provider so edits go to the right place.
            val (config, key) = loaded ?: (ProviderDefaults.config(type) to "")
            it.copy(
                activeProvider = type,
                config = config,
                apiKey = Secret(key),
                loading = false,
                loadFailed = loaded == null,
            )
        }
    }

    private suspend fun save(write: suspend () -> Unit) {
        try {
            write()
        } catch (_: IOException) {
            _uiState.update { it.copy(saveFailed = true) }
        } catch (_: GeneralSecurityException) {
            _uiState.update { it.copy(saveFailed = true) }
        }
    }

    private fun enqueue(operation: suspend () -> Unit) {
        operations.trySend(operation)
    }
}
