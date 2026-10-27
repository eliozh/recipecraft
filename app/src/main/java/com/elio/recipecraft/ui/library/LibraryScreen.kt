package com.elio.recipecraft.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.recipecraft.R
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.ui.recipe.CaloriesLabel
import com.elio.recipecraft.ui.recipe.label

@Composable
fun LibraryScreen(
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        when {
            state.loading -> Unit
            state.loadFailed -> Message(stringResource(R.string.library_load_failed), LibraryTestTags.LOAD_FAILED)
            state.libraryEmpty -> EmptyLibrary(onOpenChat)
            else -> {
                SearchAndFilter(state, viewModel::onQueryChanged, viewModel::onMealTypeSelected)
                if (state.recipes.isEmpty()) {
                    Message(stringResource(R.string.library_no_match), LibraryTestTags.NO_MATCH)
                } else {
                    RecipeList(state.recipes)
                }
            }
        }
    }
}

@Composable
private fun SearchAndFilter(state: LibraryUiState, onQueryChanged: (String) -> Unit, onMealTypeSelected: (MealType?) -> Unit) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChanged,
        placeholder = { Text(stringResource(R.string.library_search_hint)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .testTag(LibraryTestTags.SEARCH),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        FilterChip(
            selected = state.mealType == null,
            onClick = { onMealTypeSelected(null) },
            label = { Text(stringResource(R.string.library_filter_all)) },
        )
        MealType.entries.forEach { type ->
            FilterChip(
                selected = state.mealType == type,
                onClick = { onMealTypeSelected(type) },
                label = { Text(stringResource(type.label)) },
            )
        }
    }
}

@Composable
private fun RecipeList(recipes: List<Recipe>) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().testTag(LibraryTestTags.LIST),
    ) {
        items(recipes, key = { it.id }) { LibraryItem(it) }
    }
}

/** A saved recipe in the list; T11 opens its detail screen from here. */
@Composable
private fun LibraryItem(recipe: Recipe) {
    Card(Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(LibraryTestTags.ITEM)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                listOfNotNull(
                    recipe.mealType?.let { stringResource(it.label) },
                    pluralStringResource(R.plurals.recipe_servings, recipe.servings, recipe.servings),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CaloriesLabel(recipe.calories)
        }
    }
}

@Composable
private fun EmptyLibrary(onOpenChat: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag(LibraryTestTags.EMPTY),
    ) {
        Text(
            stringResource(R.string.library_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenChat) { Text(stringResource(R.string.library_open_chat)) }
    }
}

@Composable
private fun Message(text: String, tag: String) {
    Box(Modifier.fillMaxSize().padding(32.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Stable handles for UI tests. */
object LibraryTestTags {
    const val SEARCH = "library_search"
    const val LIST = "library_list"
    const val ITEM = "library_item"
    const val EMPTY = "library_empty"
    const val NO_MATCH = "library_no_match"
    const val LOAD_FAILED = "library_load_failed"
}
