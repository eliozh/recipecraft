package com.elio.recipecraft.data.llm

import com.elio.recipecraft.data.llm.RecipeParseResult.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply fixtures in resources/fixtures/replies are synthetic: written by hand in the format the
 * prompts ask for, since no real key was available. Replace them with scrubbed real replies later.
 */
class RecipeParserTest {

    private val parser = RecipeParser()

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/fixtures/replies/$name")!!.bufferedReader().readText()

    private fun success(reply: String): RecipeParseResult.Success {
        val result = parser.parse(reply)
        assertTrue("expected success, got $result", result is RecipeParseResult.Success)
        return result as RecipeParseResult.Success
    }

    private fun failure(reply: String) = parser.parse(reply) as RecipeParseResult.Failure

    private val minimal = """{"recipes":[{"name":"Toast","ingredients":[{"name":"Bread"}],"steps":[{"text":"Toast it."}]}]}"""

    @Test
    fun `a valid english reply decodes every field`() {
        val result = success(fixture("en_breakfast.txt"))
        assertEquals("Here is a warm, filling breakfast that takes about ten minutes.", result.prose)
        val recipe = result.batch.recipes.single()
        assertEquals("Banana oatmeal with milk", recipe.name)
        assertEquals("breakfast", recipe.mealType)
        assertEquals(2.0, recipe.servings)
        assertEquals(560.0, recipe.aiKcal)
        assertEquals(5, recipe.ingredients.size)
        assertEquals(IngredientWire("Whole milk", "whole milk", "1 1/2", "cups", 366.0, 223.0), recipe.ingredients[1])
        assertNull(recipe.ingredients.last().grams)
        assertEquals(listOf("Small saucepan", "Wooden spoon", "Knife", "Cutting board"), recipe.tools)
        assertEquals(StepWire("Bring the milk to a gentle simmer in the saucepan.", 3.0), recipe.steps.first())
        assertNull(recipe.steps.last().minutes)
    }

    @Test
    fun `a chinese reply keeps chinese text and english match keys`() {
        val recipe = success(fixture("zh_breakfast.txt")).batch.recipes.single()
        assertEquals("葱花鸡蛋饼", recipe.name)
        assertEquals("鸡蛋", recipe.ingredients[1].name)
        assertEquals("egg", recipe.ingredients[1].nameEn)
        assertTrue(recipe.ingredients.all { it.nameEn.isNotBlank() && it.nameEn.all { c -> c.code < 128 } })
    }

    @Test
    fun `prose after the block is kept and the block is removed`() {
        val result = success("Sure!\n```json\n$minimal\n```\nEnjoy.")
        assertEquals("Sure!\nEnjoy.", result.prose)
    }

    @Test
    fun `an upper case tag, no tag or a bare object all parse`() {
        success("Here:\n```JSON\n$minimal\n```")
        success("Here:\n```\n$minimal\n```")
        val bare = success("Here you go: $minimal")
        assertEquals("Here you go:", bare.prose)
        assertEquals("", success(minimal).prose)
    }

    @Test
    fun `an unclosed fence still parses when the json is complete`() {
        assertEquals("Toast", success("Here:\n```json\n$minimal").batch.recipes.single().name)
    }

    @Test
    fun `a non-json code block before the recipe block is skipped`() {
        val result = success("Try:\n```text\nnot json\n```\n```json\n$minimal\n```")
        assertEquals("Toast", result.batch.recipes.single().name)
    }

    @Test
    fun `trailing commas and comments are tolerated`() {
        val reply = """
            ```json
            {
              "recipes": [
                {
                  "name": "Toast", // breakfast
                  "ingredients": [{ "name": "Bread", "nameEn": "bread", },],
                  "steps": ["Toast it.",],
                },
              ],
            }
            ```
        """.trimIndent()
        assertEquals("bread", success(reply).batch.recipes.single().ingredients.single().nameEn)
    }

    @Test
    fun `missing optional fields get defaults`() {
        val recipe = success(minimal).batch.recipes.single()
        assertNull(recipe.mealType)
        assertNull(recipe.servings)
        assertNull(recipe.aiKcal)
        assertEquals(emptyList<String>(), recipe.tools)
        assertEquals(IngredientWire(name = "Bread"), recipe.ingredients.single())
        assertEquals(StepWire("Toast it."), recipe.steps.single())
    }

