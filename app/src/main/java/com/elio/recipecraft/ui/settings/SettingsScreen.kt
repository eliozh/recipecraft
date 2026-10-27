package com.elio.recipecraft.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        LanguagePicker(selected = uiState.language, onSelect = viewModel::onLanguageSelected)
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        ProviderSettings(
            state = uiState,
            onProviderSelected = viewModel::onProviderSelected,
            onModelChanged = viewModel::onModelChanged,
            onBaseUrlChanged = viewModel::onBaseUrlChanged,
            onProtocolSelected = viewModel::onProtocolSelected,
            onApiKeyChanged = viewModel::onApiKeyChanged,
            onToggleKeyVisibility = viewModel::onToggleKeyVisibility,
            onClearApiKey = viewModel::onClearApiKey,
            onTestConnection = viewModel::onTestConnection,
            onFetchModels = viewModel::onFetchModels,
            onModelPicked = viewModel::onModelPicked,
            onDismissModelPicker = viewModel::onDismissModelPicker,
        )
    }
}
