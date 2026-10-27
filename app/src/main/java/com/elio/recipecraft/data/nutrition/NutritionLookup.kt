package com.elio.recipecraft.data.nutrition

import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language

/** One nutrition table as the calorie calculator sees it. */
fun interface NutritionLookup {
    /** Energy per 100 g for [ingredient] of a recipe written in [language], or null when this table has no match. */
    fun kcalPer100g(ingredient: Ingredient, language: Language): Double?
}

/**
 * A table matched through an [IngredientMatcher] on the key [keyOf] picks. USDA, and every table
 * for an English recipe, uses `nameEn`.
 */
class TableLookup(
    private val matcher: IngredientMatcher,
    private val keyOf: (Ingredient, Language) -> String = { ingredient, _ -> ingredient.nameEn },
) : NutritionLookup {
    override fun kcalPer100g(ingredient: Ingredient, language: Language): Double? =
        matcher.match(keyOf(ingredient, language))?.kcalPer100g
}

/** The enabled tables, in the order the calculator tries them. */
interface NutritionLookups {
    suspend fun current(): List<NutritionLookup>
}
