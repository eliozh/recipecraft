package com.elio.recipecraft.data.llm

import com.elio.recipecraft.data.nutrition.CalorieCalculator
import com.elio.recipecraft.data.nutrition.NutritionLookup
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.GenerationRequest
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

/** How [RecipeAssembler.assemble] gives recipes a shared setId. */
enum class SetGrouping {
    /** A single-meal request: every setId stays null. */
    NONE,

    /** Free-form chat: recipes share a setId by their `set` number when every recipe has one; otherwise none. */
    NUMBERED,

    /**
     * A full-day request: by `set` number when every recipe has one. Otherwise, as a fallback, by meal
     * order: walking the reply in order, a breakfast, lunch or dinner that does not come later in the
     * day than the previous one starts the next set, so B L D B L D makes two sets. A snack, a dessert
     * or a recipe without a meal type joins the current set.
     */
    FULL_DAY,
}

/** Turns the model's wire recipes into domain recipes: the app owns ids, timestamps, language and calorie source. */
class RecipeAssembler(
    private val clock: Clock,
    private val newId: () -> String,
) {
    @Inject
    constructor(clock: Clock) : this(clock, { UUID.randomUUID().toString() })

    /**
     * [language] is the prompt language the reply was generated in. [lookups] are the nutrition tables
     * [CalorieCalculator] tries, in order; with none, every recipe shows the model's estimate.
     */
    fun assemble(
        batch: RecipeBatchWire,
        language: Language,
        lookups: List<NutritionLookup> = emptyList(),
        grouping: SetGrouping = SetGrouping.NONE,
    ): RecipeBatch {
        val now = clock.millis()
        val sets = setNumbers(batch.recipes, grouping)
        val setIds = sets.filterNotNull().distinct().associateWith { newId() }
        return RecipeBatch(
            batch.recipes.zip(sets)
                // Stable, so the recipes of a set stay together and in the model's order.
                .sortedBy { (_, set) -> set ?: 0 }
                .map { (wire, set) ->
                    val recipe = wire.toRecipe(language, now).copy(setId = set?.let(setIds::getValue))
                    recipe.copy(calories = CalorieCalculator.calculate(recipe, lookups))
                },
        )
    }

    /** The set each recipe belongs to, by index; null for a recipe outside any set. */
    private fun setNumbers(recipes: List<RecipeWire>, grouping: SetGrouping): List<Int?> {
        val numbers = recipes.map { wire -> wire.set?.roundToInt()?.takeIf { it >= 1 } }
        val allNumbered = numbers.all { it != null }
        return when {
            grouping == SetGrouping.NONE -> numbers.map { null }
            allNumbered -> numbers
            grouping == SetGrouping.FULL_DAY -> byMealOrder(recipes)
            else -> numbers.map { null }
        }
    }

    private fun byMealOrder(recipes: List<RecipeWire>): List<Int> {
        var set = 0
        var lastRank = -1
        return recipes.map { wire ->
            val rank = GenerationRequest.FULL_DAY_MEALS.indexOf(mealTypeOf(wire.mealType))
            if (set == 0 || (rank >= 0 && rank <= lastRank)) {
                set++
                lastRank = -1
            }
            if (rank >= 0) lastRank = rank
            set
        }
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
            // The model's estimate; assemble() swaps in a database total when every ingredient matches.
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
