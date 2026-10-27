package com.elio.recipecraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import com.elio.recipecraft.ui.chat.ComposerState
import com.elio.recipecraft.ui.chat.GenerationComposer
import com.elio.recipecraft.ui.chat.PresetChip
import com.elio.recipecraft.ui.chat.PresetChips
import com.elio.recipecraft.ui.recipe.RecipeCard
import com.elio.recipecraft.ui.theme.RecipeCraftTheme

// Previews of the adaptive frame at phone, landscape phone, foldable, tablet and desktop sizes.
// The screens take Hilt view models, so these lay out their stateless parts the same way.

@PreviewScreenSizes
@Composable
private fun ChatLayoutPreview() = PreviewFrame(TopLevelDestination.CHAT) {
    val layout = LocalAppLayout.current
    Column(Modifier.fillMaxSize()) {
        PreviewTopBar(R.string.tab_chat)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            item { RecipeCard(previewRecipes.first()) }
        }
        GenerationComposer(
            state = ComposerState(expanded = !layout.compactHeight),
            sending = false,
            onScopeChanged = {},
            onCountChanged = {},
            onPreferencesChanged = {},
            onExpandedChanged = {},
            onGenerate = {},
        )
        if (!layout.compactHeight) PresetChips(PresetChip.forChat(empty = false), enabled = true, onAction = {})
    }
}

@PreviewScreenSizes
@Composable
private fun LibraryLayoutPreview() = PreviewFrame(TopLevelDestination.LIBRARY) {
    val list = @Composable { modifier: Modifier ->
        Column(modifier) {
            PreviewTopBar(R.string.tab_library)
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(previewRecipes) { RecipeCard(it) }
            }
        }
    }
    if (LocalAppLayout.current.listDetail) {
        Row(Modifier.fillMaxSize()) {
            list(Modifier.weight(0.4f).fillMaxHeight())
            VerticalDivider()
            Box(Modifier.weight(0.6f).fillMaxHeight()) {
                Column(Modifier.fillMaxWidth()) {
                    PreviewTopBar(R.string.tab_library)
                    RecipeCard(previewRecipes.first())
                }
            }
        }
    } else {
        list(Modifier.fillMaxSize())
    }
}

@PreviewScreenSizes
@Composable
private fun SettingsLayoutPreview() = PreviewFrame(TopLevelDestination.SETTINGS) {
    Column(Modifier.fillMaxSize()) {
        PreviewTopBar(R.string.tab_settings)
        Text(stringResource(R.string.settings_language))
    }
}

@Composable
private fun PreviewFrame(selected: TopLevelDestination, content: @Composable () -> Unit) {
    RecipeCraftTheme {
        AdaptiveScaffold(layout = currentAppLayout(), isSelected = { it == selected }, onSelect = {}, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewTopBar(titleRes: Int) {
    TopAppBar(title = { Text(stringResource(titleRes)) })
}

private val previewRecipes = listOf("Cheese omelette", "Tomato soup", "Oatmeal").mapIndexed { i, name ->
    Recipe(
        id = "preview-$i",
        name = name,
        mealType = MealType.entries[i % MealType.entries.size],
        servings = 2,
        calories = Calories(420, 210, CalorieSource.AI_ESTIMATE, 420),
        ingredients = listOf(Ingredient(name = "Eggs", nameEn = "egg", quantity = "3", unit = "", grams = 150.0, aiKcal = 215)),
        tools = listOf("Pan"),
        steps = listOf(Step(1, "Whisk the eggs.", 2)),
        language = Language.EN,
        setId = null,
        createdAt = 0,
        updatedAt = 0,
    )
}
