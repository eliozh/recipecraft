package com.elio.recipecraft.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.recipecraft.R
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.ui.chat.ModifySheet
import com.elio.recipecraft.ui.common.translationErrorText
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * A saved recipe in full: every field, the database kcal of each matched ingredient, and a Delete
 * action. After a delete it calls [onDeleted]; the library then offers Undo. A recipe in another
 * language than the app's offers Translate, which updates it in place. Modify with AI shows the
 * model's version next to the saved one; Replace updates the recipe in place, and Save as copy adds
 * a new recipe that [onOpenRecipe] can open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecipeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnDeleted by rememberUpdatedState(onDeleted)
    val currentOnOpenRecipe by rememberUpdatedState(onOpenRecipe)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val replacedMessage = stringResource(R.string.recipe_replaced)
    val copiedMessage = stringResource(R.string.recipe_saved_as_copy)
    val openLabel = stringResource(R.string.action_open)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RecipeDetailEvent.Deleted -> currentOnDeleted()
                RecipeDetailEvent.Replaced -> scope.launch { snackbarHostState.showSnackbar(replacedMessage) }
                is RecipeDetailEvent.SavedCopy -> scope.launch {
                    if (snackbarHostState.showSnackbar(copiedMessage, actionLabel = openLabel) == SnackbarResult.ActionPerformed) {
                        currentOnOpenRecipe(event.id)
                    }
                }
            }
        }
    }
    val modifySaveFailedMessage = stringResource(R.string.recipe_modify_save_failed)
    LaunchedEffect(state.modifySaveFailed) {
        if (state.modifySaveFailed) {
            viewModel.onModifySaveFailedShown()
            snackbarHostState.showSnackbar(modifySaveFailedMessage)
        }
    }
    val deleteFailedMessage = stringResource(R.string.recipe_delete_failed)
    LaunchedEffect(state.deleteFailed) {
        if (state.deleteFailed) {
            viewModel.onDeleteFailedShown()
            snackbarHostState.showSnackbar(deleteFailedMessage)
        }
    }

    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose {}
    }
    val translateErrorMessage = state.translateError?.let { translationErrorText(it) }
    LaunchedEffect(translateErrorMessage) {
        if (translateErrorMessage != null) {
            viewModel.onTranslateErrorShown()
            snackbarHostState.showSnackbar(translateErrorMessage)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(RecipeDetailTestTags.BACK)) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.translating) {
                        val translating = stringResource(R.string.recipe_translating)
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(24.dp)
                                .semantics { contentDescription = translating }
                                .testTag(RecipeDetailTestTags.TRANSLATING),
                        )
                    } else if (state.canTranslate) {
                        TextButton(onClick = viewModel::translate, modifier = Modifier.testTag(RecipeDetailTestTags.TRANSLATE)) {
                            Text(stringResource(R.string.action_translate))
                        }
                    }
                    val shown = state.recipe
                    if (shown != null) {
                        IconButton(
                            onClick = { shareRecipe(context, shown) },
                            modifier = Modifier.testTag(RecipeDetailTestTags.SHARE),
                        ) {
                            Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.recipe_share))
                        }
                        IconButton(
                            onClick = viewModel::delete,
                            enabled = !state.deleting,
                            modifier = Modifier.testTag(RecipeDetailTestTags.DELETE),
                        ) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.recipe_delete))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val recipe = state.recipe
        when {
            state.loading -> Unit
            recipe != null -> RecipeDetailContent(
                recipe,
                state.ingredientKcal,
                canModify = state.canModify,
                onModify = viewModel::openModify,
                modifier = Modifier.padding(padding),
            )
            state.loadFailed -> Message(stringResource(R.string.recipe_load_failed), RecipeDetailTestTags.LOAD_FAILED, padding)
            else -> Message(stringResource(R.string.recipe_not_found), RecipeDetailTestTags.NOT_FOUND, padding)
        }
    }

    val modify = state.modify
    val preview = modify?.preview
    if (preview != null) {
        ModifyPreview(
            before = preview.before,
            after = preview.after,
            saving = modify.saving,
            onReplace = viewModel::replace,
            onSaveAsCopy = viewModel::saveAsCopy,
            onCancel = viewModel::cancelModify,
        )
    } else if (modify != null) {
        ModifySheet(
            recipeName = state.recipe?.name.orEmpty(),
            instruction = modify.instruction,
            running = modify.running,
            error = modify.error,
            onInstructionChanged = viewModel::onModifyInstructionChanged,
            onSend = viewModel::sendModify,
            onCancel = viewModel::cancelModify,
        )
    }
}

