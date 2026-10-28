package com.elio.recipecraft.ui.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.llm.ModifyError
import com.elio.recipecraft.data.llm.ModifyResult
import com.elio.recipecraft.data.llm.RecipeModifier
import com.elio.recipecraft.data.llm.TranslationError
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.data.recipe.RecipeTranslations
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
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
    /** Set while a rate-limited translation waits this many seconds to be sent again. */
    val translateRetryingInSeconds: Int? = null,
    /** Why the last Translate failed, until the screen has shown it. */
    val translateError: TranslationError? = null,
    /** Modify with AI, from the sheet to the preview of the result; null when closed. */
    val modify: DetailModifyState? = null,
    /** The last Replace or Save as copy did not reach the database, until the screen has shown it. */
    val modifySaveFailed: Boolean = false,
) {
    val canTranslate: Boolean
        get() = recipe != null && recipe.language != appLanguage && !translating && !deleting && modify == null
    val canModify: Boolean get() = recipe != null && !translating && !deleting && modify == null
}

/**
 * Modify with AI on the saved recipe. The sheet takes the [instruction]; once the model returns,
 * [preview] holds both versions, and nothing is written until Replace or Save as copy.
 */
data class DetailModifyState(
    val instruction: String = "",
    /** True while the model works on the change. */
    val running: Boolean = false,
    /** Why the last attempt failed; the sheet stays open to try again. */
    val error: ModifyError? = null,
    /** Set while a rate-limited change waits this many seconds to be sent again. */
    val retryingInSeconds: Int? = null,
    val preview: ModifyPreviewState? = null,
    /** True while Replace or Save as copy writes. */
    val saving: Boolean = false,
) {
    val canSend: Boolean get() = !running && preview == null && instruction.isNotBlank()
}

/** The saved recipe as it was when the change was sent, and the model's version of it. */
data class ModifyPreviewState(val before: Recipe, val after: Recipe)

sealed interface RecipeDetailEvent {
    /** The recipe is gone and can be restored through [UndoDelete]; leave the screen. */
    data object Deleted : RecipeDetailEvent

    /** The saved recipe now holds the changed version. */
    data object Replaced : RecipeDetailEvent

    /** The changed version was saved as the new recipe [id]; the original is as it was. */
    data class SavedCopy(val id: String) : RecipeDetailEvent
}

@HiltViewModel
class RecipeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository,
    private val undoDelete: UndoDelete,
    private val languageSettings: AppLanguageSettings,
    private val translations: RecipeTranslations,
    private val recipeModifier: RecipeModifier,
    private val clock: Clock,
) : ViewModel() {

    private val recipeId: String? = savedStateHandle[ARG_RECIPE_ID]

    private val _uiState = MutableStateFlow(RecipeDetailUiState(appLanguage = languageSettings.resolvedLanguage))
    val uiState: StateFlow<RecipeDetailUiState> = _uiState.asStateFlow()

    private val _events = Channel<RecipeDetailEvent>(Channel.BUFFERED)
    val events: Flow<RecipeDetailEvent> = _events.receiveAsFlow()

    private var modifyJob: Job? = null

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
            val error = translations.translate(recipe, state.appLanguage) { seconds ->
                _uiState.update { it.copy(translateRetryingInSeconds = seconds) }
            }
            _uiState.update { it.copy(translating = false, translateRetryingInSeconds = null, translateError = error) }
        }
    }

    fun onTranslateErrorShown() {
        _uiState.update { it.copy(translateError = null) }
    }

    /** Opens the Modify with AI sheet on this recipe. */
    fun openModify() {
        if (!_uiState.value.canModify) return
        _uiState.update { it.copy(modify = DetailModifyState()) }
    }

    fun onModifyInstructionChanged(text: String) {
        _uiState.update { state ->
            val modify = state.modify
            if (modify == null || modify.running) state else state.copy(modify = modify.copy(instruction = text))
        }
    }

    /** Asks the model for the change; on success the sheet gives way to the preview. */
    fun sendModify() {
        val state = _uiState.value
        val modify = state.modify ?: return
        val recipe = state.recipe ?: return
        if (!modify.canSend) return
        _uiState.update { it.copy(modify = modify.copy(running = true, error = null)) }
        modifyJob = viewModelScope.launch {
            val result = recipeModifier.modify(recipe, modify.instruction) { seconds ->
                _uiState.update { state -> state.copy(modify = state.modify?.copy(retryingInSeconds = seconds)) }
            }
            _uiState.update { current ->
                val open = current.modify ?: return@update current
                current.copy(
                    modify = when (result) {
                        is ModifyResult.Success ->
                            open.copy(running = false, retryingInSeconds = null, preview = ModifyPreviewState(recipe, result.recipe))
                        is ModifyResult.Failure -> open.copy(running = false, retryingInSeconds = null, error = result.error)
                    },
                )
            }
        }
    }

    /** Closes the sheet or the preview, stopping a change that runs; nothing is written. */
    fun cancelModify() {
        if (_uiState.value.modify?.saving == true) return
        modifyJob?.cancel()
        modifyJob = null
        _uiState.update { it.copy(modify = null) }
    }

    /** Writes the changed version over the saved recipe: same id and creation time, a new update time. */
    fun replace() {
        val preview = startSaving() ?: return
        viewModelScope.launch {
            val replaced = writeOrReport { repository.replace(preview.after) } ?: return@launch
            _uiState.update { it.copy(modify = null) }
            // Not replaced: the recipe was deleted meanwhile, and the screen already says so.
            if (replaced) _events.send(RecipeDetailEvent.Replaced)
        }
    }

    /** Saves the changed version as a new recipe and leaves the original as it was. */
    fun saveAsCopy() {
        val preview = startSaving() ?: return
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            writeOrReport { repository.saveAsCopy(preview.after, id, clock.millis()) } ?: return@launch
            _uiState.update { it.copy(modify = null) }
            _events.send(RecipeDetailEvent.SavedCopy(id))
        }
    }

    fun onModifySaveFailedShown() {
        _uiState.update { it.copy(modifySaveFailed = false) }
    }

    private fun startSaving(): ModifyPreviewState? {
        val modify = _uiState.value.modify ?: return null
        val preview = modify.preview ?: return null
        if (modify.saving) return null
        _uiState.update { it.copy(modify = modify.copy(saving = true), modifySaveFailed = false) }
        return preview
    }

    /** The result of [write], or null when the database failed; the preview then stays open to try again. */
    private suspend fun <T> writeOrReport(write: suspend () -> T): T? = try {
        write()
    } catch (e: CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        // Room reports an unreadable or full database as a runtime exception.
        _uiState.update { state -> state.copy(modify = state.modify?.copy(saving = false), modifySaveFailed = true) }
        null
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
