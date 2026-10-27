package com.elio.recipecraft.data.nutrition

import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import org.junit.Assert.assertEquals
import org.junit.Test

class CalorieCalculatorTest {

    private val usda = TableLookup(
        IngredientMatcher(
            listOf(
                NutritionFood("rolled oats", listOf("oats"), 379.0),
                NutritionFood("whole milk", listOf("milk"), 61.0),
                NutritionFood("banana", emptyList(), 89.0),
                NutritionFood("honey", emptyList(), 304.0),
                NutritionFood("salt", emptyList(), 0.0),
            ),
        ),
    )

    private fun ingredient(nameEn: String, grams: Double?, aiKcal: Int? = null, name: String = nameEn) =
        Ingredient(name = name, nameEn = nameEn, quantity = "", unit = "", grams = grams, aiKcal = aiKcal)

    private fun recipe(vararg ingredients: Ingredient, servings: Int = 2, aiTotal: Int? = 560, language: Language = Language.EN) =
        Recipe(
            id = "r",
            name = "Oatmeal",
            mealType = null,
            servings = servings,
            calories = Calories(aiTotal, null, CalorieSource.AI_ESTIMATE, aiTotal),
            ingredients = ingredients.toList(),
            tools = emptyList(),
            steps = emptyList(),
            language = language,
            setId = null,
            createdAt = 0,
            updatedAt = 0,
        )

    private val oatmeal = arrayOf(
        ingredient("rolled oats", 80.0),
        ingredient("whole milk", 366.0),
        ingredient("bananas", 118.0),
        ingredient("honey", 7.0),
    )

    @Test
    fun `every weighed ingredient matched gives the database total`() {
        // 80×3.79 + 366×0.61 + 118×0.89 + 7×3.04 = 303.2 + 223.26 + 105.02 + 21.28 = 652.76
        assertEquals(
            Calories(total = 653, perServing = 327, source = CalorieSource.DATABASE, aiTotal = 560),
            CalorieCalculator.calculate(recipe(*oatmeal), listOf(usda)),
        )
    }

    @Test
    fun `one unmatched ingredient falls back to the ai estimate`() {
        val calories = CalorieCalculator.calculate(recipe(*oatmeal, ingredient("dragon fruit", 50.0)), listOf(usda))
        assertEquals(Calories(560, 280, CalorieSource.AI_ESTIMATE, 560), calories)
    }

    @Test
    fun `an ingredient without grams and with few calories is ignored`() {
        val calories = CalorieCalculator.calculate(
            recipe(*oatmeal, ingredient("cinnamon", null, aiKcal = 0), ingredient("vanilla", null, aiKcal = 20), ingredient("ice", null)),
            listOf(usda),
        )
        assertEquals(CalorieSource.DATABASE, calories.source)
        assertEquals(653, calories.total)
    }

    @Test
    fun `an ingredient without grams but with real calories counts as unmatched`() {
        val calories = CalorieCalculator.calculate(recipe(*oatmeal, ingredient("peanut butter", null, aiKcal = 95)), listOf(usda))
        assertEquals(CalorieSource.AI_ESTIMATE, calories.source)
        assertEquals(560, calories.total)
    }

    @Test
    fun `no ingredient with grams falls back to the ai estimate`() {
        val calories = CalorieCalculator.calculate(recipe(ingredient("salt", null), ingredient("honey", null)), listOf(usda))
        assertEquals(Calories(560, 280, CalorieSource.AI_ESTIMATE, 560), calories)
    }

    @Test
    fun `no lookups falls back to the ai estimate`() {
        assertEquals(CalorieSource.AI_ESTIMATE, CalorieCalculator.calculate(recipe(*oatmeal), emptyList()).source)
    }

    @Test
    fun `a missing ai estimate stays unknown on fallback`() {
        assertEquals(
            Calories(null, null, CalorieSource.AI_ESTIMATE, null),
            CalorieCalculator.calculate(recipe(ingredient("dragon fruit", 50.0), aiTotal = null), listOf(usda)),
        )
    }

    @Test
    fun `the database total keeps the ai estimate and divides by servings`() {
        val calories = CalorieCalculator.calculate(recipe(ingredient("honey", 100.0), servings = 3, aiTotal = 250), listOf(usda))
        assertEquals(Calories(304, 101, CalorieSource.DATABASE, 250), calories)
    }

    @Test
    fun `the first lookup that matches wins`() {
        val imported = TableLookup(IngredientMatcher(listOf(NutritionFood("honey", emptyList(), 300.0))))
        val recipe = recipe(ingredient("honey", 100.0))
        assertEquals(300, CalorieCalculator.calculate(recipe, listOf(imported, usda)).total)
        assertEquals(304, CalorieCalculator.calculate(recipe, listOf(usda, imported)).total)
    }

    @Test
    fun `later lookups fill in what earlier ones miss`() {
        val imported = TableLookup(IngredientMatcher(listOf(NutritionFood("dragon fruit", emptyList(), 60.0))))
        val calories = CalorieCalculator.calculate(recipe(ingredient("dragon fruit", 100.0), ingredient("honey", 10.0)), listOf(imported, usda))
        assertEquals(CalorieSource.DATABASE, calories.source)
        assertEquals(90, calories.total)
    }

    @Test
    fun `a lookup can choose its key by recipe language`() {
        val chinese = TableLookup(
            IngredientMatcher(listOf(NutritionFood("蜂蜜", emptyList(), 321.0))),
            keyOf = { ingredient, language -> if (language == Language.ZH) ingredient.name else ingredient.nameEn },
        )
        val zh = recipe(ingredient("honey", 100.0, name = "蜂蜜"), language = Language.ZH)
        assertEquals(321, CalorieCalculator.calculate(zh, listOf(chinese, usda)).total)
        assertEquals(304, CalorieCalculator.calculate(zh.copy(language = Language.EN), listOf(chinese, usda)).total)
    }

    @Test
    fun `ingredient kcal gives each matched ingredient its own figure, in order`() {
        val kcal = CalorieCalculator.ingredientKcal(
            recipe(
                ingredient("rolled oats", 80.0),
                ingredient("dragon fruit", 100.0, aiKcal = 60),
                ingredient("salt", null),
                ingredient("bananas", 118.0),
            ),
            listOf(usda),
        )
        // 80 g × 379 / 100 = 303.2; 118 g × 89 / 100 = 105.02
        assertEquals(listOf(303, null, null, 105), kcal)
    }

    @Test
    fun `ingredient kcal takes the first table that matches and the key that table uses`() {
        val imported = TableLookup(IngredientMatcher(listOf(NutritionFood("香蕉", emptyList(), 100.0))), ::importedTableKey)
        val banana = ingredient("banana", 50.0, name = "香蕉")
        assertEquals(listOf(50), CalorieCalculator.ingredientKcal(recipe(banana, language = Language.ZH), listOf(imported, usda)))
        // An English recipe matches the imported table on nameEn, which it lacks, so USDA answers.
        assertEquals(listOf(45), CalorieCalculator.ingredientKcal(recipe(banana, language = Language.EN), listOf(imported, usda)))
        assertEquals(listOf<Int?>(null), CalorieCalculator.ingredientKcal(recipe(banana), emptyList()))
    }
}
