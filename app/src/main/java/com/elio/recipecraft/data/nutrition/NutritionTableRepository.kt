package com.elio.recipecraft.data.nutrition

import com.elio.recipecraft.di.IoDispatcher
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface TableImportResult {
    /** [problems] are the first few skipped rows; [skipped] counts them all. */
    data class Imported(
        val tableId: Long,
        val imported: Int,
        val skipped: Int,
        val problems: List<RowProblem>,
    ) : TableImportResult

    data class Failed(val error: TableImportError, val skipped: Int = 0) : TableImportResult
}

/** The user's imported nutrition tables: import from CSV, reorder, delete. */
@Singleton
class NutritionTableRepository @Inject constructor(
    private val dao: NutritionTableDao,
    private val importer: CsvTableImporter,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    // Reorders read the order and write it back, so they run one at a time.
    private val orderMutex = Mutex()

    /** The tables in priority order, with their food counts. */
    val tables: Flow<List<NutritionTableSummary>> = dao.observeTables()

    /** Parses the CSV that [open] returns and stores it as a table named [name], after the existing tables. */
    suspend fun import(name: String, open: () -> InputStream): TableImportResult = withContext(ioDispatcher) {
        val parsed = try {
            open().use(importer::parse)
        } catch (_: IOException) {
            return@withContext TableImportResult.Failed(TableImportError.UNREADABLE)
        } catch (_: SecurityException) {
            // The document provider revoked access.
            return@withContext TableImportResult.Failed(TableImportError.UNREADABLE)
        }
        when (parsed) {
            is CsvParseResult.Failure -> TableImportResult.Failed(parsed.error, parsed.skipped)
            is CsvParseResult.Success -> try {
                val id = orderMutex.withLock { dao.insertTableWithFoods(name, clock.millis(), parsed.foods) }
                TableImportResult.Imported(id, parsed.foods.size, parsed.skipped, parsed.problems)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                TableImportResult.Failed(TableImportError.SAVE_FAILED, parsed.skipped)
            }
        }
    }

    /** Moves the table one place earlier ([offset] -1) or later (+1); a move past either end does nothing. */
    suspend fun move(id: Long, offset: Int) = orderMutex.withLock {
        val ids = dao.tableIdsInOrder().toMutableList()
        val from = ids.indexOf(id)
        val to = from + offset
        if (from < 0 || to !in ids.indices) return@withLock
        ids.add(to, ids.removeAt(from))
        dao.setOrder(ids)
    }

    suspend fun delete(id: Long) = orderMutex.withLock { dao.deleteTable(id) }
}
