package com.elio.recipecraft.data.recipe

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step

/** A saved recipe. The list fields and [calories] are JSON columns, through [Converters]. */
@Entity(tableName = "recipes", indices = [Index("createdAt"), Index("mealType")])
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mealType: MealType?,
    val servings: Int,
    val calories: Calories,
    val ingredients: List<Ingredient>,
    val tools: List<String>,
    val steps: List<Step>,
    val language: Language,
    val setId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** The recipe name and every ingredient's `name` and `nameEn`, lowercased, so search is one LIKE on one column. */
    val searchText: String,
)

fun Recipe.toEntity() = RecipeEntity(
    id = id,
    name = name,
    mealType = mealType,
    servings = servings,
    calories = calories,
    ingredients = ingredients,
    tools = tools,
    steps = steps,
    language = language,
    setId = setId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    searchText = searchTextOf(this),
)

fun RecipeEntity.toDomain() = Recipe(
    id = id,
    name = name,
    mealType = mealType,
    servings = servings,
    calories = calories,
    ingredients = ingredients,
    tools = tools,
    steps = steps,
    language = language,
    setId = setId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** Lines rather than spaces, so a search cannot match across the end of one name and the start of the next. */
internal fun searchTextOf(recipe: Recipe): String =
    (listOf(recipe.name) + recipe.ingredients.flatMap { listOf(it.name, it.nameEn) })
        .joinToString("\n") { it.trim() }
        .lowercase()
