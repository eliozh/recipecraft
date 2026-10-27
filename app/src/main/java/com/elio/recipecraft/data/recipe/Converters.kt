package com.elio.recipecraft.data.recipe

import androidx.room.TypeConverter
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Stores a recipe's list fields and calories as JSON. The JSON follows the internal `Stored*` classes
 * below rather than the domain classes, so renaming a domain property cannot silently change what
 * is on disk; a change to these classes needs a migration.
 */
class Converters {

    @TypeConverter
    fun ingredientsToJson(value: List<Ingredient>): String = json.encodeToString(value.map { it.toStored() })

    @TypeConverter
    fun ingredientsFromJson(value: String): List<Ingredient> =
        json.decodeFromString<List<StoredIngredient>>(value).map { it.toDomain() }

    @TypeConverter
    fun stepsToJson(value: List<Step>): String = json.encodeToString(value.map { it.toStored() })

    @TypeConverter
    fun stepsFromJson(value: String): List<Step> =
        json.decodeFromString<List<StoredStep>>(value).map { it.toDomain() }

    @TypeConverter
    fun stringsToJson(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun stringsFromJson(value: String): List<String> = json.decodeFromString(value)

    @TypeConverter
    fun caloriesToJson(value: Calories): String =
        json.encodeToString(value.toStored())

    @TypeConverter
    fun caloriesFromJson(value: String): Calories =
        json.decodeFromString<StoredCalories>(value).toDomain()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal data class StoredIngredient(
    val name: String,
    val nameEn: String,
    val quantity: String,
    val unit: String,
    val grams: Double? = null,
    val aiKcal: Int? = null,
)

internal fun Ingredient.toStored() = StoredIngredient(name, nameEn, quantity, unit, grams, aiKcal)

internal fun StoredIngredient.toDomain() = Ingredient(name, nameEn, quantity, unit, grams, aiKcal)

@Serializable
internal data class StoredStep(val index: Int, val text: String, val minutes: Int? = null)

internal fun Step.toStored() = StoredStep(index, text, minutes)

internal fun StoredStep.toDomain() = Step(index, text, minutes)

@Serializable
internal data class StoredCalories(
    val total: Int? = null,
    val perServing: Int? = null,
    val source: CalorieSource,
    val aiTotal: Int? = null,
)

internal fun Calories.toStored() = StoredCalories(total, perServing, source, aiTotal)

internal fun StoredCalories.toDomain() = Calories(total, perServing, source, aiTotal)

/** A whole recipe, for the recipes a chat reply holds; saved recipes use their own columns instead. */
@Serializable
internal data class StoredRecipe(
    val id: String,
    val name: String,
    val mealType: MealType? = null,
    val servings: Int,
    val calories: StoredCalories,
    val ingredients: List<StoredIngredient>,
    val tools: List<String>,
    val steps: List<StoredStep>,
    val language: Language,
    val setId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

internal fun Recipe.toStored() = StoredRecipe(
    id = id,
    name = name,
    mealType = mealType,
    servings = servings,
    calories = calories.toStored(),
    ingredients = ingredients.map { it.toStored() },
    tools = tools,
    steps = steps.map { it.toStored() },
    language = language,
    setId = setId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun StoredRecipe.toDomain() = Recipe(
    id = id,
    name = name,
    mealType = mealType,
    servings = servings,
    calories = calories.toDomain(),
    ingredients = ingredients.map { it.toDomain() },
    tools = tools,
    steps = steps.map { it.toDomain() },
    language = language,
    setId = setId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
