package com.elio.recipecraft.data.recipe

import androidx.room.TypeConverter
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Step
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Stores a recipe's list fields and calories as JSON. The JSON follows the private `Stored*` classes
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
    fun stepsToJson(value: List<Step>): String = json.encodeToString(value.map { StoredStep(it.index, it.text, it.minutes) })

    @TypeConverter
    fun stepsFromJson(value: String): List<Step> =
        json.decodeFromString<List<StoredStep>>(value).map { Step(it.index, it.text, it.minutes) }

    @TypeConverter
    fun stringsToJson(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun stringsFromJson(value: String): List<String> = json.decodeFromString(value)

    @TypeConverter
    fun caloriesToJson(value: Calories): String =
        json.encodeToString(StoredCalories(value.total, value.perServing, value.source, value.aiTotal))

    @TypeConverter
    fun caloriesFromJson(value: String): Calories =
        json.decodeFromString<StoredCalories>(value).let { Calories(it.total, it.perServing, it.source, it.aiTotal) }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class StoredIngredient(
    val name: String,
    val nameEn: String,
    val quantity: String,
    val unit: String,
    val grams: Double? = null,
    val aiKcal: Int? = null,
)

private fun Ingredient.toStored() = StoredIngredient(name, nameEn, quantity, unit, grams, aiKcal)

private fun StoredIngredient.toDomain() = Ingredient(name, nameEn, quantity, unit, grams, aiKcal)

@Serializable
private data class StoredStep(val index: Int, val text: String, val minutes: Int? = null)

@Serializable
private data class StoredCalories(
    val total: Int? = null,
    val perServing: Int? = null,
    val source: CalorieSource,
    val aiTotal: Int? = null,
)
