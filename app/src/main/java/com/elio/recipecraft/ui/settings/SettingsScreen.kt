package com.elio.recipecraft.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.recipecraft.R

@OptIn(ExperimentalMaterial3Api::class)
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
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_settings)) })
        SettingsContent(uiState, viewModel)
    }
}

@Composable
private fun SettingsContent(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    // On a wide window the settings keep a readable width, centred.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
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
                onRetryLoad = viewModel::onRetryLoad,
            )
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            NutritionTablesSection()
        }
    }
}
