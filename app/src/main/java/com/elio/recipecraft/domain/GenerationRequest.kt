package com.elio.recipecraft.domain

/** What the composer asks for: a full-day set or a single meal type. */
enum class GenerationScope(
    /** The meal of a single-meal request; null for a full-day set. */
    val mealType: MealType?,
) {
    FULL_DAY_SET(null),
    BREAKFAST(MealType.BREAKFAST),
    LUNCH(MealType.LUNCH),
    DINNER(MealType.DINNER),
    SNACK(MealType.SNACK),
    DESSERT(MealType.DESSERT),
}

/** One press of Generate: [count] full-day sets, or [count] recipes of the scope's meal. */
data class GenerationRequest(
    val scope: GenerationScope,
    val count: Int,
    /** Free text such as "vegetarian, under 500 kcal"; blank for none. */
    val preferences: String = "",
) {
    init {
        require(count in COUNT_RANGE) { "count must be in $COUNT_RANGE, was $count" }
    }

    val isFullDaySet: Boolean get() = scope == GenerationScope.FULL_DAY_SET

    /** How many recipes the reply should hold in total. */
    val expectedRecipes: Int get() = if (isFullDaySet) count * FULL_DAY_MEALS.size else count

    /**
     * Compares the assembled [recipes] with what was asked. Null when the reply has at least as
     * many sets or recipes as asked and every set has a breakfast, a lunch and a dinner.
     */
    fun shortfall(recipes: List<Recipe>): GenerationShortfall? {
        if (!isFullDaySet) {
            return if (recipes.size < count) GenerationShortfall(count, recipes.size, sets = false) else null
        }
        val sets = recipes.daySets()
        val incomplete = sets.mapIndexedNotNull { i, set ->
            val missing = FULL_DAY_MEALS.filter { meal -> set.none { it.mealType == meal } }
            if (missing.isEmpty()) null else IncompleteSet(day = i + 1, missing = missing)
        }
        return if (sets.size < count || incomplete.isNotEmpty()) {
            GenerationShortfall(count, sets.size, sets = true, incomplete = incomplete)
        } else {
            null
        }
    }

    companion object {
        val COUNT_RANGE = 1..10

        /** The meals of a full-day set, in the order they are eaten. */
        val FULL_DAY_MEALS = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)
    }
}

/** A reply that holds less than was asked; the app keeps what came back and warns. */
data class GenerationShortfall(
    /** Sets when [sets], otherwise recipes. */
    val asked: Int,
    val got: Int,
    val sets: Boolean,
    val incomplete: List<IncompleteSet> = emptyList(),
) {
    val isShort: Boolean get() = got < asked
}

/** Day [day] (1-based, as the chat numbers sets) lacks the [missing] meals. */
data class IncompleteSet(val day: Int, val missing: List<MealType>)
