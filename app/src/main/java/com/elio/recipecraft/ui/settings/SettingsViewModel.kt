package com.elio.recipecraft.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.llm.LlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientResult
import com.elio.recipecraft.data.llm.LlmErrorKind
import com.elio.recipecraft.data.llm.LlmEvent
import com.elio.recipecraft.data.llm.LlmMessage
import com.elio.recipecraft.data.llm.LlmRequest
import com.elio.recipecraft.data.llm.ModelListResult
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
import kotlinx.coroutines.Job
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

/** The state of the "Test connection" check. */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Running : ConnectionTestState
    data object Succeeded : ConnectionTestState
    data class Failed(val kind: LlmErrorKind) : ConnectionTestState
}

/** The state of "Fetch models"; [Loaded] shows the picker. */
sealed interface ModelFetchState {
    data object Idle : ModelFetchState
    data object Loading : ModelFetchState
    data class Loaded(val models: List<String>) : ModelFetchState
    data class Failed(val kind: LlmErrorKind) : ModelFetchState
}

/** The parts of the provider settings that are saved separately, so each save can fail on its own. */
enum class SettingsField { ACTIVE_PROVIDER, PROVIDER_CONFIG, API_KEY }

data class SettingsUiState(
    val language: AppLanguage = AppLanguage.FOLLOW_SYSTEM,
    val activeProvider: ProviderType = ProviderDefaults.activeProvider,
    val config: ProviderConfig = ProviderDefaults.config(ProviderDefaults.activeProvider),
    val apiKey: Secret = Secret.EMPTY,
    val keyVisible: Boolean = false,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    /** The fields whose last save failed; each stays until that field saves. */
    val failedSaves: Set<SettingsField> = emptySet(),
    val connectionTest: ConnectionTestState = ConnectionTestState.Idle,
    val modelFetch: ModelFetchState = ModelFetchState.Idle,
) {
    val problems: Set<ConfigProblem> get() = config.problems()

    val saveFailed: Boolean get() = failedSaves.isNotEmpty()

    /**
     * The provider fields take edits only once the provider's saved settings are loaded: after a
     * failed load they show defaults, and saving those would overwrite what is stored.
     */
    val editable: Boolean get() = !loading && !loadFailed
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val languageSettings: AppLanguageSettings,
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val clientFactory: LlmClientFactory,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(language = languageSettings.choice))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Loads and saves run one at a time in order, so a slow write can never land after a newer one.
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // Network checks run outside the queue so a slow provider never holds up saves.
    private var networkJob: Job? = null

    // Bumped on every edit, so a check queued before the edit never starts.
    private var checkGeneration = 0

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
        resetNetworkChecks()
        _uiState.update { it.copy(activeProvider = type, loading = true, keyVisible = false) }
        enqueue {
            save(SettingsField.ACTIVE_PROVIDER) { settingsRepository.setActiveProvider(type) }
            loadProvider(type)
        }
    }

    /** Retry after a failed load. */
    fun onRetryLoad() = retryFailedLoad()

    fun onModelChanged(model: String) = updateConfig { it.copy(model = model) }

    fun onBaseUrlChanged(baseUrl: String) = updateConfig { it.copy(baseUrl = baseUrl) }

    fun onProtocolSelected(protocol: Protocol) = updateConfig { it.copy(protocol = protocol) }

    fun onApiKeyChanged(key: String) {
        if (_uiState.value.loadFailed) return
        val type = _uiState.value.activeProvider
        resetNetworkChecks()
        _uiState.update { it.copy(apiKey = Secret(key)) }
        enqueue { save(SettingsField.API_KEY) { secretStore.setKey(type, key) } }
    }

    fun onClearApiKey() = onApiKeyChanged("")

    fun onToggleKeyVisibility() {
        _uiState.update { it.copy(keyVisible = !it.keyVisible) }
    }

    /** Sends a one-line prompt and succeeds on the first streamed token; the rest is cancelled. */
    fun onTestConnection() = runNetworkCheck(
        start = { it.copy(connectionTest = ConnectionTestState.Running) },
    ) { config ->
        val outcome = when (val result = clientFactory.forConfig(config)) {
            is LlmClientResult.Unavailable -> ConnectionTestState.Failed(result.kind)
            is LlmClientResult.Ready -> when (val event = result.client.stream(PING).first()) {
                is LlmEvent.Error -> ConnectionTestState.Failed(event.kind)
                // A check reports a rate limit at once rather than waiting to try again.
                is LlmEvent.Retrying -> ConnectionTestState.Failed(LlmErrorKind.RateLimited)
                is LlmEvent.Delta, is LlmEvent.Done -> ConnectionTestState.Succeeded
            }
        }
        _uiState.update { it.copy(connectionTest = outcome) }
    }

    fun onFetchModels() = runNetworkCheck(
        start = { it.copy(modelFetch = ModelFetchState.Loading) },
    ) { config ->
        val outcome = when (val result = clientFactory.forConfig(config, requireModel = false)) {
            is LlmClientResult.Unavailable -> ModelFetchState.Failed(result.kind)
            is LlmClientResult.Ready -> when (val models = result.client.listModels()) {
                is ModelListResult.Success -> ModelFetchState.Loaded(models.models)
                is ModelListResult.Failure -> ModelFetchState.Failed(models.kind)
            }
        }
        _uiState.update { it.copy(modelFetch = outcome) }
    }

    fun onModelPicked(model: String) = onModelChanged(model)

    fun onDismissModelPicker() {
        _uiState.update { it.copy(modelFetch = ModelFetchState.Idle) }
    }

    /** Waits for queued saves, so the check uses the key and config the user just entered. */
    private fun runNetworkCheck(
        start: (SettingsUiState) -> SettingsUiState,
        check: suspend (ProviderConfig) -> Unit,
    ) {
        resetNetworkChecks()
        val generation = checkGeneration
        _uiState.update(start)
        enqueue {
            if (generation != checkGeneration) return@enqueue
            val config = _uiState.value.config
            networkJob = viewModelScope.launch { check(config) }
        }
    }

    private fun resetNetworkChecks() {
        checkGeneration++
        networkJob?.cancel()
        networkJob = null
        _uiState.update {
            it.copy(connectionTest = ConnectionTestState.Idle, modelFetch = ModelFetchState.Idle)
        }
    }

    private fun updateConfig(change: (ProviderConfig) -> ProviderConfig) {
        if (_uiState.value.loadFailed) return
        resetNetworkChecks()
        val config = change(_uiState.value.config)
        _uiState.update { it.copy(config = config) }
        enqueue { save(SettingsField.PROVIDER_CONFIG) { settingsRepository.updateProviderConfig(config) } }
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
            // On failure, show defaults for this provider, read-only until a retry loads the real ones.
            val (config, key) = loaded ?: (ProviderDefaults.config(type) to "")
            it.copy(
                activeProvider = type,
                config = config,
                apiKey = Secret(key),
                loading = false,
                loadFailed = loaded == null,
                // A failed key or config save was for the provider shown before; what is stored now shows instead.
                failedSaves = if (loaded == null) it.failedSaves else it.failedSaves - PROVIDER_FIELDS,
            )
        }
    }

    private suspend fun save(field: SettingsField, write: suspend () -> Unit) {
        val saved = try {
            write()
            true
        } catch (_: IOException) {
            false
        } catch (_: GeneralSecurityException) {
            false
        }
        _uiState.update { it.copy(failedSaves = if (saved) it.failedSaves - field else it.failedSaves + field) }
    }

    private fun enqueue(operation: suspend () -> Unit) {
        operations.trySend(operation)
    }

    private companion object {
        val PING = LlmRequest(listOf(LlmMessage(LlmMessage.Role.USER, "ping")))

        /** The fields saved per provider. */
        val PROVIDER_FIELDS = setOf(SettingsField.PROVIDER_CONFIG, SettingsField.API_KEY)
    }
}
