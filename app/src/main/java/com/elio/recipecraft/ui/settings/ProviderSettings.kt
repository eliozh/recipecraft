package com.elio.recipecraft.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.data.settings.ConfigProblem
import com.elio.recipecraft.data.settings.Protocol
import com.elio.recipecraft.data.settings.ProviderDefaults
import com.elio.recipecraft.data.settings.ProviderType
import com.elio.recipecraft.ui.common.llmErrorText

@Composable
fun ProviderSettings(
    state: SettingsUiState,
    onProviderSelected: (ProviderType) -> Unit,
    onModelChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onProtocolSelected: (Protocol) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onToggleKeyVisibility: () -> Unit,
    onClearApiKey: () -> Unit,
    onTestConnection: () -> Unit,
    onFetchModels: () -> Unit,
    onModelPicked: (String) -> Unit,
    onDismissModelPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.loading
    Column(modifier) {
        SectionTitle(R.string.settings_provider)
        RadioGroup(
            options = ProviderType.entries,
            selected = state.activeProvider,
            label = { it.labelRes },
            onSelect = onProviderSelected,
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            if (ProviderDefaults.hasEditableEndpoint(state.activeProvider)) {
                val urlError = when {
                    ConfigProblem.MISSING_BASE_URL in state.problems -> R.string.base_url_required
                    ConfigProblem.INVALID_BASE_URL in state.problems -> R.string.base_url_invalid
                    ConfigProblem.INSECURE_BASE_URL in state.problems -> R.string.base_url_insecure
                    else -> null
                }
                OutlinedTextField(
                    value = state.config.baseUrl,
                    onValueChange = onBaseUrlChanged,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.settings_base_url)) },
                    isError = urlError != null,
                    supportingText = urlError?.let { { Text(stringResource(it)) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        if (ProviderDefaults.hasEditableEndpoint(state.activeProvider)) {
            SectionTitle(R.string.settings_protocol)
            RadioGroup(
                options = Protocol.entries,
                selected = state.config.protocol,
                label = { it.labelRes },
                onSelect = onProtocolSelected,
            )
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            val modelMissing = ConfigProblem.MISSING_MODEL in state.problems
            OutlinedTextField(
                value = state.config.model,
                onValueChange = onModelChanged,
                enabled = enabled,
                label = { Text(stringResource(R.string.settings_model)) },
                isError = modelMissing,
                supportingText = if (modelMissing) {
                    { Text(stringResource(R.string.model_required)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            OutlinedTextField(
                value = state.apiKey.plaintext,
                onValueChange = onApiKeyChanged,
                enabled = enabled,
                label = { Text(stringResource(R.string.settings_api_key)) },
                supportingText = {
                    val text = when {
                        state.loadFailed -> R.string.settings_load_failed
                        state.saveFailed -> R.string.settings_save_failed
                        else -> R.string.api_key_stored_hint
                    }
                    Text(stringResource(text))
                },
                isError = state.loadFailed || state.saveFailed,
                singleLine = true,
                visualTransformation = if (state.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    TextButton(onClick = onToggleKeyVisibility, enabled = enabled) {
                        Text(stringResource(if (state.keyVisible) R.string.api_key_hide else R.string.api_key_show))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (state.apiKey.plaintext.isNotEmpty()) {
                TextButton(onClick = onClearApiKey, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.api_key_clear))
                }
            }

            ConnectionChecks(
                state = state,
                enabled = enabled,
                onTestConnection = onTestConnection,
                onFetchModels = onFetchModels,
            )
        }
    }

    (state.modelFetch as? ModelFetchState.Loaded)?.takeIf { it.models.isNotEmpty() }?.let { loaded ->
        ModelPickerDialog(
            models = loaded.models,
            selected = state.config.model,
            onPick = onModelPicked,
            onDismiss = onDismissModelPicker,
        )
    }
}

@Composable
private fun ConnectionChecks(
    state: SettingsUiState,
    enabled: Boolean,
    onTestConnection: () -> Unit,
    onFetchModels: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        OutlinedButton(
            onClick = onTestConnection,
            enabled = enabled && state.connectionTest != ConnectionTestState.Running,
        ) {
            Text(stringResource(R.string.settings_test_connection))
        }
        OutlinedButton(
            onClick = onFetchModels,
            enabled = enabled && state.modelFetch != ModelFetchState.Loading,
        ) {
            Text(stringResource(R.string.settings_fetch_models))
        }
    }
    val status: Pair<String, Boolean>? = when (val test = state.connectionTest) {
        ConnectionTestState.Idle -> null
        ConnectionTestState.Running -> stringResource(R.string.settings_connection_testing) to false
        ConnectionTestState.Succeeded -> stringResource(R.string.settings_connection_ok) to false
        is ConnectionTestState.Failed ->
            stringResource(R.string.settings_connection_failed, llmErrorText(test.kind)) to true
    }
    status?.let { (text, isError) -> StatusText(text, isError) }
    val modelsStatus: Pair<String, Boolean>? = when (val fetch = state.modelFetch) {
        ModelFetchState.Idle -> null
        ModelFetchState.Loading -> stringResource(R.string.settings_models_loading) to false
        is ModelFetchState.Loaded ->
            if (fetch.models.isEmpty()) stringResource(R.string.settings_models_empty) to true else null
        is ModelFetchState.Failed ->
            stringResource(R.string.settings_models_failed, llmErrorText(fetch.kind)) to true
    }
    modelsStatus?.let { (text, isError) -> StatusText(text, isError) }
}

@Composable
private fun StatusText(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_pick_model)) },
        text = {
            LazyColumn(Modifier.selectableGroup()) {
                items(models) { model ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = model == selected,
                                onClick = { onPick(model) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = model == selected, onClick = null)
                        Text(
                            text = model,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun <T> RadioGroup(
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Text(
                    text = stringResource(label(option)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

@get:StringRes
private val ProviderType.labelRes: Int
    get() = when (this) {
        ProviderType.CLAUDE -> R.string.provider_claude
        ProviderType.OPENAI -> R.string.provider_openai
        ProviderType.DEEPSEEK -> R.string.provider_deepseek
        ProviderType.CUSTOM -> R.string.provider_custom
    }

@get:StringRes
private val Protocol.labelRes: Int
    get() = when (this) {
        Protocol.OPENAI_COMPATIBLE -> R.string.protocol_openai
        Protocol.ANTHROPIC -> R.string.protocol_anthropic
    }
