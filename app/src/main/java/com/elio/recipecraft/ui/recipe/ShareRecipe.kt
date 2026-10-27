package com.elio.recipecraft.ui.recipe

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.elio.recipecraft.R
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import java.text.NumberFormat
import java.util.Locale

/** The words of a shared recipe, in the recipe's language. */
interface ShareLabels {
    val locale: Locale
    val ingredients: String
    val tools: String
    val steps: String
    val disclaimer: String
    val caloriesUnknown: String
    fun mealType(type: MealType): String
    fun servings(count: Int): String
    fun source(source: CalorieSource): String
    fun caloriesTotal(kcal: Int): String
    fun caloriesPerServing(kcal: Int): String
    fun ingredientLine(quantity: String, unit: String, name: String): String
    fun ingredientUnquantified(name: String, unit: String): String
    fun grams(formatted: String): String
    fun minutes(minutes: Int): String
}

/**
 * [recipe] as Markdown that also reads well as plain text: the name, meal and servings, calories
 * with their source and the disclaimer, then the ingredients (with weights), tools and steps.
 */
fun shareText(recipe: Recipe, labels: ShareLabels): String = buildString {
    appendLine("# ${recipe.name}")
    appendLine()
    appendLine(listOfNotNull(recipe.mealType?.let(labels::mealType), labels.servings(recipe.servings)).joinToString(" · "))
    val calories = recipe.calories
    val total = calories.total
    val figures = if (total == null) {
        listOf(labels.caloriesUnknown)
    } else {
        listOfNotNull(labels.caloriesTotal(total), calories.perServing?.let(labels::caloriesPerServing))
    }
    appendLine((listOf(labels.source(calories.source)) + figures).joinToString(" · "))
    appendLine("_${labels.disclaimer}_")

    appendLine()
    appendLine("## ${labels.ingredients}")
    val gramsFormat = NumberFormat.getNumberInstance(labels.locale).apply { maximumFractionDigits = 1 }
    recipe.ingredients.forEach { ingredient ->
        val line = when {
            ingredient.quantity.isNotEmpty() -> labels.ingredientLine(ingredient.quantity, ingredient.unit, ingredient.name)
            ingredient.unit.isNotEmpty() -> labels.ingredientUnquantified(ingredient.name, ingredient.unit)
            else -> ingredient.name
        }.replace(Regex("\\s+"), " ").trim()
        val grams = ingredient.grams?.let { " (${labels.grams(gramsFormat.format(it))})" }.orEmpty()
        appendLine("- $line$grams")
    }
    if (recipe.tools.isNotEmpty()) {
        appendLine()
        appendLine("## ${labels.tools}")
        recipe.tools.forEach { appendLine("- $it") }
    }
    appendLine()
    appendLine("## ${labels.steps}")
    recipe.steps.forEach { step ->
        val minutes = step.minutes?.let { " (${labels.minutes(it)})" }.orEmpty()
        appendLine("${step.index}. ${step.text}$minutes")
    }
}.trimEnd()

/** [ShareLabels] from the app's string resources in [language], whatever language the app shows. */
fun shareLabels(context: Context, language: Language): ShareLabels {
    val locale = when (language) {
        Language.EN -> Locale.ENGLISH
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
    }
    val configuration = Configuration(context.resources.configuration).apply { setLocales(LocaleList(locale)) }
    return ResourceShareLabels(context.createConfigurationContext(configuration).resources, locale)
}

private class ResourceShareLabels(private val res: Resources, override val locale: Locale) : ShareLabels {
    override val ingredients: String = res.getString(R.string.recipe_ingredients)
    override val tools: String = res.getString(R.string.recipe_tools)
    override val steps: String = res.getString(R.string.recipe_steps)
    override val disclaimer: String = res.getString(R.string.recipe_calories_disclaimer)
    override val caloriesUnknown: String = res.getString(R.string.recipe_calories_unknown)
    override fun mealType(type: MealType): String = res.getString(type.label)
    override fun servings(count: Int): String = res.getQuantityString(R.plurals.recipe_servings, count, count)
    override fun source(source: CalorieSource): String = res.getString(
        when (source) {
            CalorieSource.DATABASE -> R.string.recipe_calories_database
            CalorieSource.AI_ESTIMATE -> R.string.recipe_calories_ai_estimate
        },
    )
    override fun caloriesTotal(kcal: Int): String = res.getQuantityString(R.plurals.recipe_calories_total, kcal, kcal)
    override fun caloriesPerServing(kcal: Int): String = res.getQuantityString(R.plurals.recipe_calories_per_serving, kcal, kcal)
    override fun ingredientLine(quantity: String, unit: String, name: String): String =
        res.getString(R.string.recipe_ingredient_line, quantity, unit, name)
    override fun ingredientUnquantified(name: String, unit: String): String =
        res.getString(R.string.recipe_ingredient_unquantified, name, unit)
    override fun grams(formatted: String): String = res.getString(R.string.recipe_ingredient_grams, formatted)
    override fun minutes(minutes: Int): String = res.getString(R.string.recipe_step_minutes, minutes)
}

/** Opens the share sheet with [recipe] as plain text, in the recipe's own language. */
fun shareRecipe(context: Context, recipe: Recipe) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, recipe.name)
        .putExtra(Intent.EXTRA_TEXT, shareText(recipe, shareLabels(context, recipe.language)))
    context.startActivity(Intent.createChooser(send, context.getString(R.string.recipe_share)))
}
