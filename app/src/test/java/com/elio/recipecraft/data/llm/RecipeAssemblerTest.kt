package com.elio.recipecraft.data.llm

import com.elio.recipecraft.data.nutrition.IngredientMatcher
import com.elio.recipecraft.data.nutrition.NutritionFood
import com.elio.recipecraft.data.nutrition.TableLookup
import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Step
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeAssemblerTest {

    private val now = Instant.parse("2026-10-27T08:00:00Z")
    private var ids = 0
    private val assembler = RecipeAssembler(Clock.fixed(now, ZoneOffset.UTC)) { "id-${++ids}" }

    private fun wire(
        aiKcal: Double? = 400.0,
        servings: Double? = 2.0,
        mealType: String? = "breakfast",
        ingredients: List<IngredientWire> = listOf(IngredientWire("Eggs", "egg", "4", "", 200.0, 286.0)),
    ) = RecipeWire(
        name = "  Omelette ",
        mealType = mealType,
        servings = servings,
        aiKcal = aiKcal,
        ingredients = ingredients,
        tools = listOf(" Pan ", " "),
        steps = listOf(StepWire("Whisk.", 1.4), StepWire("Cook.", null), StepWire("Serve.", 0.0)),
    )

    @Test
    fun `the app assigns id, timestamps, language and an ai calorie source`() {
        val recipe = assembler.assemble(RecipeBatchWire(listOf(wire())), Language.ZH).recipes.single()
        assertEquals("id-1", recipe.id)
        assertEquals(now.toEpochMilli(), recipe.createdAt)
        assertEquals(now.toEpochMilli(), recipe.updatedAt)
        assertEquals(Language.ZH, recipe.language)
        assertNull(recipe.setId)
        assertEquals(Calories(total = 400, perServing = 200, source = CalorieSource.AI_ESTIMATE, aiTotal = 400), recipe.calories)
    }

    @Test
    fun `fields are mapped and trimmed`() {
        val recipe = assembler.assemble(RecipeBatchWire(listOf(wire())), Language.EN).recipes.single()
        assertEquals("Omelette", recipe.name)
        assertEquals(MealType.BREAKFAST, recipe.mealType)
        assertEquals(2, recipe.servings)
        assertEquals(listOf(Ingredient("Eggs", "egg", "4", "", 200.0, 286)), recipe.ingredients)
        assertEquals(listOf("Pan"), recipe.tools)
        assertEquals(listOf(Step(1, "Whisk.", 1), Step(2, "Cook.", null), Step(3, "Serve.", null)), recipe.steps)
    }

    @Test
    fun `each recipe gets its own id`() {
        val recipes = assembler.assemble(RecipeBatchWire(listOf(wire(), wire())), Language.EN).recipes
        assertNotEquals(recipes[0].id, recipes[1].id)
    }

    @Test
    fun `the default id is a random uuid`() {
        val recipe = RecipeAssembler(Clock.systemUTC()).assemble(RecipeBatchWire(listOf(wire())), Language.EN).recipes.single()
        assertEquals(36, recipe.id.length)
    }

    @Test
    fun `meal type is matched ignoring case and unknown values become null`() {
        fun mealOf(value: String?) = assembler.assemble(RecipeBatchWire(listOf(wire(mealType = value))), Language.EN)
            .recipes.single().mealType
        assertEquals(MealType.DESSERT, mealOf(" Dessert "))
        assertNull(mealOf("brunch"))
        assertNull(mealOf(null))
    }

    @Test
    fun `a missing total falls back to the sum of ingredient estimates`() {
        val ingredients = listOf(
            IngredientWire("Eggs", "egg", aiKcal = 286.4),
            IngredientWire("Salt", "salt", aiKcal = null),
            IngredientWire("Butter", "butter", aiKcal = 100.0),
        )
        val calories = assembler.assemble(RecipeBatchWire(listOf(wire(aiKcal = null, servings = 3.0, ingredients = ingredients))), Language.EN)
            .recipes.single().calories
        assertEquals(386, calories.aiTotal)
        assertEquals(129, calories.perServing)
    }

    @Test
    fun `no estimate at all leaves calories unknown`() {
        val calories = assembler.assemble(
            RecipeBatchWire(listOf(wire(aiKcal = -1.0, ingredients = listOf(IngredientWire("Water", "water"))))),
            Language.EN,
        ).recipes.single().calories
        assertEquals(Calories(null, null, CalorieSource.AI_ESTIMATE, null), calories)
    }

    @Test
    fun `matched ingredients give a database total and keep the ai estimate`() {
        val usda = TableLookup(IngredientMatcher(listOf(NutritionFood("egg", emptyList(), 143.0))))
        val calories = assembler.assemble(RecipeBatchWire(listOf(wire())), Language.EN, listOf(usda)).recipes.single().calories
        assertEquals(Calories(total = 286, perServing = 143, source = CalorieSource.DATABASE, aiTotal = 400), calories)
    }

    @Test
    fun `missing or invalid servings become one`() {
        assertEquals(1, assembler.assemble(RecipeBatchWire(listOf(wire(servings = null))), Language.EN).recipes.single().servings)
        assertEquals(1, assembler.assemble(RecipeBatchWire(listOf(wire(servings = 0.0))), Language.EN).recipes.single().servings)
    }

    @Test
    fun `a missing match key falls back to the english name only`() {
        val ingredients = listOf(IngredientWire("Rolled Oats", " "), IngredientWire("Milk", " Whole Milk ", grams = 0.0, aiKcal = -5.0))
        val en = assembler.assemble(RecipeBatchWire(listOf(wire(ingredients = ingredients))), Language.EN).recipes.single()
        assertEquals("rolled oats", en.ingredients[0].nameEn)
        assertEquals(Ingredient("Milk", "whole milk", "", "", null, null), en.ingredients[1])

        val zh = assembler.assemble(RecipeBatchWire(listOf(wire(ingredients = listOf(IngredientWire("燕麦", ""))))), Language.ZH)
        assertEquals("", zh.recipes.single().ingredients.single().nameEn)
    }
}
