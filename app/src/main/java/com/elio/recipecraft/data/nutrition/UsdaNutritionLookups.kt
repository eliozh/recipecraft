package com.elio.recipecraft.data.nutrition

import com.elio.recipecraft.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The bundled USDA table, read into memory once: it is only a few hundred rows. */
@Singleton
class UsdaNutritionLookups @Inject constructor(
    private val dao: UsdaFoodDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : NutritionLookups {

    private val mutex = Mutex()
    private var usda: NutritionLookup? = null

    override suspend fun current(): List<NutritionLookup> = listOfNotNull(load())

    /**
     * A table that cannot be copied or read (Room reports both as runtime exceptions) leaves no lookup,
     * so recipes fall back to the AI estimate instead of crashing the chat; the next call tries again.
     */
    private suspend fun load(): NutritionLookup? = mutex.withLock {
        usda ?: try {
            val foods = withContext(ioDispatcher) { dao.all() }.map { it.toFood() }
            TableLookup(IngredientMatcher(foods)).also { usda = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun UsdaFoodEntity.toFood() = NutritionFood(
        name = name,
        aliases = aliases.split('|').map(String::trim).filter(String::isNotEmpty),
        kcalPer100g = kcalPer100g,
    )
}
