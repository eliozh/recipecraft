package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.data.nutrition.CalorieCalculator
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.di.IoDispatcher
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
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
     * The saved recipe [id] with the database kcal of each of its ingredients, against the current
     * tables; null when no recipe has that id, such as after it was deleted.
     */
    fun observeDetail(id: String): Flow<RecipeDetail?> =
        dao.observeById(id)
            .map { entity ->
                entity?.toDomain()?.let { RecipeDetail(it, CalorieCalculator.ingredientKcal(it, nutrition.current())) }
            }
            .flowOn(ioDispatcher)

    /** Deletes the saved recipe [id], if there is one. */
    suspend fun delete(id: String) = dao.deleteById(id)

    /**
     * Puts back a recipe exactly as it was, such as one just deleted, without working out its
     * calories again, so the restored record is identical.
     */
    suspend fun restore(recipe: Recipe) = dao.upsert(listOf(recipe.toEntity()))

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

    /** Saved recipes in a language other than [language], newest first. */
    suspend fun notIn(language: Language): List<Recipe> = dao.notIn(language).map { it.toDomain() }

    /**
     * Writes [recipe] over the saved recipe with its id, with calories worked out again, since a
     * translated recipe matches tables on different names. Returns false, and saves nothing, when
     * that recipe is no longer saved.
     */
    suspend fun update(recipe: Recipe): Boolean {
        val lookups = nutrition.current()
        val entity = withContext(ioDispatcher) { recipe.copy(calories = CalorieCalculator.calculate(recipe, lookups)).toEntity() }
        return dao.update(entity) > 0
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

/** A saved recipe as its detail screen shows it. */
data class RecipeDetail(
    val recipe: Recipe,
    /** The database kcal of each ingredient, in the order of [Recipe.ingredients]; null where it did not match. */
    val ingredientKcal: List<Int?>,
)
