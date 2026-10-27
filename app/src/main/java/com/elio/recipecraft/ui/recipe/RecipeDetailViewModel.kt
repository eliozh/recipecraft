package com.elio.recipecraft.ui.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.llm.TranslationError
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.data.recipe.RecipeTranslations
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecipeDetailUiState(
    /** True until the recipe is first read. */
    val loading: Boolean = true,
    val recipe: Recipe? = null,
    /** The database kcal of each ingredient, in order; null where it matched no table. */
    val ingredientKcal: List<Int?> = emptyList(),
    /** No saved recipe has the requested id. */
    val notFound: Boolean = false,
    val loadFailed: Boolean = false,
    val deleting: Boolean = false,
    val deleteFailed: Boolean = false,
    /** The language the app shows now; a recipe in another one offers Translate. */
    val appLanguage: Language = Language.EN,
    val translating: Boolean = false,
    /** Why the last Translate failed, until the screen has shown it. */
    val translateError: TranslationError? = null,
) {
    val canTranslate: Boolean get() = recipe != null && recipe.language != appLanguage && !translating && !deleting
}

sealed interface RecipeDetailEvent {
    /** The recipe is gone and can be restored through [UndoDelete]; leave the screen. */
    data object Deleted : RecipeDetailEvent
}

@HiltViewModel
class RecipeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository,
    private val undoDelete: UndoDelete,
    private val languageSettings: AppLanguageSettings,
    private val translations: RecipeTranslations,
) : ViewModel() {

    private val recipeId: String? = savedStateHandle[ARG_RECIPE_ID]

    private val _uiState = MutableStateFlow(RecipeDetailUiState(appLanguage = languageSettings.resolvedLanguage))
    val uiState: StateFlow<RecipeDetailUiState> = _uiState.asStateFlow()

    private val _events = Channel<RecipeDetailEvent>(Channel.BUFFERED)
    val events: Flow<RecipeDetailEvent> = _events.receiveAsFlow()

    init {
        if (recipeId == null) {
            _uiState.update { it.copy(loading = false, notFound = true) }
        } else {
            viewModelScope.launch { observe(recipeId) }
        }
    }

    fun delete() {
        val recipe = _uiState.value.recipe ?: return
        if (_uiState.value.deleting) return
        _uiState.update { it.copy(deleting = true, deleteFailed = false) }
        viewModelScope.launch {
            try {
                repository.delete(recipe.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // Room reports an unreadable or full database as a runtime exception.
                _uiState.update { it.copy(deleting = false, deleteFailed = true) }
                return@launch
            }
            undoDelete.offer(recipe)
            _events.send(RecipeDetailEvent.Deleted)
        }
    }

    /** The language can change in system Settings while the screen is open. */
    fun onResume() {
        _uiState.update { it.copy(appLanguage = languageSettings.resolvedLanguage) }
    }

    /** Translates this recipe into the app language; the saved recipe updates in place. */
    fun translate() {
        val state = _uiState.value
        val recipe = state.recipe ?: return
        if (!state.canTranslate) return
        _uiState.update { it.copy(translating = true, translateError = null) }
        viewModelScope.launch {
            val error = translations.translate(recipe, state.appLanguage)
            _uiState.update { it.copy(translating = false, translateError = error) }
        }
    }

    fun onTranslateErrorShown() {
        _uiState.update { it.copy(translateError = null) }
    }

    fun onDeleteFailedShown() {
        _uiState.update { it.copy(deleteFailed = false) }
    }

    private suspend fun observe(id: String) {
        repository.observeDetail(id)
            .catch { e ->
                if (e !is RuntimeException || e is CancellationException) throw e
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
            .collect { detail ->
                _uiState.update { state ->
                    when {
                        // Keep showing the recipe while the screen closes after a delete.
                        state.deleting -> state
                        detail == null -> state.copy(loading = false, recipe = null, ingredientKcal = emptyList(), notFound = true)
                        else -> state.copy(
                            loading = false,
                            recipe = detail.recipe,
                            ingredientKcal = detail.ingredientKcal,
                            notFound = false,
                            loadFailed = false,
                        )
                    }
                }
            }
    }

    companion object {
        /** The navigation argument that carries the recipe id. */
        const val ARG_RECIPE_ID = "recipeId"
    }
}
