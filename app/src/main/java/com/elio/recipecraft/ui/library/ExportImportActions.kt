package com.elio.recipecraft.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.data.recipe.RecipeImportError
import com.elio.recipecraft.data.recipe.RecipeImportResult

/** The JSON type Export asks the picker for. */
internal const val EXPORT_MIME_TYPE = "application/json"

/** Some providers label a `.json` file as plain text or as unknown binary, so Import offers those too. */
internal val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

/** The Library's overflow menu: Export all and Import. Both are off while a transfer runs. */
@Composable
internal fun LibraryMenu(
    canExport: Boolean,
    busy: Boolean,
    onExportAll: () -> Unit,
    onImport: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag(LibraryTestTags.MENU)) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.library_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_export_all)) },
                onClick = {
                    expanded = false
                    onExportAll()
                },
                enabled = canExport && !busy,
                modifier = Modifier.testTag(LibraryTestTags.EXPORT_ALL),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_import)) },
                onClick = {
                    expanded = false
                    onImport()
                },
                enabled = !busy,
                modifier = Modifier.testTag(LibraryTestTags.IMPORT),
            )
        }
    }
}

/** What an import added and skipped, or why the file was rejected. */
@Composable
internal fun ImportSummaryDialog(result: RecipeImportResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag(LibraryTestTags.IMPORT_SUMMARY)) {
                when (result) {
                    is RecipeImportResult.Imported -> {
                        Text(pluralStringResource(R.plurals.library_import_imported, result.imported, result.imported))
                        if (result.duplicates > 0) {
                            Text(pluralStringResource(R.plurals.library_import_duplicates, result.duplicates, result.duplicates))
                        }
                        if (result.invalid > 0) {
                            Text(pluralStringResource(R.plurals.library_import_invalid, result.invalid, result.invalid))
                        }
                    }
                    is RecipeImportResult.Failed -> Text(stringResource(result.error.textRes))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@get:StringRes
internal val RecipeImportError.textRes: Int
    get() = when (this) {
        RecipeImportError.TOO_LARGE -> R.string.recipe_import_error_too_large
        RecipeImportError.NOT_AN_EXPORT -> R.string.recipe_import_error_not_an_export
        RecipeImportError.NEWER_VERSION -> R.string.recipe_import_error_newer_version
        RecipeImportError.UNREADABLE -> R.string.import_error_unreadable
        RecipeImportError.SAVE_FAILED -> R.string.recipe_import_error_save_failed
    }
