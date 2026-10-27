package com.elio.recipecraft.domain

/** A recipe as the app keeps it. Text fields are in [language], the app language when it was generated. */
data class Recipe(
    val id: String,
    val name: String,
    /** Null when the model did not say which meal the recipe is for. */
    val mealType: MealType?,
    val servings: Int,
    val calories: Calories,
    val ingredients: List<Ingredient>,
    val tools: List<String>,
    val steps: List<Step>,
    val language: Language,
    /** Shared by the recipes of one full-day set; null for a single recipe. */
    val setId: String?,
    /** Epoch milliseconds. */
    val createdAt: Long,
    val updatedAt: Long,
)

data class Ingredient(
    /** The displayed name, in the recipe's language. */
    val name: String,
    /** Hidden match key for nutrition tables: a simple, singular, lowercase English name such as `egg`. */
    val nameEn: String,
    /** Free text, so "1/2" and "适量" survive: `2`, `1/2`, `a pinch`. */
    val quantity: String,
    val unit: String,
    /** The model's weight for the quantity, when a weight makes sense. */
    val grams: Double?,
    /** The model's calorie estimate for this ingredient. */
    val aiKcal: Int?,
)

data class Step(
    /** 1-based position in the method. */
    val index: Int,
    val text: String,
    val minutes: Int?,
)

data class Calories(
    /** The figure to show: the database total or [aiTotal], depending on [source]. Null when unknown. */
    val total: Int?,
    val perServing: Int?,
    val source: CalorieSource,
    /** The model's own estimate, always kept so the app can fall back to it. */
    val aiTotal: Int?,
)

enum class CalorieSource { DATABASE, AI_ESTIMATE }

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK, DESSERT }

/** The recipes from one reply. */
data class RecipeBatch(val recipes: List<Recipe>)
