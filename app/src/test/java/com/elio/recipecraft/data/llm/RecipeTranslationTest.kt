package com.elio.recipecraft.data.llm

import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import com.elio.recipecraft.testing.ingredient
import com.elio.recipecraft.testing.testRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeTranslationTest {

    private val original = testRecipe(
        "r1",
        name = "Fried eggs",
        ingredients = listOf(ingredient("Eggs", "egg", grams = 100.0), ingredient("Butter", "butter", grams = 10.0)),
        setId = "set-1",
    )

    /** The reply a faithful translation would give: the same numbers and keys, Chinese text. */
    private fun reply(
        ingredients: List<IngredientWire> = listOf(
            IngredientWire("鸡蛋", "egg", "1", "个", 100.0, 100.0),
            IngredientWire("黄油", "butter", "1", "块", 10.0, 100.0),
        ),
        steps: List<StepWire> = listOf(StepWire("煎", 5.0), StepWire("装盘", null)),
        tools: List<String> = listOf("平底锅"),
        servings: Double? = 2.0,
        name: String = "煎蛋",
    ) = RecipeWire(name = name, mealType = "breakfast", servings = servings, aiKcal = 300.0, ingredients = ingredients, tools = tools, steps = steps)

    private fun merge(reply: RecipeWire) = RecipeTranslation.merge(original, reply, Language.ZH, now = 5_000)

    private fun Recipe.textOnly() = RecipeTranslation.isTextOnlyChange(original, this)

    // merge

    @Test
    fun `a faithful reply changes only the text, the language and updatedAt`() {
        val translated = merge(reply())!!

        assertEquals("煎蛋", translated.name)
        assertEquals(listOf("鸡蛋", "黄油"), translated.ingredients.map { it.name })
        assertEquals(listOf("个", "块"), translated.ingredients.map { it.unit })
        assertEquals(listOf("平底锅"), translated.tools)
        assertEquals(listOf("煎", "装盘"), translated.steps.map { it.text })
        assertEquals(Language.ZH, translated.language)
        assertEquals(5_000L, translated.updatedAt)
        val kept = original.copy(name = translated.name, ingredients = translated.ingredients, tools = translated.tools, steps = translated.steps)
        assertEquals(kept.copy(language = Language.ZH, updatedAt = 5_000), translated)
    }

    @Test
    fun `numbers the reply changes are never taken, and a changed one rejects the reply`() {
        assertNull(merge(reply(ingredients = reply().ingredients.mapIndexed { i, w -> if (i == 0) w.copy(grams = 120.0) else w })))
        assertNull(merge(reply(servings = 4.0)))
        assertNull(merge(reply(steps = listOf(StepWire("煎", 6.0), StepWire("装盘", null)))))
    }

    @Test
    fun `numbers and keys the reply leaves out come from the original`() {
        val sparse = reply(
            ingredients = listOf(IngredientWire("鸡蛋", "", "1", "个", null, null), IngredientWire("黄油", "", "1", "块", null, null)),
            steps = listOf(StepWire("煎", null), StepWire("装盘", null)),
            servings = null,
        )
        val translated = merge(sparse)!!
        assertEquals(original.ingredients.map { it.grams to it.nameEn }, translated.ingredients.map { it.grams to it.nameEn })
        assertEquals(original.steps.map { it.minutes }, translated.steps.map { it.minutes })
        assertEquals(original.calories, translated.calories)
    }

    @Test
    fun `a reordered reply is rejected rather than mixing names and grams`() {
        assertNull(merge(reply(ingredients = reply().ingredients.reversed())))
    }

    @Test
    fun `a missing or extra ingredient, step or tool is rejected`() {
        assertNull(merge(reply(ingredients = reply().ingredients.take(1))))
        assertNull(merge(reply(ingredients = reply().ingredients + IngredientWire("盐", "salt"))))
        assertNull(merge(reply(steps = reply().steps.take(1))))
        assertNull(merge(reply(steps = reply().steps + StepWire("吃", null))))
        assertNull(merge(reply(tools = emptyList())))
    }

    @Test
    fun `a blank name, ingredient, tool or step is rejected`() {
        assertNull(merge(reply(name = " ")))
        assertNull(merge(reply(ingredients = reply().ingredients.mapIndexed { i, w -> if (i == 1) w.copy(name = "") else w })))
        assertNull(merge(reply(tools = listOf(""))))
        assertNull(merge(reply(steps = listOf(StepWire("煎", 5.0), StepWire(" ", null)))))
    }

    @Test
    fun `updatedAt never goes back`() {
        assertEquals(original.updatedAt, RecipeTranslation.merge(original, reply(), Language.ZH, now = 1)!!.updatedAt)
    }

    // isTextOnlyChange

    @Test
    fun `accepts changes to text, language and updatedAt`() {
        val translated = original.copy(
            name = "煎蛋",
            ingredients = original.ingredients.map { it.copy(name = "x", quantity = "一", unit = "个") },
            tools = listOf("锅"),
            steps = original.steps.map { it.copy(text = "做") },
            language = Language.ZH,
            updatedAt = 9_999,
        )
        assertTrue(translated.textOnly())
    }

    @Test
    fun `rejects a changed id, set, meal or timestamp`() {
        assertFalse(original.copy(id = "r2").textOnly())
        assertFalse(original.copy(setId = null).textOnly())
        assertFalse(original.copy(mealType = null).textOnly())
        assertFalse(original.copy(createdAt = 2).textOnly())
    }

    @Test
    fun `rejects a changed number or match key`() {
        assertFalse(original.copy(servings = 3).textOnly())
        assertFalse(original.copy(calories = original.calories.copy(aiTotal = 301)).textOnly())
        assertFalse(original.copy(ingredients = original.ingredients.map { it.copy(grams = 1.0) }).textOnly())
        assertFalse(original.copy(ingredients = original.ingredients.map { it.copy(aiKcal = 1) }).textOnly())
        assertFalse(original.copy(ingredients = original.ingredients.map { it.copy(nameEn = "x") }).textOnly())
        assertFalse(original.copy(steps = original.steps.map { it.copy(minutes = 1) }).textOnly())
    }

    @Test
    fun `rejects a missing or extra step, ingredient or tool`() {
        assertFalse(original.copy(steps = original.steps.dropLast(1)).textOnly())
        assertFalse(original.copy(steps = original.steps + Step(3, "Eat", null)).textOnly())
        assertFalse(original.copy(ingredients = original.ingredients.dropLast(1)).textOnly())
        assertFalse(original.copy(ingredients = original.ingredients + ingredient("salt")).textOnly())
        assertFalse(original.copy(tools = original.tools + "whisk").textOnly())
    }

    @Test
    fun `rejects blank text`() {
        assertFalse(original.copy(name = "").textOnly())
        assertFalse(original.copy(tools = listOf(" ")).textOnly())
        assertNotNull(merge(reply()))
    }
}
