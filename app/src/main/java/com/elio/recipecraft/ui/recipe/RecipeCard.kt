package com.elio.recipecraft.ui.recipe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/** One recipe with collapsible ingredients, tools and steps. */
@Composable
fun RecipeCard(
    recipe: Recipe,
    modifier: Modifier = Modifier,
) {
    Card(modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(RecipeCardTestTags.CARD)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                recipe.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                listOfNotNull(
                    recipe.mealType?.let { stringResource(it.label) },
                    pluralStringResource(R.plurals.recipe_servings, recipe.servings, recipe.servings),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CaloriesLabel(recipe.calories)
            Text(
                stringResource(R.string.recipe_calories_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun StepRow(step: Step) {
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
private fun ingredientLine(ingredient: Ingredient): String {
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
}
