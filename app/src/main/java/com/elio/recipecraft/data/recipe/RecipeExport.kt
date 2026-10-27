package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * The file that Export writes and Import reads: UTF-8 JSON, version [VERSION].
 *
 * ```json
 * {
 *   "version": 1,
 *   "exportedAt": 1761609600000,
 *   "recipes": [
 *     {
 *       "id": "…", "name": "…", "mealType": "BREAKFAST", "servings": 2,
 *       "calories": { "total": 420, "perServing": 210, "source": "DATABASE", "aiTotal": 400 },
 *       "ingredients": [
 *         { "name": "鸡蛋", "nameEn": "egg", "quantity": "3", "unit": "个", "grams": 150.0, "aiKcal": 210 }
 *       ],
 *       "tools": ["…"],
 *       "steps": [ { "index": 1, "text": "…", "minutes": 2 } ],
 *       "language": "ZH", "setId": null, "createdAt": 1761609600000, "updatedAt": 1761609600000
 *     }
 *   ]
 * }
 * ```
 *
 * - Every key is always written; a field without a value is `null`. Times are epoch milliseconds.
 * - `mealType` is one of [MealType] or null, `language` one of [Language], `source` one of [CalorieSource].
 * - The file holds saved recipes only. API keys, provider settings, nutrition tables and chats are
 *   never part of it.
 * - These classes, not the domain classes, define the format, so renaming a domain property cannot
 *   change it. Any change to what they write needs a new [VERSION]; a file with a newer version is
 *   rejected on import.
 */
@Serializable
data class RecipeExport(
    val version: Int,
    val exportedAt: Long,
    val recipes: List<ExportedRecipe>,
) {
    companion object {
        /** The newest format this app writes and reads. */
        const val VERSION = 1
    }
}

@Serializable
data class ExportedRecipe(
    val id: String,
    val name: String,
    val mealType: MealType? = null,
    val servings: Int,
    val calories: ExportedCalories,
    val ingredients: List<ExportedIngredient>,
    val tools: List<String>,
    val steps: List<ExportedStep>,
    val language: Language,
    val setId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ExportedCalories(
    val total: Int? = null,
    val perServing: Int? = null,
    val source: CalorieSource,
    val aiTotal: Int? = null,
)

@Serializable
data class ExportedIngredient(
    val name: String,
    val nameEn: String,
    val quantity: String,
    val unit: String,
    val grams: Double? = null,
    val aiKcal: Int? = null,
)

@Serializable
data class ExportedStep(val index: Int, val text: String, val minutes: Int? = null)

fun Recipe.toExported() = ExportedRecipe(
    id = id,
    name = name,
    mealType = mealType,
    servings = servings,
    calories = ExportedCalories(calories.total, calories.perServing, calories.source, calories.aiTotal),
    ingredients = ingredients.map { ExportedIngredient(it.name, it.nameEn, it.quantity, it.unit, it.grams, it.aiKcal) },
    tools = tools,
    steps = steps.map { ExportedStep(it.index, it.text, it.minutes) },
    language = language,
    setId = setId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ExportedRecipe.toDomain() = Recipe(
    id = id,
    name = name,
    mealType = mealType,
    servings = servings,
    calories = Calories(calories.total, calories.perServing, calories.source, calories.aiTotal),
    ingredients = ingredients.map { Ingredient(it.name, it.nameEn, it.quantity, it.unit, it.grams, it.aiKcal) },
    tools = tools,
    steps = steps.map { Step(it.index, it.text, it.minutes) },
    language = language,
    setId = setId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** The name Export suggests for a file written on [date]: `recipecraft-2026-10-28.json`. */
fun exportFileName(date: LocalDate): String = "recipecraft-$date.json"
