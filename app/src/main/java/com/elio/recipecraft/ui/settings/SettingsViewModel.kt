package com.elio.recipecraft.ui.settings

import androidx.lifecycle.ViewModel
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.domain.AppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(val language: AppLanguage = AppLanguage.FOLLOW_SYSTEM)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val languageSettings: AppLanguageSettings,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(language = languageSettings.choice))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun onLanguageSelected(language: AppLanguage) {
        // The platform recreates the activity with the new locale; this ViewModel survives it.
        languageSettings.setChoice(language)
        _uiState.update { it.copy(language = language) }
    }
}
