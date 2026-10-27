package com.elio.recipecraft.testing

import com.elio.recipecraft.data.nutrition.IngredientMatcher
import com.elio.recipecraft.data.nutrition.NutritionFood
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.data.nutrition.TableLookup
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun ingredient(name: String, nameEn: String = name, grams: Double? = 100.0) =
    Ingredient(name = name, nameEn = nameEn, quantity = "1", unit = "", grams = grams, aiKcal = 100)

/**
 * A recipe whose calories are what [com.elio.recipecraft.data.nutrition.CalorieCalculator] gives
 * with no tables: the model's [aiTotal], split over two servings.
 */
fun testRecipe(
    id: String,
    name: String = "Recipe $id",
    mealType: MealType? = MealType.BREAKFAST,
    ingredients: List<Ingredient> = listOf(ingredient("egg")),
    aiTotal: Int? = 300,
    language: Language = Language.EN,
    setId: String? = null,
    createdAt: Long = 1_000,
) = Recipe(
    id = id,
    name = name,
    mealType = mealType,
    servings = 2,
    calories = Calories(aiTotal, aiTotal?.let { it / 2 }, CalorieSource.AI_ESTIMATE, aiTotal),
    ingredients = ingredients,
    tools = listOf("pan"),
    steps = listOf(Step(1, "Cook", 5), Step(2, "Serve", null)),
    language = language,
    setId = setId,
    createdAt = createdAt,
    updatedAt = createdAt,
)

/** A table that knows [foods] (name to kcal per 100 g), matched on `nameEn`. */
fun lookupOf(vararg foods: Pair<String, Double>): NutritionLookup =
    TableLookup(IngredientMatcher(foods.map { (name, kcal) -> NutritionFood(name, emptyList(), kcal) }))

/**
 * A faithful model reply that translates [recipe]: every text field prefixed with [prefix], every
 * number and match key as it was.
 */
fun translatedReply(recipe: Recipe, prefix: String = "译 "): String = buildJsonObject {
    put("name", prefix + recipe.name)
    put("servings", recipe.servings)
    put(
        "ingredients",
        JsonArray(
            recipe.ingredients.map {
                buildJsonObject {
                    put("name", prefix + it.name)
                    put("nameEn", it.nameEn)
                    put("quantity", it.quantity)
                    put("unit", prefix + it.unit)
                    put("grams", it.grams)
                    put("aiKcal", it.aiKcal)
                }
            },
        ),
    )
    put("tools", JsonArray(recipe.tools.map { JsonPrimitive(prefix + it) }))
    put(
        "steps",
        JsonArray(
            recipe.steps.map {
                buildJsonObject {
                    put("text", prefix + it.text)
                    put("minutes", it.minutes)
                }
            },
        ),
    )
}.toString()
