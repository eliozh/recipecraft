package com.elio.recipecraft.data.llm

import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a model's translation into a recipe the app can store, so that the model can change only
 * the text.
 *
 * [merge] never copies structure from the reply: it starts from the original recipe and takes from
 * the reply only the text fields, position by position (the name, each ingredient's `name`,
 * `quantity` and `unit`, each tool, each step's `text`). Ids, set, meal type, servings, calories,
 * grams, `aiKcal`, `nameEn`, step numbers and minutes all come from the original. Because the
 * merge is positional, it first checks that the reply lines up with the original: the same number
 * of ingredients, tools and steps, and every number or match key the reply repeats (servings,
 * grams, `nameEn`, minutes) equal to the original at the same position, so a reordered or
 * shortened reply is rejected rather than mixing one ingredient's name with another's grams.
 * Finally [isTextOnlyChange] checks the merged result against the original.
 */
object RecipeTranslation {

    /**
     * The original [recipe] with the text of [reply], in [target], updated at [now]; null when the
     * reply does not line up with the recipe or leaves a text field blank.
     */
    fun merge(recipe: Recipe, reply: RecipeWire, target: Language, now: Long): Recipe? {
        if (!linesUp(recipe, reply)) return null
        val translated = recipe.copy(
            name = reply.name.trim(),
            ingredients = recipe.ingredients.zip(reply.ingredients) { original, wire ->
                original.copy(name = wire.name.trim(), quantity = wire.quantity.trim(), unit = wire.unit.trim())
            },
            tools = reply.tools.map(String::trim),
            steps = recipe.steps.zip(reply.steps) { original, wire -> original.copy(text = wire.text.trim()) },
            language = target,
            updatedAt = maxOf(now, recipe.updatedAt),
        )
        return translated.takeIf { isTextOnlyChange(recipe, it) }
    }

    /**
     * True when [translated] differs from [original] only in its text fields, `language` and
     * `updatedAt`, and has no blank name, ingredient name, tool or step.
     */
    fun isTextOnlyChange(original: Recipe, translated: Recipe): Boolean =
        original.withoutText() == translated.withoutText() &&
            translated.name.isNotBlank() &&
            translated.ingredients.all { it.name.isNotBlank() } &&
            translated.tools.all { it.isNotBlank() } &&
            translated.steps.all { it.text.isNotBlank() }

    private fun Recipe.withoutText() = copy(
        name = "",
        ingredients = ingredients.map { it.copy(name = "", quantity = "", unit = "") },
        tools = tools.map { "" },
        steps = steps.map { it.copy(text = "") },
        language = Language.EN,
        updatedAt = 0,
    )

    /** The reply has the recipe's shape, and whatever numbers and keys it repeats match by position. */
    private fun linesUp(recipe: Recipe, reply: RecipeWire): Boolean {
        if (reply.ingredients.size != recipe.ingredients.size) return false
        if (reply.tools.size != recipe.tools.size || reply.steps.size != recipe.steps.size) return false
        if (reply.servings != null && reply.servings.roundToInt() != recipe.servings) return false
        val ingredientsMatch = recipe.ingredients.zip(reply.ingredients).all { (original, wire) ->
            (wire.nameEn.isBlank() || wire.nameEn.trim().equals(original.nameEn, ignoreCase = true)) &&
                sameIfBothKnown(original.grams, wire.grams)
        }
        val stepsMatch = recipe.steps.zip(reply.steps).all { (original, wire) ->
            sameIfBothKnown(original.minutes?.toDouble(), wire.minutes)
        }
        return ingredientsMatch && stepsMatch
    }

    /** A value the model left out is not a change, since the merge keeps the original's anyway. */
    private fun sameIfBothKnown(original: Double?, reply: Double?) =
        original == null || reply == null || abs(original - reply) < TOLERANCE

    private const val TOLERANCE = 0.01
}
