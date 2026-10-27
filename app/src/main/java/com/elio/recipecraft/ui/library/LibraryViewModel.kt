package com.elio.recipecraft.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.recipe.ExportImportService
import com.elio.recipecraft.data.recipe.RecipeDocuments
import com.elio.recipecraft.data.recipe.RecipeExportResult
import com.elio.recipecraft.data.recipe.RecipeImportResult
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.ui.recipe.UndoDelete
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val query: String = "",
    /** Null shows every meal type. */
    val mealType: MealType? = null,
    /** The saved recipes that pass the filter and search, newest first. */
    val recipes: List<Recipe> = emptyList(),
    /** Nothing is saved at all, as opposed to nothing matching. */
    val libraryEmpty: Boolean = false,
    /** True until the first results arrive, so no empty state flashes. */
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    /** Putting back a deleted recipe failed; shown once, then cleared by [LibraryViewModel.onRestoreFailedShown]. */
    val restoreFailed: Boolean = false,
    /** True while an export or import runs. */
    val transferring: Boolean = false,
    /** The last export's outcome, until the screen has shown it. */
    val exportResult: RecipeExportResult? = null,
    /** The last import's summary, until the user dismisses it. */
    val importResult: RecipeImportResult? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: RecipeRepository,
    private val undoDelete: UndoDelete,
    private val exportImport: ExportImportService,
    private val documents: RecipeDocuments,
) : ViewModel() {

    /** The recipe just deleted from its detail screen, while its "Recipe deleted" snackbar can still undo it. */
    val pendingUndo: StateFlow<Recipe?> = undoDelete.pending

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { observeRecipes() }
    }

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun onMealTypeSelected(mealType: MealType?) {
        _uiState.update { it.copy(mealType = mealType) }
    }

    /** Undo on the snackbar: puts [recipe] back exactly as it was. */
    fun onUndo(recipe: Recipe) {
        if (!undoDelete.take(recipe)) return
        viewModelScope.launch {
            try {
                repository.restore(recipe)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                _uiState.update { it.copy(restoreFailed = true) }
            }
        }
    }

    /** The snackbar went away without Undo, so the deletion stands. */
    fun onUndoDismissed(recipe: Recipe) {
        undoDelete.take(recipe)
    }

    fun onRestoreFailedShown() {
        _uiState.update { it.copy(restoreFailed = false) }
    }

    /** Export all: writes every saved recipe to [uri]; null when the user backed out of the picker. */
    fun onExportTo(uri: String?) {
        if (uri == null || !startTransfer()) return
        viewModelScope.launch {
            val result = exportImport.export { documents.openOutput(uri) }
            _uiState.update { it.copy(transferring = false, exportResult = result) }
        }
    }

    /** Import: adds the recipes of the export at [uri]; null when the user backed out of the picker. */
    fun onImportFrom(uri: String?) {
        if (uri == null || !startTransfer()) return
        viewModelScope.launch {
            val result = exportImport.import { documents.openInput(uri) }
            _uiState.update { it.copy(transferring = false, importResult = result) }
        }
    }

    fun onExportResultShown() {
        _uiState.update { it.copy(exportResult = null) }
    }

    fun onImportResultDismissed() {
        _uiState.update { it.copy(importResult = null) }
    }

    /** Marks a transfer as running; false when one already is. */
    private fun startTransfer(): Boolean {
        var started = false
        _uiState.update { state ->
            started = !state.transferring
            if (started) state.copy(transferring = true, exportResult = null, importResult = null) else state
        }
        return started
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeRecipes() {
        _uiState
            .map { it.mealType to it.query.trim() }
            .distinctUntilChanged()
            .flatMapLatest { (mealType, query) -> repository.observe(mealType, query) }
            .combine(repository.count) { recipes, count -> recipes to count }
            .catch { e ->
                // Room reports an unreadable database as a runtime exception.
                if (e !is RuntimeException || e is CancellationException) throw e
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
            .collect { (recipes, count) ->
                _uiState.update { it.copy(recipes = recipes, libraryEmpty = count == 0, loading = false, loadFailed = false) }
            }
    }
}
