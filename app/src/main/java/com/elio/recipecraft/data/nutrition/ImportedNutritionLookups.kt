package com.elio.recipecraft.data.nutrition

import com.elio.recipecraft.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The user's imported tables in priority order, each matched with [importedTableKey].
 *
 * A table's foods never change after import, so each table is read into memory once and kept
 * until the table list no longer holds it. The list itself comes from Room's Flow, so an import,
 * reorder or delete shows up on the next read and in [observe].
 */
@Singleton
class ImportedNutritionLookups @Inject constructor(
    private val dao: NutritionTableDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : NutritionLookups {

    private val mutex = Mutex()

    // Keyed by id and import time, so a table can never pick up another table's cached foods.
    private val cache = mutableMapOf<Pair<Long, Long>, NutritionLookup>()

    override suspend fun current(): List<NutritionLookup> = observe().first()

    /** A database that cannot be read (Room throws runtime exceptions) leaves no imported tables, so recipes still get USDA or the AI estimate. */
    override fun observe(): Flow<List<NutritionLookup>> =
        dao.observeTables()
            .map { tables -> lookupsFor(tables) }
            .catch { if (it is RuntimeException && it !is CancellationException) emit(emptyList()) else throw it }

    private suspend fun lookupsFor(tables: List<NutritionTableSummary>): List<NutritionLookup> = mutex.withLock {
        val keys = tables.map { it.id to it.importedAt }
        cache.keys.retainAll(keys.toSet())
        keys.map { key ->
            cache.getOrPut(key) {
                val foods = withContext(ioDispatcher) { dao.foods(key.first) }.map { it.toFood() }
                TableLookup(IngredientMatcher(foods), keyOf = ::importedTableKey)
            }
        }
    }
}

/** What the calorie calculator uses: the imported tables in priority order, then USDA. */
@Singleton
class CombinedNutritionLookups @Inject constructor(
    private val imported: ImportedNutritionLookups,
    private val usda: UsdaNutritionLookups,
) : NutritionLookups {

    override suspend fun current(): List<NutritionLookup> = imported.current() + usda.current()

    override fun observe(): Flow<List<NutritionLookup>> = imported.observe().map { it + usda.current() }
}
