package com.elio.recipecraft.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.domain.GenerationRequest
import com.elio.recipecraft.domain.GenerationScope
import com.elio.recipecraft.ui.recipe.label

/**
 * The panel above the chat input that asks for full-day sets or single meals and how many. It
 * folds down to its title so free-form chat keeps the room.
 */
@Composable
fun GenerationComposer(
    state: ComposerState,
    sending: Boolean,
    onScopeChanged: (GenerationScope) -> Unit,
    onCountChanged: (Int) -> Unit,
    onPreferencesChanged: (String) -> Unit,
    onExpandedChanged: (Boolean) -> Unit,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().testTag(ComposerTestTags.COMPOSER),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = state.expanded, role = Role.Button, onValueChange = onExpandedChanged)
                    .padding(vertical = 12.dp)
                    .testTag(ComposerTestTags.TOGGLE),
            ) {
                Text(
                    stringResource(R.string.composer_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = stringResource(
                        if (state.expanded) R.string.composer_hide else R.string.composer_show,
                    ),
                    modifier = Modifier.rotate(if (state.expanded) 180f else 0f),
                )
            }
            AnimatedVisibility(state.expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    ScopePicker(state.scope, onScopeChanged)
                    CountStepper(state, onCountChanged)
                    OutlinedTextField(
                        value = state.preferences,
                        onValueChange = onPreferencesChanged,
                        label = { Text(stringResource(R.string.composer_preferences)) },
                        placeholder = { Text(stringResource(R.string.composer_preferences_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().testTag(ComposerTestTags.PREFERENCES),
                    )
                    Button(
                        onClick = onGenerate,
                        enabled = !sending,
                        modifier = Modifier.align(Alignment.End).testTag(ComposerTestTags.GENERATE),
                    ) {
                        Text(stringResource(R.string.composer_generate))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopePicker(selected: GenerationScope, onScopeChanged: (GenerationScope) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.composer_scope),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GenerationScope.entries.forEach { scope ->
                FilterChip(
                    selected = scope == selected,
                    onClick = { onScopeChanged(scope) },
                    label = { Text(scopeLabel(scope)) },
                )
            }
        }
    }
}

@Composable
private fun CountStepper(state: ComposerState, onCountChanged: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.composer_count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        OutlinedIconButton(
            onClick = { onCountChanged(state.count - 1) },
            enabled = state.count > GenerationRequest.COUNT_RANGE.first,
            modifier = Modifier.testTag(ComposerTestTags.DECREASE),
        ) {
            Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.composer_count_decrease))
        }
        Text(
            pluralStringResource(
                if (state.scope == GenerationScope.FULL_DAY_SET) R.plurals.composer_count_sets else R.plurals.composer_count_recipes,
                state.count,
                state.count,
            ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .widthIn(min = 72.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(ComposerTestTags.COUNT),
        )
        OutlinedIconButton(
            onClick = { onCountChanged(state.count + 1) },
            enabled = state.count < GenerationRequest.COUNT_RANGE.last,
            modifier = Modifier.testTag(ComposerTestTags.INCREASE),
        ) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.composer_count_increase))
        }
    }
}

@Composable
internal fun scopeLabel(scope: GenerationScope): String =
    stringResource(scope.mealType?.label ?: R.string.composer_scope_full_day)

/** "Full-day set × 2 · vegetarian": what a composer turn asked for, shown in its bubble. */
@Composable
internal fun generationSummary(request: GenerationRequest): String {
    val summary = stringResource(R.string.generation_summary, scopeLabel(request.scope), request.count)
    return if (request.preferences.isBlank()) {
        summary
    } else {
        stringResource(R.string.generation_summary_preferences, summary, request.preferences)
    }
}

/** Stable handles for UI tests. */
object ComposerTestTags {
    const val COMPOSER = "composer"
    const val TOGGLE = "composer_toggle"
    const val COUNT = "composer_count"
    const val DECREASE = "composer_decrease"
    const val INCREASE = "composer_increase"
    const val PREFERENCES = "composer_preferences"
    const val GENERATE = "composer_generate"
}
