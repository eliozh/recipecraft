package com.elio.recipecraft.data.nutrition

import java.text.Normalizer
import java.util.Locale

/** A food in a nutrition table: its match key, alternative keys and energy. */
data class NutritionFood(
    val name: String,
    val aliases: List<String>,
    val kcalPer100g: Double,
)

/**
 * Normalized **exact** matching of ingredient keys against one table's names and aliases.
 *
 * Keys are NFKC-folded (so full-width `ｅｇｇ` reads as `egg`), lowercased, trimmed and have their
 * inner whitespace collapsed. When the key itself does not match, a few simple English plural
 * endings are stripped (`tomatoes` → `tomato`, `berries` → `berry`, `leaves` → `leaf`, `eggs` → `egg`).
 * There is no fuzzy or partial matching: `egg` never matches `eggplant`, because a wrong match is
 * worse than an honest AI estimate.
 */
class IngredientMatcher(foods: List<NutritionFood>) {

    private val index: Map<String, NutritionFood> = buildMap {
        // Names win over aliases when the same key appears as both.
        foods.forEach { food -> normalize(food.name).takeIf { it.isNotEmpty() }?.let { putIfAbsent(it, food) } }
        foods.forEach { food ->
            food.aliases.forEach { alias -> normalize(alias).takeIf { it.isNotEmpty() }?.let { putIfAbsent(it, food) } }
        }
    }

    /** The food whose name or alias equals [key] after normalization, or null. */
    fun match(key: String): NutritionFood? {
        val normalized = normalize(key)
        if (normalized.isEmpty()) return null
        return candidates(normalized).firstNotNullOfOrNull { index[it] }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private const val MIN_STEM = 3
        private val ES_PLURALS = listOf("ches", "shes", "sses", "xes", "zes")
        private val NOT_PLURAL = listOf("ss", "us", "is")

        fun normalize(key: String): String =
            Normalizer.normalize(key, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .trim()
                .replace(WHITESPACE, " ")

        /** [key] first, then its singular guesses; a wrong guess only misses, since it must still match exactly. */
        private fun candidates(key: String): List<String> = buildList {
            add(key)
            fun strip(suffix: String, replacement: String) {
                if (key.endsWith(suffix)) {
                    val stem = key.dropLast(suffix.length)
                    if (stem.length >= MIN_STEM && !stem.endsWith(" ")) add(stem + replacement)
                }
            }
            strip("ies", "y")
            strip("ves", "f")
            strip("oes", "o")
            when {
                // peaches → peach, molasses → molass: never the bare "-s" guess, which would give "molasse".
                ES_PLURALS.any(key::endsWith) -> strip("es", "")
                // Not words that merely end in "s", such as hummus, swiss or asparagus.
                NOT_PLURAL.none(key::endsWith) -> strip("s", "")
            }
        }
    }
}
