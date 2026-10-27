package com.elio.recipecraft.ui.recipe

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step

/**
 * The saved recipe [before] next to the model's version [after], stacked so it reads on a narrow
 * phone: name, servings and calories, then the ingredients and steps with each line marked as
 * added (+), removed (−, struck through) or changed (~, old then new). Nothing is written until
 * Replace or Save as copy; Cancel and dismissing leave the saved recipe as it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModifyPreview(
    before: Recipe,
    after: Recipe,
    saving: Boolean,
    onReplace: () -> Unit,
    onSaveAsCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onCancel() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(ModifyPreviewTestTags.SHEET),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.modify_preview_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            val ingredients = remember(before, after) { ingredientDiff(before.ingredients, after.ingredients) }
            val steps = remember(before, after) { stepDiff(before.steps, after.steps) }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false).testTag(ModifyPreviewTestTags.LIST),
            ) {
                item { FieldChange(R.string.modify_preview_name, before.name, after.name, ModifyPreviewTestTags.NAME) }
                item {
                    FieldChange(
                        R.string.modify_preview_servings,
                        pluralStringResource(R.plurals.recipe_servings, before.servings, before.servings),
                        pluralStringResource(R.plurals.recipe_servings, after.servings, after.servings),
                        ModifyPreviewTestTags.SERVINGS,
                    )
                }
                item {
                    FieldChange(
                        R.string.modify_preview_calories,
                        caloriesText(before.calories),
                        caloriesText(after.calories),
                        ModifyPreviewTestTags.CALORIES,
                    )
                }
                diffSection(R.string.recipe_ingredients, ingredients) { ingredientLine(it) }
                diffSection(R.string.recipe_steps, steps) { stepLine(it) }
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.testTag(ModifyPreviewTestTags.CANCEL)) {
                    Text(stringResource(R.string.action_cancel))
                }
                OutlinedButton(onClick = onSaveAsCopy, enabled = !saving, modifier = Modifier.testTag(ModifyPreviewTestTags.SAVE_AS_COPY)) {
                    Text(stringResource(R.string.action_save_as_copy))
                }
                Button(onClick = onReplace, enabled = !saving, modifier = Modifier.testTag(ModifyPreviewTestTags.REPLACE)) {
                    Text(stringResource(R.string.action_replace))
                }
            }
        }
    }
}

/** A field with one value: shown once when it stayed the same, else Before struck through and After. */
@Composable
private fun FieldChange(@StringRes label: Int, before: String, after: String, tag: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}.testTag(tag)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (before == after) {
            Text(after, style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(
                stringResource(R.string.modify_preview_before, before),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
            )
            Text(
                stringResource(R.string.modify_preview_after, after),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun <T> LazyListScope.diffSection(@StringRes title: Int, entries: List<DiffEntry<T>>, line: @Composable (T) -> String) {
    item {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
        )
    }
    items(entries) { entry -> DiffRow(entry, line) }
}

@Composable
private fun <T> DiffRow(entry: DiffEntry<T>, line: @Composable (T) -> String) {
    val before = entry.before?.let { line(it) }
    val after = entry.after?.let { line(it) }
    val colors = MaterialTheme.colorScheme
    val (marker, background, description, tag) = when (entry.change) {
        Change.SAME -> DiffStyle(" ", Color.Transparent, after.orEmpty(), ModifyPreviewTestTags.SAME)
        Change.ADDED -> DiffStyle("+", colors.primaryContainer, stringResource(R.string.modify_preview_added, after.orEmpty()), ModifyPreviewTestTags.ADDED)
        Change.REMOVED -> DiffStyle("−", colors.errorContainer, stringResource(R.string.modify_preview_removed, before.orEmpty()), ModifyPreviewTestTags.REMOVED)
        Change.CHANGED -> DiffStyle(
            "~",
            colors.secondaryContainer,
            stringResource(R.string.modify_preview_changed, after.orEmpty(), before.orEmpty()),
            ModifyPreviewTestTags.CHANGED,
        )
    }
    Surface(
        color = background,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().testTag(tag).clearAndSetSemantics { contentDescription = description },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(marker, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
            Column {
                if (entry.change == Change.CHANGED || entry.change == Change.REMOVED) {
                    Text(
                        before.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (entry.change == Change.CHANGED) colors.onSurfaceVariant else colors.onErrorContainer,
                        textDecoration = TextDecoration.LineThrough,
                    )
                }
                if (after != null) Text(after, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class DiffStyle(val marker: String, val background: Color, val description: String, val tag: String)

@Composable
private fun caloriesText(calories: Calories): String {
    val total = calories.total ?: return stringResource(R.string.recipe_calories_unknown)
    return pluralStringResource(R.plurals.recipe_calories_total, total, total)
}

@Composable
private fun stepLine(step: Step): String {
    val minutes = step.minutes?.let { " · " + stringResource(R.string.recipe_step_minutes, it) }.orEmpty()
    return "${step.index}. ${step.text}$minutes"
}

/** Stable handles for UI tests. */
object ModifyPreviewTestTags {
    const val SHEET = "modify_preview"
    const val LIST = "modify_preview_list"
    const val NAME = "modify_preview_name"
    const val SERVINGS = "modify_preview_servings"
    const val CALORIES = "modify_preview_calories"
    const val SAME = "modify_preview_same"
    const val ADDED = "modify_preview_added"
    const val REMOVED = "modify_preview_removed"
    const val CHANGED = "modify_preview_changed"
    const val CANCEL = "modify_preview_cancel"
    const val SAVE_AS_COPY = "modify_preview_save_as_copy"
    const val REPLACE = "modify_preview_replace"
}
