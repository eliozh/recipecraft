package com.elio.recipecraft.domain

import com.elio.recipecraft.domain.MealType.BREAKFAST
import com.elio.recipecraft.domain.MealType.DINNER
import com.elio.recipecraft.domain.MealType.LUNCH
import com.elio.recipecraft.domain.MealType.SNACK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationRequestTest {

    private var ids = 0

    private fun recipe(mealType: MealType?, setId: String? = null) = Recipe(
        id = "r${++ids}",
        name = "Dish $ids",
        mealType = mealType,
        servings = 1,
        calories = Calories(null, null, CalorieSource.AI_ESTIMATE, null),
        ingredients = emptyList(),
        tools = emptyList(),
        steps = emptyList(),
        language = Language.EN,
        setId = setId,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun day(setId: String, vararg meals: MealType) = meals.map { recipe(it, setId) }

    @Test
    fun `count must be between 1 and 10`() {
        GenerationRequest(GenerationScope.DINNER, 1)
        GenerationRequest(GenerationScope.DINNER, 10)
        assertThrows(IllegalArgumentException::class.java) { GenerationRequest(GenerationScope.DINNER, 0) }
        assertThrows(IllegalArgumentException::class.java) { GenerationRequest(GenerationScope.FULL_DAY_SET, 11) }
    }

    @Test
    fun `a full-day set expects three recipes per set, a meal one per count`() {
        assertEquals(6, GenerationRequest(GenerationScope.FULL_DAY_SET, 2).expectedRecipes)
        assertEquals(3, GenerationRequest(GenerationScope.DINNER, 3).expectedRecipes)
        assertTrue(GenerationRequest(GenerationScope.FULL_DAY_SET, 1).isFullDaySet)
        assertFalse(GenerationRequest(GenerationScope.SNACK, 1).isFullDaySet)
    }

    @Test
    fun `each meal scope maps to its meal type and the full-day set to none`() {
        assertNull(GenerationScope.FULL_DAY_SET.mealType)
        assertEquals(
            MealType.entries,
            GenerationScope.entries.mapNotNull { it.mealType },
        )
    }

    @Test
    fun `single meals are short only when fewer recipes came back`() {
        val request = GenerationRequest(GenerationScope.DINNER, 3)
        assertNull(request.shortfall(List(3) { recipe(DINNER) }))
        assertNull(request.shortfall(List(4) { recipe(DINNER) }))
        assertEquals(GenerationShortfall(asked = 3, got = 2, sets = false), request.shortfall(List(2) { recipe(DINNER) }))
        assertTrue(request.shortfall(emptyList())!!.isShort)
    }

    @Test
    fun `complete full-day sets are not short`() {
        val request = GenerationRequest(GenerationScope.FULL_DAY_SET, 2)
        // A snack on top of a day does no harm.
        val recipes = day("a", BREAKFAST, LUNCH, DINNER) + day("b", BREAKFAST, LUNCH, DINNER, SNACK)
        assertNull(request.shortfall(recipes))
    }

    @Test
    fun `fewer sets than asked is short`() {
        val shortfall = GenerationRequest(GenerationScope.FULL_DAY_SET, 3).shortfall(day("a", BREAKFAST, LUNCH, DINNER))!!
        assertEquals(GenerationShortfall(asked = 3, got = 1, sets = true), shortfall)
        assertTrue(shortfall.isShort)
    }

    @Test
    fun `a set without a breakfast, lunch or dinner is reported by day`() {
        val recipes = day("a", BREAKFAST, LUNCH, DINNER) + day("b", BREAKFAST)
        val shortfall = GenerationRequest(GenerationScope.FULL_DAY_SET, 2).shortfall(recipes)!!
        assertFalse(shortfall.isShort)
        assertEquals(listOf(IncompleteSet(day = 2, missing = listOf(LUNCH, DINNER))), shortfall.incomplete)
    }

    @Test
    fun `recipes outside a set do not count as a set`() {
        val shortfall = GenerationRequest(GenerationScope.FULL_DAY_SET, 1).shortfall(listOf(recipe(BREAKFAST)))!!
        assertEquals(0, shortfall.got)
    }

    @Test
    fun `day sets keep the order of first appearance and skip single recipes`() {
        val recipes = listOf(recipe(SNACK), recipe(BREAKFAST, "x"), recipe(BREAKFAST, "y"), recipe(LUNCH, "x"))
        val sets = recipes.daySets()
        assertEquals(listOf(listOf("x", "x"), listOf("y")), sets.map { set -> set.map { it.setId } })
        assertEquals(listOf(BREAKFAST, LUNCH), sets.first().map { it.mealType })
    }
}
