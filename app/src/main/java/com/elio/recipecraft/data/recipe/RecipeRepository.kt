package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.data.nutrition.CalorieCalculator
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.di.IoDispatcher
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The user's saved recipes. */
@Singleton
class RecipeRepository @Inject constructor(
    private val dao: RecipeDao,
    private val nutrition: NutritionLookups,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** The ids of every saved recipe, so the chat can mark cards that are already saved. */
    val savedIds: Flow<Set<String>> = dao.observeIds().map { it.toSet() }

    val count: Flow<Int> = dao.observeCount()

    /**
     * Saved recipes of [mealType] (null for all) whose name or ingredient names contain [query],
     * ignoring case, newest first.
     */
    fun observe(mealType: MealType?, query: String): Flow<List<Recipe>> =
        dao.observe(mealType, RecipeDao.escapeLike(query.trim().lowercase()))
            .map { entities -> entities.map { it.toDomain() } }

    /**
     * Saves [recipes], replacing any saved recipe with the same id. Calories are worked out again
     * against the current tables, in case a table changed since the recipe was generated.
     */
    suspend fun save(recipes: List<Recipe>) {
        if (recipes.isEmpty()) return
        val lookups = nutrition.current()
        val entities = withContext(ioDispatcher) {
            recipes.map { it.copy(calories = CalorieCalculator.calculate(it, lookups)).toEntity() }
        }
        dao.upsert(entities)
    }

    /**
     * Recalculates every saved recipe's calories against [lookups] and writes back only those that
     * changed. The model's own estimate is kept. Returns how many recipes changed.
     */
    suspend fun recalculate(lookups: List<NutritionLookup>): Int {
        val changes = withContext(ioDispatcher) {
            dao.all()
                .map { it.toDomain() }
                .mapNotNull { recipe ->
                    val calories = CalorieCalculator.calculate(recipe, lookups)
                    if (calories == recipe.calories) null else recipe.id to calories
                }
                .toMap()
        }
        if (changes.isNotEmpty()) dao.setCalories(changes)
        return changes.size
    }
}
