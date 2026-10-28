package com.elio.recipecraft.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.elio.recipecraft.ui.common.AppError
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
    onRetryLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.editable
    Column(modifier) {
        SectionTitle(R.string.settings_provider)
        RadioGroup(
            options = ProviderType.entries,
            selected = state.activeProvider,
            label = { it.labelRes },
            onSelect = onProviderSelected,
            enabled = !state.loading,
            modifier = Modifier.testTag(ProviderSettingsTestTags.PROVIDERS),
        )
        if (SettingsField.ACTIVE_PROVIDER in state.failedSaves) {
            StatusText(stringResource(AppError.SETTINGS_SAVE_FAILED.message), isError = true, Modifier.padding(horizontal = 16.dp))
        }
        if (state.loadFailed) LoadFailed(onRetryLoad)

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
                    supportingText = {
                        val text = urlError ?: when (state.config.protocol) {
                            Protocol.OPENAI_COMPATIBLE -> R.string.base_url_hint_openai
                            Protocol.ANTHROPIC -> R.string.base_url_hint_anthropic
                        }
                        Text(stringResource(text))
                    },
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
                enabled = enabled,
            )
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            val modelMissing = ConfigProblem.MISSING_MODEL in state.problems
            val configSaveFailed = SettingsField.PROVIDER_CONFIG in state.failedSaves
            OutlinedTextField(
                value = state.config.model,
                onValueChange = onModelChanged,
                enabled = enabled,
                label = { Text(stringResource(R.string.settings_model)) },
                isError = modelMissing || configSaveFailed,
                supportingText = when {
                    configSaveFailed -> {
                        { Text(stringResource(AppError.SETTINGS_SAVE_FAILED.message)) }
                    }
                    modelMissing -> {
                        { Text(stringResource(R.string.model_required)) }
                    }
                    else -> null
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
                    val keySaveFailed = SettingsField.API_KEY in state.failedSaves
                    Text(stringResource(if (keySaveFailed) AppError.SETTINGS_SAVE_FAILED.message else R.string.api_key_stored_hint))
                },
                isError = SettingsField.API_KEY in state.failedSaves,
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
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(ProviderSettingsTestTags.API_KEY),
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
    // Wraps onto two lines when large text leaves no room for both buttons side by side.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
            stringResource(R.string.settings_connection_failed, llmErrorText(test.kind, inSettings = true)) to true
    }
    status?.let { (text, isError) -> StatusText(text, isError) }
    val modelsStatus: Pair<String, Boolean>? = when (val fetch = state.modelFetch) {
        ModelFetchState.Idle -> null
        ModelFetchState.Loading -> stringResource(R.string.settings_models_loading) to false
        is ModelFetchState.Loaded ->
            if (fetch.models.isEmpty()) stringResource(AppError.MODELS_EMPTY.message) to true else null
        is ModelFetchState.Failed ->
            stringResource(R.string.settings_models_failed, llmErrorText(fetch.kind, inSettings = true)) to true
    }
    modelsStatus?.let { (text, isError) -> StatusText(text, isError) }
}

@Composable
private fun StatusText(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The provider's saved settings could not be read; the fields stay read-only until Retry loads them. */
@Composable
private fun LoadFailed(onRetry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).testTag(ProviderSettingsTestTags.LOAD_FAILED),
    ) {
        Text(
            stringResource(AppError.SETTINGS_LOAD_FAILED.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, modifier = Modifier.testTag(ProviderSettingsTestTags.RETRY)) {
            Text(stringResource(R.string.action_retry))
        }
    }
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
                            .heightIn(min = 48.dp)
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

/** A section title, marked as a heading so screen readers can jump between sections. */
@Composable
internal fun SectionTitle(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
    )
}

/** One choice of [options] as radio rows, each a full-width touch target at least 48dp high. */
@Composable
internal fun <T> RadioGroup(
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        enabled = enabled,
                        role = Role.RadioButton,
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                RadioButton(selected = option == selected, onClick = null, enabled = enabled)
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

/** Stable handles for UI tests. */
object ProviderSettingsTestTags {
    const val PROVIDERS = "settings_providers"
    const val API_KEY = "settings_api_key"
    const val LOAD_FAILED = "settings_load_failed"
    const val RETRY = "settings_retry"
}