@Composable
private fun RecipeDetailContent(
    recipe: Recipe,
    ingredientKcal: List<Int?>,
    canModify: Boolean,
    onModify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxSize().testTag(RecipeDetailTestTags.SCREEN),
        ) {
            item { Header(recipe, canModify, onModify) }
            sectionTitle(R.string.recipe_ingredients, RecipeDetailTestTags.INGREDIENTS)
            itemsIndexed(recipe.ingredients) { index, ingredient ->
                IngredientRow(ingredient, ingredientKcal.getOrNull(index))
            }
            if (recipe.tools.isNotEmpty()) {
                sectionTitle(R.string.recipe_tools, RecipeDetailTestTags.TOOLS)
                itemsIndexed(recipe.tools) { _, tool ->
                    Text("• $tool", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(RecipeDetailTestTags.TOOL))
                }
            }
            sectionTitle(R.string.recipe_steps, RecipeDetailTestTags.STEPS)
            itemsIndexed(recipe.steps) { _, step ->
                Box(Modifier.testTag(RecipeDetailTestTags.STEP)) { StepRow(step) }
            }
        }
    }
}

@Composable
private fun Header(recipe: Recipe, canModify: Boolean, onModify: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            recipe.name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }.testTag(RecipeDetailTestTags.NAME),
        )
        Text(
            listOfNotNull(
                recipe.mealType?.let { stringResource(it.label) },
                pluralStringResource(R.plurals.recipe_servings, recipe.servings, recipe.servings),
                stringResource(recipe.language.label),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(RecipeDetailTestTags.META),
        )
        val locale = LocalConfiguration.current.locales[0]
        val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
        Text(
            listOfNotNull(
                stringResource(R.string.recipe_created, dateFormat.format(Date(recipe.createdAt))),
                if (recipe.updatedAt != recipe.createdAt) {
                    stringResource(R.string.recipe_updated, dateFormat.format(Date(recipe.updatedAt)))
                } else {
                    null
                },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(RecipeDetailTestTags.DATES),
        )
        CaloriesLabel(recipe.calories)
        Text(
            stringResource(R.string.recipe_calories_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(RecipeDetailTestTags.DISCLAIMER),
        )
        FilledTonalButton(onClick = onModify, enabled = canModify, modifier = Modifier.testTag(RecipeDetailTestTags.MODIFY)) {
            Text(stringResource(R.string.recipe_modify))
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

private fun LazyListScope.sectionTitle(title: Int, tag: String) {
    item(contentType = "section") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() }.testTag(tag),
        )
    }
}

/** The ingredient line, then its weight and calories: the table figure when it matched, else the model's estimate. */
@Composable
private fun IngredientRow(ingredient: Ingredient, databaseKcal: Int?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(RecipeDetailTestTags.INGREDIENT)) {
        Text("•", style = MaterialTheme.typography.bodyLarge)
        Column {
            Text(ingredientLine(ingredient), style = MaterialTheme.typography.bodyLarge)
            val locale = LocalConfiguration.current.locales[0]
            val details = listOfNotNull(
                ingredient.grams?.let {
                    val grams = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(it)
                    stringResource(R.string.recipe_ingredient_grams, grams)
                },
                when {
                    databaseKcal != null -> pluralStringResource(R.plurals.recipe_ingredient_kcal, databaseKcal, databaseKcal)
                    ingredient.aiKcal != null -> pluralStringResource(R.plurals.recipe_ingredient_kcal_ai, ingredient.aiKcal, ingredient.aiKcal)
                    else -> null
                },
            )
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(RecipeDetailTestTags.INGREDIENT_DETAILS),
                )
            }
        }
    }
}

@Composable
private fun Message(text: String, tag: String, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding).padding(32.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The name of [Language], in that language. */
internal val Language.label: Int
    get() = when (this) {
        Language.EN -> R.string.language_english
        Language.ZH -> R.string.language_chinese
    }

/** Stable handles for UI tests. */
object RecipeDetailTestTags {
    const val SCREEN = "recipe_detail"
    const val BACK = "recipe_detail_back"
    const val DELETE = "recipe_detail_delete"
    const val SHARE = "recipe_detail_share"
    const val TRANSLATE = "recipe_detail_translate"
    const val TRANSLATING = "recipe_detail_translating"
    const val MODIFY = "recipe_detail_modify"
    const val NAME = "recipe_detail_name"
    const val META = "recipe_detail_meta"
    const val DATES = "recipe_detail_dates"
    const val DISCLAIMER = "recipe_detail_disclaimer"
    const val INGREDIENTS = "recipe_detail_ingredients"
    const val INGREDIENT = "recipe_detail_ingredient"
    const val INGREDIENT_DETAILS = "recipe_detail_ingredient_details"
    const val TOOLS = "recipe_detail_tools"
    const val TOOL = "recipe_detail_tool"
    const val STEPS = "recipe_detail_steps"
    const val STEP = "recipe_detail_step"
    const val NOT_FOUND = "recipe_detail_not_found"
    const val LOAD_FAILED = "recipe_detail_load_failed"
}
