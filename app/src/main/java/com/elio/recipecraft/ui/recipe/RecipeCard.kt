package com.elio.recipecraft.ui.recipe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step

/** What a recipe card's save action shows. */
enum class SaveState {
    /** Not in the library: Save. */
    UNSAVED,

    /** In the library as the card shows it: Saved. */
    SAVED,

    /** In the library, but the card has been changed since: Save changes, which replaces the saved copy. */
    CHANGED,
}

/**
 * One recipe with collapsible ingredients, tools and steps. With [onSave] it offers the action
 * [saveState] calls for. With [onModify] it offers Modify with AI, and with [onUndo] an Undo of
 * the last change. A [modified] card is marked as changed. While [busy] (a change is running) the
 * card shows progress and its actions are off.
 */
@Composable
fun RecipeCard(
    recipe: Recipe,
    modifier: Modifier = Modifier,
    saveState: SaveState = SaveState.UNSAVED,
    onSave: (() -> Unit)? = null,
    modified: Boolean = false,
    busy: Boolean = false,
    onModify: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
) {
    Card(modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(RecipeCardTestTags.CARD)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    recipe.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (onSave != null) SaveButton(saveState, enabled = !busy, onSave)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(
                        recipe.mealType?.let { stringResource(it.label) },
                        pluralStringResource(R.plurals.recipe_servings, recipe.servings, recipe.servings),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (modified) ModifiedMarker()
            }
            CaloriesLabel(recipe.calories)
            Text(
                stringResource(R.string.recipe_calories_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onModify != null || onUndo != null) ModifyActions(busy, onModify, onUndo)
            HorizontalDivider()
            Section(stringResource(R.string.recipe_ingredients), RecipeCardTestTags.INGREDIENTS) {
                recipe.ingredients.forEach { Bullet(ingredientLine(it)) }
            }
            if (recipe.tools.isNotEmpty()) {
                Section(stringResource(R.string.recipe_tools), RecipeCardTestTags.TOOLS) {
                    recipe.tools.forEach { Bullet(it) }
                }
            }
            Section(stringResource(R.string.recipe_steps), RecipeCardTestTags.STEPS) {
                recipe.steps.forEach { StepRow(it) }
            }
        }
    }
}

@Composable
private fun SaveButton(state: SaveState, enabled: Boolean, onSave: () -> Unit) {
    when (state) {
        SaveState.SAVED ->
            TextButton(onClick = {}, enabled = false, modifier = Modifier.testTag(RecipeCardTestTags.SAVED)) {
                Text(stringResource(R.string.recipe_saved))
            }
        SaveState.UNSAVED ->
            FilledTonalButton(onClick = onSave, enabled = enabled, modifier = Modifier.testTag(RecipeCardTestTags.SAVE)) {
                Text(stringResource(R.string.recipe_save))
            }
        SaveState.CHANGED ->
            FilledTonalButton(onClick = onSave, enabled = enabled, modifier = Modifier.testTag(RecipeCardTestTags.SAVE_CHANGES)) {
                Text(stringResource(R.string.recipe_save_changes))
            }
    }
}

@Composable
private fun ModifiedMarker() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.testTag(RecipeCardTestTags.MODIFIED),
    ) {
        Text(
            stringResource(R.string.recipe_modified),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ModifyActions(busy: Boolean, onModify: (() -> Unit)?, onUndo: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onModify != null) {
            OutlinedButton(onClick = onModify, enabled = !busy, modifier = Modifier.testTag(RecipeCardTestTags.MODIFY)) {
                Text(stringResource(R.string.recipe_modify))
            }
        }
        if (onUndo != null) {
            TextButton(onClick = onUndo, enabled = !busy, modifier = Modifier.testTag(RecipeCardTestTags.UNDO)) {
                Text(stringResource(R.string.action_undo))
            }
        }
    }
    if (busy) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.testTag(RecipeCardTestTags.MODIFYING),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.recipe_modifying),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, tag: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        val action = stringResource(
            if (expanded) R.string.recipe_section_collapse else R.string.recipe_section_expand,
            title,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = expanded, role = Role.Button, onValueChange = { expanded = it })
                .padding(vertical = 8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = action,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun StepRow(step: Step) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${step.index}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Column {
            Text(step.text, style = MaterialTheme.typography.bodyMedium)
            step.minutes?.let {
                Text(
                    stringResource(R.string.recipe_step_minutes, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ingredientLine(ingredient: Ingredient): String {
    val line = when {
        ingredient.quantity.isNotEmpty() ->
            stringResource(R.string.recipe_ingredient_line, ingredient.quantity, ingredient.unit, ingredient.name)
        ingredient.unit.isNotEmpty() ->
            stringResource(R.string.recipe_ingredient_unquantified, ingredient.name, ingredient.unit)
        else -> ingredient.name
    }
    // An empty unit leaves a double space in the format.
    return line.replace(Regex("\\s+"), " ").trim()
}

internal val MealType.label: Int
    get() = when (this) {
        MealType.BREAKFAST -> R.string.meal_breakfast
        MealType.LUNCH -> R.string.meal_lunch
        MealType.DINNER -> R.string.meal_dinner
        MealType.SNACK -> R.string.meal_snack
        MealType.DESSERT -> R.string.meal_dessert
    }

/** Stable handles for UI tests. */
object RecipeCardTestTags {
    const val CARD = "recipe_card"
    const val CALORIES = "recipe_calories"
    const val INGREDIENTS = "recipe_ingredients"
    const val TOOLS = "recipe_tools"
    const val STEPS = "recipe_steps"
    const val SAVE = "recipe_save"
    const val SAVED = "recipe_saved"
    const val SAVE_CHANGES = "recipe_save_changes"
    const val MODIFY = "recipe_modify"
    const val UNDO = "recipe_undo"
    const val MODIFIED = "recipe_modified"
    const val MODIFYING = "recipe_modifying"
}
