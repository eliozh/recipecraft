package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps saved recipes' calories in step with the nutrition tables: each time the user imports,
 * reorders or deletes a table (and once at start, for changes made before), every saved recipe is
 * recalculated. A newer change cancels a recalculation still running.
 */
@Singleton
class SavedRecipeRecalculator @Inject constructor(
    private val repository: RecipeRepository,
    private val nutrition: NutritionLookups,
    @ApplicationScope private val scope: CoroutineScope,
) {

    fun start(): Job = scope.launch {
        nutrition.observe().collectLatest { lookups ->
            try {
                repository.recalculate(lookups)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // Room reports an unreadable or full database as a runtime exception. The recipes
                // keep their last calories, and the next table change tries again.
            }
        }
    }
}
