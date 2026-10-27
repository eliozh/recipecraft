package com.elio.recipecraft.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.nutrition.CsvDocuments
import com.elio.recipecraft.data.nutrition.NutritionTableRepository
import com.elio.recipecraft.data.nutrition.NutritionTableSummary
import com.elio.recipecraft.data.nutrition.TableImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A picked file waiting for the user to confirm its table name. */
data class PendingImport(val uri: String, val name: String)

data class NutritionTablesUiState(
    val tables: List<NutritionTableSummary> = emptyList(),
    val pendingImport: PendingImport? = null,
    val importing: Boolean = false,
    /** The last import's summary, with the name it was imported under. */
    val lastImport: Pair<String, TableImportResult>? = null,
    val confirmDelete: NutritionTableSummary? = null,
    /** A reorder or delete could not be saved. */
    val actionFailed: Boolean = false,
    val loadFailed: Boolean = false,
)

@HiltViewModel
class NutritionTablesViewModel @Inject constructor(
    private val repository: NutritionTableRepository,
    private val documents: CsvDocuments,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NutritionTablesUiState())
    val uiState: StateFlow<NutritionTablesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.tables
                .catch { e ->
                    // Room reports an unreadable database as a runtime exception.
                    if (e !is RuntimeException || e is CancellationException) throw e
                    _uiState.update { it.copy(loadFailed = true) }
                }
                .collect { tables -> _uiState.update { it.copy(tables = tables, loadFailed = false) } }
        }
    }

    /** [uri] is null when the user backed out of the picker. The table is named after the file, minus `.csv`. */
    fun onFilePicked(uri: String?) {
        if (uri == null) return
        viewModelScope.launch {
            val fileName = documents.displayName(uri)?.trim().orEmpty()
            val name = if (fileName.endsWith(CSV_EXTENSION, ignoreCase = true)) fileName.dropLast(CSV_EXTENSION.length) else fileName
            _uiState.update { it.copy(pendingImport = PendingImport(uri, name.trim().take(MAX_NAME_LENGTH))) }
        }
    }

    fun onPendingNameChanged(name: String) {
        _uiState.update { state -> state.copy(pendingImport = state.pendingImport?.copy(name = name.take(MAX_NAME_LENGTH))) }
    }

    fun onDismissPendingImport() {
        _uiState.update { it.copy(pendingImport = null) }
    }

    fun onConfirmImport() {
        val state = _uiState.value
        val pending = state.pendingImport ?: return
        val name = pending.name.trim()
        if (name.isEmpty() || state.importing) return
        _uiState.update { it.copy(pendingImport = null, importing = true, lastImport = null, actionFailed = false) }
        viewModelScope.launch {
            val result = repository.import(name) { documents.open(pending.uri) }
            _uiState.update { it.copy(importing = false, lastImport = name to result) }
        }
    }

    fun onDismissImportSummary() {
        _uiState.update { it.copy(lastImport = null) }
    }

    fun onMoveUp(id: Long) = runAction { repository.move(id, -1) }

    fun onMoveDown(id: Long) = runAction { repository.move(id, +1) }

    fun onDeleteRequested(table: NutritionTableSummary) {
        _uiState.update { it.copy(confirmDelete = table) }
    }

    fun onDeleteDismissed() {
        _uiState.update { it.copy(confirmDelete = null) }
    }

    fun onDeleteConfirmed() {
        val table = _uiState.value.confirmDelete ?: return
        _uiState.update { it.copy(confirmDelete = null) }
        runAction { repository.delete(table.id) }
    }

    /** Room reports a failed write as a runtime exception. */
    private fun runAction(action: suspend () -> Unit) {
        _uiState.update { it.copy(actionFailed = false) }
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                _uiState.update { it.copy(actionFailed = true) }
            }
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
        private const val CSV_EXTENSION = ".csv"
    }
}
