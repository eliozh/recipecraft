package com.elio.recipecraft.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.recipecraft.R
import com.elio.recipecraft.data.recipe.RecipeExportResult
import com.elio.recipecraft.data.recipe.exportFileName
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.ui.LocalAppLayout
import com.elio.recipecraft.ui.common.AppError
import com.elio.recipecraft.ui.cover.CoverTopBar
import com.elio.recipecraft.ui.cover.CoverTouchTarget
import com.elio.recipecraft.ui.recipe.CaloriesLabel
import com.elio.recipecraft.ui.recipe.label
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenChat: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The recipe open beside the list, if any; its item is highlighted. */
    selectedId: String? = null,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingUndo by viewModel.pendingUndo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE)) { uri ->
        viewModel.onExportTo(uri?.toString())
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onImportFrom(uri?.toString())
    }
    // The cover screen folds the search behind an icon and the meal filter into a menu.
    val cover = LocalAppLayout.current.cover
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searchShown = searchOpen || state.query.isNotEmpty()
    val canBrowse = !state.loading && !state.loadFailed && !state.libraryEmpty
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val menu = @Composable {
                LibraryMenu(
                    canExport = canBrowse,
                    busy = state.transferring,
                    onExportAll = { exportLauncher.launch(exportFileName(LocalDate.now())) },
                    onImport = { importLauncher.launch(IMPORT_MIME_TYPES) },
                )
            }
            if (cover) {
                CoverTopBar(title = stringResource(R.string.tab_library)) {
                    if (canBrowse) {
                        CoverSearchToggle(
                            shown = searchShown,
                            onShownChanged = { shown ->
                                searchOpen = shown
                                if (!shown) viewModel.onQueryChanged("")
                            },
                        )
                        MealFilterMenu(state.mealType, viewModel::onMealTypeSelected)
                    }
                    menu()
                }
            } else {
                TopAppBar(title = { Text(stringResource(R.string.tab_library)) }, actions = { menu() })
            }
            if (state.transferring) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag(LibraryTestTags.TRANSFERRING))
            }
            when {
                state.loading -> Unit
                state.loadFailed -> Message(stringResource(AppError.LIBRARY_LOAD_FAILED.message), LibraryTestTags.LOAD_FAILED)
                state.libraryEmpty -> EmptyLibrary(onOpenChat)
                else -> {
                    if (!cover) {
                        SearchAndFilter(state, viewModel::onQueryChanged, viewModel::onMealTypeSelected)
                    } else if (searchShown) {
                        CoverSearchField(state.query, viewModel::onQueryChanged)
                    }
                    if (state.recipes.isEmpty()) {
                        Message(stringResource(R.string.library_no_match), LibraryTestTags.NO_MATCH)
                    } else if (cover) {
                        CoverRecipeList(state.recipes, onOpenRecipe)
                    } else {
                        RecipeList(state.recipes, selectedId, onOpenRecipe)
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    val deletedMessage = stringResource(R.string.recipe_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(pendingUndo) {
        val recipe = pendingUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            deletedMessage,
            actionLabel = undoLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.onUndo(recipe) else viewModel.onUndoDismissed(recipe)
    }
    state.importResult?.let { ImportSummaryDialog(it, viewModel::onImportResultDismissed) }
    val exportResult = state.exportResult
    val exportMessage = when (exportResult) {
        is RecipeExportResult.Exported -> pluralStringResource(R.plurals.library_exported, exportResult.count, exportResult.count)
        RecipeExportResult.Failed -> stringResource(AppError.EXPORT_FAILED.message)
        null -> null
    }
    LaunchedEffect(exportResult) {
        if (exportMessage != null) {
            // Cleared only once the snackbar is gone: clearing first would change the key and cancel it.
            snackbarHostState.showSnackbar(exportMessage)
            viewModel.onExportResultShown()
        }
    }
    val restoreFailedMessage = stringResource(AppError.RECIPE_RESTORE_FAILED.message)
    LaunchedEffect(state.restoreFailed) {
        if (state.restoreFailed) {
            // As with the export result: cleared only once the snackbar is gone.
            snackbarHostState.showSnackbar(restoreFailedMessage, withDismissAction = true, duration = SnackbarDuration.Long)
            viewModel.onRestoreFailedShown()
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
private fun RecipeList(recipes: List<Recipe>, selectedId: String?, onOpenRecipe: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().testTag(LibraryTestTags.LIST),
    ) {
        items(recipes, key = { it.id }) { recipe -> LibraryItem(recipe, selected = recipe.id == selectedId, onClick = { onOpenRecipe(recipe.id) }) }
    }
}

/** A saved recipe in the list; tapping it opens its detail screen. */
@Composable
private fun LibraryItem(recipe: Recipe, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .testTag(LibraryTestTags.ITEM),
    ) {
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
private fun CoverSearchToggle(shown: Boolean, onShownChanged: (Boolean) -> Unit) {
    IconButton(onClick = { onShownChanged(!shown) }, modifier = Modifier.testTag(LibraryTestTags.SEARCH_TOGGLE)) {
        if (shown) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.library_close_search))
        } else {
            Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.library_search))
        }
    }
}

/** The search field on the cover screen, focused as it opens. */
@Composable
private fun CoverSearchField(query: String, onQueryChanged: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) focusRequester.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        placeholder = { Text(stringResource(R.string.library_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .focusRequester(focusRequester)
            .testTag(LibraryTestTags.SEARCH),
    )
}

/** The meal filter as a menu, where the cover screen has no width for a row of chips. */
@Composable
private fun MealFilterMenu(selected: MealType?, onSelected: (MealType?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag(LibraryTestTags.FILTER)) {
            Icon(
                painterResource(R.drawable.ic_filter_list),
                contentDescription = stringResource(R.string.library_filter),
                tint = if (selected != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(null) + MealType.entries).forEach { type ->
                DropdownMenuItem(
                    text = { Text(stringResource(type?.label ?: R.string.library_filter_all)) },
                    leadingIcon = { RadioButton(selected = type == selected, onClick = null) },
                    onClick = {
                        expanded = false
                        onSelected(type)
                    },
                )
            }
        }
    }
}

/** The library as a dense list for the cover screen: a name and its calories per row. */
@Composable
private fun CoverRecipeList(recipes: List<Recipe>, onOpenRecipe: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(LibraryTestTags.LIST)) {
        items(recipes, key = { it.id }) { recipe ->
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = CoverTouchTarget)
                    .clickable { onOpenRecipe(recipe.id) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(LibraryTestTags.ITEM),
            ) {
                Text(
                    recipe.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                CaloriesLabel(recipe.calories)
            }
            HorizontalDivider()
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
    const val SEARCH_TOGGLE = "library_search_toggle"
    const val FILTER = "library_filter"
    const val LIST = "library_list"
    const val ITEM = "library_item"
    const val EMPTY = "library_empty"
    const val NO_MATCH = "library_no_match"
    const val LOAD_FAILED = "library_load_failed"
    const val MENU = "library_menu"
    const val EXPORT_ALL = "library_export_all"
    const val IMPORT = "library_import"
    const val IMPORT_SUMMARY = "library_import_summary"
    const val TRANSFERRING = "library_transferring"
    const val DETAIL_PANE = "library_detail_pane"
}
