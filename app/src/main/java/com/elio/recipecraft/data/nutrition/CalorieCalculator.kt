package com.elio.recipecraft.data.nutrition

import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Recipe
import kotlin.math.roundToInt

/**
 * Picks the calorie figure a recipe shows: the database total when every ingredient that carries
 * calories matched a table, otherwise the model's own estimate. [Calories.aiTotal] is always kept.
 *
 * - Each ingredient tries the lookups in order and the first match wins (imported tables go before USDA).
 * - Only ingredients with `grams` count towards the database total: Σ grams × kcal / 100, rounded.
 * - An ingredient without `grams` (such as "salt to taste") is ignored, unless the model gave it more
 *   than [SIGNIFICANT_AI_KCAL] kcal. Then it carries calories the database cannot weigh, so it counts
 *   as unmatched; this keeps the rule that the database total needs every ingredient with calories matched.
 * - Any unmatched ingredient, or no ingredient with `grams` at all, gives [CalorieSource.AI_ESTIMATE].
 */
object CalorieCalculator {

    const val SIGNIFICANT_AI_KCAL = 20

    fun calculate(recipe: Recipe, lookups: List<NutritionLookup>): Calories {
        val aiTotal = recipe.calories.aiTotal
        val fallback = Calories(
            total = aiTotal,
            perServing = aiTotal?.let { perServing(it, recipe.servings) },
            source = CalorieSource.AI_ESTIMATE,
            aiTotal = aiTotal,
        )
        val weighed = recipe.ingredients.filter { it.grams != null }
        if (weighed.isEmpty()) return fallback
        if (recipe.ingredients.any { it.grams == null && (it.aiKcal ?: 0) > SIGNIFICANT_AI_KCAL }) return fallback

        var kcal = 0.0
        for (ingredient in weighed) {
            val per100g = ingredient.kcalPer100g(recipe, lookups) ?: return fallback
            kcal += ingredient.grams!! * per100g / 100
        }
        val total = kcal.roundToInt()
        return Calories(total, perServing(total, recipe.servings), CalorieSource.DATABASE, aiTotal)
    }

    /**
     * The database kcal of each ingredient of [recipe], in order: grams × kcal / 100, rounded, from
     * the first of [lookups] that matches it. Null for an ingredient without `grams` or with no match.
     */
    fun ingredientKcal(recipe: Recipe, lookups: List<NutritionLookup>): List<Int?> =
        recipe.ingredients.map { ingredient ->
            val grams = ingredient.grams ?: return@map null
            ingredient.kcalPer100g(recipe, lookups)?.let { (grams * it / 100).roundToInt() }
        }

    private fun Ingredient.kcalPer100g(recipe: Recipe, lookups: List<NutritionLookup>): Double? =
        lookups.firstNotNullOfOrNull { it.kcalPer100g(this, recipe.language) }

    private fun perServing(total: Int, servings: Int) = (total.toDouble() / servings.coerceAtLeast(1)).roundToInt()
}
