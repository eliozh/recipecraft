package com.elio.recipecraft.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.recipecraft.R
import com.elio.recipecraft.data.nutrition.NutritionTableSummary
import com.elio.recipecraft.data.nutrition.TableImportResult
import com.elio.recipecraft.ui.common.AppError
import com.elio.recipecraft.ui.common.ErrorMessages

/** Some providers label CSV files as plain text or as unknown binary, so those are offered too. */
private val CSV_MIME_TYPES = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")

@Composable
fun NutritionTablesSection(
    modifier: Modifier = Modifier,
    viewModel: NutritionTablesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onFilePicked(uri?.toString())
    }
    NutritionTables(
        state = state,
        onImport = { picker.launch(CSV_MIME_TYPES) },
        onPendingNameChanged = viewModel::onPendingNameChanged,
        onConfirmImport = viewModel::onConfirmImport,
        onDismissPendingImport = viewModel::onDismissPendingImport,
        onDismissImportSummary = viewModel::onDismissImportSummary,
        onMoveUp = viewModel::onMoveUp,
        onMoveDown = viewModel::onMoveDown,
        onDeleteRequested = viewModel::onDeleteRequested,
        onDeleteConfirmed = viewModel::onDeleteConfirmed,
        onDeleteDismissed = viewModel::onDeleteDismissed,
        modifier = modifier,
    )
}

@Composable
fun NutritionTables(
    state: NutritionTablesUiState,
    onImport: () -> Unit,
    onPendingNameChanged: (String) -> Unit,
    onConfirmImport: () -> Unit,
    onDismissPendingImport: () -> Unit,
    onDismissImportSummary: () -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onDeleteRequested: (NutritionTableSummary) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.settings_nutrition_tables),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp).semantics { heading() },
        )
        Text(
            text = stringResource(R.string.nutrition_tables_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.nutrition_tables_format),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(
            onClick = onImport,
            enabled = !state.importing,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(stringResource(R.string.nutrition_tables_import))
        }
        if (state.importing) {
            Text(stringResource(R.string.nutrition_tables_importing), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        state.lastImport?.let { (name, result) -> ImportSummary(name, result, onDismissImportSummary) }
        if (state.actionFailed) ErrorText(stringResource(AppError.TABLES_ACTION_FAILED.message))
        if (state.loadFailed) ErrorText(stringResource(AppError.TABLES_LOAD_FAILED.message))

        if (state.tables.isEmpty() && !state.loadFailed) {
            Text(
                text = stringResource(R.string.nutrition_tables_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        state.tables.forEachIndexed { index, table ->
            TableRow(
                table = table,
                canMoveUp = index > 0,
                canMoveDown = index < state.tables.lastIndex,
                onMoveUp = { onMoveUp(table.id) },
                onMoveDown = { onMoveDown(table.id) },
                onDelete = { onDeleteRequested(table) },
            )
        }
    }

    state.pendingImport?.let { pending ->
        NameDialog(
            name = pending.name,
            onNameChanged = onPendingNameChanged,
            onConfirm = onConfirmImport,
            onDismiss = onDismissPendingImport,
        )
    }
    state.confirmDelete?.let { table ->
        AlertDialog(
            onDismissRequest = onDeleteDismissed,
            title = { Text(stringResource(R.string.nutrition_tables_delete_title)) },
            text = { Text(stringResource(R.string.nutrition_tables_delete_message, table.name)) },
            confirmButton = {
                TextButton(onClick = onDeleteConfirmed) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = onDeleteDismissed) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun TableRow(
    table: NutritionTableSummary,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(table.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = pluralStringResource(R.plurals.nutrition_tables_food_count, table.foodCount, table.foodCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(painterResource(R.drawable.ic_arrow_up), stringResource(R.string.nutrition_tables_move_up, table.name))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(painterResource(R.drawable.ic_arrow_down), stringResource(R.string.nutrition_tables_move_down, table.name))
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.nutrition_tables_delete, table.name))
        }
    }
}

@Composable
private fun ImportSummary(name: String, result: TableImportResult, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when (result) {
            is TableImportResult.Imported -> {
                Text(
                    text = pluralStringResource(R.plurals.nutrition_tables_imported, result.imported, result.imported, name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (result.skipped > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.nutrition_tables_skipped, result.skipped, result.skipped),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                result.problems.forEach { problem ->
                    Text(
                        text = stringResource(R.string.nutrition_tables_row_problem, problem.row, stringResource(ErrorMessages.row(problem.error))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is TableImportResult.Failed -> {
                ErrorText(stringResource(ErrorMessages.tableImport(result.error)))
                if (result.skipped > 0) {
                    ErrorText(pluralStringResource(R.plurals.nutrition_tables_skipped, result.skipped, result.skipped))
                }
            }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
    }
}

@Composable
private fun NameDialog(name: String, onNameChanged: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nutrition_tables_name_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChanged,
                label = { Text(stringResource(R.string.nutrition_tables_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
