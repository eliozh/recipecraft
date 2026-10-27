package com.elio.recipecraft.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
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
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: RecipeRepository,
) : ViewModel() {

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
