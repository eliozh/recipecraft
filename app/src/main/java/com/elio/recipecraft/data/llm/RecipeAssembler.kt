package com.elio.recipecraft.data.llm

import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.RecipeBatch
import com.elio.recipecraft.domain.Step
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/** Turns the model's wire recipes into domain recipes: the app owns ids, timestamps, language and calorie source. */
class RecipeAssembler(
    private val clock: Clock,
    private val newId: () -> String,
) {
    @Inject
    constructor(clock: Clock) : this(clock, { UUID.randomUUID().toString() })

    /** [language] is the prompt language the reply was generated in. */
    fun assemble(batch: RecipeBatchWire, language: Language): RecipeBatch {
        val now = clock.millis()
        return RecipeBatch(batch.recipes.map { it.toRecipe(language, now) })
    }

    private fun RecipeWire.toRecipe(language: Language, now: Long): Recipe {
        val servings = servings?.roundToInt()?.takeIf { it >= 1 } ?: 1
        val ingredients = ingredients.map { it.toIngredient(language) }
        val aiTotal = aiKcal?.takeIf { it >= 0 }?.roundToInt()
            ?: ingredients.mapNotNull { it.aiKcal }.takeIf { it.isNotEmpty() }?.sum()
        return Recipe(
            id = newId(),
            name = name.trim(),
            mealType = mealTypeOf(mealType),
            servings = servings,
            // Until database matching exists, the model's estimate is the only figure.
            calories = Calories(
                total = aiTotal,
                perServing = aiTotal?.let { (it.toDouble() / servings).roundToInt() },
                source = CalorieSource.AI_ESTIMATE,
                aiTotal = aiTotal,
            ),
            ingredients = ingredients,
            tools = tools.map(String::trim).filter(String::isNotEmpty),
            steps = steps.mapIndexed { i, step ->
                Step(index = i + 1, text = step.text.trim(), minutes = step.minutes?.roundToInt()?.takeIf { it > 0 })
            },
            language = language,
            setId = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun IngredientWire.toIngredient(language: Language): Ingredient {
        val key = nameEn.trim().lowercase()
        return Ingredient(
            name = name.trim(),
            // An English display name is the next best match key; a Chinese one is useless to USDA.
            nameEn = key.ifEmpty { if (language == Language.EN) name.trim().lowercase() else "" },
            quantity = quantity.trim(),
            unit = unit.trim(),
            grams = grams?.takeIf { it > 0 },
            aiKcal = aiKcal?.takeIf { it >= 0 }?.roundToInt(),
        )
    }

    private fun mealTypeOf(value: String?): MealType? =
        MealType.entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
}