    @Test
    fun `unknown keys, nulls and loosely typed values are accepted`() {
        val reply = """
            {"version": 2, "recipes": [{
              "name": "Toast", "cuisine": "any", "servings": "2", "tools": null,
              "ingredients": [{"name": "Bread", "quantity": 2, "grams": "60", "aiKcal": null, "brand": "x"}],
              "steps": [{"text": "Toast it.", "minutes": 2.5, "temperature": 180}]
            }]}
        """.trimIndent()
        val recipe = success(reply).batch.recipes.single()
        assertEquals(2.0, recipe.servings)
        assertEquals(emptyList<String>(), recipe.tools)
        assertEquals(IngredientWire(name = "Bread", quantity = "2", grams = 60.0), recipe.ingredients.single())
        assertEquals(2.5, recipe.steps.single().minutes)
    }

    @Test
    fun `a single recipe object or a bare array is read as a batch`() {
        val single = """{"name":"Toast","ingredients":[{"name":"Bread"}],"steps":["Toast it."]}"""
        assertEquals(1, success(single).batch.recipes.size)
        assertEquals(2, success("```json\n[$single, $single]\n```").batch.recipes.size)
    }

    @Test
    fun `blank entries are dropped`() {
        val reply = """{"recipes":[{"name":"Toast","ingredients":[{"name":"Bread"},{"name":" "}],"tools":["", "Toaster"],"steps":["Toast it.", ""]}]}"""
        val recipe = success(reply).batch.recipes.single()
        assertEquals(1, recipe.ingredients.size)
        assertEquals(listOf("Toaster"), recipe.tools)
        assertEquals(1, recipe.steps.size)
    }

    @Test
    fun `plain prose is no json, not an error`() {
        assertEquals(Reason.NO_JSON, failure(fixture("en_free_text.txt")).reason)
        assertEquals(Reason.NO_JSON, failure("Use {curly} braces sparingly.").reason)
        assertEquals(Reason.NO_JSON, failure("```kotlin\nval x = 1\n```").reason)
    }

    @Test
    fun `malformed json fails with a description for the repair prompt`() {
        val result = failure(fixture("en_malformed.txt"))
        assertEquals(Reason.INVALID_JSON, result.reason)
        assertTrue(result.description.startsWith("the JSON is not valid"))
    }

    @Test
    fun `json of the wrong shape fails`() {
        assertEquals(Reason.INVALID_JSON, failure("""{"recipes": "none"}""").reason)
        assertEquals(Reason.INVALID_JSON, failure("```json\n[1, 2]\n```").reason)
    }

    @Test
    fun `missing required fields fail with the reason`() {
        assertEquals(Reason.NO_RECIPES, failure("""{"recipes": []}""").reason)
        assertEquals(
            Reason.MISSING_NAME,
            failure("""{"recipes":[{"ingredients":[{"name":"Bread"}],"steps":["Toast it."]}]}""").reason,
        )
        assertEquals(
            Reason.NO_INGREDIENTS,
            failure("""{"recipes":[{"name":"Toast","ingredients":[],"steps":["Toast it."]}]}""").reason,
        )
        assertEquals(
            Reason.NO_STEPS,
            failure("""{"recipes":[{"name":"Toast","ingredients":[{"name":"Bread"}]}]}""").reason,
        )
        assertEquals("a recipe has no \"steps\"", Reason.NO_STEPS.description)
    }

    @Test
    fun `the repaired bare reply parses`() {
        assertEquals("Peanut butter toast", success(fixture("en_repaired.txt")).batch.recipes.single().name)
    }

    @Test
    fun `while streaming only the prose before the json shows`() {
        assertEquals(StreamingReply("Here is", hasJson = false), parser.streaming("Here is"))
        assertEquals(StreamingReply("Here is one.", hasJson = true), parser.streaming("Here is one.\n```js"))
        assertEquals(StreamingReply("", hasJson = true), parser.streaming("  {\"reci"))
        assertFalse(parser.streaming(fixture("en_free_text.txt")).hasJson)
    }
}
